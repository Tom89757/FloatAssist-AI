package com.dshgojava.assist.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import com.dshgojava.assist.R;
import com.dshgojava.assist.platform.BallGeometry;
import com.dshgojava.assist.platform.OverlayViewUtils;
import java.util.function.Supplier;

/** Small non-touchable pill that explains recording and transcription state. */
public final class AssistVoiceHud {
    private static final String TAG = "DshAssist";
    private static final long ERROR_MS = 3_000L;

    private final Context context;
    private final WindowManager windows;
    private final int windowType;
    private final Supplier<BallGeometry.SafeArea> safeArea;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable dismissTask = this::dismissNow;

    private TextView view;
    private WindowManager.LayoutParams params;

    public AssistVoiceHud(Context context, WindowManager windows, int windowType,
                          Supplier<BallGeometry.SafeArea> safeArea) {
        this.context = context;
        this.windows = windows;
        this.windowType = windowType;
        this.safeArea = safeArea;
    }

    public boolean recording(String partial) {
        String text = context.getString(R.string.assist_voice_recording);
        if (partial != null && !partial.isBlank()) text += "\n" + partial;
        return show(text, false);
    }

    public boolean transcribing() {
        return show(context.getString(R.string.assist_voice_transcribing), false);
    }

    public boolean error(String message) {
        boolean shown = show(message == null || message.isBlank()
                ? context.getString(R.string.assist_voice_failed) : message, true);
        if (!shown) return false;
        main.removeCallbacks(dismissTask);
        main.postDelayed(dismissTask, ERROR_MS);
        return true;
    }

    public void dismissNow() {
        main.removeCallbacks(dismissTask);
        TextView current = view;
        view = null;
        params = null;
        if (current == null) return;
        try {
            windows.removeViewImmediate(current);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean show(String text, boolean error) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> show(text, error));
            return true;
        }
        main.removeCallbacks(dismissTask);
        if (view == null) {
            TextView next = new TextView(context);
            next.setTextColor(Color.WHITE);
            next.setTextSize(14);
            next.setGravity(Gravity.CENTER);
            int padH = OverlayViewUtils.dp(context, 16);
            int padV = OverlayViewUtils.dp(context, 10);
            next.setPadding(padH, padV, padH, padV);
            next.setMaxWidth(Math.max(1, safeArea().screenW() - OverlayViewUtils.dp(context, 64)));
            view = next;
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.y = safeArea().bottom() + OverlayViewUtils.dp(context, 88);
            params.setTitle(context.getString(R.string.assist_voice_hud_title));
            try {
                windows.addView(next, params);
            } catch (RuntimeException failure) {
                Log.w(TAG, "voice HUD addView failed", failure);
                view = null;
                params = null;
                return false;
            }
        }
        view.setText(text);
        GradientDrawable background = new GradientDrawable();
        background.setColor(error ? Color.rgb(178, 44, 44) : surfaceColor());
        background.setCornerRadius(OverlayViewUtils.dpf(context, 22f));
        view.setBackground(background);
        return true;
    }

    private int surfaceColor() {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES
                ? Color.rgb(38, 42, 48) : Color.rgb(31, 36, 43);
    }

    private BallGeometry.SafeArea safeArea() {
        BallGeometry.SafeArea area = safeArea.get();
        return area == null ? new BallGeometry.SafeArea(
                context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels, 0, 0, 0, 0) : area;
    }
}
