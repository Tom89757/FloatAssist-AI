package com.dshgojava.assist.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.dshgojava.assist.A11yBridge;
import com.dshgojava.assist.R;
import com.dshgojava.assist.service.AssistOverlayService;
import com.dshgojava.assist.store.AssistHistoryEntry;
import com.dshgojava.assist.store.AssistHistoryStore;
import com.dshgojava.assist.store.AssistPrefs;
import java.text.DateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Native settings and local history screen. Preference/history reads and writes
 * run on one background executor; the API key is never loaded back into a view
 * or written to a log.
 */
public final class AssistSettingsActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 7009;
    private static final int REQUEST_MICROPHONE = 7010;
    private static final String EXTRA_REQUEST_MICROPHONE = "request_microphone";

    private final DateFormat dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

    private ExecutorService io;
    private AssistPrefs prefs;
    private AssistHistoryStore history;
    private Switch overlaySwitch;
    private Switch visionSwitch;
    private TextView overlayStatus;
    private TextView a11yStatus;
    private TextView micStatus;
    private TextView notificationStatus;
    private TextView keyStatus;
    private TextView modelStatus;
    private EditText baseUrl;
    private EditText modelName;
    private EditText apiKey;
    private Button saveModel;
    private Button clearKey;
    private Button clearHistory;
    private LinearLayout historyList;
    private TextView historyEmpty;
    private HorizontalScrollView dateSelector;
    private LinearLayout dateSelectorRow;
    private final DateTimeFormatter dateLabelFormatter = DateTimeFormatter.ofPattern("M月d日");
    private final List<AssistHistoryEntry> allEntries = new ArrayList<>();
    private final Map<LocalDate, List<AssistHistoryEntry>> entriesByDate = new LinkedHashMap<>();
    private LocalDate selectedDate;
    private boolean binding;
    private boolean destroyed;
    private boolean preferencesLoaded;
    private boolean noticeShown;
    private boolean pendingOverlayEnable;

    public static Intent intent(Context context) {
        return new Intent(context, AssistSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    public static Intent microphoneIntent(Context context) {
        return intent(context).putExtra(EXTRA_REQUEST_MICROPHONE, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assist_settings);
        prefs = new AssistPrefs(this);
        history = AssistHistoryStore.forApp(this);
        io = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "assist-settings"));

        overlaySwitch = findViewById(R.id.settingsOverlaySwitch);
        visionSwitch = findViewById(R.id.settingsVisionSwitch);
        overlayStatus = findViewById(R.id.settingsOverlayStatus);
        a11yStatus = findViewById(R.id.settingsA11yStatus);
        micStatus = findViewById(R.id.settingsMicStatus);
        notificationStatus = findViewById(R.id.settingsNotificationStatus);
        keyStatus = findViewById(R.id.settingsKeyStatus);
        modelStatus = findViewById(R.id.settingsModelStatus);
        baseUrl = findViewById(R.id.settingsBaseUrl);
        modelName = findViewById(R.id.settingsModelName);
        apiKey = findViewById(R.id.settingsApiKey);
        apiKey.setSaveEnabled(false);
        saveModel = findViewById(R.id.settingsSaveModel);
        clearKey = findViewById(R.id.settingsClearKey);
        clearHistory = findViewById(R.id.settingsClearHistory);
        historyList = findViewById(R.id.settingsHistoryList);
        historyEmpty = findViewById(R.id.settingsHistoryEmpty);
        dateSelector = findViewById(R.id.settingsDateSelector);
        dateSelectorRow = findViewById(R.id.settingsDateSelectorRow);

        overlaySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (binding || !preferencesLoaded) return;
            if (checked) requestOverlayEnable();
            else setOverlayEnabled(false);
        });
        findViewById(R.id.settingsOverlayButton).setOnClickListener(
                view -> openOverlaySettings());
        findViewById(R.id.settingsA11yButton).setOnClickListener(
                view -> openAccessibilitySettings());
        findViewById(R.id.settingsMicButton).setOnClickListener(
                view -> requestMicrophonePermission());
        findViewById(R.id.settingsNotificationButton).setOnClickListener(
                view -> openNotificationSettings());
        saveModel.setOnClickListener(view -> saveModel());
        clearKey.setOnClickListener(view -> clearApiKey());
        clearHistory.setOnClickListener(view -> confirmClearHistory());

        loadPreferences(true);
        if (getIntent() != null && getIntent().getBooleanExtra(
                EXTRA_REQUEST_MICROPHONE, false)) {
            findViewById(android.R.id.content).post(this::requestMicrophonePermission);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        if (pendingOverlayEnable && Settings.canDrawOverlays(this)) {
            pendingOverlayEnable = false;
            setOverlayEnabled(true);
        }
        loadHistory();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            refreshPermissionStatus();
        } else if (requestCode == REQUEST_MICROPHONE) {
            refreshPermissionStatus();
            if (!microphoneGranted()) {
                Toast.makeText(this, R.string.assist_settings_mic_denied,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ExecutorService current = io;
        io = null;
        if (current != null) current.shutdownNow();
        super.onDestroy();
    }

    private void loadPreferences(boolean fillFields) {
        runIo(() -> {
            AssistPrefs.Snapshot snapshot;
            try {
                snapshot = prefs.read();
            } catch (RuntimeException error) {
                runOnUi(() -> {
                    preferencesLoaded = true;
                    modelStatus.setText(R.string.assist_settings_load_failed);
                    refreshPermissionStatus();
                });
                return;
            }
            AssistPrefs.Snapshot loaded = snapshot;
            runOnUi(() -> applyPreferences(loaded, fillFields));
        });
    }

    private void applyPreferences(AssistPrefs.Snapshot snapshot, boolean fillFields) {
        preferencesLoaded = true;
        binding = true;
        overlaySwitch.setChecked(snapshot.overlayEnabled() || AssistOverlayService.isRunning());
        visionSwitch.setChecked(snapshot.model().supportsVision());
        binding = false;
        if (fillFields) {
            baseUrl.setText(snapshot.model().baseUrl());
            modelName.setText(snapshot.model().model());
        }
        apiKey.setText("");
        keyStatus.setText(snapshot.hasApiKey()
                ? R.string.assist_settings_key_saved : R.string.assist_settings_key_missing);
        applyModelStatus(snapshot, false);
        refreshPermissionStatus();
        if (!snapshot.noticeSeen()) showNotice();
        if (snapshot.overlayEnabled() && !AssistOverlayService.isRunning()
                && Settings.canDrawOverlays(this)) {
            overlaySwitch.post(() -> setOverlayEnabled(true));
        }
    }

    private void applyModelStatus(AssistPrefs.Snapshot snapshot, boolean justSaved) {
        if (snapshot.connected()) {
            modelStatus.setText(justSaved
                    ? R.string.assist_settings_saved_connected
                    : R.string.assist_settings_connected);
        } else {
            modelStatus.setText(justSaved
                    ? R.string.assist_settings_saved_disconnected
                    : R.string.assist_settings_disconnected);
        }
    }

    private void requestOverlayEnable() {
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlayEnable = true;
            binding = true;
            overlaySwitch.setChecked(false);
            binding = false;
            overlayStatus.setText(R.string.assist_settings_overlay_required);
            Toast.makeText(this, R.string.assist_settings_overlay_required,
                    Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        setOverlayEnabled(true);
    }

    private void setOverlayEnabled(boolean enabled) {
        if (enabled && !Settings.canDrawOverlays(this)) {
            requestOverlayEnable();
            return;
        }
        pendingOverlayEnable = false;
        binding = true;
        overlaySwitch.setChecked(enabled);
        binding = false;
        runIo(() -> prefs.setOverlayEnabled(enabled));
        if (enabled) {
            requestNotificationPermissionIfNeeded();
            if (!AssistOverlayService.show(this)) {
                binding = true;
                overlaySwitch.setChecked(false);
                binding = false;
                runIo(() -> prefs.setOverlayEnabled(false));
                overlayStatus.setText(R.string.assist_settings_overlay_start_failed);
                Toast.makeText(this, R.string.assist_settings_overlay_start_failed,
                        Toast.LENGTH_LONG).show();
            }
        } else {
            AssistOverlayService.hide(this);
        }
        refreshPermissionStatus();
        if (enabled) overlayStatus.postDelayed(this::refreshPermissionStatus, 500L);
    }

    private void refreshPermissionStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        overlayStatus.setText(overlay
                ? (AssistOverlayService.isRunning()
                        ? R.string.assist_settings_overlay_running
                        : R.string.assist_settings_overlay_allowed)
                : R.string.assist_settings_overlay_blocked);
        a11yStatus.setText(A11yBridge.isEnabled(this)
                ? R.string.assist_settings_status_on : R.string.assist_settings_status_off);
        notificationStatus.setText(notificationsEnabled()
                ? R.string.assist_settings_status_on : R.string.assist_settings_status_off);
        micStatus.setText(microphoneGranted()
                ? R.string.assist_settings_status_on : R.string.assist_settings_status_off);
    }

    private void requestMicrophonePermission() {
        if (microphoneGranted()) {
            refreshPermissionStatus();
            return;
        }
        requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO},
                REQUEST_MICROPHONE);
    }

    private boolean microphoneGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void saveModel() {
        String endpoint = baseUrl.getText().toString().strip();
        String name = modelName.getText().toString().strip();
        String secret = apiKey.getText().toString().strip();
        if (!endpoint.isEmpty() && !validEndpoint(endpoint)) {
            modelStatus.setText(R.string.assist_settings_invalid_url);
            return;
        }
        setModelControlsEnabled(false);
        runIo(() -> {
            try {
                prefs.saveModel(endpoint, secret, name, visionSwitch.isChecked());
                AssistPrefs.Snapshot snapshot = prefs.read();
                runOnUi(() -> {
                    apiKey.setText("");
                    keyStatus.setText(snapshot.hasApiKey()
                            ? R.string.assist_settings_key_saved
                            : R.string.assist_settings_key_missing);
                    baseUrl.setText(snapshot.model().baseUrl());
                    modelName.setText(snapshot.model().model());
                    applyModelStatus(snapshot, true);
                    setModelControlsEnabled(true);
                    reloadRunningOverlay();
                });
            } catch (RuntimeException error) {
                runOnUi(() -> {
                    modelStatus.setText(R.string.assist_settings_save_failed);
                    setModelControlsEnabled(true);
                });
            }
        });
    }

    private void clearApiKey() {
        setModelControlsEnabled(false);
        runIo(() -> {
            try {
                prefs.clearApiKey();
                AssistPrefs.Snapshot snapshot = prefs.read();
                runOnUi(() -> {
                    apiKey.setText("");
                    keyStatus.setText(R.string.assist_settings_key_missing);
                    applyModelStatus(snapshot, false);
                    setModelControlsEnabled(true);
                    Toast.makeText(this, R.string.assist_settings_key_cleared,
                            Toast.LENGTH_SHORT).show();
                    reloadRunningOverlay();
                });
            } catch (RuntimeException error) {
                runOnUi(() -> {
                    modelStatus.setText(R.string.assist_settings_save_failed);
                    setModelControlsEnabled(true);
                });
            }
        });
    }

    private void reloadRunningOverlay() {
        if (AssistOverlayService.isRunning()) AssistOverlayService.reload(this);
    }

    private void setModelControlsEnabled(boolean enabled) {
        baseUrl.setEnabled(enabled);
        modelName.setEnabled(enabled);
        apiKey.setEnabled(enabled);
        visionSwitch.setEnabled(enabled);
        saveModel.setEnabled(enabled);
        clearKey.setEnabled(enabled);
    }

    private void loadHistory() {
        runIo(() -> {
            List<AssistHistoryEntry> entries;
            try {
                entries = history.list();
            } catch (RuntimeException error) {
                runOnUi(() -> {
                    historyEmpty.setText(R.string.assist_history_load_failed);
                    clearHistory.setEnabled(false);
                });
                return;
            }
            List<AssistHistoryEntry> loaded = List.copyOf(entries);
            runOnUi(() -> renderHistory(loaded));
        });
    }

    private void renderHistory(List<AssistHistoryEntry> entries) {
        allEntries.clear();
        allEntries.addAll(entries);
        groupByDate();
        renderDateSelector();
        renderSelectedDate();
    }

    private void groupByDate() {
        entriesByDate.clear();
        for (AssistHistoryEntry entry : allEntries) {
            LocalDate day = Instant.ofEpochMilli(entry.createdAtMillis())
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            entriesByDate.computeIfAbsent(day, key -> new ArrayList<>()).add(entry);
        }
        if (selectedDate == null || !entriesByDate.containsKey(selectedDate)) {
            selectedDate = entriesByDate.isEmpty()
                    ? null : entriesByDate.keySet().iterator().next();
        }
    }

    private void renderDateSelector() {
        dateSelectorRow.removeAllViews();
        boolean show = !entriesByDate.isEmpty();
        dateSelector.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        for (LocalDate day : entriesByDate.keySet()) {
            dateSelectorRow.addView(dateChip(day));
        }
    }

    private TextView dateChip(LocalDate day) {
        boolean selected = day.equals(selectedDate);
        TextView chip = new TextView(this);
        chip.setText(dateLabel(day));
        chip.setTextSize(14);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setTextColor(selected ? 0xFFFFFFFF : 0xFF333333);
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? 0xFF4B67B4 : 0xFFE2E2E2);
        background.setCornerRadius(dp(18));
        chip.setBackground(background);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(view -> selectDate(day));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        chip.setLayoutParams(params);
        return chip;
    }

    private String dateLabel(LocalDate day) {
        LocalDate today = LocalDate.now();
        if (day.equals(today)) return getString(R.string.assist_history_today);
        if (day.equals(today.minusDays(1))) return getString(R.string.assist_history_yesterday);
        return day.format(dateLabelFormatter);
    }

    private void selectDate(LocalDate day) {
        if (day == null || day.equals(selectedDate)) return;
        selectedDate = day;
        renderDateSelector();
        renderSelectedDate();
    }

    private void renderSelectedDate() {
        historyList.removeAllViews();
        List<AssistHistoryEntry> entries = selectedDate == null
                ? List.of() : entriesByDate.getOrDefault(selectedDate, List.of());
        boolean empty = allEntries.isEmpty();
        historyEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        historyEmpty.setText(R.string.assist_history_empty);
        clearHistory.setEnabled(!empty);
        for (AssistHistoryEntry entry : entries) historyList.addView(historyRow(entry));
    }

    private View historyRow(AssistHistoryEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(10), dp(6), dp(10));
        row.setBackgroundColor(0xFFFFFFFF);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> showHistoryEntry(entry));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(entry.followUp()
                ? getString(R.string.assist_history_follow_up_title, entry.taskTitle())
                : entry.taskTitle());
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xFF1E1E1E);
        text.addView(title);

        TextView preview = new TextView(this);
        preview.setText(entry.shortText(90));
        preview.setTextSize(13);
        preview.setTextColor(0xFF555555);
        preview.setMaxLines(2);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(preview);

        TextView time = new TextView(this);
        time.setText(dateFormat.format(new Date(entry.createdAtMillis())));
        time.setTextSize(11);
        time.setTextColor(0xFF888888);
        text.addView(time);

        row.addView(text, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button delete = new Button(this);
        delete.setText(R.string.assist_history_delete);
        delete.setTextSize(12);
        delete.setMinWidth(0);
        delete.setMinimumWidth(0);
        delete.setPadding(dp(8), 0, dp(8), 0);
        delete.setOnClickListener(view -> deleteHistory(entry.id()));
        row.addView(delete, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        row.setLayoutParams(params);
        return row;
    }

    private void showHistoryEntry(AssistHistoryEntry entry) {
        TextView body = new TextView(this);
        body.setText(entry.text());
        body.setTextIsSelectable(true);
        body.setTextSize(15);
        body.setPadding(dp(8), dp(4), dp(8), dp(4));

        ScrollView scroller = new ScrollView(this);
        scroller.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroller.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(entry.followUp()
                        ? getString(R.string.assist_history_follow_up_title, entry.taskTitle())
                        : entry.taskTitle())
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void deleteHistory(String id) {
        runIo(() -> {
            try {
                history.delete(id);
                loadHistory();
            } catch (RuntimeException error) {
                runOnUi(() -> Toast.makeText(this, R.string.assist_history_delete_failed,
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.assist_history_clear_confirm_title)
                .setMessage(R.string.assist_history_clear_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.assist_history_clear, (dialog, which) -> clearHistory())
                .show();
    }

    private void clearHistory() {
        runIo(() -> {
            try {
                history.clear();
                loadHistory();
            } catch (RuntimeException error) {
                runOnUi(() -> Toast.makeText(this, R.string.assist_history_clear_failed,
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showNotice() {
        if (noticeShown) return;
        noticeShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.assist_notice_title)
                .setMessage(R.string.assist_notice_body)
                .setCancelable(false)
                .setPositiveButton(R.string.assist_notice_ok, (dialog, which) ->
                        runIo(() -> prefs.setNoticeSeen(true)))
                .show();
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.assist_settings_settings_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(A11yBridge.accessibilitySettings());
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.assist_settings_settings_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled()) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.assist_settings_settings_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || notificationsEnabled()) return;
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }

    private boolean notificationsEnabled() {
        android.app.NotificationManager manager =
                getSystemService(android.app.NotificationManager.class);
        if (manager != null && !manager.areNotificationsEnabled()) return false;
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean validEndpoint(String endpoint) {
        return endpoint.startsWith("https://") || endpoint.startsWith("http://");
    }

    private void runIo(Runnable task) {
        ExecutorService current = io;
        if (current == null) return;
        try {
            current.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void runOnUi(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed) action.run();
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
