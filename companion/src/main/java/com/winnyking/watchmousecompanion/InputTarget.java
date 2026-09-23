package com.winnyking.watchmousecompanion;

/** Target that turns the parsed watch messages into real interactions. */
public interface InputTarget {
    void onMouseEvent(MouseInput input);

    void onKeyEvent(int modifier, int[] keys);
}