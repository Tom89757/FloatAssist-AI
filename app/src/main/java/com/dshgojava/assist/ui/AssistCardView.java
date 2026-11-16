package com.dshgojava.assist.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.dshgojava.assist.AssistAction;
import com.dshgojava.assist.AssistResult;
import com.dshgojava.assist.OutputKind;
import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.R;
import com.dshgojava.assist.Stage;
import com.dshgojava.assist.platform.OverlayMotion;
import com.dshgojava.assist.platform.OverlayPalette;
import com.dshgojava.assist.platform.OverlayViewUtils;
import java.util.List;

/**
 * Streaming result card. Model deltas may arrive on any thread; this view posts
 * them to the main thread and coalesces text updates to at most one every 80 ms.
 * The first visible state is a skeleton, never a fake answer.
 */
public final class AssistCardView extends FrameLayout {
    public interface Listener {
        void onCopy(String text);

        void onShare(String text);

        void onAction(AssistAction action);

        void onAsk(String question);

        void onRetry();

        void onStop();

        void onClose();

        /** Host must make the overlay focusable before the input requests focus. */
        void onAskOpened();

        /** Host must restore the non-focusable overlay and base position. */
        void onAskClosed();

        void onIme(int bottomInset, boolean visible);
    }

    private static final long COALESCE_MS = 80L;
    private static final int DRAG_DISMISS_DP = 72;

    private final Listener listener;
    private final OverlayPalette palette;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout skeleton;
    private final ScrollView answerScroll;
    private final TextView answer;
    private final TextView status;
    private final TextView actionSheetTitle;
    private final HorizontalScrollView actionSheetScroll;
    private final LinearLayout actionSheetRow;
    private final TextView actionSheetStatus;
    private final AssistAskBar askBar;
    private final TextView copy;
    private final TextView share;
    private final TextView ask;
    private final TextView retry;
    private final TextView stop;
    private final TextView title;
    private final View dragHandle;
    private final Runnable flush = this::flush;

    private QuickTask task;
    private AssistResult lastResult = AssistResult.idle();
    private String lastDisplayText = "";
    private boolean retryAvailable;
    private Pending pending;
    private boolean scheduled;
    private long lastFlushAt;
    private float dragStartY;
    private float dragStartRawY;
    private VelocityTracker dragVelocity;

    public AssistCardView(Context context, Listener listener) {
        super(context);
        palette = night(context) ? OverlayPalette.DARK : OverlayPalette.LIGHT;
        this.listener = listener;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClipToPadding(false);
        setBackground(cardBackground());
        setElevation(dp(16));

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(6), dp(16), dp(14));
        addView(panel, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        dragHandle = dragHandle(context);
        LinearLayout.LayoutParams dragParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(24));
        panel.addView(dragHandle, dragParams);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(context);
        title.setTextColor(palette.ink());
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(header, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        status = new TextView(context);
        status.setTextSize(12);
        status.setTextColor(palette.muted());
        status.setVisibility(GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(6);
        panel.addView(status, statusParams);

        FrameLayout content = new FrameLayout(context);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f);
        contentParams.topMargin = dp(10);
        panel.addView(content, contentParams);

        skeleton = skeleton(context);
        content.addView(skeleton, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));

        answerScroll = new ScrollView(context);
        answerScroll.setFillViewport(true);
        answerScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        answer = new TextView(context);
        answer.setTextColor(palette.ink());
        answer.setTextSize(15);
        answer.setLineSpacing(dp(4), 1f);
        answer.setTextIsSelectable(true);
        answer.setPadding(0, 0, 0, dp(4));
        answerScroll.addView(answer, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        content.addView(answerScroll, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        actionSheetTitle = new TextView(context);
        actionSheetTitle.setText(R.string.assist_action_sheet_title);
        actionSheetTitle.setTextColor(palette.muted());
        actionSheetTitle.setTextSize(11);
        actionSheetTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        actionSheetTitle.setVisibility(GONE);
        LinearLayout.LayoutParams sheetTitleParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sheetTitleParams.topMargin = dp(8);
        panel.addView(actionSheetTitle, sheetTitleParams);

        actionSheetScroll = new HorizontalScrollView(context);
        actionSheetScroll.setHorizontalScrollBarEnabled(false);
        actionSheetScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        actionSheetScroll.setVisibility(GONE);
        actionSheetRow = new LinearLayout(context);
        actionSheetRow.setOrientation(LinearLayout.HORIZONTAL);
        actionSheetRow.setGravity(Gravity.CENTER_VERTICAL);
        actionSheetScroll.addView(actionSheetRow, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sheetParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sheetParams.topMargin = dp(6);
        panel.addView(actionSheetScroll, sheetParams);

        actionSheetStatus = new TextView(context);
        actionSheetStatus.setText(R.string.assist_action_none);
        actionSheetStatus.setTextColor(palette.faint());
        actionSheetStatus.setTextSize(11);
        actionSheetStatus.setVisibility(GONE);
        LinearLayout.LayoutParams sheetStatusParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sheetStatusParams.topMargin = dp(6);
        panel.addView(actionSheetStatus, sheetStatusParams);

        askBar = new AssistAskBar(context, new AssistAskBar.Listener() {
            @Override
            public void onSend(String text) {
                askBar.setBusy(true);
                closeAsk(true);
                listener.onAsk(text);
            }

            @Override
            public void onCancel() {
                closeAsk(true);
            }
        });
        askBar.setVisibility(GONE);
        panel.addView(askBar, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        HorizontalScrollView actionScroll = new HorizontalScrollView(context);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        copy = pill(context, R.string.assist_card_copy, palette.ink(), palette.tonal());
        copy.setOnClickListener(view -> listener.onCopy(lastResult.text()));
        share = pill(context, R.string.assist_card_share, palette.ink(), palette.tonal());
        share.setOnClickListener(view -> listener.onShare(lastResult.text()));
        ask = pill(context, R.string.assist_card_ask,
                palette.onAccentSoft(), palette.accentSoft());
        ask.setOnClickListener(view -> openAsk());
        retry = pill(context, R.string.assist_card_retry,
                palette.onDangerSoft(), palette.dangerSoft());
        retry.setOnClickListener(view -> listener.onRetry());
        stop = pill(context, R.string.assist_card_stop,
                palette.onAccent(), palette.accent());
        stop.setOnClickListener(view -> listener.onStop());
        TextView close = pill(context, R.string.assist_card_close,
                palette.ink(), palette.tonal());
        close.setOnClickListener(view -> listener.onClose());
        addAction(actions, copy);
        addAction(actions, share);
        addAction(actions, ask);
        addAction(actions, retry);
        addAction(actions, stop);
        addAction(actions, close);
        actionScroll.addView(actions, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(10);
        panel.addView(actionScroll, actionParams);

        dragHandle.setOnTouchListener(this::drag);
        if (Build.VERSION.SDK_INT >= 30) {
            setOnApplyWindowInsetsListener((view, insets) -> {
                listener.onIme(insets.getInsets(WindowInsets.Type.ime()).bottom,
                        insets.isVisible(WindowInsets.Type.ime()));
                return insets;
            });
        } else {
            // API 26-29 expose the IME only through the legacy system-window inset.
            setOnApplyWindowInsetsListener((view, insets) -> {
                int bottom = insets.getSystemWindowInsetBottom();
                listener.onIme(bottom, bottom > dp(80));
                return insets;
            });
        }
    }

    /** Binds the header without changing the current result. */
    public void bindTask(QuickTask task) {
        this.task = task;
        title.setText(task == null ? getContext().getString(R.string.assist_menu_title)
                : task.title());
        render(lastResult, lastDisplayText, retryAvailable);
    }

    public void render(AssistResult result, String displayText, boolean retryAvailable) {
        AssistResult snapshot = result == null ? AssistResult.idle() : result;
        String text = displayText == null ? "" : displayText;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> enqueue(snapshot, text, retryAvailable));
            return;
        }
        enqueue(snapshot, text, retryAvailable);
    }

    public boolean askOpen() {
        return askBar.isOpen();
    }

    public String renderedText() {
        return lastResult.text();
    }

    public void closeAsk(boolean notifyHost) {
        if (!askBar.isOpen()) {
            if (notifyHost) listener.onAskClosed();
            return;
        }
        askBar.close();
        askBar.setBusy(false);
        if (notifyHost) listener.onAskClosed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            if (askBar.isOpen()) closeAsk(true);
            else listener.onClose();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (askBar.isOpen()) closeAsk(true);
                else listener.onClose();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void enqueue(AssistResult result, String displayText, boolean retryable) {
        pending = new Pending(result, displayText, retryable);
        if (result.terminal()) {
            main.removeCallbacks(flush);
            scheduled = false;
            flush.run();
            return;
        }
        long now = SystemClock.uptimeMillis();
        long wait = lastFlushAt == 0 ? 0 : Math.max(0, COALESCE_MS - (now - lastFlushAt));
        if (wait == 0) {
            main.removeCallbacks(flush);
            scheduled = false;
            flush.run();
        } else if (!scheduled) {
            scheduled = true;
            main.postDelayed(flush, wait);
        }
    }

    private void flush() {
        scheduled = false;
        Pending next = pending;
        if (next == null) return;
        pending = null;
        lastFlushAt = SystemClock.uptimeMillis();
        apply(next);
    }

    private void apply(Pending next) {
        lastResult = next.result();
        lastDisplayText = next.displayText();
        retryAvailable = next.retryAvailable();
        CardUiState.State state = CardUiState.from(lastResult, lastDisplayText, retryAvailable);

        skeleton.setVisibility(state.skeleton() ? VISIBLE : GONE);
        answerScroll.setVisibility(state.textVisible() || lastResult.error() != null
                || lastResult.stage() == Stage.CANCELLED
                ? VISIBLE : GONE);
        if (state.textVisible()) {
            answer.setTextColor(palette.ink());
            answer.setText(state.text());
        } else if (lastResult.error() != null) {
            answer.setTextColor(palette.danger());
            answer.setText(lastResult.error());
        } else if (lastResult.stage() == Stage.CANCELLED) {
            answer.setTextColor(palette.muted());
            answer.setText(R.string.assist_error_cancelled);
        } else {
            answer.setText("");
        }
        if (state.textVisible()) answerScroll.post(() -> answerScroll.fullScroll(View.FOCUS_DOWN));

        if (state.status() == CardUiState.Status.GATHERING) {
            showStatus(gatheringStatus(), false);
        } else if (state.status() == CardUiState.Status.THINKING) {
            showStatus(R.string.assist_status_thinking, false);
        } else {
            status.setVisibility(GONE);
        }

        setEnabled(copy, state.copyEnabled(), 0.4f);
        setEnabled(share, state.shareEnabled(), 0.4f);
        setEnabled(ask, state.askEnabled(), 0.35f);
        retry.setVisibility(state.retryEnabled() ? VISIBLE : GONE);
        stop.setVisibility(state.stopEnabled() ? VISIBLE : GONE);
        renderActions();
        invalidate();
    }

    private void renderActions() {
        List<AssistAction> actions = lastResult.actions();
        boolean show = lastResult.stage() == Stage.DONE && !actions.isEmpty();
        actionSheetTitle.setVisibility(show ? VISIBLE : GONE);
        actionSheetScroll.setVisibility(show ? VISIBLE : GONE);
        actionSheetRow.removeAllViews();
        if (show) {
            for (AssistAction action : actions) {
                TextView view = pill(getContext(), actionLabel(action),
                        palette.onAccentSoft(), palette.accentSoft());
                view.setOnClickListener(ignored -> listener.onAction(action));
                addAction(actionSheetRow, view);
            }
        }
        boolean noActions = lastResult.stage() == Stage.DONE
                && task != null && task.output() == OutputKind.ACTION_SHEET
                && actions.isEmpty();
        actionSheetStatus.setVisibility(noActions ? VISIBLE : GONE);
    }

    private String actionLabel(AssistAction action) {
        if (action.label() != null && !action.label().isBlank()) return action.label();
        return getContext().getString(switch (action.kind()) {
            case NAVIGATE -> R.string.assist_action_navigate;
            case CALENDAR -> R.string.assist_action_calendar;
            case DIAL -> R.string.assist_action_dial;
            case COPY -> R.string.assist_action_copy;
        });
    }

    private void openAsk() {
        if (task == null || lastResult.stage() != Stage.DONE
                || lastResult.text().isBlank()) {
            return;
        }
        showAskBar();
    }

    /** Opens the follow-up composer; used by both the ask button and free chat. */
    void showAskBar() {
        askBar.clear();
        askBar.setBusy(false);
        askBar.setVisibility(VISIBLE);
        listener.onAskOpened();
        askBar.open();
    }

    private void showStatus(int text, boolean error) {
        status.setText(text);
        status.setTextColor(error ? palette.danger() : palette.muted());
        status.setVisibility(VISIBLE);
    }

    private boolean drag(View view, MotionEvent event) {
        if (askBar.isOpen()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dragStartY = getTranslationY();
                dragStartRawY = event.getRawY();
                dragVelocity = VelocityTracker.obtain();
                dragVelocity.addMovement(event);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (dragVelocity != null) dragVelocity.addMovement(event);
                setTranslationY(Math.max(0f, dragStartY + event.getRawY() - dragStartRawY));
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                float distance = getTranslationY();
                float velocity = 0f;
                if (dragVelocity != null) {
                    dragVelocity.addMovement(event);
                    dragVelocity.computeCurrentVelocity(1_000);
                    velocity = dragVelocity.getYVelocity();
                    dragVelocity.recycle();
                    dragVelocity = null;
                }
                if (CardPlacement.shouldDismiss(distance, velocity, dp(DRAG_DISMISS_DP))) {
                    animate().translationY(getHeight()).alpha(0f).setDuration(160L)
                            .withEndAction(listener::onClose).start();
                } else {
                    animate().translationY(0f).setDuration(160L).start();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                if (dragVelocity != null) {
                    dragVelocity.recycle();
                    dragVelocity = null;
                }
                animate().translationY(0f).setDuration(160L).start();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private int gatheringStatus() {
        if (task == null) return R.string.assist_status_gathering;
        return switch (task.source()) {
            case CLIP -> R.string.assist_status_clipboard;
            case IMAGE -> R.string.assist_status_image;
            case VOICE -> R.string.assist_status_voice;
            default -> R.string.assist_status_gathering;
        };
    }

    private View dragHandle(Context context) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.outline());
        background.setCornerRadius(dp(3));
        FrameLayout wrapper = new FrameLayout(context);
        View bar = new View(context);
        bar.setBackground(background);
        wrapper.addView(bar, new FrameLayout.LayoutParams(
                dp(36), dp(4), Gravity.CENTER));
        wrapper.setContentDescription(getContext().getString(R.string.assist_card_drag));
        return wrapper;
    }

    private LinearLayout skeleton(Context context) {
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < 3; i++) {
            View row = new View(context);
            GradientDrawable background = new GradientDrawable();
            background.setColor(palette.tonal());
            background.setCornerRadius(dp(6));
            row.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    i == 2 ? dp(180) : LayoutParams.MATCH_PARENT, dp(13));
            params.bottomMargin = dp(10);
            rows.addView(row, params);
        }
        rows.animate().alpha(0.45f).setDuration(650L)
                .withEndAction(() -> rows.animate().alpha(1f).setDuration(650L).start()).start();
        return rows;
    }

    private TextView pill(Context context, int text, int ink, int fill) {
        TextView view = new TextView(context);
        view.setText(text);
        return stylePill(view, ink, fill);
    }

    private TextView pill(Context context, String text, int ink, int fill) {
        TextView view = new TextView(context);
        view.setText(text);
        return stylePill(view, ink, fill);
    }

    private TextView stylePill(TextView view, int ink, int fill) {
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(ink);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(18));
        view.setBackground(background);
        return view;
    }

    private void addAction(LinearLayout row, TextView view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        row.addView(view, params);
    }

    private static void setEnabled(TextView view, boolean enabled, float disabledAlpha) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : disabledAlpha);
    }

    private GradientDrawable cardBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.surfaceTop());
        background.setCornerRadius(dp(24));
        background.setStroke(Math.max(1, dp(1)), palette.outline());
        return background;
    }

    private int dp(int value) {
        return OverlayViewUtils.dp(getContext(), value);
    }

    private static boolean night(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private record Pending(AssistResult result, String displayText, boolean retryAvailable) { }
}
