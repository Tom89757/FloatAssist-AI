package com.dshgojava.assist.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.accessibilityservice.AccessibilityService;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Toast;
import com.dshgojava.assist.A11yBridge;
import com.dshgojava.assist.AssistRequest;
import com.dshgojava.assist.DefaultAssistRunner;
import com.dshgojava.assist.ModelRetry;
import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.R;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.TaskRegistry;
import com.dshgojava.assist.model.DirectApiModelChannel;
import com.dshgojava.assist.model.ModelChannel;
import com.dshgojava.assist.model.NullModelChannel;
import com.dshgojava.assist.platform.BallGeometry;
import com.dshgojava.assist.source.AssistSource;
import com.dshgojava.assist.source.ClipSource;
import com.dshgojava.assist.source.ImageSource;
import com.dshgojava.assist.source.ScreenSource;
import com.dshgojava.assist.source.VoiceSource;
import com.dshgojava.assist.source.WebSource;
import com.dshgojava.assist.store.AssistHistoryStore;
import com.dshgojava.assist.store.AssistPrefs;
import com.dshgojava.assist.ui.AssistBall;
import com.dshgojava.assist.ui.AssistCardLayer;
import com.dshgojava.assist.ui.AssistCaptureActivity;
import com.dshgojava.assist.ui.AssistGate;
import com.dshgojava.assist.ui.AssistMenuModel;
import com.dshgojava.assist.ui.AssistSettingsActivity;
import com.dshgojava.assist.ui.AssistVoiceHud;
import com.dshgojava.assist.voice.VoiceCapture;
import com.dshgojava.assist.voice.VoicePolicy;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Foreground owner of the floating assistant. It creates the ball only after the
 * display-over-other-apps permission is present; every unavailable capability is
 * surfaced in the menu instead of silently doing nothing.
 */
public final class AssistOverlayService extends Service {
    public static final String ACTION_SHOW = "com.dshgojava.assist.action.SHOW";
    public static final String ACTION_HIDE = "com.dshgojava.assist.action.HIDE";
    public static final String ACTION_RELOAD = "com.dshgojava.assist.action.RELOAD";

    private static final String TAG = "DshAssist";
    private static final String CHANNEL_ID = "assist-overlay";
    private static final String PERMISSION_CHANNEL_ID = "assist-overlay-permission";
    private static final int NOTIFICATION_ID = 4905;
    private static final int PERMISSION_NOTIFICATION_ID = 4906;
    private static final int REQUEST_OPEN = 4907;
    private static final int REQUEST_HIDE = 4908;
    private static final int REQUEST_PERMISSION = 4909;

    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ModelChannel model = NullModelChannel.INSTANCE;
    private final AssistMenuModel.TaskGate gate = this::blockFor;
    private AssistPrefs prefs;
    private AssistHistoryStore history;
    private ExecutorService io;
    private Map<SourceKind, AssistSource> sources;
    private AssistMenuModel menuModel;
    private ImageSource imageSource;
    private ClipSource clipSource;
    private ScreenSource screenSource;
    private VoiceSource voiceSource;
    private WebSource webSource;
    private DefaultAssistRunner runner;
    private AssistBall ball;
    private AssistCardLayer cardLayer;
    private AssistVoiceHud voiceHud;
    private Context overlayContext;
    private ChannelKind activeChannel = ChannelKind.APPLICATION;
    private Runnable captureRegistration;
    private Runnable a11yConnectionListener;
    private boolean voiceCapturing;
    private boolean voiceStopping;

    public static boolean isRunning() {
        return running;
    }

    /** Returns false when Android refuses the background foreground-service start. */
    public static boolean show(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            app.startForegroundService(new Intent(app, AssistOverlayService.class)
                    .setAction(ACTION_SHOW));
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "overlay service start refused", error);
            return false;
        }
    }

    public static void hide(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        app.stopService(new Intent(app, AssistOverlayService.class));
    }

    /** Reloads model settings into an already-running overlay. */
    public static void reload(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            app.startService(new Intent(app, AssistOverlayService.class)
                    .setAction(ACTION_RELOAD));
        } catch (RuntimeException error) {
            Log.w(TAG, "overlay settings reload refused", error);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new AssistPrefs(this);
        history = AssistHistoryStore.forApp(this);
        io = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "assist-settings-io"));
        imageSource = new ImageSource(this);
        clipSource = new ClipSource(this);
        screenSource = new ScreenSource(this);
        voiceSource = new VoiceSource(this);
        webSource = new WebSource(this);
        Map<SourceKind, AssistSource> available = new EnumMap<>(SourceKind.class);
        available.put(SourceKind.SCREEN, screenSource);
        available.put(SourceKind.CLIP, clipSource);
        available.put(SourceKind.IMAGE, imageSource);
        available.put(SourceKind.VOICE, voiceSource);
        available.put(SourceKind.WEB, webSource);
        sources = Map.copyOf(available);
        runner = new DefaultAssistRunner(model, sources);
        menuModel = AssistMenuModel.all();
        captureRegistration = A11yBridge.registerCaptureOverlay(this::setOverlaysHiddenForCapture);
        a11yConnectionListener = A11yBridge.addConnectionListener(this::onA11yConnectionChanged);
        reloadPreferences();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            stopOverlay();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            if (!running) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            ensureChannels();
            if (!goForeground()) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            reloadPreferences();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(action)) {
            AssistCaptureActivity.clearCache(this);
        }

        ensureChannels();
        if (!goForeground()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        // 优先无障碍通道（豁免 setHideOverlayWindows），不可用再回退应用悬浮窗。
        ChannelKind preferred = preferredChannel();
        if (channelAvailable(preferred)) {
            activeChannel = preferred;
        } else if (channelAvailable(ChannelKind.APPLICATION)) {
            activeChannel = ChannelKind.APPLICATION;
        } else {
            notifyOverlayPermissionMissing(preferred);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        Log.i(TAG, "overlay channel=" + activeChannel);
        if (ball == null && !createBall()) {
            notifyAttachFailed();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        running = true;
        if (AssistCaptureActivity.ACTION_CLIP_TEXT.equals(action)) {
            acceptClipboard(intent);
        } else if (AssistCaptureActivity.ACTION_IMAGE_SELECTED.equals(action)) {
            acceptImage(intent);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (ball != null) ball.reposition();
        if (cardLayer != null) cardLayer.reposition();
    }

    @Override
    public void onDestroy() {
        running = false;
        AssistBall current = ball;
        ball = null;
        if (current != null) current.detach();
        if (cardLayer != null) {
            cardLayer.dismissNow();
            cardLayer = null;
        }
        if (voiceHud != null) {
            voiceHud.dismissNow();
            voiceHud = null;
        }
        voiceCapturing = false;
        voiceStopping = false;
        if (voiceSource != null) {
            voiceSource.close();
            voiceSource = null;
        }
        if (captureRegistration != null) {
            captureRegistration.run();
            captureRegistration = null;
        }
        if (a11yConnectionListener != null) {
            a11yConnectionListener.run();
            a11yConnectionListener = null;
        }
        if (screenSource != null) {
            screenSource.close();
            screenSource = null;
        }
        if (clipSource != null) {
            clipSource.close();
            clipSource = null;
        }
        if (imageSource != null) {
            imageSource.close();
            imageSource = null;
        }
        if (webSource != null) {
            webSource.close();
            webSource = null;
        }
        ExecutorService currentIo = io;
        io = null;
        if (currentIo != null) currentIo.shutdownNow();
        AssistCaptureActivity.clearCache(this);
        super.onDestroy();
    }

    private void setOverlaysHiddenForCapture(boolean hidden) {
        AssistBall currentBall = ball;
        if (currentBall != null) currentBall.setHiddenForCapture(hidden);
        AssistCardLayer cards = cardLayer;
        if (cards != null) cards.setHiddenForCapture(hidden);
    }

    private boolean createBall() {
        overlayContext = channelContext(activeChannel);
        WindowManager windows = channelWindows(activeChannel);
        if (windows == null) return false;
        int windowType = activeChannel.windowType();
        AssistBall next = new AssistBall(overlayContext, windows, windowType,
                this::safeArea, menuModel, gate, ballListener);
        if (!next.attach(getString(R.string.assist_menu_title),
                getString(R.string.assist_service_label))) {
            return false;
        }
        ball = next;
        cardLayer = createCardLayer(windows, windowType);
        voiceHud = new AssistVoiceHud(overlayContext, windows, windowType, this::safeArea);
        return true;
    }

    private AssistCardLayer createCardLayer(WindowManager windows, int windowType) {
        return new AssistCardLayer(overlayContext, windows, windowType,
                this::safeArea, this::ballSide, model, this::recordHistory);
    }

    /** Which window host the floating overlay uses. */
    private enum ChannelKind {
        ACCESSIBILITY(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY),
        APPLICATION(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);

        private final int windowType;

        ChannelKind(int windowType) {
            this.windowType = windowType;
        }

        int windowType() {
            return windowType;
        }
    }

    /**
     * Accessibility overlay first: Android 12+ lets any app hide every
     * TYPE_APPLICATION_OVERLAY window via Window.setHideOverlayWindows(true), which
     * finance and banking apps enable on their sensitive screens.
     * TYPE_ACCESSIBILITY_OVERLAY is exempt from that switch, so the ball stays visible
     * there whenever accessibility is connected. Without accessibility the plain app
     * overlay keeps the ball working everywhere else.
     */
    private ChannelKind preferredChannel() {
        return A11yBridge.isConnected() ? ChannelKind.ACCESSIBILITY : ChannelKind.APPLICATION;
    }

    private boolean channelAvailable(ChannelKind kind) {
        if (kind == ChannelKind.ACCESSIBILITY) return A11yBridge.isConnected();
        return canDrawOverlays();
    }

    private Context channelContext(ChannelKind kind) {
        if (kind == ChannelKind.ACCESSIBILITY) {
            AccessibilityService service = A11yBridge.service();
            if (service != null) return service;
        }
        return overlayWindowContext();
    }

    private WindowManager channelWindows(ChannelKind kind) {
        Context context = overlayContext == null ? this : overlayContext;
        WindowManager windows = context.getSystemService(WindowManager.class);
        return windows == null ? getSystemService(WindowManager.class) : windows;
    }

    /** Switches the live overlay between channels without dropping the service. */
    private void onA11yConnectionChanged() {
        main.post(() -> {
            if (!running || ball == null) return;
            ChannelKind next = preferredChannel();
            if (next == activeChannel) return;
            Log.i(TAG, "a11y changed, switching channel " + activeChannel + " -> " + next);
            rebuildOverlay(next);
        });
    }

    private void rebuildOverlay(ChannelKind kind) {
        AssistBall previousBall = ball;
        ball = null;
        if (previousBall != null) previousBall.detach();
        AssistCardLayer previousCards = cardLayer;
        cardLayer = null;
        if (previousCards != null) previousCards.dismissNow();
        if (voiceHud != null) {
            voiceHud.dismissNow();
            voiceHud = null;
        }
        activeChannel = kind;
        if (!channelAvailable(kind)) {
            notifyOverlayPermissionMissing(kind);
            stopOverlay();
            return;
        }
        if (!createBall()) {
            notifyAttachFailed();
            stopOverlay();
        }
    }

    private void reloadPreferences() {
        ExecutorService current = io;
        if (current == null) return;
        try {
            current.execute(() -> {
                AssistPrefs.Snapshot snapshot;
                try {
                    snapshot = prefs.read();
                } catch (RuntimeException error) {
                    Log.w(TAG, "assist preferences read failed", error);
                    return;
                }
                main.post(() -> applyPreferences(snapshot));
            });
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "assist preferences executor stopped", error);
        }
    }

    private void applyPreferences(AssistPrefs.Snapshot snapshot) {
        ModelChannel next = snapshot.model().configured()
                ? new DirectApiModelChannel(snapshot.model())
                : NullModelChannel.INSTANCE;
        model = next;
        if (sources != null) runner = new DefaultAssistRunner(next, sources);
        if (cardLayer != null) {
            AssistCardLayer previous = cardLayer;
            cardLayer = null;
            previous.dismissNow();
            WindowManager windows = overlayContext == null
                    ? null : overlayContext.getSystemService(WindowManager.class);
            if (windows != null) cardLayer = createCardLayer(windows, activeChannel.windowType());
        }
    }

    private void recordHistory(QuickTask task, String text, boolean followUp) {
        if (task == null || text == null || text.isBlank()) return;
        ExecutorService current = io;
        if (current == null) return;
        try {
            current.execute(() -> {
                try {
                    history.record(task.id(), task.title(), text, followUp,
                            System.currentTimeMillis());
                } catch (RuntimeException error) {
                    Log.w(TAG, "assist history write failed", error);
                }
            });
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "assist history executor stopped", error);
        }
    }

    private BallGeometry.Side ballSide() {
        AssistBall current = ball;
        return current == null ? BallGeometry.Side.RIGHT : current.side();
    }

    private final AssistBall.Listener ballListener = new AssistBall.Listener() {
        @Override
        public void onTaskSelected(QuickTask task) {
            if (task.source() == SourceKind.CLIP || TaskRegistry.isClipboardWeb(task)) {
                AssistCaptureActivity.readClipboard(AssistOverlayService.this, task);
            } else if (task.source() == SourceKind.IMAGE && !imageSource.available()) {
                AssistCaptureActivity.pickImage(AssistOverlayService.this, task);
            } else {
                startTask(task, AssistRequest.of(task));
            }
        }

        @Override
        public void onUnavailable(QuickTask task, AssistMenuModel.Block block) {
            handleUnavailable(task, block);
        }

        @Override
        public void onLongPress() {
            beginVoiceCapture();
        }

        @Override
        public void onLongPressEnd(boolean cancelled) {
            endVoiceCapture(cancelled);
        }

        @Override
        public void onSettings() {
            openSettings();
        }

        @Override
        public void onMenuFailed() {
            toast(R.string.assist_overlay_attach_failed);
        }
    };

    private AssistMenuModel.Block blockFor(QuickTask task) {
        boolean clipboard = getSystemService(android.content.ClipboardManager.class) != null;
        return AssistGate.blockFor(task, new AssistGate.Capabilities(
                A11yBridge.isConnected(), model.ready(), model.supportsVision(),
                clipboard, imageSource != null && imageSource.available(),
                voiceSource != null && voiceSource.available() && microphoneGranted()));
    }

    private void handleUnavailable(QuickTask task, AssistMenuModel.Block block) {
        switch (block) {
            case ACCESSIBILITY -> openIntent(A11yBridge.accessibilitySettings());
            case MODEL, VISION -> openSettings();
            case SOURCE -> {
                if (task.source() == SourceKind.IMAGE) {
                    AssistCaptureActivity.pickImage(this, task);
                } else if (task.source() == SourceKind.VOICE) {
                    toast(R.string.assist_unavailable_voice);
                } else {
                    toast(R.string.assist_menu_source_unavailable);
                }
            }
            case COMING_SOON -> toast(R.string.assist_menu_coming_soon);
            default -> { }
        }
    }

    private void acceptClipboard(Intent intent) {
        QuickTask task = clipboardTaskFrom(intent);
        String text = intent == null ? null : intent.getStringExtra(
                AssistCaptureActivity.EXTRA_CLIP_TEXT);
        if (task == null) {
            toast(R.string.assist_capture_unavailable);
            return;
        }
        if (text == null) {
            toast(R.string.assist_clipboard_empty);
            return;
        }
        startTask(task, new AssistRequest(task, Map.of(ClipSource.CONTEXT_TEXT, text), ""));
    }

    private void acceptImage(Intent intent) {
        QuickTask task = taskFrom(intent, SourceKind.IMAGE);
        Uri uri = imageUri(intent);
        if (task == null || uri == null) {
            toast(R.string.assist_image_pick_failed);
            return;
        }
        if (imageSource == null) {
            toast(R.string.assist_capture_unavailable);
            return;
        }
        imageSource.select(uri);
        startTask(task, AssistRequest.of(task));
    }

    @SuppressWarnings("deprecation")
    private static Uri imageUri(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(
                    AssistCaptureActivity.EXTRA_IMAGE_URI, Uri.class);
        }
        return intent.getParcelableExtra(AssistCaptureActivity.EXTRA_IMAGE_URI);
    }

    /** Clipboard bridge tasks: the text group plus the clipboard-URL web task. */
    private static QuickTask clipboardTaskFrom(Intent intent) {
        if (intent == null) return null;
        String id = intent.getStringExtra(AssistCaptureActivity.EXTRA_TASK_ID);
        if (id == null || id.isBlank()) return null;
        QuickTask task = TaskRegistry.find(id).orElse(null);
        if (task == null) return null;
        boolean bridged = task.source() == SourceKind.CLIP || TaskRegistry.isClipboardWeb(task);
        return bridged ? task : null;
    }

    private static QuickTask taskFrom(Intent intent, SourceKind source) {        if (intent == null) return null;
        String id = intent.getStringExtra(AssistCaptureActivity.EXTRA_TASK_ID);
        if (id == null || id.isBlank()) return null;
        QuickTask task = TaskRegistry.find(id).orElse(null);
        return task != null && task.source() == source ? task : null;
    }

    private void startTask(QuickTask task, AssistRequest request) {
        if (cardLayer == null || runner == null) {
            toast(R.string.assist_overlay_attach_failed);
            return;
        }
        com.dshgojava.assist.AssistRunner.AssistRun run = runner.start(task, request);
        ModelRetry retry = run instanceof ModelRetry value ? value : null;
        cardLayer.show(task, run, retry);
    }

    private void beginVoiceCapture() {
        if (voiceCapturing || voiceSource == null || voiceHud == null) return;
        if (!voiceSource.available()) {
            toast(R.string.assist_unavailable_voice);
            return;
        }
        if (!microphoneGranted()) {
            toast(R.string.assist_voice_mic_required);
            openIntent(AssistSettingsActivity.microphoneIntent(this));
            return;
        }
        if (!model.ready()) {
            toast(R.string.assist_unavailable_model);
            openSettings();
            return;
        }
        if (!voiceSource.start(voiceListener)) {
            toast(R.string.assist_voice_failed);
            return;
        }
        voiceCapturing = true;
        voiceStopping = false;
        if (!voiceHud.recording(null)) toast(R.string.assist_voice_recording);
    }

    private void endVoiceCapture(boolean cancelled) {
        if (!voiceCapturing || voiceSource == null) return;
        if (cancelled) {
            voiceCapturing = false;
            voiceStopping = false;
            voiceSource.cancel();
            if (voiceHud != null) voiceHud.dismissNow();
            return;
        }
        if (voiceStopping) return;
        voiceStopping = true;
        voiceSource.stop();
        if (voiceHud != null && !voiceHud.transcribing()) {
            toast(R.string.assist_voice_transcribing);
        }
    }

    private final VoiceCapture.Listener voiceListener = new VoiceCapture.Listener() {
        @Override
        public void onPartial(String text) {
            if (!voiceCapturing || voiceStopping || voiceHud == null) return;
            voiceHud.recording(text);
        }

        @Override
        public void onFinal(String text) {
            if (!voiceCapturing) return;
            voiceCapturing = false;
            voiceStopping = false;
            if (voiceHud != null) voiceHud.dismissNow();
            if (!VoicePolicy.usable(text)) {
                toast(R.string.assist_voice_no_speech);
                return;
            }
            QuickTask task = TaskRegistry.require("voice.transcribe");
            startTask(task, new AssistRequest(task,
                    Map.of(VoiceSource.CONTEXT_TEXT, VoicePolicy.normalize(text)), ""));
        }

        @Override
        public void onError(String message) {
            if (!voiceCapturing) return;
            voiceCapturing = false;
            voiceStopping = false;
            String shown = message == null || message.isBlank()
                    ? getString(R.string.assist_voice_failed) : message;
            if (voiceHud == null || !voiceHud.error(shown)) {
                Toast.makeText(AssistOverlayService.this, shown, Toast.LENGTH_LONG).show();
            }
        }
    };

    private boolean microphoneGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openSettings() {
        openIntent(AssistSettingsActivity.intent(this));
    }

    private void stopOverlay() {
        voiceCapturing = false;
        voiceStopping = false;
        if (voiceSource != null) voiceSource.cancel();
        if (voiceHud != null) voiceHud.dismissNow();
        AssistBall current = ball;
        ball = null;
        if (current != null) current.detach();
        AssistCardLayer cards = cardLayer;
        cardLayer = null;
        if (cards != null) cards.dismissNow();
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private boolean canDrawOverlays() {
        try {
            return Settings.canDrawOverlays(this);
        } catch (RuntimeException error) {
            Log.w(TAG, "overlay permission check failed", error);
            return false;
        }
    }

    private Context overlayWindowContext() {
        if (Build.VERSION.SDK_INT >= 30) {
            DisplayManager displays = getSystemService(DisplayManager.class);
            Display display = displays == null
                    ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                try {
                    return createDisplayContext(display).createWindowContext(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                } catch (RuntimeException error) {
                    Log.w(TAG, "overlay window context unavailable", error);
                }
            }
        }
        return this;
    }

    private BallGeometry.SafeArea safeArea() {
        Context context = overlayContext == null ? this : overlayContext;
        WindowManager windows = context.getSystemService(WindowManager.class);
        if (windows == null) windows = getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= 30 && windows != null) {
            WindowMetrics metrics = windows.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new BallGeometry.SafeArea(bounds.width(), bounds.height(),
                    insets.left, insets.top, insets.right, insets.bottom);
        }

        DisplayMetrics metrics = new DisplayMetrics();
        Display display = windows == null ? null : windows.getDefaultDisplay();
        if (display != null) display.getRealMetrics(metrics);
        else metrics = getResources().getDisplayMetrics();
        Resources system = Resources.getSystem();
        int status = dimension(system, "status_bar_height");
        int nav = dimension(system, "navigation_bar_height");
        int rotation = display == null ? Surface.ROTATION_0 : display.getRotation();
        if (rotation == Surface.ROTATION_90) {
            return new BallGeometry.SafeArea(metrics.widthPixels, metrics.heightPixels,
                    0, status, nav, 0);
        }
        if (rotation == Surface.ROTATION_270) {
            return new BallGeometry.SafeArea(metrics.widthPixels, metrics.heightPixels,
                    nav, status, 0, 0);
        }
        return new BallGeometry.SafeArea(metrics.widthPixels, metrics.heightPixels,
                0, status, 0, nav);
    }

    private static int dimension(Resources resources, String name) {
        int id = resources.getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : resources.getDimensionPixelSize(id);
    }

    private boolean goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, runningNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, runningNotification());
            }
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "overlay service could not go foreground", error);
            return false;
        }
    }

    private void ensureChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel runningChannel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.assist_channel), NotificationManager.IMPORTANCE_MIN);
        runningChannel.setShowBadge(false);
        manager.createNotificationChannel(runningChannel);
        NotificationChannel permissionChannel = new NotificationChannel(PERMISSION_CHANNEL_ID,
                getString(R.string.assist_permission_channel),
                NotificationManager.IMPORTANCE_HIGH);
        permissionChannel.setShowBadge(true);
        manager.createNotificationChannel(permissionChannel);
    }

    private Notification runningNotification() {
        PendingIntent open = PendingIntent.getActivity(this, REQUEST_OPEN,
                AssistSettingsActivity.intent(this),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent hide = PendingIntent.getService(this, REQUEST_HIDE,
                new Intent(this, AssistOverlayService.class).setAction(ACTION_HIDE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_assist_notification)
                .setContentTitle(getString(R.string.assist_service_label))
                .setContentText(getString(R.string.assist_running))
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.assist_menu_close), hide).build());
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void notifyOverlayPermissionMissing(ChannelKind kind) {
        boolean accessibility = kind == ChannelKind.ACCESSIBILITY;
        int messageRes = accessibility
                ? R.string.assist_unavailable_a11y_overlay
                : R.string.assist_unavailable_overlay;
        int titleRes = accessibility
                ? R.string.assist_permission_a11y
                : R.string.assist_permission_overlay;
        Intent settings = accessibility
                ? A11yBridge.accessibilitySettings()
                : overlaySettingsIntent();
        toast(messageRes);
        Notification notification = new Notification.Builder(this, PERMISSION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_assist_notification)
                .setContentTitle(getString(titleRes))
                .setContentText(getString(messageRes))
                .setContentIntent(PendingIntent.getActivity(this, REQUEST_PERMISSION,
                        settings,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
        notify(notification);
        try {
            startActivity(settings);
        } catch (RuntimeException error) {
            Log.w(TAG, "could not open permission settings", error);
        }
    }

    private void notifyAttachFailed() {
        toast(R.string.assist_overlay_attach_failed);
        Notification notification = new Notification.Builder(this, PERMISSION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_assist_notification)
                .setContentTitle(getString(R.string.assist_overlay_attach_failed))
                .setContentText(getString(R.string.assist_unavailable_overlay))
                .setContentIntent(PendingIntent.getActivity(this, REQUEST_PERMISSION,
                        appSettingsIntent(),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
        notify(notification);
    }

    private void notify(Notification notification) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            manager.notify(PERMISSION_NOTIFICATION_ID, notification);
        } catch (RuntimeException error) {
            Log.w(TAG, "permission notification failed", error);
        }
    }

    private Intent overlaySettingsIntent() {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private Intent appSettingsIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void openIntent(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "overlay could not open settings", error);
        }
    }

    private void toast(int text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
