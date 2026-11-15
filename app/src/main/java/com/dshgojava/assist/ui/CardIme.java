package com.dshgojava.assist.ui;

/**
 * Recovers the stable full keyboard height. For a non-fullscreen, movable
 * overlay the IME bottom inset is the overlap between the window and the
 * keyboard, so it changes as the window moves. Reconstructing the full height
 * from the overlap and the window position avoids that feedback loop.
 */
public final class CardIme {
    private int cachedKeyboard;

    /** Returns the full keyboard height in pixels; 0 while the IME is hidden. */
    public int resolve(int reportedBottom, boolean visible,
                       int screenH, int cardY, int cardHeight) {
        if (!visible) {
            cachedKeyboard = 0;
            return 0;
        }
        if (reportedBottom > 0) {
            int full = reportedBottom + screenH - cardY - cardHeight;
            if (full > cachedKeyboard) cachedKeyboard = full;
        }
        return cachedKeyboard;
    }

    public int cachedKeyboard() {
        return cachedKeyboard;
    }

    public void reset() {
        cachedKeyboard = 0;
    }
}
