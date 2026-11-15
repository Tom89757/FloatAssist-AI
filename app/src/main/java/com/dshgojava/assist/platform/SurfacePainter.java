package com.dshgojava.assist.platform;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/** Paints the solid rounded surface with its top rim, hairline and shadow. */
public final class SurfacePainter {
    private final Context context;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF inner = new RectF();
    private float shadedTop = Float.NaN;
    private OverlayPalette shadedWith;

    public SurfacePainter(Context context) {
        this.context = context;
        rim.setStyle(Paint.Style.STROKE);
        hairline.setStyle(Paint.Style.STROKE);
    }

    public void draw(Canvas canvas, RectF rect, float radius, OverlayPalette palette) {
        float top = rect.top;
        float span = OverlayViewUtils.dpf(context, CapsuleMetrics.HEIGHT_DP);
        if (top != shadedTop || palette != shadedWith) {
            fill.setShader(new LinearGradient(0f, top, 0f, top + span,
                    palette.surfaceTop(), palette.surfaceBottom(), Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0f, top, 0f, top + span / 3f,
                    palette.rim(), palette.rim() & 0x00FFFFFF, Shader.TileMode.CLAMP));
            shadedTop = top;
            shadedWith = palette;
        }
        canvas.drawRoundRect(rect, radius, radius, fill);
        float hair = OverlayViewUtils.dpf(context, 1f);
        inner.set(rect);
        inner.inset(hair / 2, hair / 2);
        rim.setStrokeWidth(hair);
        hairline.setStrokeWidth(hair);
        hairline.setColor(palette.hairline());
        canvas.drawRoundRect(inner, radius - hair / 2, radius - hair / 2, rim);
        canvas.drawRoundRect(inner, radius - hair / 2, radius - hair / 2, hairline);
    }
}
