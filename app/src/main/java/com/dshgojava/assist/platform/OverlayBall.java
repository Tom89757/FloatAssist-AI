package com.dshgojava.assist.platform;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;

import java.util.function.Supplier;

/** The folded control: a 48 dp ball with the capsule's surface, state mark and hold-to-stop ring. */
public final class OverlayBall {
    private static final String TAG = "DshA11y";
    private static final String K_SIDE = "ballSide";
    private static final String K_Y = "ballY";
    public static final float DEFAULT_Y = 0.3f;
    public static final int BALL_DP = CapsuleMetrics.HEIGHT_DP;
    public static final int PAD_DP = 10;
    public static final int EDGE_DP = CapsuleMetrics.EDGE_DP;
    public static final long GLIDE_MS = 260L;
    public static final long MARK_MS = 200L;
    public static final float PRESS_SCALE = 0.94f;
    /** Stable custom accessibility action, equivalent to the source R.id.overlay_action_stop. */
    public static final int OVERLAY_ACTION_STOP = 0x0F0A1101;

    private final Context context;
    private final WindowManager windows;
    private final Supplier<BallGeometry.SafeArea> area;
    private final OverlayPalette palette;
    private final Runnable onTap;
    private final Runnable onStop;
    private final Runnable onTouchDown;
    private Runnable onHoldRelease = () -> { };
    private Runnable onHoldCancel = () -> { };
    private final SharedPreferences prefs;
    private final BallView ball;
    private final FrameLayout root;
    private final int pad;
    private final int diameter;
    private final int size;
    private final int margin;
    private final WindowManager.LayoutParams params;
    private final int slop;
    private final HoldToStop hold = new HoldToStop();

    private BallGeometry.Dock dock;
    private ValueAnimator glide;
    private ValueAnimator markFade;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private boolean dragging;
    private boolean holdFired;
    private VelocityTracker tracker;

    public OverlayBall(Context context, WindowManager windows,
                       Supplier<BallGeometry.SafeArea> area, OverlayPalette palette,
                       Runnable onTap, Runnable onStop) {
        this(context, windows, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                area, palette, onTap, onStop, () -> { });
    }

    public OverlayBall(Context context, WindowManager windows,
                       Supplier<BallGeometry.SafeArea> area, OverlayPalette palette,
                       Runnable onTap, Runnable onStop, Runnable onTouchDown) {
        this(context, windows, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                area, palette, onTap, onStop, onTouchDown);
    }

    /**
     * Same ball, hosted with an explicit window type. QuickAssist uses
     * TYPE_APPLICATION_OVERLAY so the foreground service can show the ball without
     * requiring the accessibility service to stay connected.
     */
    public OverlayBall(Context context, WindowManager windows, int windowType,
                       Supplier<BallGeometry.SafeArea> area, OverlayPalette palette,
                       Runnable onTap, Runnable onStop, Runnable onTouchDown) {
        this.context = context;
        this.windows = windows;
        this.area = area;
        this.palette = palette;
        this.onTap = onTap;
        this.onStop = onStop;
        this.onTouchDown = onTouchDown;
        this.prefs = context.getSharedPreferences(OverlayPrefs.FILE, Context.MODE_PRIVATE);
        this.dock = new BallGeometry.Dock(
                "left".equals(prefs.getString(K_SIDE, null))
                        ? BallGeometry.Side.LEFT : BallGeometry.Side.RIGHT,
                prefs.getFloat(K_Y, DEFAULT_Y));
        this.pad = OverlayViewUtils.dp(context, PAD_DP);
        this.diameter = OverlayViewUtils.dp(context, BALL_DP);
        this.size = diameter + 2 * pad;
        this.margin = BallGeometry.margin(OverlayViewUtils.dp(context, EDGE_DP), pad);
        this.ball = new BallView(context);
        this.root = new FrameLayout(context);
        root.setPadding(pad, pad, pad, pad);
        root.setClipToPadding(false);
        root.setClipChildren(false);
        root.addView(ball, new FrameLayout.LayoutParams(diameter, diameter));
        this.params = new WindowManager.LayoutParams(size, size,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        ball.setOnClickListener(view -> onTap.run());
        ball.setOnTouchListener(this::onTouch);
    }

    public FrameLayout root() { return root; }
    public WindowManager.LayoutParams params() { return params; }
    public BallGeometry.Dock dock() { return dock; }
    public int pad() { return pad; }
    public int diameter() { return diameter; }
    public int size() { return size; }
    public boolean attached() { return root.isAttachedToWindow(); }
    public BallGeometry.WindowPos pos() { return new BallGeometry.WindowPos(params.x, params.y); }

    /**
     * QuickAssist extension over the copied ball: the original callbacks expose the
     * hold threshold only. Hold-to-talk also needs the finger-up/cancel edge.
     */
    public void setHoldReleaseListener(Runnable release, Runnable cancel) {
        onHoldRelease = release == null ? () -> { } : release;
        onHoldCancel = cancel == null ? () -> { } : cancel;
    }

    public boolean attach(CharSequence title, boolean untouchable, BallGeometry.WindowPos at) {
        if (attached()) return true;
        params.setTitle(title);
        if (untouchable) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.x = at.x();
        params.y = at.y();
        try {
            windows.addView(root, params);
            root.setVisibility(View.VISIBLE);
            return true;
        } catch (Exception error) {
            Log.w(TAG, "ball addView failed", error);
            return false;
        }
    }

    public void detach() {
        cancelGlide();
        if (markFade != null) markFade.cancel();
        markFade = null;
        finishHold(true);
        endHold();
        endDrag();
        root.animate().cancel();
        try {
            windows.removeView(root);
        } catch (Exception ignored) { }
    }

    public void setLook(MarkLook look, CharSequence description, boolean badge,
                        CharSequence stopLabel) {
        ball.look = look;
        ball.badge = badge;
        ball.setContentDescription(description);
        ball.stopLabel = stopLabel;
    }

    public void setIdle(float alpha, boolean animated, long durationMs) {
        OverlayMotion.fadeIdle(root, alpha, animated, durationMs);
    }

    public BallGeometry.WindowPos dockPos() {
        return BallGeometry.position(dock, size, size, area.get(), margin);
    }

    public void move(BallGeometry.WindowPos to) {
        cancelGlide();
        params.x = to.x();
        params.y = to.y();
        update();
    }

    public void glideTo(BallGeometry.WindowPos to, boolean animated) {
        glideTo(to, animated, GLIDE_MS, null);
    }

    public void glideTo(BallGeometry.WindowPos to, boolean animated, long durationMs,
                        Runnable then) {
        cancelGlide();
        int fromX = params.x;
        int fromY = params.y;
        if (!animated || !OverlayMotion.on() || (fromX == to.x() && fromY == to.y())) {
            move(to);
            if (then != null) then.run();
            return;
        }
        glide = ValueAnimator.ofFloat(0f, 1f);
        glide.setDuration(durationMs);
        glide.setInterpolator(OverlayMotion.GLIDE);
        glide.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            params.x = Math.round(fromX + (to.x() - fromX) * fraction);
            params.y = Math.round(fromY + (to.y() - fromY) * fraction);
            update();
        });
        glide.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (glide == animation) glide = null;
                if (!cancelled && then != null) then.run();
            }
        });
        glide.start();
    }

    public void cancelGlide() {
        if (glide != null) glide.cancel();
        glide = null;
    }

    public void fadeMark(float to, boolean animated) {
        fadeMark(to, animated, MARK_MS);
    }

    public void fadeMark(float to, boolean animated, long durationMs) {
        if (markFade != null) markFade.cancel();
        markFade = null;
        if (!animated || !OverlayMotion.on()) {
            ball.markAlpha = to;
            return;
        }
        markFade = ValueAnimator.ofFloat(ball.markAlpha, to);
        markFade.setDuration(durationMs);
        markFade.setInterpolator(OverlayMotion.GLIDE);
        markFade.addUpdateListener(animation -> ball.markAlpha = (float) animation.getAnimatedValue());
        markFade.start();
    }

    private void update() {
        try {
            if (root.isAttachedToWindow()) windows.updateViewLayout(root, params);
        } catch (Exception error) {
            Log.w(TAG, "ball update failed", error);
        }
    }

    private boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                cancelGlide();
                onTouchDown.run();
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                dragging = false;
                if (tracker != null) tracker.recycle();
                tracker = VelocityTracker.obtain();
                track(tracker, event);
                press(true);
                startHold();
            }
            case MotionEvent.ACTION_MOVE -> {
                if (tracker != null) track(tracker, event);
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!dragging && hold.state() != HoldToStop.State.FIRED
                        && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    dragging = true;
                    hold.moved();
                    endHold();
                    press(false);
                }
                if (dragging) {
                    BallGeometry.WindowPos position = BallGeometry.clampDrag(
                            startX + Math.round(dx), startY + Math.round(dy), size, size, area.get());
                    params.x = position.x();
                    params.y = position.y();
                    update();
                }
            }
            case MotionEvent.ACTION_UP -> {
                if (tracker != null) track(tracker, event);
                press(false);
                HoldToStop.Release release = hold.up(SystemClock.uptimeMillis());
                endHold();
                switch (release) {
                    case TAP -> view.performClick();
                    case DRAG -> settle(velocityX());
                    default -> { }
                }
                finishHold(false);
                endDrag();
            }
            case MotionEvent.ACTION_CANCEL -> {
                press(false);
                hold.cancel();
                endHold();
                finishHold(true);
                if (dragging) settle(0f);
                endDrag();
            }
            default -> { }
        }
        return true;
    }

    private final Runnable holdTick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (hold.tick(now)) {
                fire();
                return;
            }
            ball.holdProgress = hold.progress(now);
            if (hold.nextAt() != null) ball.postOnAnimation(this);
        }
    };

    private void fire() {
        ball.holdProgress = 1f;
        holdFired = true;
        ball.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        press(false);
        onStop.run();
    }

    private void finishHold(boolean cancelled) {
        if (!holdFired) return;
        holdFired = false;
        Runnable callback = cancelled ? onHoldCancel : onHoldRelease;
        callback.run();
    }

    private void startHold() {
        endHold();
        hold.down(SystemClock.uptimeMillis());
        ball.postOnAnimation(holdTick);
    }

    private void endHold() {
        ball.removeCallbacks(holdTick);
        if (hold.state() != HoldToStop.State.FIRED) ball.holdProgress = 0f;
    }

    private static void track(VelocityTracker tracker, MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        copy.setLocation(event.getRawX(), event.getRawY());
        tracker.addMovement(copy);
        copy.recycle();
    }

    private float velocityX() {
        if (tracker == null) return 0f;
        tracker.computeCurrentVelocity(1000);
        return tracker.getXVelocity();
    }

    private void endDrag() {
        dragging = false;
        if (tracker != null) tracker.recycle();
        tracker = null;
    }

    private void settle(float velocityX) {
        dock = BallGeometry.dockFor(params.x, params.y, size, size, area.get(), margin, velocityX);
        prefs.edit()
                .putString(K_SIDE, dock.side() == BallGeometry.Side.LEFT ? "left" : "right")
                .putFloat(K_Y, dock.yFraction())
                .apply();
        glideTo(BallGeometry.position(dock, size, size, area.get(), margin), OverlayMotion.on());
    }

    private void press(boolean down) {
        float scale = down ? PRESS_SCALE : 1f;
        if (!OverlayMotion.on()) {
            ball.setScaleX(scale);
            ball.setScaleY(scale);
            return;
        }
        ball.animate().scaleX(scale).scaleY(scale)
                .setDuration(down ? 90 : 200)
                .setInterpolator(OverlayMotion.GLIDE).start();
    }

    private final class BallView extends View {
        private final MarkPainter painter = new MarkPainter(getContext());
        private final SurfacePainter surface = new SurfacePainter(getContext());
        private final RectF circle = new RectF();
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator breath;
        private float phase = 1f;
        private boolean shown;
        private MarkLook look = MarkLook.RUNNING;
        private boolean badge;
        private float holdProgress;
        private CharSequence stopLabel;
        private float markAlpha = 1f;

        BallView(Context context) {
            super(context);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            OverlayViewUtils.softShadow(this, palette, 5f);
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (stopLabel != null) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        OVERLAY_ACTION_STOP, stopLabel));
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == OVERLAY_ACTION_STOP && stopLabel != null) {
                onStop.run();
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }

        @Override
        public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            shown = isVisible;
            syncBreath();
        }

        @Override
        protected void onDetachedFromWindow() {
            shown = false;
            syncBreath();
            super.onDetachedFromWindow();
        }

        private void syncBreath() {
            boolean want = shown && look == MarkLook.RUNNING && OverlayMotion.on();
            if (want && breath == null) {
                breath = ValueAnimator.ofFloat(0f, 1f);
                breath.setDuration(OverlayMotion.BREATH_MS);
                breath.setRepeatCount(ValueAnimator.INFINITE);
                breath.setInterpolator(new android.view.animation.LinearInterpolator());
                breath.addUpdateListener(animation -> {
                    float value = (float) animation.getAnimatedValue();
                    phase = (float) (0.5 + 0.5 * Math.cos(2 * Math.PI * value));
                    invalidate();
                });
                breath.start();
            } else if (!want && breath != null) {
                breath.cancel();
                breath = null;
                phase = 1f;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            circle.set(0f, 0f, getWidth(), getHeight());
            surface.draw(canvas, circle, getWidth() / 2f, palette);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            painter.draw(canvas, cx, cy, look, palette, phase, markAlpha);
            if (holdProgress > 0f) {
                ringPaint.setColor(palette.danger());
                ringPaint.setStrokeWidth(OverlayViewUtils.dpf(getContext(), 2.5f));
                float radius = OverlayViewUtils.dpf(getContext(), 10f);
                canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                        -90f, 360f * holdProgress, false, ringPaint);
            }
            if (badge && markAlpha > 0f) {
                float off = getWidth() / 2f * 0.7071f;
                float bx = cx + off;
                float by = cy - off;
                dotPaint.setColor(palette.surfaceTop());
                dotPaint.setAlpha(Math.round(255 * markAlpha));
                canvas.drawCircle(bx, by, OverlayViewUtils.dpf(getContext(), 5.5f), dotPaint);
                dotPaint.setColor(palette.danger());
                dotPaint.setAlpha(Math.round(255 * markAlpha));
                canvas.drawCircle(bx, by, OverlayViewUtils.dpf(getContext(), 4f), dotPaint);
            }
        }
    }
}
