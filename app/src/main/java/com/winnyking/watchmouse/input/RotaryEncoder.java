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

import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Small framework-only replacement for the rotary encoder helpers that used to live in the Wear OS
 * support library.
 */
public final class RotaryEncoder {
    private RotaryEncoder() {}

    /** Whether the given scroll event originates from a rotation of the watch crown. */
    public static boolean isFromRotaryEncoder(MotionEvent event) {
        return (event.getSource() & InputDevice.SOURCE_ROTARY_ENCODER) != 0;
    }

    /** Returns the signed scroll amount of the rotation, in pixel units. */
    public static float getRotaryAxisValue(MotionEvent event) {
        return event.getAxisValue(MotionEvent.AXIS_VSCROLL);
    }
}