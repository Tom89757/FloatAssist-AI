package com.dshgojava.assist.platform;

import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;

/** dp helpers and the control's soft outline shadow. */
public final class OverlayViewUtils {
    private OverlayViewUtils() { }

    public static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static float dpf(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    public static void softShadow(View view, OverlayPalette palette, float elevationDp) {
        view.setElevation(dpf(view.getContext(), elevationDp));
        if (Build.VERSION.SDK_INT >= 28) {
            int ink = palette.shadow() & 0x00FFFFFF;
            view.setOutlineSpotShadowColor(ink | (palette.dark()
                    ? 0xFF000000 : 0x80000000));
            view.setOutlineAmbientShadowColor(ink | (palette.dark()
                    ? 0xCC000000 : 0x66000000));
        }
    }
}
