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

package com.winnyking.watchmouse;

import android.app.Application;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;
import com.winnyking.watchmouse.bluetooth.HidDataSender;
import com.winnyking.watchmouse.input.SettingsUtil;
import com.winnyking.watchmouse.input.SettingsUtil.SettingKey;
import com.winnyking.watchmouse.net.NetDataSender;
import com.winnyking.watchmouse.ota.RemoteUpdateController;
import com.winnyking.watchmouse.ui.devices.NotificationService;

public class WatchMouseApp extends Application {

    private HidDataSender hidDataSender;
    private SettingsUtil settingsUtil;

    private final LifecycleObserver lifecycleObserver =
            new LifecycleObserver() {
                @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
                public void onMoveToBackground() {
                    if (!settingsUtil.getBoolean(SettingKey.STAY_CONNECTED)) {
                        hidDataSender.requestConnect(null);
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        hidDataSender = HidDataSender.getInstance();
        settingsUtil = new SettingsUtil(this);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(lifecycleObserver);
        NetDataSender.getInstance().registerCommandListener(new RemoteUpdateController(this));
    }
}
