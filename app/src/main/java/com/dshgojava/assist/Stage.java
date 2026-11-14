package com.dshgojava.assist;

/** Lifecycle stages of one quick-assist run. */
public enum Stage {
    IDLE,
    GATHERING,
    THINKING,
    STREAMING,
    DONE,
    FAILED,
    CANCELLED
}
