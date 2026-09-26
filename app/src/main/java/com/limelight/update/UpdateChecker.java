package com.limelight.update;

import android.os.Build;

import com.google.gson.Gson;
import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Checks this fork's GitHub releases for a build newer than the one running.
 * Mirrors XStreaming's own updater (src/shared/lib/updater.ts): CI publishes a
 * single rolling "latest" pre-release whose tag never changes, so the release
 * carrying a given build is identified by a build number embedded in its name
 * ("Latest APK (build N)", stamped by .github/workflows/build-release.yml)
 * rather than by the tag or a semver version string.
 */
class UpdateChecker {
    private static final String RELEASES_URL = "https://api.github.com/repos/Mulyu/moonlight-android/releases";
    private static final Pattern BUILD_NUMBER_PATTERN = Pattern.compile("build\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    interface Callback {
        /** Called on a background thread with the newer release, or null if none was found. */
        void onResult(UpdateInfo info);
    }

    private UpdateChecker() {
    }

    static void check(final Callback callback) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "moonlight-android-updater")
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LimeLog.warning("UpdateChecker: check failed: " + e.getMessage());
                callback.onResult(null);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null) {
                        callback.onResult(null);
                        return;
                    }
                    GithubRelease[] releases = new Gson().fromJson(body.string(), GithubRelease[].class);
                    callback.onResult(parse(releases));
                } catch (Exception e) {
                    LimeLog.warning("UpdateChecker: parse failed: " + e.getMessage());
                    callback.onResult(null);
                }
            }
        });
    }

    private static UpdateInfo parse(GithubRelease[] releases) {
        if (releases == null || releases.length == 0) {
            return null;
        }

        GithubRelease release = null;
        for (GithubRelease r : releases) {
            if ("latest".equals(r.tag_name)) {
                release = r;
                break;
            }
        }
        if (release == null) {
            release = releases[0];
        }

        Matcher matcher = BUILD_NUMBER_PATTERN.matcher(release.name != null ? release.name : "");
        if (!matcher.find()) {
            return null;
        }

        int remoteBuild;
        try {
            remoteBuild = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (remoteBuild <= BuildConfig.BUILD_NUMBER) {
            return null;
        }

        UpdateInfo info = new UpdateInfo();
        info.buildNumber = remoteBuild;
        info.releaseName = release.name;
        info.releaseNotes = release.body;
        info.releasePageUrl = release.html_url;
        info.downloadUrl = findAssetForThisDevice(release.assets);
        return info;
    }

    /** Prefers an APK matching the device's primary ABI, then any ABI the device can run. */
    private static String findAssetForThisDevice(List<GithubRelease.Asset> assets) {
        if (assets == null) {
            return null;
        }
        for (String abi : Build.SUPPORTED_ABIS) {
            for (GithubRelease.Asset asset : assets) {
                if (asset.name != null && asset.name.endsWith(".apk") && asset.name.contains(abi)) {
                    return asset.browser_download_url;
                }
            }
        }
        return null;
    }
}
