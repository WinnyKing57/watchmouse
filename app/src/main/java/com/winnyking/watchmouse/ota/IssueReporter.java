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

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winnyking.watchmouse.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Collects device logs and opens a bug report issue on the GitHub tracker. */
public final class IssueReporter {

    private static final String TAG = "IssueReporter";
    private static final String API_URL =
            "https://api.github.com/repos/WinnyKing57/watchmouse/issues";

    public interface Callback {
        void onSuccess(long issueNumber);

        void onError(String message);
    }

    private IssueReporter() {}

    /** Runs the report in the background and delivers the result on the main thread. */
    public static void report(final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                final long issueNumber = createIssue();
                                main.post(() -> callback.onSuccess(issueNumber));
                            } catch (Exception e) {
                                Log.e(TAG, "Bug report failed", e);
                                main.post(
                                        () ->
                                                callback.onError(
                                                        e.getMessage() == null
                                                                ? "Unknown error"
                                                                : e.getMessage()));
                            }
                        },
                        "IssueReporter");
        thread.start();
    }

    private static long createIssue() throws Exception {
        String title =
                "[Rapport] WatchMouse "
                        + BuildConfig.VERSION_NAME
                        + " – "
                        + Build.MANUFACTURER
                        + " "
                        + Build.MODEL;
        JSONObject body = new JSONObject();
        body.put("title", title);
        body.put("body", buildReportBody());

        HttpURLConnection connection =
                (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + BuildConfig.GITHUB_ISSUES_TOKEN);
        try {
            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_CREATED) {
                throw new IOException("GitHub API returned " + code);
            }
            String response = readStream(connection.getInputStream());
            JSONObject issue = new JSONObject(response);
            return issue.getLong("number");
        } finally {
            connection.disconnect();
        }
    }

    private static String buildReportBody() {
        StringBuilder builder = new StringBuilder();
        builder.append("## Device\n");
        builder.append("- Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        builder.append("- Model: ").append(Build.MODEL).append('\n');
        builder.append("- Version: ").append(Build.VERSION.RELEASE).append('\n');
        builder.append("- SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        builder.append("- Build: ").append(Build.VERSION.INCREMENTAL).append('\n');
        builder.append("- App version: ")
                .append(BuildConfig.VERSION_NAME)
                .append(" (")
                .append(BuildConfig.VERSION_CODE)
                .append(")\n\n");
        builder.append("## Logs\n\n```text\n");
        builder.append(readOwnLogcat());
        builder.append("\n```\n");
        return builder.toString();
    }

    private static String readOwnLogcat() {
        try {
            Process process =
                    new ProcessBuilder("logcat", "-d", "-t", "600")
                            .redirectErrorStream(true)
                            .start();
            String output = readStream(process.getInputStream());
            process.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "Failed to read logcat", e);
            return "logcat unavailable";
        }
    }

    private static String readStream(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }
}