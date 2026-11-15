package com.dshgojava.assist.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.dshgojava.assist.R;
import com.dshgojava.assist.platform.OverlayPalette;
import com.dshgojava.assist.platform.OverlayViewUtils;

/**
 * Native follow-up composer embedded below the result. The host owns window
 * focus and IME insets; this view keeps the reference AskBar's send/IME rules
 * without depending on the reference project's resources.
 */
public final class AssistAskBar extends LinearLayout {
    public interface Listener {
        void onSend(String text);

        void onCancel();
    }

    static final int MAX_TEXT = 4_000;

    private final Listener listener;
    private final OverlayPalette palette;
    private final EditText input;
    private final TextView send;
    private final TextView cancel;
    private final TextView status;
    private boolean busy;

    public AssistAskBar(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        palette = night(context) ? OverlayPalette.DARK : OverlayPalette.LIGHT;
        setOrientation(VERTICAL);
        setPadding(dp(2), dp(8), dp(2), dp(2));
        setFocusableInTouchMode(true);

        TextView title = new TextView(context);
        title.setText(R.string.assist_followup_limit);
        title.setTextColor(palette.muted());
        title.setTextSize(11);
        addView(title, matchWrap());

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(HORIZONTAL);

        input = new EditText(context);
        input.setHint(R.string.assist_ask_hint);
        input.setHintTextColor(palette.faint());
        input.setTextColor(palette.ink());
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEXT)});
        input.setBackground(fieldBackground());
        input.setPadding(dp(12), dp(9), dp(12), dp(9));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                refreshSend();
            }
        });
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        send = pill(context, R.string.assist_ask_send, palette.onAccent(), palette.accent());
        send.setOnClickListener(view -> send());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        sendParams.leftMargin = dp(8);
        row.addView(send, sendParams);

        cancel = pill(context, R.string.assist_ask_cancel, palette.ink(), palette.tonal());
        cancel.setOnClickListener(view -> closeAsk(true));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        cancelParams.leftMargin = dp(6);
        row.addView(cancel, cancelParams);
        addView(row, matchWrap());

        status = new TextView(context);
        status.setTextSize(12);
        status.setTextColor(palette.danger());
        status.setVisibility(GONE);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(4);
        addView(status, statusParams);
        refreshSend();
    }

    public void open() {
        setVisibility(VISIBLE);
        input.requestFocus();
        post(() -> {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    public void close() {
        closeAsk(false);
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        input.setEnabled(!busy);
        refreshSend();
    }

    public void setStatus(CharSequence text, boolean error) {
        status.setText(text == null ? "" : text);
        status.setTextColor(error ? palette.danger() : palette.muted());
        status.setVisibility(text == null || text.length() == 0 ? GONE : VISIBLE);
    }

    public void clear() {
        input.setText("");
        setStatus(null, false);
    }

    public EditText input() {
        return input;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) closeAsk(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    static boolean canSend(String text, boolean busy) {
        return !busy && text != null && !text.isBlank() && text.strip().length() <= MAX_TEXT;
    }

    private void send() {
        Editable value = input.getText();
        String text = value == null ? "" : value.toString().strip();
        if (!canSend(text, busy)) {
            if (text.isEmpty()) setStatus(getContext().getString(R.string.assist_followup_empty), true);
            return;
        }
        listener.onSend(text);
    }

    private void closeAsk(boolean notify) {
        if (notify) {
            listener.onCancel();
            return;
        }
        setVisibility(GONE);
        setStatus(null, false);
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void refreshSend() {
        String text = input.getText() == null ? "" : input.getText().toString();
        boolean enabled = canSend(text, busy);
        send.setEnabled(enabled);
        send.setAlpha(enabled ? 1f : 0.4f);
    }

    private TextView pill(Context context, int text, int ink, int fill) {
        TextView view = new TextView(context);
        view.setText(text);
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

    private GradientDrawable fieldBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.field());
        background.setCornerRadius(dp(15));
        background.setStroke(Math.max(1, dp(1)), palette.outline());
        return background;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return OverlayViewUtils.dp(getContext(), value);
    }

    private static boolean night(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}
