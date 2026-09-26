package com.limelight.update;

import android.content.Context;

import com.limelight.LimeLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads an update APK into the app's cache dir, off the main thread. */
final class UpdateDownloader {
    private static final int BUFFER_SIZE = 64 * 1024;

    interface Listener {
        /** Called repeatedly on a background thread; percent and totalBytes are -1 if the size is unknown. */
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onComplete(File apkFile);
        void onError(Exception e);
    }

    private UpdateDownloader() {
    }

    static void download(final Context context, final String url, final Listener listener) {
        new Thread(() -> {
            File apkFile = null;
            try {
                File updateDir = new File(context.getCacheDir(), "updates");
                deleteContents(updateDir);
                //noinspection ResultOfMethodCallIgnored
                updateDir.mkdirs();

                String fileName = url.substring(url.lastIndexOf('/') + 1);
                if (fileName.isEmpty() || !fileName.endsWith(".apk")) {
                    fileName = "update.apk";
                }
                apkFile = new File(updateDir, fileName);

                OkHttpClient client = new OkHttpClient();
                Response response = client.newCall(new Request.Builder().url(url).build()).execute();
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    listener.onError(new IOException("HTTP " + response.code()));
                    return;
                }

                long total = body.contentLength();
                long downloaded = 0;
                int lastPercent = -1;

                try (InputStream in = body.byteStream();
                     OutputStream out = new FileOutputStream(apkFile)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int percent = (int) (downloaded * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                listener.onProgress(percent, downloaded, total);
                            }
                        } else {
                            listener.onProgress(-1, downloaded, -1);
                        }
                    }
                }

                listener.onComplete(apkFile);
            } catch (Exception e) {
                LimeLog.warning("UpdateDownloader: download failed: " + e.getMessage());
                if (apkFile != null) {
                    //noinspection ResultOfMethodCallIgnored
                    apkFile.delete();
                }
                listener.onError(e);
            }
        }, "UpdateDownloader").start();
    }

    private static void deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
