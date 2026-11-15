package com.dshgojava.assist.platform;

/** The control's solid mist-neutral palette: light and dark variants. */
public final class OverlayPalette {
    public static final OverlayPalette LIGHT = new OverlayPalette(
            0xFFFDFDFF, 0xFFF5F7FC, 0x1A0B152D, 0xE6FFFFFF, 0xFF0B152D,
            0xFF1E232E, 0xFF4F5562, 0xFF646974,
            0xFF4B67B4, 0xFFDEE8FF, 0xFF213262, 0xFFFFFFFF,
            0xFFECEFF6, 0xFFEEF1F7, 0x1F1E232E, 0x3D0B152D,
            0xFFA54743, 0xFFFBE5E3, 0xFF642724,
            false);

    public static final OverlayPalette DARK = new OverlayPalette(
            0xFF2C313C, 0xFF262A34, 0x1AFFFFFF, 0x24FFFFFF, 0xFF000000,
            0xFFE6E9F0, 0xFFB6BBC5, 0xFF9A9EA9,
            0xFF97B1F0, 0xFF2E3C5F, 0xFFD8E1F6, 0xFF121C34,
            0xFF393E4A, 0xFF333844, 0x2EFFFFFF, 0x40FFFFFF,
            0xFFE69089, 0xFF4E2D2C, 0xFFF8D9D7,
            true);

    private final int surfaceTop;
    private final int surfaceBottom;
    private final int hairline;
    private final int rim;
    private final int shadow;
    private final int ink;
    private final int muted;
    private final int faint;
    private final int accent;
    private final int accentSoft;
    private final int onAccentSoft;
    private final int onAccent;
    private final int tonal;
    private final int field;
    private final int ripple;
    private final int outline;
    private final int danger;
    private final int dangerSoft;
    private final int onDangerSoft;
    private final boolean dark;

    public OverlayPalette(int surfaceTop, int surfaceBottom, int hairline, int rim, int shadow,
                          int ink, int muted, int faint, int accent, int accentSoft,
                          int onAccentSoft, int onAccent, int tonal, int field, int ripple,
                          int outline, int danger, int dangerSoft, int onDangerSoft, boolean dark) {
        this.surfaceTop = surfaceTop;
        this.surfaceBottom = surfaceBottom;
        this.hairline = hairline;
        this.rim = rim;
        this.shadow = shadow;
        this.ink = ink;
        this.muted = muted;
        this.faint = faint;
        this.accent = accent;
        this.accentSoft = accentSoft;
        this.onAccentSoft = onAccentSoft;
        this.onAccent = onAccent;
        this.tonal = tonal;
        this.field = field;
        this.ripple = ripple;
        this.outline = outline;
        this.danger = danger;
        this.dangerSoft = dangerSoft;
        this.onDangerSoft = onDangerSoft;
        this.dark = dark;
    }

    public int surfaceTop() { return surfaceTop; }
    public int surfaceBottom() { return surfaceBottom; }
    public int hairline() { return hairline; }
    public int rim() { return rim; }
    public int shadow() { return shadow; }
    public int ink() { return ink; }
    public int muted() { return muted; }
    public int faint() { return faint; }
    public int accent() { return accent; }
    public int accentSoft() { return accentSoft; }
    public int onAccentSoft() { return onAccentSoft; }
    public int onAccent() { return onAccent; }
    public int tonal() { return tonal; }
    public int field() { return field; }
    public int ripple() { return ripple; }
    public int outline() { return outline; }
    public int danger() { return danger; }
    public int dangerSoft() { return dangerSoft; }
    public int onDangerSoft() { return onDangerSoft; }
    public boolean dark() { return dark; }
}
