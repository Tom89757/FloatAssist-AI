package com.dshgojava.assist.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.R;
import com.dshgojava.assist.platform.BallGeometry;
import com.dshgojava.assist.platform.MarkLook;
import com.dshgojava.assist.platform.OverlayBall;
import com.dshgojava.assist.platform.OverlayMotion;
import com.dshgojava.assist.platform.OverlayPalette;
import com.dshgojava.assist.platform.OverlayPrefs;
import com.dshgojava.assist.platform.OverlayViewUtils;
import java.util.function.Supplier;

/** Owns the floating ball window and its adjacent two-level menu window. */
public final class AssistBall {
    public interface Listener {
        void onTaskSelected(QuickTask task);

        void onUnavailable(QuickTask task, AssistMenuModel.Block block);

        /** Long press starts hold-to-talk after the ball's arm/fill delay. */
        void onLongPress();

        /** Finger-up/cancel after {@link #onLongPress()}; cancelled captures must be discarded. */
        void onLongPressEnd(boolean cancelled);

        void onSettings();

        void onMenuFailed();
    }

    private static final int MENU_WIDTH_DP = 304;
    private static final int MENU_GAP_DP = 8;
    private static final long IDLE_AFTER_MS = 5_000L;
    private static final float IDLE_SCALE = 32f / 48f;
    private static final float IDLE_ALPHA = 0.35f;

    private final Context context;
    private final WindowManager windows;
    private final int windowType;
    private final Supplier<BallGeometry.SafeArea> safeArea;
    private final AssistMenuModel model;
    private final AssistMenuModel.TaskGate gate;
    private final Listener listener;
    private final OverlayBall ball;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable idle = this::enterIdle;

    private AssistMenuView menu;
    private WindowManager.LayoutParams menuParams;
    private boolean dismissedByTouch;
    private boolean captureHidden;
    private float activeAlpha;

    public AssistBall(Context context, WindowManager windows, int windowType,
                      Supplier<BallGeometry.SafeArea> safeArea, AssistMenuModel model,
                      AssistMenuModel.TaskGate gate, Listener listener) {
        this.context = context;
        this.windows = windows;
        this.windowType = windowType;
        this.safeArea = safeArea;
        this.model = model;
        this.gate = gate;
        this.listener = listener;
        OverlayPalette palette = night(context) ? OverlayPalette.DARK : OverlayPalette.LIGHT;
        this.ball = new OverlayBall(context, windows, windowType, safeArea, palette,
                this::onTap, this::onLongPress, this::onTouchDown);
        this.ball.setHoldReleaseListener(
                () -> listener.onLongPressEnd(false),
                () -> listener.onLongPressEnd(true));
    }

    public boolean attach(CharSequence title, CharSequence description) {
        BallGeometry.WindowPos start = ball.dockPos();
        if (!ball.attach(title, false, start)) return false;
        ball.setLook(MarkLook.RUNNING, description, false, null);
        activeAlpha = OverlayPrefs.read(context).idleOpacity();
        wakeBall();
        scheduleIdle();
        return true;
    }

    public void detach() {
        dismissMenu();
        main.removeCallbacks(idle);
        captureHidden = false;
        ball.detach();
    }

    public void reposition() {
        dismissMenu();
        if (ball.attached()) ball.move(ball.dockPos());
    }

    public boolean menuShowing() {
        return menu != null;
    }

    /** Side the ball is docked to; the result card enters from the same edge. */
    public BallGeometry.Side side() {
        return ball.dock().side();
    }

    /** Called on the main thread by the accessibility screenshot guard. */
    public void setHiddenForCapture(boolean hidden) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> setHiddenForCapture(hidden));
            return;
        }
        if (captureHidden == hidden) return;
        captureHidden = hidden;
        if (hidden) {
            dismissMenu();
            main.removeCallbacks(idle);
            ball.root().setVisibility(View.INVISIBLE);
            return;
        }
        if (!ball.attached()) return;
        ball.root().setVisibility(View.VISIBLE);
        wakeBall();
        scheduleIdle();
    }

    private void onTap() {
        if (dismissedByTouch) {
            dismissedByTouch = false;
            return;
        }
        if (menu != null) dismissMenu();
        else showMenu();
    }

    private void onTouchDown() {
        wakeBall();
        dismissedByTouch = menu != null;
        dismissMenu();
    }

    private void onLongPress() {
        dismissMenu();
        listener.onLongPress();
        scheduleIdle();
    }

    private void showMenu() {
        if (menu != null || !ball.attached() || captureHidden) return;
        BallGeometry.SafeArea area = safeArea.get();
        int margin = dp(MENU_GAP_DP);
        int usableWidth = Math.max(2,
                area.screenW() - area.left() - area.right() - 2 * margin);
        int width = Math.min(dp(MENU_WIDTH_DP), usableWidth);
        int maxHeight = Math.max(1,
                area.screenH() - area.top() - area.bottom() - 2 * margin);

        AssistMenuView next = new AssistMenuView(context);
        next.bind(model, gate, menuListener);
        int height = Math.min(next.measureHeight(width, maxHeight), maxHeight);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle(context.getString(R.string.assist_menu_title));
        if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
        placeMenu(params, area, width, height, margin);

        try {
            windows.addView(next, params);
        } catch (RuntimeException error) {
            listener.onMenuFailed();
            return;
        }
        menu = next;
        menuParams = params;
        main.removeCallbacks(idle);
        if (OverlayMotion.on()) {
            next.setAlpha(0f);
            next.setScaleX(0.96f);
            next.setScaleY(0.96f);
            next.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(OverlayMotion.QUICK_MS)
                    .setInterpolator(OverlayMotion.GLIDE).start();
        }
    }

    private final AssistMenuView.Listener menuListener = new AssistMenuView.Listener() {
        @Override
        public void onTaskSelected(QuickTask task) {
            dismissMenu();
            listener.onTaskSelected(task);
        }

        @Override
        public void onUnavailable(QuickTask task, AssistMenuModel.Block block) {
            dismissMenu();
            listener.onUnavailable(task, block);
        }

        @Override
        public void onSettings() {
            dismissMenu();
            listener.onSettings();
        }

        @Override
        public void onClose() {
            dismissMenu();
        }

        @Override
        public void onContentChanged() {
            resizeMenu();
        }
    };

    private void resizeMenu() {
        AssistMenuView current = menu;
        WindowManager.LayoutParams params = menuParams;
        if (current == null || params == null) return;
        BallGeometry.SafeArea area = safeArea.get();
        int margin = dp(MENU_GAP_DP);
        int maxHeight = Math.max(1,
                area.screenH() - area.top() - area.bottom() - 2 * margin);
        int height = Math.min(current.measureHeight(params.width, maxHeight), maxHeight);
        params.height = height;
        params.y = menuY(area, height, margin);
        params.x = menuX(area, params.width, margin);
        try {
            windows.updateViewLayout(current, params);
        } catch (RuntimeException error) {
            dismissMenu();
            listener.onMenuFailed();
        }
    }

    private void dismissMenu() {
        AssistMenuView current = menu;
        menu = null;
        menuParams = null;
        if (current != null) {
            try {
                windows.removeViewImmediate(current);
            } catch (RuntimeException ignored) {
            }
        }
        scheduleIdle();
    }

    private void wakeBall() {
        main.removeCallbacks(idle);
        if (!ball.attached() || captureHidden) return;
        ball.setIdle(activeAlpha, true, 180L);
        ball.root().animate().scaleX(1f).scaleY(1f)
                .setDuration(OverlayMotion.QUICK_MS).start();
    }

    private void scheduleIdle() {
        main.removeCallbacks(idle);
        if (ball.attached() && menu == null && !captureHidden) {
            main.postDelayed(idle, IDLE_AFTER_MS);
        }
    }

    private void enterIdle() {
        if (!ball.attached() || menu != null || captureHidden) return;
        ball.setIdle(IDLE_ALPHA, true, 600L);
        ball.root().animate().scaleX(IDLE_SCALE).scaleY(IDLE_SCALE)
                .setDuration(600L).start();
    }

    private void placeMenu(WindowManager.LayoutParams params, BallGeometry.SafeArea area,
                           int width, int height, int margin) {
        params.x = menuX(area, width, margin);
        params.y = menuY(area, height, margin);
    }

    private int menuX(BallGeometry.SafeArea area, int width, int margin) {
        BallGeometry.WindowPos pos = ball.pos();
        int x = ball.dock().side() == BallGeometry.Side.RIGHT
                ? pos.x() + ball.size() / 2 - width
                : pos.x() + ball.size() / 2;
        int min = area.left() + margin;
        int max = Math.max(min, area.screenW() - area.right() - margin - width);
        return Math.max(min, Math.min(x, max));
    }

    private int menuY(BallGeometry.SafeArea area, int height, int margin) {
        BallGeometry.WindowPos pos = ball.pos();
        int y = pos.y() + ball.size() / 2 - height / 2;
        int min = area.top() + margin;
        int max = Math.max(min, area.screenH() - area.bottom() - margin - height);
        return Math.max(min, Math.min(y, max));
    }

    private int dp(int value) {
        return OverlayViewUtils.dp(context, value);
    }

    private static boolean night(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}
