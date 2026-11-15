package com.dshgojava.assist;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;
import com.dshgojava.assist.platform.AssistA11yService;
import com.dshgojava.assist.platform.CaptureVisibility;
import com.dshgojava.assist.platform.UiBox;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local endpoint shared by sources that need accessibility screenshots and UI trees. */
public final class A11yBridge {
    private static final long OVERLAY_HIDE_TIMEOUT_MS = 1_000L;
    private static final CaptureVisibility CAPTURE_VISIBILITY = new CaptureVisibility();
    private static final java.util.List<Runnable> CONNECTION_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile AccessibilityService service;
    private static volatile CaptureOverlay captureOverlay;

    /** Owns the floating windows that must not appear in an accessibility screenshot. */
    public interface CaptureOverlay {
        void setHiddenForCapture(boolean hidden);
    }

    private A11yBridge() { }

    public static void attach(AccessibilityService connected) {
        boolean wasConnected = service != null;
        service = connected;
        if (!wasConnected) notifyConnectionChanged();
    }

    public static void detach(AccessibilityService disconnected) {
        if (service != disconnected) return;
        service = null;
        notifyConnectionChanged();
    }

    /**
     * Observes accessibility connect/disconnect on the main thread. The overlay host uses
     * it to switch between the accessibility window channel and the plain app overlay
     * channel without restarting the foreground service.
     */
    public static Runnable addConnectionListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        CONNECTION_LISTENERS.add(listener);
        return () -> CONNECTION_LISTENERS.remove(listener);
    }

    private static void notifyConnectionChanged() {
        for (Runnable listener : CONNECTION_LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // One overlay host failing must not block the remaining listeners.
            }
        }
    }

    public static AccessibilityService service() {
        return service;
    }

    public static boolean isConnected() {
        return service != null;
    }

    public static boolean available(Context context) {
        return service != null && isEnabled(context);
    }

    public static CaptureVisibility captureVisibility() {
        return CAPTURE_VISIBILITY;
    }

    /**
     * Registers the process overlay that must be hidden while a full-screen image
     * is captured. The returned action unregisters only this instance.
     */
    public static Runnable registerCaptureOverlay(CaptureOverlay overlay) {
        CaptureOverlay registered = Objects.requireNonNull(overlay, "overlay");
        captureOverlay = registered;
        return () -> {
            synchronized (A11yBridge.class) {
                if (captureOverlay == registered) captureOverlay = null;
            }
        };
    }

    /**
     * Hides or restores registered overlays on the main thread. A false result
     * means the caller must not take a screenshot that could leak the overlay.
     */
    public static boolean setCaptureOverlayHidden(boolean hidden) {
        CaptureOverlay overlay = captureOverlay;
        if (overlay == null) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                overlay.setHiddenForCapture(hidden);
                return true;
            } catch (RuntimeException error) {
                return false;
            }
        }

        AtomicBoolean applied = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper());
        boolean posted = main.post(() -> {
            try {
                overlay.setHiddenForCapture(hidden);
                applied.set(true);
            } catch (RuntimeException ignored) {
                // The capture caller receives an explicit failure instead of an overlay leak.
            } finally {
                done.countDown();
            }
        });
        if (!posted) return false;
        try {
            if (!done.await(OVERLAY_HIDE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
        return applied.get();
    }

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            ComponentName expected = component(context);
            for (String entry : enabled.split(":")) {
                ComponentName candidate = ComponentName.unflattenFromString(entry.trim());
                if (expected.equals(candidate)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    public static ComponentName component(Context context) {
        return new ComponentName(context, AssistA11yService.class);
    }

    public static Intent accessibilitySettings() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static UiBox screenBounds(AccessibilityService connected) {
        WindowManager windows = connected.getSystemService(WindowManager.class);
        if (windows == null) return new UiBox(0, 0, 1, 1);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect rect = windows.getCurrentWindowMetrics().getBounds();
            return new UiBox(rect.left, rect.top, rect.right, rect.bottom);
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        return new UiBox(0, 0, Math.max(1, metrics.widthPixels),
                Math.max(1, metrics.heightPixels));
    }
}
