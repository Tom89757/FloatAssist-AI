package com.dshgojava.assist.platform;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Paints the shared running / paused / waiting / stopped state mark. */
public final class MarkPainter {
    private final Context context;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public MarkPainter(Context context) {
        this.context = context;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    public void draw(Canvas canvas, float cx, float cy, MarkLook look, OverlayPalette palette,
                     float phase, float alpha) {
        if (alpha <= 0f) return;
        ring.setStrokeWidth(OverlayViewUtils.dpf(context, 2f));
        float radius = OverlayViewUtils.dpf(context, 10f);
        switch (look) {
            case RUNNING -> {
                ring.setColor(palette.accent());
                ring.setAlpha(alpha(0.3f + 0.7f * phase, alpha));
                canvas.drawCircle(cx, cy, radius, ring);
                fill.setColor(palette.accent());
                fill.setAlpha(alpha(1f, alpha));
                canvas.drawCircle(cx, cy, OverlayViewUtils.dpf(context, 3.5f), fill);
            }
            case PAUSED -> {
                ring.setColor(palette.muted());
                ring.setAlpha(alpha(0.34f, alpha));
                canvas.drawCircle(cx, cy, radius, ring);
                fill.setColor(palette.muted());
                fill.setAlpha(alpha(1f, alpha));
                float width = OverlayViewUtils.dpf(context, 2.5f);
                float height = OverlayViewUtils.dpf(context, 9f);
                float gap = OverlayViewUtils.dpf(context, 3f);
                rect.set(cx - gap / 2 - width, cy - height / 2, cx - gap / 2, cy + height / 2);
                canvas.drawRoundRect(rect, width / 2, width / 2, fill);
                rect.set(cx + gap / 2, cy - height / 2, cx + gap / 2 + width, cy + height / 2);
                canvas.drawRoundRect(rect, width / 2, width / 2, fill);
            }
            case WAITING -> {
                ring.setColor(palette.accent());
                ring.setAlpha(alpha(1f, alpha));
                canvas.drawCircle(cx, cy, radius, ring);
                fill.setColor(palette.accent());
                fill.setAlpha(alpha(1f, alpha));
                float step = OverlayViewUtils.dpf(context, 4.5f);
                float dot = OverlayViewUtils.dpf(context, 1.6f);
                for (int i = -1; i <= 1; i++) canvas.drawCircle(cx + i * step, cy, dot, fill);
            }
            case STOPPED -> {
                ring.setColor(palette.muted());
                ring.setAlpha(alpha(0.34f, alpha));
                canvas.drawCircle(cx, cy, radius, ring);
                fill.setColor(palette.muted());
                fill.setAlpha(alpha(1f, alpha));
                float size = OverlayViewUtils.dpf(context, 8f);
                rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
                canvas.drawRoundRect(rect, OverlayViewUtils.dpf(context, 2f),
                        OverlayViewUtils.dpf(context, 2f), fill);
            }
        }
    }

    private static int alpha(float value, float multiplier) {
        return Math.max(0, Math.min(255, Math.round(255 * value * multiplier)));
    }
}
