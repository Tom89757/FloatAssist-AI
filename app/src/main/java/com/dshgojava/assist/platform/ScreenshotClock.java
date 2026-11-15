package com.dshgojava.assist.platform;

/** Converts the screenshot uptime timestamp to wall-clock milliseconds. */
public final class ScreenshotClock {
    private ScreenshotClock() { }

    public static long wallTime(long timestamp, long uptimeMillis, long wallTimeMillis) {
        return wallTimeMillis - (uptimeMillis - timestamp);
    }
}
