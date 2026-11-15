package com.dshgojava.assist.platform;

import java.util.List;

/** The control's sizes in dp and the pure width decisions behind the capsule. */
public final class CapsuleMetrics {
    public static final int HEIGHT_DP = 48;
    public static final int RADIUS_DP = 24;
    public static final int KEY_DP = 48;
    public static final int DISC_DP = 36;
    public static final int KEY_GAP_DP = 4;
    public static final int LOCK_DP = 14;
    public static final int LOCK_GAP_DP = 6;
    public static final int CHEVRON_DP = 14;
    public static final int CHEVRON_GAP_DP = 6;
    public static final int LINE_END_DP = 6;
    public static final int STOPPED_END_DP = 20;
    public static final int MIN_DP = 120;
    public static final int MAX_DP = 400;
    public static final int STEP_DP = 8;
    public static final int EDGE_DP = 12;
    public static final int TOP_GAP_DP = 6;

    public enum Slot { MARK, LINE, PAUSE, STOP }

    private CapsuleMetrics() { }

    public static List<Slot> slots(boolean stopped) {
        return stopped
                ? List.of(Slot.MARK, Slot.LINE)
                : List.of(Slot.MARK, Slot.LINE, Slot.PAUSE, Slot.STOP);
    }

    /** The capsule's content width in dp for a line {@code textDp} wide. */
    public static float contentDp(float textDp, boolean lock, boolean stopped, boolean chevron) {
        float width = KEY_DP + textDp;
        if (lock) width += LOCK_DP + LOCK_GAP_DP;
        if (chevron) width += CHEVRON_GAP_DP + CHEVRON_DP;
        width += stopped
                ? STOPPED_END_DP
                : (float) (LINE_END_DP + KEY_DP + KEY_GAP_DP + KEY_DP);
        return width;
    }

    public static int maxPx(int screenW, float density) {
        int edges = screenW - Math.round(2 * EDGE_DP * density);
        int cap = Math.round(MAX_DP * density);
        return Math.max(Math.round(MIN_DP * density), Math.min(edges, cap));
    }

    public static int widthPx(float contentPx, int screenW, float density) {
        float step = STEP_DP * density;
        int stepped = Math.round((float) Math.ceil(contentPx / step) * step);
        int min = Math.round(MIN_DP * density);
        return Math.max(min, Math.min(stepped, maxPx(screenW, density)));
    }

    /** One step on a short screen (landscape), otherwise three. */
    public static int steps(float screenHeightDp) {
        return screenHeightDp < 560f ? 1 : StepHistory.CAPACITY;
    }
}
