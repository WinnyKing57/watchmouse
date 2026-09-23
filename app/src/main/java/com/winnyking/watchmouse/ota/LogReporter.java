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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
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
import java.util.UUID;

/** Collects device info and logcat, then POSTs them to the report endpoint. */
public final class LogReporter {

    private static final String TAG = "LogReporter";
    private static final String PREFS = "log_reporter";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String POST_PATH = "/log";

    public interface Callback {
        void onSuccess(long reportId);

        void onError(String message);
    }

    private LogReporter() {}

    /** Runs the report in the background and delivers the result on the main thread. */
    public static void report(final Context context, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                final long reportId =
                                        sendReport(context, BuildConfig.REPORT_ENDPOINT);
                                main.post(() -> callback.onSuccess(reportId));
                            } catch (Exception e) {
                                Log.e(TAG, "Report failed", e);
                                main.post(
                                        () ->
                                                callback.onError(
                                                        e.getMessage() == null
                                                                ? "Unknown error"
                                                                : e.getMessage()));
                            }
                        },
                        "LogReporter");
        thread.start();
    }

    private static long sendReport(Context context, String endpoint) throws Exception {
        String url = endpoint.replaceAll("/+$", "") + POST_PATH;
        JSONObject payload = new JSONObject();
        payload.put("client_id", getClientId(context));
        payload.put("version", BuildConfig.VERSION_NAME);
        payload.put("version_code", BuildConfig.VERSION_CODE);
        payload.put("manufacturer", Build.MANUFACTURER);
        payload.put("model", Build.MODEL);
        payload.put("sdk", Build.VERSION.SDK_INT);
        payload.put("build", Build.VERSION.INCREMENTAL);
        payload.put("battery", readBattery(context));
        payload.put("logs", readOwnLogcat());

        HttpURLConnection connection =
                (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try {
            OutputStream output = connection.getOutputStream();
            output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_CREATED && code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned " + code);
            }
            String response = readStream(connection.getInputStream());
            JSONObject json = new JSONObject(response);
            return json.optLong("id");
        } finally {
            connection.disconnect();
        }
    }

    private static String getClientId(Context context) {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_CLIENT_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_CLIENT_ID, id).apply();
        }
        return id;
    }

    private static int readBattery(Context context) {
        BatteryManager manager =
                (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (manager == null) {
            return -1;
        }
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
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