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

package com.winnyking.watchmouse.input;

import com.winnyking.watchmouse.bluetooth.HidDataSender;
import com.winnyking.watchmouse.bluetooth.KeyboardReport.KeyboardDataSender;
import com.winnyking.watchmouse.bluetooth.MouseReport.MouseDataSender;
import com.winnyking.watchmouse.net.NetDataSender;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Routes every input event to the active transport: Bluetooth HID Device (legacy) or the network
 * TCP transport. All input controllers talk to this class instead of {@link HidDataSender} or
 * {@link NetDataSender} directly.
 */
public final class SendRouter implements MouseDataSender, KeyboardDataSender {

    /** Notified on the main thread when the current transport can no longer send. */
    public interface StatusListener {
        void onTransportDisconnected();
    }

    static final class InstanceHolder {
        static final SendRouter INSTANCE = new SendRouter();
    }

    private final HidDataSender hidDataSender = HidDataSender.getInstance();
    private final NetDataSender netDataSender = NetDataSender.getInstance();
    private final Set<StatusListener> statusListeners = new CopyOnWriteArraySet<>();

    private volatile boolean networkMode;

    private final NetDataSender.StatusListener netStatus =
            connected -> {
                if (!connected && networkMode) {
                    notifyTransportDisconnected();
                }
            };

    private SendRouter() {}

    public static SendRouter getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /** @return {@code true} if input should be sent over the network instead of Bluetooth HID. */
    public boolean isNetworkMode() {
        return networkMode;
    }

    /** Switch between the Bluetooth HID transport and the network transport. */
    public void setNetworkMode(boolean on) {
        networkMode = on;
        if (on) {
            netDataSender.registerStatusListener(netStatus);
        } else {
            netDataSender.unregisterStatusListener(netStatus);
            netDataSender.setEnabled(false);
        }
    }

    /** @return {@code true} if the active transport currently has a live connection. */
    public boolean isConnected() {
        return networkMode ? netDataSender.isConnected() : hidDataSender.isConnected();
    }

    /** Should be called on the main thread. */
    public void registerStatusListener(StatusListener listener) {
        statusListeners.add(listener);
    }

    /** Should be called on the main thread. */
    public void unregisterStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
    }

    private void notifyTransportDisconnected() {
        for (StatusListener listener : statusListeners) {
            listener.onTransportDisconnected();
        }
    }

    @Override
    public void sendMouse(
            boolean left, boolean right, boolean middle, int dX, int dY, int dWheel) {
        if (networkMode) {
            netDataSender.sendMouse(left, right, middle, dX, dY, dWheel);
        } else {
            hidDataSender.sendMouse(left, right, middle, dX, dY, dWheel);
        }
    }

    @Override
    public void sendKeyboard(
            int modifier, int key1, int key2, int key3, int key4, int key5, int key6) {
        if (networkMode) {
            netDataSender.sendKeyboard(modifier, key1, key2, key3, key4, key5, key6);
        } else {
            hidDataSender.sendKeyboard(modifier, key1, key2, key3, key4, key5, key6);
        }
    }
}