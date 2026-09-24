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

package com.winnyking.watchmouse.net;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;

/** Persisted settings for the network (TCP/Wi-Fi) transport. */
public final class NetTransport {

    private static final String TAG = "NetTransport";

    private static final String PREF = "com.winnyking.watchmouse.NET";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_PIN = "pin";

    public static final int DEFAULT_PORT = 8888;

    private NetTransport() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static String getHost(Context context) {
        return prefs(context).getString(KEY_HOST, "192.168.1.1");
    }

    public static void setHost(Context context, String host) {
        prefs(context).edit().putString(KEY_HOST, host).apply();
    }

    public static int getPort(Context context) {
        return prefs(context).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void setPort(Context context, int port) {
        prefs(context).edit().putInt(KEY_PORT, port).apply();
    }

    public static String getPin(Context context) {
        return prefs(context).getString(KEY_PIN, "");
    }

    public static void setPin(Context context, String pin) {
        prefs(context).edit().putString(KEY_PIN, pin == null ? "" : pin).apply();
    }

    private static SharedPreferences prefs(@Nullable Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}