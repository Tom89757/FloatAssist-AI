package com.dshgojava.assist.platform;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/** Overlay idle opacity and auto-fold settings. */
public record OverlayPrefs(float idleOpacity, boolean autoFold) {
    public static final String FILE = "dsh_phone_overlay";
    public static final String K_OPACITY = "idleOpacity";
    public static final String K_AUTO_FOLD = "autoFold";
    public static final float DEFAULT_OPACITY = 0.7f;
    public static final float MIN_OPACITY = 0.3f;
    public static final float MAX_OPACITY = 1f;

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idleOpacity", (double) idleOpacity);
        out.put("autoCollapse", autoFold);
        return out;
    }

    public static float clampOpacity(float value) {
        if (Float.isNaN(value)) return DEFAULT_OPACITY;
        float clamped = Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, value));
        return Math.round(clamped * 100f) / 100f;
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static OverlayPrefs read(Context context) {
        return from(prefs(context));
    }

    public static OverlayPrefs from(SharedPreferences prefs) {
        return new OverlayPrefs(clampOpacity(prefs.getFloat(K_OPACITY, DEFAULT_OPACITY)),
                prefs.getBoolean(K_AUTO_FOLD, false));
    }

    public static OverlayPrefs write(Context context, Map<?, ?> map) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor edit = prefs.edit();
        Object opacity = map == null ? null : map.get("idleOpacity");
        if (opacity instanceof Number number) {
            edit.putFloat(K_OPACITY, clampOpacity(number.floatValue()));
        }
        Object auto = map == null ? null : map.get("autoCollapse");
        if (auto instanceof Boolean value) edit.putBoolean(K_AUTO_FOLD, value);
        edit.apply();
        return from(prefs);
    }
}
