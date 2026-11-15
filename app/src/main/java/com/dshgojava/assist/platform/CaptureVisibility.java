package com.dshgojava.assist.platform;

/** Paired main-thread holds; overlapping pet and agent captures restore after both finish. */
public final class CaptureVisibility {
    private int holds;

    public boolean hidden() {
        return holds > 0;
    }

    public synchronized boolean update(boolean hide) {
        boolean before = hidden();
        if (hide) holds++;
        else if (holds > 0) holds--;
        return before != hidden();
    }
}
