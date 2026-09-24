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

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Checks the GitHub Releases API for the latest WatchMouse build. */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String API_URL =
            "https://api.github.com/repos/WinnyKing57/watchmouse/releases/latest";
    private static final String ASSET_PREFERRED = "universal";

    public interface Callback {
        void onSuccess(UpdateInfo info);

        void onError(String message);
    }

    private UpdateChecker() {}

    /** Runs the check in the background and delivers the result on the main thread. */
    public static void check(final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                final UpdateInfo info = fetchLatestRelease();
                                main.post(() -> callback.onSuccess(info));
                            } catch (IOException e) {
                                Log.e(TAG, "Update check failed", e);
                                final String message = "Network error";
                                main.post(() -> callback.onError(message));
                            } catch (Exception e) {
                                Log.e(TAG, "Update check failed", e);
                                final String message =
                                        e.getMessage() == null ? "Unknown error" : e.getMessage();
                                main.post(() -> callback.onError(message));
                            }
                        },
                        "UpdateChecker");
        thread.start();
    }

    private static UpdateInfo fetchLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "WatchMouse");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub API returned " + code);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            JSONObject release = new JSONObject(body.toString());
            String tagName = release.optString("tag_name", "");
            if (tagName.startsWith("v")) {
                tagName = tagName.substring(1);
            }
            if (tagName.isEmpty()) {
                throw new IOException("Release has no version tag");
            }
            String apkUrl = pickApkUrl(release.optJSONArray("assets"));
            if (apkUrl == null) {
                throw new IOException("No APK asset found in latest release");
            }
            return new UpdateInfo(tagName, apkUrl);
        } finally {
            connection.disconnect();
        }
    }

    private static String pickApkUrl(JSONArray assets) {
        String fallback = null;
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", null);
            if (url == null || !name.endsWith(".apk")) {
                continue;
            }
            if (name.contains(ASSET_PREFERRED)) {
                return url;
            }
            if (fallback == null) {
                fallback = url;
            }
        }
        return fallback;
    }
}