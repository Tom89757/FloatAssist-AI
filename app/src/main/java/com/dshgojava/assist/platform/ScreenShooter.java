package com.dshgojava.assist.platform;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Captures API 30+ screenshots, masks secret fields, numbers elements and returns bounded JPEG. */
public final class ScreenShooter {
    private final AccessibilityService service;
    private final CaptureVisibility visibility;
    private final LocalOcr localOcr;
    private final Object captureLock = new Object();
    private final java.util.concurrent.ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "dsh-a11y-shot"));
    private final Executor callbackExecutor;
    private boolean closed;

    public ScreenShooter(AccessibilityService service, CaptureVisibility visibility) {
        this(service, visibility, NoLocalOcr.INSTANCE);
    }

    public ScreenShooter(AccessibilityService service, CaptureVisibility visibility,
                         LocalOcr localOcr) {
        this.service = service;
        this.visibility = visibility;
        this.localOcr = localOcr;
        this.callbackExecutor = callback -> {
            try {
                executor.execute(callback);
            } catch (RejectedExecutionException error) {
                boolean isClosed;
                synchronized (captureLock) {
                    isClosed = closed;
                }
                if (!isClosed) throw error;
                callback.run();
            }
        };
    }

    public LocalOcrResult localTextWithoutFrame() {
        LocalOcrResult.Reason reason = localOcr.unavailableReason();
        return new LocalOcrResult.Unavailable(reason == null
                ? LocalOcrResult.Reason.NO_FRAME : reason);
    }

    public void close() {
        synchronized (captureLock) {
            closed = true;
        }
        executor.shutdown();
    }

    public Shot shoot(UiWindow window, UiBox screen, List<ScreenElement> marks,
                      List<UiBox> secrets, long timeoutMs, boolean image,
                      boolean readLocalText, boolean localTextPrivacyAllowed,
                      BooleanSupplier localTextCancelled) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        if (Build.VERSION.SDK_INT < 30) {
            return new Shot(null, null, null, false,
                    "本机系统低于 Android 11，不支持无障碍截图", null, null);
        }
        boolean useWindow = Build.VERSION.SDK_INT >= 34 && window != null;
        Raw raw;
        if (useWindow) {
            raw = capture(timeoutMs, callback -> service.takeScreenshotOfWindow(
                    window.id(), callbackExecutor, callback));
        } else {
            visibility.update(true);
            try {
                raw = capture(timeoutMs, callback -> service.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY, callbackExecutor, callback));
            } finally {
                visibility.update(false);
            }
        }
        if (raw.secure()) {
            return new Shot(null, null, null, true, "此页面禁止截图（安全窗口）", null, null);
        }
        Bitmap bitmap = raw.bitmap();
        if (bitmap == null) {
            return new Shot(null, null, null, false,
                    raw.error() == null ? "截图失败" : raw.error(), null, null);
        }
        try {
            if (looksBlack(bitmap)) {
                return new Shot(null, null, null, true, "截图全黑，可能是禁止截图的页面", null, null);
            }
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted()
                    || localTextCancelled.getAsBoolean() || isClosed();
            LocalOcrResult localText = LocalOcrBoundary.read(readLocalText && image,
                    localTextPrivacyAllowed, bitmap.getWidth(), bitmap.getHeight(), deadline,
                    SystemClock::uptimeMillis, cancelled,
                    () -> localOcr.recognize(bitmap, deadline, cancelled));
            Long takenAt = raw.takenAtMillis();
            UiBox origin = useWindow ? window.bounds() : screen;
            VisualFrame frame = VisualFrame.capture(bitmap.getWidth(), bitmap.getHeight(),
                    (y, row) -> bitmap.getPixels(row, 0, bitmap.getWidth(), 0, y,
                            bitmap.getWidth(), 1));
            float factor = Math.min(1f, 1300f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            int width = Math.max(1, Math.round(bitmap.getWidth() * factor));
            int height = Math.max(1, Math.round(bitmap.getHeight() * factor));
            float sx = (float) width / Math.max(1, origin.width());
            float sy = (float) height / Math.max(1, origin.height());
            ImageGeometry geometry = new ImageGeometry(origin.left(), origin.top(), sx, sy,
                    width, height);
            ImageObservation observation = new ImageObservation(geometry,
                    raw.capturedUptimeMs(), frame);
            if (!image) {
                return new Shot(null, null, geometry, false, null, takenAt, observation,
                        localText);
            }
            Bitmap scaled = factor < 1f
                    ? Bitmap.createScaledBitmap(bitmap, width, height, true) : bitmap;
            if (scaled == bitmap || !scaled.isMutable()) {
                Bitmap mutable = scaled.copy(Bitmap.Config.ARGB_8888, true);
                if (scaled != bitmap) scaled.recycle();
                scaled = mutable;
            }
            try {
                draw(scaled, marks, secrets, origin, sx, sy);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out);
                return new Shot(out.toByteArray(), "image/jpeg", geometry, false, null,
                        takenAt, observation, localText);
            } finally {
                scaled.recycle();
            }
        } finally {
            bitmap.recycle();
        }
    }

    public Shot shoot(UiWindow window, UiBox screen, List<ScreenElement> marks,
                      List<UiBox> secrets, long timeoutMs) {
        return shoot(window, screen, marks, secrets, timeoutMs, true, false, false, () -> false);
    }

    private boolean isClosed() {
        synchronized (captureLock) {
            return closed;
        }
    }

    private record Raw(Bitmap bitmap, boolean secure, String error, Long takenAtMillis,
                       long capturedUptimeMs) { }

    private Raw capture(long timeoutMs,
                        java.util.function.Consumer<AccessibilityService.TakeScreenshotCallback> start) {
        if (Build.VERSION.SDK_INT < 30) return new Raw(null, false, "unsupported", null, 0L);
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        for (int attempt = 0; attempt < 2; attempt++) {
            CountDownLatch latch = new CountDownLatch(1);
            boolean[] accepting = {true};
            Raw[] result = {null};
            boolean[] tooSoon = {false};
            AccessibilityService.TakeScreenshotCallback callback =
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                            HardwareBuffer buffer = screenshot.getHardwareBuffer();
                            try {
                                if (!accepting()) return;
                                Raw next;
                                try {
                                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer,
                                            screenshot.getColorSpace());
                                    Bitmap software = null;
                                    try {
                                        if (hardware != null) {
                                            software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                        }
                                    } finally {
                                        if (hardware != null) hardware.recycle();
                                    }
                                    long wallClock = ScreenshotClock.wallTime(
                                            screenshot.getTimestamp(), SystemClock.uptimeMillis(),
                                            System.currentTimeMillis());
                                    next = new Raw(software, false,
                                            software == null ? "截图失败" : null, wallClock,
                                            screenshot.getTimestamp());
                                } catch (Throwable error) {
                                    next = new Raw(null, false, "截图失败", null, 0L);
                                }
                                publish(next, false);
                            } finally {
                                buffer.close();
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            boolean secure = Build.VERSION.SDK_INT >= 34
                                    && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW;
                            publish(new Raw(null, secure, "截图失败（错误 " + errorCode + "）",
                                    null, 0L), errorCode == AccessibilityService
                                    .ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT);
                            latch.countDown();
                        }

                        private boolean accepting() {
                            synchronized (captureLock) {
                                return accepting[0] && !closed;
                            }
                        }

                        private void publish(Raw next, boolean retry) {
                            boolean accepted;
                            synchronized (captureLock) {
                                accepted = accepting[0] && !closed;
                                if (accepted) {
                                    result[0] = next;
                                    tooSoon[0] = retry;
                                }
                            }
                            if (!accepted && next.bitmap() != null) next.bitmap().recycle();
                        }
                    };
            Raw received = null;
            boolean retry;
            try {
                if (isClosed()) return new Raw(null, false, "截图失败", null, 0L);
                start.accept(callback);
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0 || !latch.await(remaining, TimeUnit.MILLISECONDS)) {
                    return new Raw(null, false, "截图超时", null, 0L);
                }
                synchronized (captureLock) {
                    if (!closed) {
                        received = result[0];
                        result[0] = null;
                        retry = tooSoon[0];
                    } else {
                        retry = false;
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new Raw(null, false, "截图失败", null, 0L);
            } finally {
                Raw abandoned;
                synchronized (captureLock) {
                    accepting[0] = false;
                    abandoned = result[0];
                    result[0] = null;
                }
                if (abandoned != null && abandoned.bitmap() != null) abandoned.bitmap().recycle();
            }
            if (!retry || attempt == 1) {
                return received == null ? new Raw(null, false, "截图失败", null, 0L) : received;
            }
            if (deadline - SystemClock.uptimeMillis() <= 1_050L) {
                return received == null ? new Raw(null, false, "截图超时", null, 0L) : received;
            }
            try {
                Thread.sleep(1_050L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new Raw(null, false, "截图失败", null, 0L);
            }
        }
        return new Raw(null, false, "截图失败", null, 0L);
    }

    private static boolean looksBlack(Bitmap bitmap) {
        int dark = 0;
        int count = 32;
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                int color = bitmap.getPixel((bitmap.getWidth() - 1) * i / (count - 1),
                        (bitmap.getHeight() - 1) * j / (count - 1));
                if (Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color))) < 12) {
                    dark++;
                }
            }
        }
        return dark >= count * count * 97 / 100;
    }

    private static void draw(Bitmap bitmap, List<ScreenElement> marks, List<UiBox> secrets,
                             UiBox origin, float sx, float sy) {
        Canvas canvas = new Canvas(bitmap);
        Paint grey = new Paint();
        grey.setColor(Color.rgb(128, 128, 128));
        for (UiBox box : secrets) {
            canvas.drawRect((box.left() - origin.left()) * sx,
                    (box.top() - origin.top()) * sy,
                    (box.right() - origin.left()) * sx,
                    (box.bottom() - origin.top()) * sy, grey);
        }
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2f);
        outline.setColor(Color.argb(200, 61, 90, 152));
        Paint tag = new Paint(Paint.ANTI_ALIAS_FLAG);
        tag.setColor(Color.argb(230, 33, 50, 98));
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(1.5f);
        halo.setColor(Color.WHITE);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(18f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        Paint.FontMetrics metrics = text.getFontMetrics();
        for (ScreenElement mark : marks) {
            if (mark.kind() == ElementKind.LIST) continue;
            RectF rect = new RectF((mark.bounds().left() - origin.left()) * sx,
                    (mark.bounds().top() - origin.top()) * sy,
                    (mark.bounds().right() - origin.left()) * sx,
                    (mark.bounds().bottom() - origin.top()) * sy);
            if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= bitmap.getWidth()
                    || rect.top >= bitmap.getHeight()) continue;
            canvas.drawRect(rect, outline);
            String label = Integer.toString(mark.id());
            float textWidth = text.measureText(label) + 8f;
            float textHeight = metrics.descent - metrics.ascent + 2f;
            float left = Math.max(0f, Math.min(rect.left, bitmap.getWidth() - textWidth));
            float top = Math.max(0f, Math.min(rect.top, bitmap.getHeight() - textHeight));
            RectF box = new RectF(left, top, left + textWidth, top + textHeight);
            canvas.drawRoundRect(box, 4f, 4f, tag);
            canvas.drawRoundRect(box, 4f, 4f, halo);
            canvas.drawText(label, left + 4f, top + 1f - metrics.ascent, text);
        }
    }
}
