package com.winnyking.watchmousecompanion;

/** One relative mouse update received from the watch. */
public final class MouseInput {
    public final boolean left;
    public final boolean right;
    public final boolean middle;
    public final int dx;
    public final int dy;
    public final int wheel;

    MouseInput(boolean left, boolean right, boolean middle, int dx, int dy, int wheel) {
        this.left = left;
        this.right = right;
        this.middle = middle;
        this.dx = dx;
        this.dy = dy;
        this.wheel = wheel;
    }
}