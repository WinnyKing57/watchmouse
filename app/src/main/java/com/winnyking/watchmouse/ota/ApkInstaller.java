/*
 * Copyright 2018 Google LLC All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.winnyking.watchmouse.ota;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads an update APK and installs it using the package installer session API. */
public final class ApkInstaller {

    private static final String TAG = "ApkInstaller";
    private static final String APK_FILE_NAME = "watchmouse-universal.apk";

    public interface Listener {
        void onProgress(int percent);

        void onError(String message);

        void onInstallPermissionNeeded();
    }

    private ApkInstaller() {}

    /** Downloads and installs in the background, reporting progress on the main thread. */
    public static void downloadAndInstall(
            final Context context, final String apkUrl, final Listener listener) {
        Thread thread =
                new Thread(
                        () -> {
                            File apk;
                            try {
                                apk = download(context, apkUrl, listener);
                            } catch (IOException e) {
                                Log.e(TAG, "Download failed", e);
                                postError(listener, "Download failed");
                                return;
                            }
                            install(context, apk, listener);
                        },
                        "ApkDownload");
        thread.start();
    }

    private static File download(Context context, String apkUrl, Listener listener)
            throws IOException {
        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create cache dir");
        }
        File apk = new File(dir, APK_FILE_NAME);

        HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned " + connection.getResponseCode());
            }
            long total = connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream();
                    FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buffer = new byte[64 * 1024];
                long downloaded = 0;
                int lastPercent = -1;
                int bytes;
                while ((bytes = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytes);
                    downloaded += bytes;
                    int percent = total > 0 ? (int) (downloaded * 100 / total) : -1;
                    if (percent >= 0 && percent != lastPercent) {
                        lastPercent = percent;
                        postProgress(listener, percent);
                    }
                }
                out.getFD().sync();
            }
        } finally {
            connection.disconnect();
        }
        return apk;
    }

    private static void install(Context context, File apk, Listener listener) {
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            postInstallPermissionNeeded(listener);
            return;
        }
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                try (InputStream in = new FileInputStream(apk);
                        OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int bytes;
                    while ((bytes = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytes);
                    }
                    session.fsync(out);
                }
                Intent resultIntent = new Intent(context, InstallResultReceiver.class)
                        .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                session.commit(pendingIntent.getIntentSender());
            } finally {
                session.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Install failed", e);
            postError(listener, "Install failed");
        }
    }

    private static void postProgress(final Listener listener, final int percent) {
        new Handler(Looper.getMainLooper()).post(() -> listener.onProgress(percent));
    }

    private static void postInstallPermissionNeeded(final Listener listener) {
        new Handler(Looper.getMainLooper()).post(listener::onInstallPermissionNeeded);
    }

    private static void postError(final Listener listener, final String message) {
        new Handler(Looper.getMainLooper()).post(() -> listener.onError(message));
    }
}