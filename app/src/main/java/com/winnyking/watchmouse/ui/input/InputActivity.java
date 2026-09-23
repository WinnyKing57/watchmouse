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

package com.winnyking.watchmouse.ui.input;

import static android.content.Intent.ACTION_SCREEN_OFF;
import static android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP;
import static android.os.PowerManager.SCREEN_DIM_WAKE_LOCK;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.IntDef;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.wear.ambient.AmbientModeSupport;

import com.winnyking.watchmouse.R;
import com.winnyking.watchmouse.input.KeyboardInputController;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Implements a "card-flip" animation using custom fragment transactions. */
public class InputActivity extends FragmentActivity
        implements AmbientModeSupport.AmbientCallbackProvider {
    public static final String EXTRA_INPUT_MODE = "input_mode";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({InputMode.MOUSE, InputMode.KEYPAD, InputMode.TOUCHPAD})
    public @interface InputMode {
        int MOUSE = 1;
        int KEYPAD = 2;
        int TOUCHPAD = 3;
    }

    private static final int INPUT_REQUEST_CODE = 1;

    private KeyboardInputController keyboardController;
    private @InputMode int currentMode;
    private WakeLock wakeLock;
    private AmbientModeSupport.AmbientController ambientController;

    @Override
    public AmbientModeSupport.AmbientCallback getAmbientCallback() {
        return new AmbientModeSupport.AmbientCallback() {
            @Override
            public void onEnterAmbient(Bundle ambientDetails) {
                wakeUpAndFinish();
            }
        };
    }

    private final BroadcastReceiver screenReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        wakeUpAndFinish();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_flip);

        ambientController = AmbientModeSupport.attach(this);

        PowerManager powerManager = getSystemService(PowerManager.class);
        wakeLock =
                powerManager.newWakeLock(
                        SCREEN_DIM_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP, "WatchMouse:PokeScreen");

        keyboardController = new KeyboardInputController(this::finish);
        keyboardController.onCreate(this);

        currentMode = InputMode.MOUSE;
        Intent intent = getIntent();
        if (intent != null) {
            int mode = intent.getIntExtra(EXTRA_INPUT_MODE, -1);
            if (mode > 0) {
                currentMode = mode;
            }
        }

        getSupportFragmentManager()
                .beginTransaction()
                .add(R.id.fragment_container, getFragment(currentMode))
                .commit();

        registerReceiver(screenReceiver, new IntentFilter(ACTION_SCREEN_OFF));
    }

    @Override
    public void onResume() {
        super.onResume();
        keyboardController.onResume();
        getFragment().getView().requestFocus();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(screenReceiver);
        keyboardController.onDestroy(this);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (keyCode == KeyEvent.KEYCODE_STEM_1) {
            if (action == KeyEvent.ACTION_UP) {
                flipCard(
                        currentMode == InputMode.MOUSE
                                ? InputMode.TOUCHPAD
                                : currentMode == InputMode.TOUCHPAD
                                        ? InputMode.KEYPAD
                                        : InputMode.MOUSE);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_STEM_2) {
            if (action == KeyEvent.ACTION_UP) {
                Intent intent = keyboardController.getInputIntent(getPackageManager());
                if (intent != null) {
                    startActivityForResult(intent, INPUT_REQUEST_CODE);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == INPUT_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            keyboardController.onActivityResult(data);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        return ((currentMode == InputMode.MOUSE
                                && ((MouseFragment) getFragment()).onGenericMotionEvent(ev))
                        || (currentMode == InputMode.TOUCHPAD
                                && ((TouchpadFragment) getFragment()).onGenericMotionEvent(ev))
                        || (currentMode == InputMode.KEYPAD
                                && ((KeypadFragment) getFragment()).onGenericMotionEvent(ev)))
                || super.onGenericMotionEvent(ev);
    }

    private Fragment getFragment() {
        return getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    }

    private void flipCard(@InputMode int mode) {
        currentMode = mode;
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(R.animator.card_flip_right_in, R.animator.card_flip_right_out)
                .replace(R.id.fragment_container, getFragment(currentMode))
                .commit();
    }

    private Fragment getFragment(@InputMode int mode) {
        return mode == InputMode.KEYPAD
                ? new KeypadFragment()
                : mode == InputMode.MOUSE ? new MouseFragment() : new TouchpadFragment();
    }

    private void wakeUpAndFinish() {
        wakeLock.acquire(500);
        wakeLock.release();
        finish();
    }
}
