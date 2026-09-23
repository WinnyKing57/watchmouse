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

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.winnyking.watchmouse.R;
import com.winnyking.watchmouse.ota.ApkInstaller;
import com.winnyking.watchmouse.ota.UpdateChecker;
import com.winnyking.watchmouse.ota.UpdateInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

public class AboutFragment extends Fragment {

    private Button updateButton;
    private TextView updateStatus;
    private String currentVersion;
    private UpdateInfo pendingUpdate;
    private boolean downloading;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_about, container, false);

        Context context = getContext();
        PackageInfo packageInfo;
        currentVersion = "0.01";
        try {
            packageInfo =
                    context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            currentVersion = packageInfo.versionName;
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }

        TextView message = root.findViewById(R.id.message);
        message.setText(currentVersion);

        updateButton = root.findViewById(R.id.update);
        updateStatus = root.findViewById(R.id.update_status);
        updateButton.setOnClickListener(v -> onUpdateButtonClicked());

        Button license = root.findViewById(R.id.license);
        license.setOnClickListener(v -> createLicenseDialog(getContext()));

        Button changelog = root.findViewById(R.id.changelog);
        changelog.setOnClickListener(
                v -> {
                    Intent intent =
                            new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                            "https://github.com/WinnyKing57/watchmouse/releases"));
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(
                                        getContext(),
                                        R.string.update_checkFailed,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                });

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        getView().requestFocus();
    }

    private void onUpdateButtonClicked() {
        if (downloading) {
            return;
        }
        if (pendingUpdate == null) {
            checkForUpdates();
        } else {
            downloadAndInstall();
        }
    }

    private void checkForUpdates() {
        updateStatus.setText(R.string.pref_about_checkingUpdates);
        updateButton.setEnabled(false);
        final String current = currentVersion;
        UpdateChecker.check(
                new UpdateChecker.Callback() {
                    @Override
                    public void onSuccess(UpdateInfo info) {
                        updateButton.setEnabled(true);
                        if (UpdateInfo.compareVersions(info.latestVersionName, current) > 0) {
                            pendingUpdate = info;
                            updateStatus.setText(
                                    getString(R.string.update_available, info.latestVersionName));
                            updateButton.setText(R.string.update_downloadAndInstall);
                        } else {
                            updateStatus.setText(R.string.update_upToDate);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        updateButton.setEnabled(true);
                        updateStatus.setText(getString(R.string.update_checkFailed, message));
                    }
                });
    }

    private void downloadAndInstall() {
        downloading = true;
        updateButton.setEnabled(false);
        updateStatus.setText(R.string.update_downloading);
        ApkInstaller.downloadAndInstall(
                getContext(),
                pendingUpdate.apkUrl,
                new ApkInstaller.Listener() {
                    @Override
                    public void onProgress(int percent) {
                        updateStatus.setText(getString(R.string.update_downloadProgress, percent));
                    }

                    @Override
                    public void onError(String message) {
                        downloading = false;
                        updateButton.setEnabled(true);
                        updateStatus.setText(message);
                    }
                });
    }

    private static void createLicenseDialog(Context context) {
        Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_open_source);
        TextView message = dialog.findViewById(R.id.license_text);
        message.setText(
                getTextFromInputStream(
                        context.getResources().openRawResource(R.raw.apache_license)));
        dialog.show();
        dialog.findViewById(R.id.root_view).requestFocus();
    }

    private static String getTextFromInputStream(InputStream stream) {
        byte[] buffer = new byte[1024];
        ByteArrayOutputStream textArray = new ByteArrayOutputStream();

        try {
            int bytes;
            while ((bytes = stream.read(buffer, 0, buffer.length)) != -1) {
                textArray.write(buffer, 0, bytes);
            }
            stream.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read license", e);
        }

        try {
            return textArray.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(
                    "Unsupported encoding UTF8. This should always be supported.", e);
        }
    }
}