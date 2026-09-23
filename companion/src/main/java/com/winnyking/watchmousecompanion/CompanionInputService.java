package com.winnyking.watchmousecompanion;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import java.util.List;
import java.util.ArrayList;

/**
 * The accessibility service that turns incoming TCP input into real touch gestures on this device.
 *
 * <p>Android has no "move the pointer" API, so the companion keeps a logical cursor and maps events
 * to gestures: tap, long-press (right button) and drag (left button held + move, which the watch
 * touchpad mode produces naturally).
 */
public class CompanionInputService extends AccessibilityService {

    private static final String TAG = "CompanionInputService";

    private static volatile CompanionInputService instance;

    /** @return the running service instance, or {@code null} if accessibility is not enabled. */
    public static CompanionInputService getInstance() {
        return instance;
    }

    /** @return {@code true} if the user enabled this app's accessibility service. */
    public static boolean isRunning() {
        return instance != null;
    }

    private final Handler mainThread = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    /** Tap the logical cursor position. */
    public void performTap(float x, float y) {
        dispatch(x, y, x, y, 60);
    }

    /** Long-press at the logical cursor position (used for the right mouse button). */
    public void performLongPress(float x, float y) {
        dispatch(x, y, x, y, 600);
    }

    /** Drag from one point to another (used when the left button is held while moving). */
    public void performSwipe(float x1, float y1, float x2, float y2, long durationMs) {
        dispatch(x1, y1, x2, y2, durationMs);
    }

    public void performBack() {
        mainThread.post(
                () -> {
                    if (instance != null) {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                });
    }

    private void dispatch(
            final float x1, final float y1, final float x2, final float y2, final long durationMs) {
        mainThread.post(
                () -> {
                    if (instance == null) {
                        return;
                    }
                    Path path = new Path();
                    path.moveTo(x1, y1);
                    path.lineTo(x2, y2);
                    GestureDescription.Builder builder = new GestureDescription.Builder();
                    GestureDescription.StrokeDescription stroke =
                            new GestureDescription.StrokeDescription(path, 0, durationMs);
                    builder.addStroke(stroke);
                    if (!dispatchGesture(builder.build(), null, null)) {
                        Log.w(TAG, "dispatchGesture rejected; touch interaction may be blocked");
                    }
                });
    }
}