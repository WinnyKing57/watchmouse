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

package com.winnyking.watchmouse.ui.devices;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import com.winnyking.watchmouse.R;
import com.winnyking.watchmouse.input.SendRouter;
import com.winnyking.watchmouse.net.NetDataSender;
import com.winnyking.watchmouse.net.NetTransport;
import com.winnyking.watchmouse.ui.input.ModeSelectFragment;

/**
 * Target selection and status for the network (TCP/Wi-Fi) transport.
 *
 * <p>Overrides the Bluetooth HID transport: the watch then sends the exact same input events to a
 * receiver on the local network (a PC or an Android companion app).
 */
public class NetworkTargetFragment extends PreferenceFragmentCompat {

    private static final String TAG = "NetTargetFragment";
    private static final String KEY_TRANSPORT = "pref_transport_network";
    private static final String KEY_HOST = "pref_net_host";
    private static final String KEY_PORT = "pref_net_port";
    private static final String KEY_STATUS = "pref_net_status";
    private static final String KEY_OPEN = "pref_net_open";

    private final SendRouter sendRouter = SendRouter.getInstance();
    private final NetDataSender netDataSender = NetDataSender.getInstance();

    private SwitchPreferenceCompat transportPref;
    private EditTextPreference hostPref;
    private EditTextPreference portPref;
    private Preference statusPref;

    private final NetDataSender.StatusListener statusListener = this::updateStatus;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs_network_target, rootKey);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "opened, host=" + NetTransport.getHost(getContext())
                + " port=" + NetTransport.getPort(getContext())
                + " enabled=" + NetTransport.isEnabled(getContext()));

        transportPref = findPreference(KEY_TRANSPORT);
        hostPref = findPreference(KEY_HOST);
        portPref = findPreference(KEY_PORT);
        statusPref = findPreference(KEY_STATUS);

        refresh();
        updateTarget();

        if (transportPref != null) {
            transportPref.setOnPreferenceChangeListener(
                    (preference, value) -> {
                        boolean on = Boolean.TRUE.equals(value);
                        applyTransport(on);
                        return true;
                    });
        }
        if (hostPref != null) {
            hostPref.setOnPreferenceChangeListener(
                    (preference, value) -> {
                        NetTransport.setHost(getContext(), String.valueOf(value).trim());
                        refresh();
                        updateTarget();
                        return true;
                    });
        }
        if (portPref != null) {
            portPref.setOnPreferenceChangeListener(
                    (preference, value) -> {
                        NetTransport.setPort(getContext(), parsePort(value));
                        refresh();
                        updateTarget();
                        return true;
                    });
        }

        Preference openPref = findPreference(KEY_OPEN);
        if (openPref != null) {
            openPref.setOnPreferenceClickListener(
                    preference -> {
                        ((WelcomeActivity) getActivity())
                                .startPreferenceFragment(new ModeSelectFragment(), true);
                        return true;
                    });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        netDataSender.registerStatusListener(statusListener);
        refresh();
        Log.i(TAG, "resumed, connected=" + netDataSender.isConnected());
        updateStatus(netDataSender.isConnected());
    }

    @Override
    public void onPause() {
        netDataSender.unregisterStatusListener(statusListener);
        super.onPause();
    }

    private void applyTransport(boolean on) {
        sendRouter.setNetworkMode(on);
        NetTransport.setEnabled(getContext(), on);
        Log.i(TAG, "transport network " + (on ? "enabled" : "disabled"));
        if (on) {
            updateTarget();
            netDataSender.setEnabled(true);
        } else {
            netDataSender.setEnabled(false);
        }
        refresh();
        updateStatus(netDataSender.isConnected());
    }

    private void updateTarget() {
        if (sendRouter.isNetworkMode()) {
            String host = NetTransport.getHost(getContext());
            int port = NetTransport.getPort(getContext());
            netDataSender.setTarget(host, port);
        }
    }

    private void refresh() {
        if (transportPref != null) {
            boolean on = NetTransport.isEnabled(getContext());
            transportPref.setChecked(on);
            if (on != sendRouter.isNetworkMode()) {
                sendRouter.setNetworkMode(on);
            }
        }
        if (hostPref != null) {
            hostPref.setText(NetTransport.getHost(getContext()));
        }
        if (portPref != null) {
            portPref.setText(String.valueOf(NetTransport.getPort(getContext())));
        }
    }

    private void updateStatus(boolean connected) {
        if (statusPref == null) {
            return;
        }
        if (connected) {
            statusPref.setSummary(getString(R.string.pref_net_connected, NetTransport.getHost(getContext())));
        } else {
            statusPref.setSummary(R.string.pref_net_disconnected);
        }
    }

    private static int parsePort(Object value) {
        String text = String.valueOf(value).trim();
        if (TextUtils.isEmpty(text)) {
            return NetTransport.DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(text);
            if (port > 0 && port < 65536) {
                return port;
            }
        } catch (NumberFormatException ignored) {
        }
        return NetTransport.DEFAULT_PORT;
    }
}