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
import android.widget.Toast;
import com.winnyking.watchmouse.BuildConfig;
import com.winnyking.watchmouse.R;
import com.winnyking.watchmouse.net.NetDataSender;

/**
 * Runs the app's self-update when the network receiver asks for it.
 *
 * <p>Wired into {@link NetDataSender} at application start. When the companion app (or any
 * receiver) sends <code>{"t":"cmd","a":"update"}</code>, this checks the GitHub releases and, if a
 * newer build exists, downloads and installs it using the existing {@link ApkInstaller} flow.
 */
public final class RemoteUpdateController implements NetDataSender.CommandListener {

    private final Context appContext;

    public RemoteUpdateController(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void onRemoteCommand(String command) {
        if (!"update".equals(command)) {
            return;
        }
        final String current = BuildConfig.VERSION_NAME;
        UpdateChecker.check(
                new UpdateChecker.Callback() {
                    @Override
                    public void onSuccess(UpdateInfo info) {
                        if (UpdateInfo.compareVersions(info.latestVersionName, current) <= 0) {
                            toast(getString(R.string.update_upToDate));
                            return;
                        }
                        toast(getString(R.string.update_available, info.latestVersionName));
                        ApkInstaller.downloadAndInstall(
                                appContext,
                                info.apkUrl,
                                new ApkInstaller.Listener() {
                                    @Override
                                    public void onProgress(int percent) {}

                                    @Override
                                    public void onError(String message) {
                                        toast(getString(R.string.update_installFailed));
                                    }

                                    @Override
                                    public void onInstallPermissionNeeded() {
                                        toast(getString(R.string.update_installFailed));
                                    }
                                });
                    }

                    @Override
                    public void onError(String message) {
                        toast(getString(R.string.update_checkFailed, message));
                    }
                });
    }

    private String getString(int resId, Object... args) {
        return appContext.getString(resId, args);
    }

    private void toast(String message) {
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
    }
}