package com.dshgojava.assist.platform;

import android.animation.ValueAnimator;
import android.os.Build;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Shared animation curves and durations. */
public final class OverlayMotion {
    public static final PathInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    public static final PathInterpolator GLIDE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    public static final long MORPH_MS = 340L;
    public static final long RESIZE_MS = 240L;
    public static final long QUICK_MS = 160L;
    public static final long BREATH_MS = 2_400L;

    private OverlayMotion() { }

    public static boolean on() {
        return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
    }

    public static void fadeIdle(View view, float alpha, boolean animated, long durationMs) {
        view.animate().cancel();
        if (!animated || !on()) {
            view.setAlpha(alpha);
            return;
        }
        view.animate().alpha(alpha).setDuration(durationMs).setInterpolator(EMPHASIZED).start();
    }
}
