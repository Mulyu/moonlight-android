package com.limelight.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.limelight.BuildConfig;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Drives the whole "check GitHub for a newer sideload build, offer to install
 * it" flow: one check per app launch (gated by the "Check for updates"
 * preference), a prompt naming the release, then a progress dialog while
 * {@link UpdateDownloader} fetches the APK and a FileProvider/ACTION_VIEW
 * intent hands it to the system installer. Mirrors XStreaming's own updater,
 * minus the React Native layer this app doesn't have.
 */
public final class UpdateManager {
    private static boolean checkedThisProcess = false;

    private UpdateManager() {
    }

    public static void checkOnLaunch(final Activity activity) {
        if (checkedThisProcess) {
            return;
        }
        checkedThisProcess = true;

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        if (!prefs.checkForUpdates) {
            return;
        }

        UpdateChecker.check(info -> {
            if (info == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    showUpdateDialog(activity, info);
                }
            });
        });
    }

    private static void showUpdateDialog(final Activity activity, final UpdateInfo info) {
        String message = activity.getString(R.string.update_available_message,
                info.releaseName != null ? info.releaseName : "", stripMarkdown(info.releaseNotes));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.update_action_manual, (d, w) -> openReleasePage(activity, info));

        if (info.downloadUrl != null) {
            builder.setPositiveButton(R.string.update_action_install, (d, w) -> startDownload(activity, info));
        }

        builder.show();
    }

    private static void openReleasePage(Activity activity, UpdateInfo info) {
        if (info.releasePageUrl == null) {
            return;
        }
        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(info.releasePageUrl)));
    }

    private static void startDownload(final Activity activity, final UpdateInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.update_permission_required, Toast.LENGTH_LONG).show();
            Uri packageUri = Uri.parse("package:" + activity.getPackageName());
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri));
            return;
        }

        final ProgressUi ui = ProgressUi.show(activity);

        UpdateDownloader.download(activity, info.downloadUrl, new UpdateDownloader.Listener() {
            @Override
            public void onProgress(final int percent, final long downloadedBytes, final long totalBytes) {
                activity.runOnUiThread(() -> ui.update(percent, downloadedBytes, totalBytes));
            }

            @Override
            public void onComplete(final File apkFile) {
                activity.runOnUiThread(() -> {
                    ui.dismiss();
                    installApk(activity, apkFile);
                });
            }

            @Override
            public void onError(final Exception e) {
                activity.runOnUiThread(() -> {
                    ui.dismiss();
                    Toast.makeText(activity,
                            activity.getString(R.string.update_download_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static void installApk(Activity activity, File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".fileprovider", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Some installers (and older OEM skins) need the permission granted to
        // every activity that could resolve this intent, not just the one
        // Android happens to pick.
        List<ResolveInfo> resolveInfos = activity.getPackageManager()
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo resolveInfo : resolveInfos) {
            activity.grantUriPermission(resolveInfo.activityInfo.packageName, apkUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        activity.startActivity(intent);
    }

    private static String stripMarkdown(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        return text
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("[*_`]", "")
                .trim();
    }

    /** A small dialog with a ProgressBar and a status line, updated as the APK downloads. */
    private static final class ProgressUi {
        private final AlertDialog dialog;
        private final ProgressBar progressBar;
        private final TextView statusText;

        private ProgressUi(AlertDialog dialog, ProgressBar progressBar, TextView statusText) {
            this.dialog = dialog;
            this.progressBar = progressBar;
            this.statusText = statusText;
        }

        static ProgressUi show(Activity activity) {
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
            root.setPadding(pad, pad, pad, pad);

            TextView statusText = new TextView(activity);
            statusText.setText(R.string.update_downloading);
            root.addView(statusText);

            ProgressBar progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(true);
            progressBar.setMax(100);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = pad;
            progressBar.setLayoutParams(params);
            root.addView(progressBar);

            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_downloading_title)
                    .setView(root)
                    .setCancelable(false)
                    .show();

            return new ProgressUi(dialog, progressBar, statusText);
        }

        void update(int percent, long downloadedBytes, long totalBytes) {
            if (!dialog.isShowing()) {
                return;
            }
            String label = statusText.getResources().getString(R.string.update_downloading);
            if (percent >= 0) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(percent);
                statusText.setText(String.format(Locale.getDefault(), "%s %d%% (%.1f / %.1f MB)",
                        label, percent, downloadedBytes / 1048576f, totalBytes / 1048576f));
            } else {
                statusText.setText(String.format(Locale.getDefault(), "%s (%.1f MB)",
                        label, downloadedBytes / 1048576f));
            }
        }

        void dismiss() {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        }
    }
}
