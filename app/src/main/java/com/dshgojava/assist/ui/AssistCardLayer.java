package com.dshgojava.assist.ui;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import com.dshgojava.assist.AssistAction;
import com.dshgojava.assist.AssistFollowUp;
import com.dshgojava.assist.AssistResult;
import com.dshgojava.assist.AssistRunner;
import com.dshgojava.assist.ModelRetry;
import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.R;
import com.dshgojava.assist.Stage;
import com.dshgojava.assist.action.ActionIntents;
import com.dshgojava.assist.model.ModelChannel;
import com.dshgojava.assist.platform.BallGeometry;
import com.dshgojava.assist.platform.OverlayMotion;
import com.dshgojava.assist.platform.OverlayViewUtils;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Owns the result-card window and its follow-up lifetime. Window operations stay
 * on the main thread; the card itself marshals model callbacks.
 */
public final class AssistCardLayer implements AssistCardView.Listener,
        AssistFollowUp.Listener {
    private static final int GAP_DP = 16;
    private static final int MIN_HEIGHT_DP = 260;
    private static final int MAX_HEIGHT_DP = 560;

    /** Called on the main thread once per completed answer. */
    public interface HistorySink {
        void record(QuickTask task, String text, boolean followUp);
    }

    private final Context context;
    private final WindowManager windows;
    private final int windowType;
    private final Supplier<BallGeometry.SafeArea> safeArea;
    private final Supplier<BallGeometry.Side> side;
    private final ModelChannel followUpChannel;
    private final HistorySink history;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CardIme ime = new CardIme();
    private AssistFollowUp followUp;

    private AssistCardView card;
    private WindowManager.LayoutParams params;
    private AssistRunner.AssistRun run;
    private Runnable unsubscribe;
    private ModelRetry initialRetry;
    private QuickTask task;
    private AssistResult current = AssistResult.idle();
    private CardPlacement.Placement placement;
    private ValueAnimator slide;
    private boolean followUpStarted;
    private boolean asking;
    private boolean dismissing;
    private boolean captureHidden;
    private boolean initialRecorded;
    private boolean chatMode;
    private int baseY;
    private String transcript = "";
    private String pendingQuestion = "";

    public AssistCardLayer(Context context, WindowManager windows, int windowType,
                           Supplier<BallGeometry.SafeArea> safeArea,
                           Supplier<BallGeometry.Side> side,
                           ModelChannel followUpChannel,
                           HistorySink history) {
        this.context = Objects.requireNonNull(context, "context");
        this.windows = Objects.requireNonNull(windows, "windows");
        this.windowType = windowType;
        this.safeArea = Objects.requireNonNull(safeArea, "safeArea");
        this.side = Objects.requireNonNull(side, "side");
        this.followUpChannel = Objects.requireNonNull(followUpChannel, "followUpChannel");
        this.history = history;
        this.followUp = new AssistFollowUp(this.followUpChannel, this);
    }

    public boolean showing() {
        return card != null;
    }

    /** Hides the result window so a full-screen screenshot never contains its skeleton. */
    public void setHiddenForCapture(boolean hidden) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> setHiddenForCapture(hidden));
            return;
        }
        captureHidden = hidden;
        if (card != null) {
            card.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /**
     * Shows a card for an already-started run. {@code retry} may be null when
     * the runner cannot repeat its model call without gathering again.
     */
    public void show(QuickTask task, AssistRunner.AssistRun run, ModelRetry retry) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(run, "run");
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> show(task, run, retry));
            return;
        }
        dismissNow();
        followUp = new AssistFollowUp(followUpChannel, this);
        this.task = task;
        this.run = run;
        this.initialRetry = retry;
        this.current = run.state().state();
        this.followUpStarted = false;
        this.asking = false;
        this.dismissing = false;
        this.initialRecorded = false;
        this.chatMode = false;
        this.transcript = "";
        this.pendingQuestion = "";

        AssistCardView next = new AssistCardView(context, this);
        next.bindTask(task);
        if (!place()) {
            Toast.makeText(context, R.string.assist_overlay_attach_failed,
                    Toast.LENGTH_SHORT).show();
            run.close();
            return;
        }
        params = baseParams();
        params.x = placement.hiddenX();
        try {
            windows.addView(next, params);
        } catch (RuntimeException error) {
            run.close();
            Toast.makeText(context, R.string.assist_overlay_attach_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        card = next;
        card.setVisibility(captureHidden ? View.INVISIBLE : View.VISIBLE);
        card.render(current, displayText(), canRetry());
        unsubscribe = run.state().subscribe(state -> {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onRunState(state);
            } else {
                main.post(() -> onRunState(state));
            }
        });
        slideTo(placement.x(), true, null);
    }

    /** Opens a free chat card with no task result; the role persona is already in the model. */
    public void showChat(QuickTask task) {
        Objects.requireNonNull(task, "task");
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> showChat(task));
            return;
        }
        dismissNow();
        followUp = new AssistFollowUp(followUpChannel, this);
        this.task = task;
        this.chatMode = true;
        this.current = AssistResult.done("", List.of());
        this.followUpStarted = false;
        this.asking = false;
        this.dismissing = false;
        this.initialRecorded = false;
        this.transcript = "";
        this.pendingQuestion = "";

        AssistCardView next = new AssistCardView(context, this);
        next.bindTask(task);
        if (!place()) {
            Toast.makeText(context, R.string.assist_overlay_attach_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        params = baseParams();
        params.x = placement.hiddenX();
        try {
            windows.addView(next, params);
        } catch (RuntimeException error) {
            Toast.makeText(context, R.string.assist_overlay_attach_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        card = next;
        card.setVisibility(captureHidden ? View.INVISIBLE : View.VISIBLE);
        card.render(current, "", false);
        slideTo(placement.x(), true, () -> card.showAskBar());
    }

    public void reposition() {
        if (card == null || params == null || dismissing) return;
        if (slide != null) {
            slide.cancel();
            slide = null;
        }
        if (card.askOpen()) card.closeAsk(true);
        if (!place()) return;
        params.x = placement.x();
        params.y = placement.y();
        int bottom = safeArea().screenH() - safeArea().bottom() - dp(GAP_DP);
        params.y = CardPlacement.liftedY(baseY, params.height,
                safeArea().top() + dp(GAP_DP), bottom, ime.cachedKeyboard());
        try {
            windows.updateViewLayout(card, params);
        } catch (RuntimeException error) {
            dismissNow();
        }
    }

    public void dismiss() {
        if (card == null || params == null || dismissing) return;
        dismissing = true;
        card.closeAsk(false);
        CardPlacement.Placement at = placement;
        int hiddenX = at == null ? params.x : at.hiddenX();
        slideTo(hiddenX, false, this::removeNow);
    }

    public void dismissNow() {
        if (slide != null) {
            slide.cancel();
            slide = null;
        }
        removeNow();
    }

    @Override
    public void onCopy(String text) {
        if (text == null || text.isBlank()) return;
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            toast(R.string.assist_copy_failed);
            return;
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    context.getString(R.string.assist_card_copy), text));
            toast(R.string.assist_copied);
        } catch (RuntimeException error) {
            toast(R.string.assist_copy_failed);
        }
    }

    @Override
    public void onShare(String text) {
        if (text == null || text.isBlank()) return;
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        Intent chooser = Intent.createChooser(
                send, context.getString(R.string.assist_card_share_chooser));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(chooser);
        } catch (RuntimeException error) {
            toast(R.string.assist_share_failed);
        }
    }

    @Override
    public void onAction(AssistAction action) {
        if (action == null) return;
        if (action.kind() == AssistAction.Kind.COPY) {
            onCopy(action.value());
            return;
        }
        ActionIntents.Failure failure = ActionIntents.dispatch(context, action);
        if (failure == ActionIntents.Failure.INCOMPLETE) {
            toast(R.string.assist_action_incomplete);
        } else if (failure == ActionIntents.Failure.NO_HANDLER) {
            toast(R.string.assist_action_no_handler);
        }
    }

    @Override
    public void onAsk(String question) {
        if (card == null || followUp == null || task == null
                || current.stage() != Stage.DONE || followUp.busy()) return;
        String clean = question == null ? "" : question.strip();
        pendingQuestion = clean;
        AssistResult state;
        if (followUpStarted) {
            state = followUp.continueFollowUp(clean);
        } else if (chatMode) {
            state = followUp.chat(clean);
            followUpStarted = true;
        } else {
            state = followUp.begin(task.title(), current.text(), clean);
            followUpStarted = true;
        }
        if (state.stage() == Stage.FAILED) {
            pendingQuestion = "";
            current = state;
            render();
        }
    }

    @Override
    public void onRetry() {
        if (current.stage() != Stage.FAILED) return;
        if (followUpStarted && followUp != null && followUp.hasFailed()) {
            followUp.retry();
        } else if (initialRetry != null) {
            initialRetry.retryModel();
        }
    }

    @Override
    public void onStop() {
        if (followUp != null && followUp.busy()) {
            followUp.cancel();
            return;
        }
        AssistRunner.AssistRun active = run;
        if (active != null && !current.terminal()) active.cancel();
    }

    @Override
    public void onClose() {
        dismiss();
    }

    @Override
    public void onAskOpened() {
        if (card == null || params == null || dismissing) return;
        asking = true;
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        updateWindow();
    }

    @Override
    public void onAskClosed() {
        if (card == null || params == null || dismissing) return;
        asking = false;
        ime.reset();
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        if (placement != null) params.height = placement.height();
        params.y = baseY;
        InputMethodManager imm = context.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(card.getWindowToken(), 0);
        card.clearFocus();
        updateWindow();
    }

    @Override
    public void onIme(int bottomInset, boolean visible) {
        if (card == null || params == null || !asking || dismissing) return;
        BallGeometry.SafeArea area = safeArea();
        int keyboard = ime.resolve(bottomInset, visible,
                area.screenH(), params.y, params.height);
        if (keyboard <= 0) return;
        int gap = dp(GAP_DP);
        int minY = area.top() + gap;
        int maxBottom = area.screenH() - area.bottom() - gap;
        int baseHeight = placement == null ? params.height : placement.height();
        int available = Math.max(1, maxBottom - minY - keyboard);
        int targetHeight = Math.max(1, Math.min(baseHeight, available));
        int targetY = CardPlacement.liftedY(baseY, targetHeight, minY, maxBottom, keyboard);
        android.util.Log.d("DshAssist",
                "ime keyboard=" + keyboard + " y=" + params.y + "->" + targetY
                        + " h=" + params.height + "->" + targetHeight);
        if (params.y == targetY && params.height == targetHeight) return;
        params.height = targetHeight;
        params.y = targetY;
        updateWindow();
    }

    @Override
    public void onStateChanged(AssistResult state) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onFollowUpState(state);
        } else {
            main.post(() -> onFollowUpState(state));
        }
    }

    private void onRunState(AssistResult state) {
        if (card == null || dismissing || followUpStarted) return;
        current = state;
        if (state.stage() == Stage.DONE) {
            transcript = state.text();
            if (!initialRecorded) {
                initialRecorded = true;
                record(state.text(), false);
            }
        }
        render();
    }

    private void onFollowUpState(AssistResult state) {
        if (card == null || dismissing) return;
        current = state;
        if (state.terminal()) {
            card.closeAsk(false);
            if (state.stage() == Stage.DONE) {
                transcript = join(transcript, "【追问】" + pendingQuestion, state.text());
                pendingQuestion = "";
                record(state.text(), true);
            }
        }
        render();
    }

    private void record(String text, boolean followUp) {
        if (history == null || task == null || text == null || text.isBlank()) return;
        history.record(task, text, followUp);
    }

    private void render() {
        if (card != null) card.render(current, displayText(), canRetry());
    }

    private String displayText() {
        if (followUpStarted) {
            String live = current == null ? "" : current.text();
            return pendingQuestion.isEmpty()
                    ? transcript
                    : join(transcript, "【追问】" + pendingQuestion, live);
        }
        return current == null ? "" : current.text();
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(part.strip());
        }
        return out.toString();
    }

    private boolean canRetry() {
        if (current.stage() != Stage.FAILED) return false;
        return followUpStarted && followUp != null
                ? followUp.hasFailed()
                : initialRetry != null && initialRetry.canRetryModel();
    }

    private boolean place() {
        BallGeometry.SafeArea area = safeArea();
        BallGeometry.Side anchor = side.get();
        int preferred = Math.max(dp(MIN_HEIGHT_DP),
                Math.min(dp(MAX_HEIGHT_DP), Math.round(area.screenH() * 0.62f)));
        placement = CardPlacement.place(area, anchor, dp(GAP_DP), preferred);
        baseY = placement.y();
        if (params != null) {
            params.width = placement.width();
            params.height = placement.height();
            params.x = placement.x();
            params.y = baseY;
        }
        return placement.width() > 1 && placement.height() > 1;
    }

    private WindowManager.LayoutParams baseParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        WindowManager.LayoutParams next = new WindowManager.LayoutParams(
                placement.width(), placement.height(), windowType, flags,
                PixelFormat.TRANSLUCENT);
        next.gravity = Gravity.TOP | Gravity.START;
        next.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        next.setTitle(context.getString(R.string.assist_card_window_title));
        if (Build.VERSION.SDK_INT >= 28) {
            next.layoutInDisplayCutoutMode = WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) next.setFitInsetsTypes(0);
        return next;
    }

    private void slideTo(int targetX, boolean opening, Runnable then) {
        if (card == null || params == null) {
            if (then != null) then.run();
            return;
        }
        if (slide != null) slide.cancel();
        int fromX = params.x;
        if (!OverlayMotion.on() || fromX == targetX) {
            params.x = targetX;
            updateWindow();
            if (then != null) then.run();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(fromX, targetX);
        animator.setDuration(opening ? 220L : 180L);
        animator.setInterpolator(OverlayMotion.GLIDE);
        animator.addUpdateListener(animation -> {
            if (card == null || params == null) return;
            params.x = (int) animation.getAnimatedValue();
            updateWindow();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(android.animation.Animator animation) {
                slide = null;
                if (!cancelled && then != null) then.run();
            }
        });
        slide = animator;
        animator.start();
    }

    private void updateWindow() {
        if (card == null || params == null) return;
        try {
            windows.updateViewLayout(card, params);
        } catch (RuntimeException error) {
            dismissNow();
        }
    }

    private void removeNow() {
        AssistCardView currentCard = card;
        card = null;
        params = null;
        placement = null;
        asking = false;
        dismissing = false;
        ime.reset();
        if (unsubscribe != null) {
            unsubscribe.run();
            unsubscribe = null;
        }
        if (followUp != null) followUp.cancel();
        AssistRunner.AssistRun active = run;
        run = null;
        if (active != null) active.close();
        if (currentCard != null) {
            try {
                windows.removeViewImmediate(currentCard);
            } catch (RuntimeException ignored) {
            }
        }
        current = AssistResult.idle();
        followUpStarted = false;
        initialRecorded = false;
        chatMode = false;
        transcript = "";
        pendingQuestion = "";
        task = null;
        initialRetry = null;
    }

    private BallGeometry.SafeArea safeArea() {
        BallGeometry.SafeArea area = safeArea.get();
        return area == null ? new BallGeometry.SafeArea(
                context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels, 0, 0, 0, 0) : area;
    }

    private void toast(int text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return OverlayViewUtils.dp(context, value);
    }
}
