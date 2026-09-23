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

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.splashscreen.SplashScreen;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.winnyking.watchmouse.R;
import com.winnyking.watchmouse.ui.onboarding.OnboardingController.ScreenKey;
import com.winnyking.watchmouse.ui.onboarding.OnboardingRequest;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/** Main activity that is started from launcher. */
public class WelcomeActivity extends FragmentActivity {
    private OnboardingRequest onboardingRequest;
    private int permissionRequests = 0;
    private static final List<String> requiredPermissions = ImmutableList.of(
            BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH
    );

    /** Replaces the currently displayed preference fragment. */
    public void startPreferenceFragment(Fragment fragment, boolean addToBackStack) {
        androidx.fragment.app.FragmentTransaction transaction =
                getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        if (addToBackStack) {
            transaction.addToBackStack(fragment.getClass().getName());
        }
        transaction.commit();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            List<String> missingPermissions = findMissingPermissions();
            if (missingPermissions.isEmpty()) {
                maybeStartOnboarding();
            } else if (permissionRequests < 2) {
                requestPermissions(
                        missingPermissions.toArray(new String[0]), 1);
            } else {
                android.widget.Toast.makeText(
                                this, R.string.pref_bluetoothScan_permission,
                                android.widget.Toast.LENGTH_LONG)
                        .show();
                finish();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preferences);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> missingPermissions = findMissingPermissions();
            if (!missingPermissions.isEmpty()) {
                permissionRequests++;
                requestPermissions(missingPermissions.toArray(new String[0]), 1);
                return;
            }
        }

        maybeStartOnboarding();
    }

    private List<String> findMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : requiredPermissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (onboardingRequest.isMyResult(requestCode, data)) {
            if (resultCode == RESULT_OK) {
                onboardingRequest.setComplete();
                startPreferenceFragment(new PairedDevicesFragment(), true);
            }
            finish();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void maybeStartOnboarding() {
        onboardingRequest = new OnboardingRequest(this, ScreenKey.WELCOME);
        if (onboardingRequest.isComplete()) {
            startDevicesFragment();
        } else {
            onboardingRequest.start();
        }
    }

    private void startDevicesFragment() {
        startPreferenceFragment(new PairedDevicesFragment(), false);
    }
}
