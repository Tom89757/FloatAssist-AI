package com.dshgojava.assist.platform;

/** Scroll-direction bits collected from an accessibility node's action list. */
public final class Scroll {
    public static final int FORWARD = 1;
    public static final int BACKWARD = 2;
    public static final int UP = 4;
    public static final int DOWN = 8;
    public static final int LEFT = 16;
    public static final int RIGHT = 32;

    private Scroll() { }
}
