package com.dshgojava.assist.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.R;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.platform.OverlayPalette;
import com.dshgojava.assist.platform.OverlayViewUtils;
import com.dshgojava.assist.role.RoleMenu;

/** Native two-level menu: source groups first, then the tasks in that group. */
public final class AssistMenuView extends FrameLayout {
    public interface Listener {
        void onTaskSelected(QuickTask task);

        void onUnavailable(QuickTask task, AssistMenuModel.Block block);

        void onSettings();

        void onClose();

        void onDeleteRole(QuickTask task);

        /** Called after root/group navigation so the window can be re-measured. */
        void onContentChanged();
    }

    private final OverlayPalette palette;
    private final LinearLayout panel;
    private final LinearLayout rows;
    private final TextView title;
    private final TextView back;
    private final TextView close;
    private AssistMenuModel model;
    private AssistMenuModel.TaskGate gate;
    private Listener listener;
    private AssistMenuModel.Group current;

    public AssistMenuView(Context context) {
        super(context);
        palette = night(context) ? OverlayPalette.DARK : OverlayPalette.LIGHT;
        setBackground(panelBackground());
        setElevation(OverlayViewUtils.dpf(context, 10f));
        setClipToPadding(false);
        setPadding(dp(10), dp(8), dp(10), dp(10));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        back = headerButton(context, R.string.assist_menu_back, () -> showRoot(true));
        back.setVisibility(View.GONE);
        header.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = new TextView(context);
        title.setText(R.string.assist_menu_title);
        title.setTextColor(palette.ink());
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(8);
        header.addView(title, titleParams);

        TextView settings = headerButton(context, R.string.assist_menu_settings, () -> {
            Listener currentListener = listener;
            if (currentListener != null) currentListener.onSettings();
        });
        header.addView(settings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        close = headerButton(context, R.string.assist_menu_close, () -> {
            Listener currentListener = listener;
            if (currentListener != null) currentListener.onClose();
        });
        header.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroller = new ScrollView(context);
        scroller.setFillViewport(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollerParams.topMargin = dp(4);
        panel.addView(scroller, scrollerParams);
    }

    public void bind(AssistMenuModel model, AssistMenuModel.TaskGate gate, Listener listener) {
        this.model = model;
        this.gate = gate;
        this.listener = listener;
        current = null;
        rebuild(false);
    }

    /** Re-binds the model/gate without leaving the current group, so a role switch
     *  can update the selection highlight in place. */
    public void refresh(AssistMenuModel model, AssistMenuModel.TaskGate gate) {
        this.model = model;
        this.gate = gate;
        rebuild(false);
    }

    public boolean showingGroup() {
        return current != null;
    }

    /** Measure with a fixed width and a height cap so the window never leaves the screen. */
    public int measureHeight(int widthPx, int maxHeightPx) {
        int width = View.MeasureSpec.makeMeasureSpec(
                Math.max(1, widthPx), View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(
                Math.max(1, maxHeightPx), View.MeasureSpec.AT_MOST);
        measure(width, height);
        return Math.max(dp(80), getMeasuredHeight());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            Listener currentListener = listener;
            if (currentListener != null) currentListener.onClose();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void showRoot(boolean notify) {
        current = null;
        rebuild(notify);
    }

    private void openGroup(AssistMenuModel.Group group) {
        current = group;
        rebuild(true);
    }

    private void rebuild(boolean notify) {
        rows.removeAllViews();
        back.setVisibility(current == null ? View.GONE : View.VISIBLE);
        title.setText(current == null
                ? getContext().getString(R.string.assist_menu_title) : groupLabel(current));
        if (model == null || gate == null) return;

        if (current == null) {
            for (AssistMenuModel.GroupRow group : model.groups(gate)) {
                addGroup(group);
            }
        } else if (current == AssistMenuModel.Group.ROLE) {
            renderRoleGroup();
        } else {
            AssistMenuModel.GroupRow group = model.group(current, gate);
            for (AssistMenuModel.TaskRow task : group.tasks()) {
                addTask(task);
            }
        }
        requestLayout();
        invalidate();
        if (notify && listener != null) listener.onContentChanged();
    }

    private void addGroup(AssistMenuModel.GroupRow group) {
        int total = group.tasks().size();
        boolean interactive = total > 0;
        String detail;
        if (group.group() == AssistMenuModel.Group.ROLE) {
            detail = getContext().getString(R.string.assist_role_current, model.selectedRoleName());
        } else if (total == 0) {
            detail = getContext().getString(R.string.assist_menu_coming_soon);
        } else if (group.available()) {
            detail = group.availableCount() == total
                    ? getContext().getString(R.string.assist_menu_task_count, total)
                    : getContext().getString(R.string.assist_menu_available_count,
                            group.availableCount(), total);
        } else {
            detail = blockText(group.block(), group.group().source());
        }
        addRow(groupLabel(group.group()), detail, interactive, group.available(),
                interactive ? () -> openGroup(group.group()) : null);
    }

    private void addTask(AssistMenuModel.TaskRow row) {
        String detail = row.enabled() ? "" : blockText(row.block(), row.task().source());
        addRow(taskLabel(row.task()), detail, true, row.enabled(), () -> {
            Listener currentListener = listener;
            if (currentListener == null) return;
            if (row.enabled()) currentListener.onTaskSelected(row.task());
            else currentListener.onUnavailable(row.task(), row.block());
        });
    }

    private void renderRoleGroup() {
        AssistMenuModel.GroupRow group = model.group(AssistMenuModel.Group.ROLE, gate);
        for (AssistMenuModel.TaskRow row : group.tasks()) {
            addRoleRow(row);
        }
        addRoleActions();
    }

    private void addRoleRow(AssistMenuModel.TaskRow row) {
        boolean selected = model.isSelectedRole(row.task());
        if (RoleMenu.isCustomRoleTask(row.task().id())) {
            addDeletableRoleRow(row.task(), selected);
        } else {
            addRow(taskLabel(row.task()), null, true, true,
                    () -> onRoleTaskSelected(row.task()), selected);
        }
    }

    private void addDeletableRoleRow(QuickTask task, boolean selected) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(selected ? highlightBackground() : rowBackground(true));
        row.setAlpha(1f);

        TextView name = new TextView(getContext());
        name.setText(taskLabel(task));
        name.setTextColor(selected ? palette.accent() : palette.ink());
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView delete = new TextView(getContext());
        delete.setText(R.string.assist_role_delete);
        delete.setTextColor(palette.danger());
        delete.setTextSize(12);
        delete.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        delete.setGravity(Gravity.CENTER);
        delete.setPadding(dp(10), dp(6), dp(4), dp(6));
        delete.setClickable(true);
        delete.setFocusable(true);
        delete.setOnClickListener(view -> {
            Listener current = listener;
            if (current != null) current.onDeleteRole(task);
        });
        row.addView(delete, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> onRoleTaskSelected(task));
        row.setContentDescription(taskLabel(task));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        rows.addView(row, params);
    }

    private void addRoleActions() {
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        TextView create = roleActionButton(
                getContext().getString(R.string.assist_role_create),
                palette.onAccent(), palette.accent());
        create.setOnClickListener(view -> onRoleTaskSelected(RoleMenu.createTask()));

        TextView chat = roleActionButton(
                getContext().getString(R.string.assist_role_chat),
                palette.onAccentSoft(), palette.accentSoft());
        chat.setOnClickListener(view -> onRoleTaskSelected(RoleMenu.chatTask()));

        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        createParams.rightMargin = dp(6);
        actions.addView(create, createParams);
        actions.addView(chat, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.topMargin = dp(8);
        rows.addView(actions, wrap);
    }

    private void onRoleTaskSelected(QuickTask task) {
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onTaskSelected(task);
    }

    private TextView roleActionButton(String text, int ink, int fill) {
        TextView button = new TextView(getContext());
        button.setText(text);
        button.setTextColor(ink);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(14));
        button.setBackground(background);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void addRow(String label, String detail, boolean clickable, boolean enabled,
                        Runnable action) {
        addRow(label, detail, clickable, enabled, action, false);
    }

    private void addRow(String label, String detail, boolean clickable, boolean enabled,
                        Runnable action, boolean highlighted) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(highlighted ? highlightBackground() : rowBackground(enabled));
        row.setAlpha(enabled ? 1f : 0.68f);

        TextView name = new TextView(getContext());
        name.setText(label);
        name.setTextColor(highlighted ? palette.accent()
                : (enabled ? palette.ink() : palette.muted()));
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (detail != null && !detail.isBlank()) {
            TextView note = new TextView(getContext());
            note.setText(detail);
            note.setTextColor(enabled ? palette.muted() : palette.faint());
            note.setTextSize(12);
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noteParams.topMargin = dp(2);
            row.addView(note, noteParams);
        }

        if (clickable && action != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> action.run());
            row.setContentDescription(detail == null || detail.isBlank()
                    ? label : label + ", " + detail);
        } else {
            row.setClickable(false);
            row.setFocusable(false);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        rows.addView(row, params);
    }

    private TextView headerButton(Context context, int text, Runnable action) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(palette.accent());
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {palette.surfaceTop(), palette.surfaceBottom()});
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(Math.max(1, dp(1)), palette.outline());
        return drawable;
    }

    private GradientDrawable rowBackground(boolean enabled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(enabled ? palette.tonal() : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private GradientDrawable highlightBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(palette.accentSoft());
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private String groupLabel(AssistMenuModel.Group group) {
        return switch (group) {
            case SCREEN -> getContext().getString(R.string.assist_group_screen);
            case WEB -> getContext().getString(R.string.assist_group_web);
            case TEXT -> getContext().getString(R.string.assist_group_text);
            case IMAGE -> getContext().getString(R.string.assist_group_image);
            case ROLE -> getContext().getString(R.string.assist_group_role);
        };
    }

    private String taskLabel(QuickTask task) {
        int resource = switch (task.id()) {
            case "screen.summarize" -> R.string.assist_task_screen_summarize;
            case "screen.extract" -> R.string.assist_task_screen_extract;
            case "screen.translate" -> R.string.assist_task_screen_translate;
            case "screen.what" -> R.string.assist_task_screen_what;
            case "screen.actions" -> R.string.assist_task_screen_actions;
            case "clip.summarize" -> R.string.assist_task_clip_summarize;
            case "clip.polish" -> R.string.assist_task_clip_polish;
            case "image.ocr" -> R.string.assist_task_image_ocr;
            case "image.explain" -> R.string.assist_task_image_explain;
            case "image.qr" -> R.string.assist_task_image_qr;
            case "web.summarize" -> R.string.assist_task_web_summarize;
            case "web.ask" -> R.string.assist_task_web_ask;
            case "voice.transcribe" -> R.string.assist_task_voice_transcribe;
            case "pdf.summarize" -> R.string.assist_task_pdf_summarize;
            default -> 0;
        };
        return resource == 0 ? task.title() : getContext().getString(resource);
    }

    private String blockText(AssistMenuModel.Block block, SourceKind source) {
        return switch (block) {
            case ACCESSIBILITY -> getContext().getString(R.string.assist_unavailable_a11y);
            case MODEL -> getContext().getString(R.string.assist_unavailable_model);
            case VISION -> getContext().getString(R.string.assist_unavailable_vision);
            case COMING_SOON -> getContext().getString(R.string.assist_menu_coming_soon);
            case SOURCE -> switch (source) {
                case SCREEN -> getContext().getString(R.string.assist_unavailable_a11y);
                case CLIP -> getContext().getString(R.string.assist_unavailable_clipboard);
                case IMAGE -> getContext().getString(R.string.assist_unavailable_image);
                case VOICE -> getContext().getString(R.string.assist_unavailable_voice);
                default -> getContext().getString(R.string.assist_menu_source_unavailable);
            };
            case NONE -> "";
        };
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
