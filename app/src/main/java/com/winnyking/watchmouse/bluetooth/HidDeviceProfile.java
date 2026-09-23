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

package com.winnyking.watchmouse.bluetooth;

import static com.google.common.base.Preconditions.checkNotNull;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;
import androidx.annotation.MainThread;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Wrapper for BluetoothHidDevice profile that manages paired HID Host devices. */
public class HidDeviceProfile {

    private static final String TAG = "HidDeviceProfile";
    private static final ParcelUuid HOGP_UUID =
            ParcelUuid.fromString("00001812-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid HID_UUID =
            ParcelUuid.fromString("00001124-0000-1000-8000-00805f9b34fb");

    /** Used to call back when a profile proxy connection state has changed. */
    public interface ServiceStateListener {
        /**
         * Callback to receive the new profile proxy object.
         *
         * @param proxy Profile proxy object or {@code null} if the service was disconnected.
         */
        @MainThread
        void onServiceStateChanged(BluetoothProfile proxy);
    }

    private final BluetoothAdapter bluetoothAdapter;
    @Nullable private ServiceStateListener serviceStateListener;
    @Nullable private BluetoothHidDevice service;

    HidDeviceProfile() {
        this.bluetoothAdapter = checkNotNull(BluetoothAdapter.getDefaultAdapter());
    }

    /**
     * Check if a device supports HID Host profile.
     *
     * @param device Device to check.
     * @return {@code true} if the HID Host profile is supported, {@code false} otherwise.
     */
    public boolean isProfileSupported(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        // If a device reports itself as a HID Device, then it isn't a HID Host.
        try {
            ParcelUuid[] uuidArray = device.getUuids();
            if (uuidArray != null) {
                for (ParcelUuid uuid : uuidArray) {
                    if (HID_UUID.equals(uuid) || HOGP_UUID.equals(uuid)) {
                        Log.i(TAG, "Filtered out HID-device " + describeDevice(device)
                                + " (uuid=" + uuid + ")");
                        return false;
                    }
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Permission denied reading device UUIDs; assuming supported", e);
        }
        return true;
    }

    /**
     * Initiate the connection to the profile proxy service.
     *
     * @param context Context that is required to establish the service connection.
     * @param listener Callback that will receive the profile proxy object.
     */
    @MainThread
    void registerServiceListener(Context context, ServiceStateListener listener) {
        context = checkNotNull(context).getApplicationContext();
        serviceStateListener = checkNotNull(listener);
        bluetoothAdapter.getProfileProxy(
                context, new ServiceListener(), BluetoothProfile.HID_DEVICE);
    }

    /** Close the profile service connection. */
    @MainThread
    void unregisterServiceListener() {
        if (service != null) {
            try {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, service);
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up proxy", t);
            }
            service = null;
        }
        serviceStateListener = null;
    }

    /**
     * Examine the device for current connection status.
     *
     * @param device Remote Bluetooth device to examine.
     * @return A Bluetooth profile connection state.
     */
    public int getConnectionState(BluetoothDevice device) {
        if (service == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return service.getConnectionState(checkNotNull(device));
    }

    /**
     * Initiate the connection to the remote HID Host device.
     *
     * @param device Device to connect to.
     */
    @MainThread
    boolean connect(BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "connect(null) ignored");
            return false;
        }
        if (service != null && isProfileSupported(device)) {
            boolean ok = service.connect(device);
            Log.i(TAG, "connect(" + describeDevice(device) + ") -> " + ok);
            return ok;
        }
        Log.w(
                TAG,
                "connect skipped for " + describeDevice(device)
                        + " service=" + (service == null ? "null" : "ok")
                        + " supported=" + isProfileSupported(device));
        return false;
    }

    /**
     * Close the connection with the remote HID Host device.
     *
     * @param device Device to disconnect from.
     */
    @MainThread
    void disconnect(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        if (service != null && isProfileSupported(device)) {
            boolean ok = service.disconnect(device);
            Log.i(TAG, "disconnect(" + describeDevice(device) + ") -> " + ok);
        }
    }

    /** Structured Bluetooth/HID diagnostics for the bug report. */
    public String describeStatus() {
        StringBuilder sb = new StringBuilder();
        if (bluetoothAdapter == null) {
            return "adapter=null";
        }
        sb.append("adapter_state=").append(bluetoothAdapter.getState());
        sb.append(", enabled=").append(bluetoothAdapter.isEnabled());
        sb.append(", discovering=").append(bluetoothAdapter.isDiscovering());
        sb.append(", hid_service=").append(service == null ? "null" : "connected");
        if (service != null) {
            try {
                sb.append(", unit=").append(BluetoothProfile.HID_DEVICE);
                sb.append(", connected_devices=")
                        .append(service.getConnectedDevices().size());
                sb.append(", states=[");
                int[] states = {BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING};
                for (int i = 0; i < states.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(states[i])
                            .append('=')
                            .append(service.getDevicesMatchingConnectionStates(new int[] {states[i]}).size());
                }
                sb.append(']');
            } catch (SecurityException e) {
                sb.append(", query_denied=").append(e.getMessage());
            }
        }
        return sb.toString();
    }

    private static String describeDevice(BluetoothDevice device) {
        if (device == null) {
            return "null";
        }
        String name = device.getName();
        return (name == null ? "" : name + " ") + device.getAddress()
                + " bond=" + device.getBondState() + " type=" + device.getType();
    }

    /**
     * Get all devices that are in the "Connected" state.
     *
     * @return Connected devices list.
     */
    @MainThread
    List<BluetoothDevice> getConnectedDevices() {
        if (service == null) {
            return new ArrayList<>();
        }
        return service.getConnectedDevices();
    }

    /**
     * Get all devices that match one of the specified connection states.
     *
     * @param states List of states we are interested in.
     * @return List of devices that match one of the states.
     */
    @MainThread
    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (service == null) {
            return new ArrayList<>();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    private final class ServiceListener implements BluetoothProfile.ServiceListener {
        @Override
        @MainThread
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            Log.i(TAG, "HID_DEVICE proxy connected: profile=" + profile + " proxy=" + proxy);
            service = (BluetoothHidDevice) proxy;
            if (serviceStateListener != null) {
                serviceStateListener.onServiceStateChanged(service);
            } else {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy);
            }
        }

        @Override
        @MainThread
        public void onServiceDisconnected(int profile) {
            Log.w(TAG, "HID_DEVICE proxy disconnected: profile=" + profile);
            service = null;
            if (serviceStateListener != null) {
                serviceStateListener.onServiceStateChanged(null);
            }
        }
    }
}
