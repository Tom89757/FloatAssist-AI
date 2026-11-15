package com.dshgojava.assist.ui;

/**
 * Caches the last non-zero IME bottom inset. Android can report a visible IME
 * with a transient zero height while the window is being re-laid out.
 */
public final class CardIme {
    private int cachedBottom;

    public int resolve(int reportedBottom, boolean visible) {
        if (!visible) {
            cachedBottom = 0;
            return 0;
        }
        if (reportedBottom > 0) {
            cachedBottom = reportedBottom;
        }
        return cachedBottom;
    }

    public int cachedBottom() {
        return cachedBottom;
    }

    public void reset() {
        cachedBottom = 0;
    }
}
