package com.winnyking.watchmousecompanion;

import android.util.Log;

/**
 * Maps the relative mouse deltas the watch sends to touch gestures on this device.
 *
 * <p>There is no system cursor on a phone/tablet, so the companion owns a logical cursor:
 *
 * <ul>
 *   <li>Left click (press + release without moving) → tap at the cursor.
 *   <li>Left button held while moving (the watch touchpad drag) → a real drag gesture.
 *   <li>Right click → long-press at the cursor.
 *   <li>Middle button → back.
 *   <li>Wheel → vertical swipe.
 * </ul>
 *
 * <p>Keyboard input cannot be injected without a focused editable field, so it is logged and
 * ignored for now (see README "Limitations").
 */
public final class GestureTarget implements InputTarget {

    private static final String TAG = "GestureTarget";

    private static final float SENSITIVITY = 3.0f;
    private static final float DRAG_THRESHOLD_PX = 32.0f;
    private static final long TAP_DURATION_MS = 60;

    private final float screenWidth;
    private final float screenHeight;

    private float cursorX;
    private float cursorY;

    private boolean wasLeft;
    private boolean wasRight;
    private boolean wasMiddle;
    private boolean dragActive;
    private float dragStartX;
    private float dragStartY;

    public GestureTarget(float screenWidth, float screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        cursorX = screenWidth / 2.0f;
        cursorY = screenHeight / 2.0f;
    }

    private CompanionInputService service() {
        CompanionInputService service = CompanionInputService.getInstance();
        return service;
    }

    @Override
    public synchronized void onMouseEvent(MouseInput input) {
        cursorX = clamp(cursorX + input.dx * SENSITIVITY, 0, screenWidth);
        cursorY = clamp(cursorY + input.dy * SENSITIVITY, 0, screenHeight);
        handleWheel(input);
        handleLeft(input);
        handleRight(input);
        handleMiddle(input);
    }

    @Override
    public synchronized void onKeyEvent(int modifier, int[] keys) {
        CompanionInputService service = service();
        if (service == null) {
            return;
        }
        StringBuilder pressed = new StringBuilder("mod=").append(modifier);
        for (int key : keys) {
            if (key != 0) {
                pressed.append(" ").append(key);
            }
        }
        // TODO(feature): type into a focused editable field with ACTION_SET_TEXT.
        Log.i(TAG, "key event ignored (no focused field support): " + pressed);
    }

    private void handleWheel(MouseInput input) {
        if (input.wheel == 0) {
            return;
        }
        CompanionInputService service = service();
        if (service == null) {
            return;
        }
        float len = input.wheel * 48.0f;
        float y2 = clamp(cursorY + len, 0, screenHeight);
        service.performSwipe(cursorX, cursorY, cursorX, y2, 150);
    }

    private void handleLeft(MouseInput input) {
        CompanionInputService service = service();
        if (service == null) {
            return;
        }
        if (!wasLeft && input.left) {
            // Button just went down: remember where so a later drag can start from here.
            wasLeft = true;
            dragActive = false;
            dragStartX = cursorX;
            dragStartY = cursorY;
        } else if (wasLeft && !input.left) {
            outputLeftRelease(service);
            wasLeft = false;
        } else if (wasLeft && input.left && !dragActive) {
            float dx = cursorX - dragStartX;
            float dy = cursorY - dragStartY;
            if (dx * dx + dy * dy >= DRAG_THRESHOLD_PX * DRAG_THRESHOLD_PX) {
                dragActive = true;
            }
        }
    }

    /** A button-up without any movement is a tap; with movement it finishes a drag. */
    private void outputLeftRelease(CompanionInputService service) {
        if (dragActive) {
            float dx = cursorX - dragStartX;
            float dy = cursorY - dragStartY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            long duration = (long) Math.max(120, Math.min(500, dist));
            service.performSwipe(dragStartX, dragStartY, cursorX, cursorY, duration);
            Log.i(TAG, "drag (" + dragStartX + "," + dragStartY + ") -> ("
                    + cursorX + "," + cursorY + ")");
        } else {
            service.performTap(cursorX, cursorY);
            Log.i(TAG, "tap at (" + cursorX + "," + cursorY + ")");
        }
    }

    private void handleRight(MouseInput input) {
        CompanionInputService service = service();
        if (service == null) {
            return;
        }
        if (!wasRight && input.right) {
            wasRight = true;
            service.performLongPress(cursorX, cursorY);
            Log.i(TAG, "long-press at (" + cursorX + "," + cursorY + ")");
        } else if (wasRight && !input.right) {
            wasRight = false;
        }
    }

    private void handleMiddle(MouseInput input) {
        CompanionInputService service = service();
        if (service == null) {
            return;
        }
        if (!wasMiddle && input.middle) {
            wasMiddle = true;
            service.performBack();
        } else if (wasMiddle && !input.middle) {
            wasMiddle = false;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}