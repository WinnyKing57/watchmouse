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

import static android.Manifest.permission.BLUETOOTH;
import static android.Manifest.permission.BLUETOOTH_ADVERTISE;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP;
import static android.os.PowerManager.FULL_WAKE_LOCK;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.StrictMode;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

import com.winnyking.watchmouse.R;
import com.winnyking.watchmouse.bluetooth.HidDataSender;
import com.winnyking.watchmouse.bluetooth.HidDeviceProfile;
import com.google.common.base.Preconditions;

/**
 * Bluetooth device discovery scan for available devices.
 *
 * <p>When this fragment is created a scan will automatically be initiated. There is also a
 * preference to initiate a BT re-scan from this fragment once the initial scan has terminated due
 * to limited discovery time.
 *
 * <p>Once a scan has initiated, each BT device discovered is bound to a preference indexed by the
 * BT mac address. These preferences are then shown in an available preference category presented to
 * the user.
 *
 * <p>Users click on a preference in a the available preference category to initiate a bonding
 * sequence with the device. Should the bond be successful, the device will disappear from the
 * available preference category and appear in the previous fragment bonded preference list. If the
 * bond fails the device will remain in the available preference category.
 */
public class AvailableDevicesFragment extends PreferenceFragmentCompat {
    private static final String TAG = "BluetoothScan";

    private static final String KEY_PREF_BLUETOOTH_SCAN = "pref_bluetoothScan";
    private static final String KEY_PREF_BLUETOOTH_AVAILABLE = "pref_bluetoothAvailable";

    private static final int DISCOVERABLE_REQUEST = 2;
    private static final int PERMISSION_REQUEST = 1;

    private BluetoothAdapter bluetoothAdapter;
    private HidDeviceProfile hidDeviceProfile;
    private HidDataSender hidDataSender;

    private BluetoothStateReceiver stateReceiver;
    private BluetoothScanReceiver scanReceiver;

    private Preference initiateScanDevices;
    private PreferenceGroup availableDevices;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        try {
            setPreferencesFromResource(R.xml.prefs_available_devices, rootKey);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getContext();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        hidDataSender = HidDataSender.getInstance();
        hidDeviceProfile = hidDataSender.register(context, profileListener);

        initiateScanDevices = findPreference(KEY_PREF_BLUETOOTH_SCAN);
        availableDevices = (PreferenceGroup) findPreference(KEY_PREF_BLUETOOTH_AVAILABLE);
        availableDevices.setLayoutResource(R.layout.preference_group_no_title);

        initScanDevices(initiateScanDevices);
        initAvailableDevices();

        registerStateReceiver();

        PowerManager powerManager = getContext().getSystemService(PowerManager.class);
        wakeLock =
                powerManager.newWakeLock(
                        FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP, "WatchMouse:PokeScreen");
    }

    @Override
    public void onResume() {
        super.onResume();
        getView().requestFocus();
    }

    @Override
    public void onStart() {
        super.onStart();
        checkBluetoothState();
        wakeLock.acquire(3*60*1000L /*3 minutes*/);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        if (hasBluetoothPermissions()) {
            initiateScanDevices.setSummary(null);
            startDiscovery();
        } else {
            initiateScanDevices.setTitle(R.string.pref_bluetoothScan_permission);
            initiateScanDevices.setSummary(R.string.pref_bluetoothScan_error);
            initiateScanDevices.setEnabled(false);
        }
    }

    @Override
    public void onDestroy() {
        wakeLock.release();
        stopDiscovery();
        unregisterScanReceiver();
        unregisterStateReceiver();
        hidDataSender.unregister(getContext(), profileListener);
        super.onDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == DISCOVERABLE_REQUEST) {
            startDiscovery();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    protected void initScanDevices(Preference pref) {
        if (bluetoothAdapter.isDiscovering()) {
            pref.setEnabled(false);
        }

        pref.setOnPreferenceClickListener(
                (p) -> {
                    clearAvailableDevices();
                    startDiscovery();
                    return true;
                });
    }

    protected void initAvailableDevices() {
        clearAvailableDevices();
    }

    protected BluetoothDevicePreference addAvailableDevice(BluetoothDevice device) {
        final BluetoothDevicePreference pref = findOrAllocateDevicePreference(device);
        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            availableDevices.addPreference(pref);
            pref.setEnabled(true);
        }
        return pref;
    }

    /** Re-examine the device and update if necessary. */
    protected void updateAvailableDevice(BluetoothDevice device) {
        final BluetoothDevicePreference pref = findDevicePreference(device);
        if (pref != null) {
            pref.updateBondState();
            switch (device.getBondState()) {
                case BluetoothDevice.BOND_BONDED:
                    pref.setEnabled(false);
                    availableDevices.removePreference(pref);
                    break;
                case BluetoothDevice.BOND_BONDING:
                    pref.setEnabled(false);
                    break;
                case BluetoothDevice.BOND_NONE:
                    pref.setEnabled(true);
                    addAvailableDevice(device);
                    break;
                default: // fall out
            }
        }
    }

    protected void clearAvailableDevices() {
        availableDevices.removeAll();
    }

    /** Handles changes in the bluetooth adapter state. */
    protected void checkBluetoothState() {
        switch (bluetoothAdapter.getState()) {
            case BluetoothAdapter.STATE_OFF:
                initiateScanDevices.setTitle(R.string.generic_disabled);
                initiateScanDevices.setSummary(null);
                initiateScanDevices.setEnabled(false);
                clearAvailableDevices();
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
            case BluetoothAdapter.STATE_TURNING_OFF:
                initiateScanDevices.setEnabled(false);
                clearAvailableDevices();
                startActivity(new Intent(getActivity(), BluetoothStateActivity.class));
                break;
            case BluetoothAdapter.STATE_ON:
                initiateScanDevices.setTitle(R.string.pref_bluetoothScan);
                initiateScanDevices.setEnabled(true);
                if (!hasBluetoothPermissions()) {
                    initiateScanDevices.setTitle(R.string.pref_bluetoothScan_permission);
                    initiateScanDevices.setSummary(R.string.pref_bluetoothScan_error);
                    requestPermissions(requiredBluetoothPermissions(), PERMISSION_REQUEST);
                    return;
                }
                initiateScanDevices.setSummary(null);
                registerScanReceiver();
                startDiscovery();
                break;
            default: // fall out
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ContextCompat.checkSelfPermission(getContext(), BLUETOOTH_ADVERTISE)
                        == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(getContext(), BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(getContext(), BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(getContext(), BLUETOOTH)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private String[] requiredBluetoothPermissions() {
        return new String[] {BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH};
    }

    private void startDiscovery() {
        if (!hasBluetoothPermissions()) {
            initiateScanDevices.setTitle(R.string.pref_bluetoothScan_permission);
            initiateScanDevices.setSummary(R.string.pref_bluetoothScan_error);
            initiateScanDevices.setEnabled(false);
            requestPermissions(requiredBluetoothPermissions(), PERMISSION_REQUEST);
            return;
        }
        initiateScanDevices.setSummary(null);
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        boolean started;
        try {
            started = bluetoothAdapter.startDiscovery();
        } catch (SecurityException e) {
            Log.e(TAG, "startDiscovery blocked", e);
            started = false;
        }
        if (started) {
            initiateScanDevices.setEnabled(false);
            initiateScanDevices.setTitle(R.string.pref_bluetoothScan_scanning);
        } else {
            initiateScanDevices.setEnabled(true);
            initiateScanDevices.setTitle(R.string.pref_bluetoothScan_error);
            initiateScanDevices.setSummary(R.string.pref_bluetoothScan_error);
        }
    }

    private void stopDiscovery() {
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
    }

    private void registerScanReceiver() {
        if (scanReceiver != null) {
            return;
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        intentFilter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        getContext()
                .registerReceiver(
                        scanReceiver = new BluetoothScanReceiver(),
                        intentFilter,
                        android.content.Context.RECEIVER_NOT_EXPORTED);

        if (!BluetoothUtils.setScanMode(
                bluetoothAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 0)) {
            Intent discoverableIntent =
                new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            startActivityForResult(discoverableIntent, DISCOVERABLE_REQUEST);
        }
    }

    private void unregisterScanReceiver() {
        if (scanReceiver != null) {
            getContext().unregisterReceiver(scanReceiver);
            scanReceiver = null;
            BluetoothUtils.setScanMode(bluetoothAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE, 0);
        }
    }

    private void registerStateReceiver() {
        Preconditions.checkArgument(stateReceiver == null);
        final IntentFilter intentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        getContext()
                .registerReceiver(
                        stateReceiver = new BluetoothStateReceiver(),
                        intentFilter,
                        android.content.Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterStateReceiver() {
        if (stateReceiver != null) {
            getContext().unregisterReceiver(stateReceiver);
            stateReceiver = null;
        }
    }

    /** Handles bluetooth scan responses and other indicators. */
    protected class BluetoothScanReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getContext() == null) {
                Log.w(TAG, "BluetoothScanReceiver context disappeared");
                return;
            }

            final String action = intent.getAction();
            final BluetoothDevice device =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                            ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                            : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            switch (action == null ? "" : action) {
                case BluetoothDevice.ACTION_FOUND:
                    try {
                        if (hidDeviceProfile.isProfileSupported(device)) {
                            addAvailableDevice(device);
                            initiateScanDevices.setSummary(null);
                        }
                    } catch (SecurityException e) {
                        Log.w(TAG, "Permission denied while inspecting device", e);
                    }
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    initiateScanDevices.setEnabled(false);
                    initiateScanDevices.setTitle(R.string.pref_bluetoothScan_scanning);
                    initiateScanDevices.setSummary(null);
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    initiateScanDevices.setEnabled(true);
                    initiateScanDevices.setTitle(R.string.pref_bluetoothScan);
                    if (availableDevices.getPreferenceCount() == 0) {
                        initiateScanDevices.setSummary(R.string.pref_bluetoothScan_none);
                    }
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    updateAvailableDevice(device);
                    break;
                case BluetoothDevice.ACTION_NAME_CHANGED:
                    BluetoothDevicePreference pref = findDevicePreference(device);
                    if (pref != null) {
                        pref.updateName();
                    }
                    break;
                default: // fall out
            }
        }
    }

    /** Receiver to listen for changes in the bluetooth adapter state. */
    protected class BluetoothStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                checkBluetoothState();
            }
        }
    }

    private final HidDataSender.ProfileListener profileListener =
            new HidDataSender.ProfileListener() {
                @Override
                @MainThread
                public void onServiceStateChanged(BluetoothProfile proxy) {}

                @Override
                @MainThread
                public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    final BluetoothDevicePreference pref = findOrAllocateDevicePreference(device);
                    pref.updateProfileConnectionState();
                }

                @Override
                @MainThread
                public void onAppStatusChanged(boolean registered) {
                    if (!registered) {
                        getActivity().finish();
                    }
                }
            };

    /**
     * Looks for a preference in the preference group.
     *
     * <p>Returns null if no preference available.
     */
    private BluetoothDevicePreference findDevicePreference(final BluetoothDevice device) {
        return (BluetoothDevicePreference) findPreference(device.getAddress());
    }

    /**
     * Looks for a preference in the preference group.
     *
     * <p>Allocates a new preference if none found.
     */
    private BluetoothDevicePreference findOrAllocateDevicePreference(final BluetoothDevice device) {
        BluetoothDevicePreference pref = findDevicePreference(device);
        if (pref == null) {
            pref = new BluetoothDevicePreference(getContext(), device, hidDeviceProfile);
        }
        return pref;
    }
}
