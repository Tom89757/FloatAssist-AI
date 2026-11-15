package com.dshgojava.assist.source;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import com.dshgojava.assist.A11yBridge;
import com.dshgojava.assist.AssistRequest;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.model.PromptImage;
import com.dshgojava.assist.platform.Capture;
import com.dshgojava.assist.platform.ScreenShooter;
import com.dshgojava.assist.platform.Shot;
import com.dshgojava.assist.platform.TreeCapture;
import com.dshgojava.assist.platform.UiBox;
import com.dshgojava.assist.platform.UiWindow;
import com.dshgojava.assist.platform.WindowKind;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Reads the accessibility tree first, then screenshots only when pixels are actually needed. */
public final class ScreenSource implements AssistSource {
    private static final int MAX_EDGE_PX = 1_024;
    private static final long TREE_BUDGET_MS = 1_500L;
    private static final long SCREENSHOT_TIMEOUT_MS = 2_500L;
    private static final long OVERLAY_SETTLE_MS = 48L;

    private final Context context;
    private final Executor executor;

    public ScreenSource(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "assist-screen"));
    }

    @Override
    public SourceKind kind() {
        return SourceKind.SCREEN;
    }

    @Override
    public boolean available() {
        return A11yBridge.isConnected();
    }

    @Override
    public CompletableFuture<CaptureBundle> gather(AssistRequest request) {
        if (!A11yBridge.isConnected()) {
            return SourceFutures.failed(new CaptureException("开启无障碍以读取屏幕"));
        }
        try {
            return SourceFutures.supply(executor, () -> gatherNow(request));
        } catch (RejectedExecutionException error) {
            return SourceFutures.failed(new CaptureException("屏幕读取已停止"));
        }
    }

    /** Stops the source executor when its owning foreground service is destroyed. */
    public void close() {
        if (executor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdownNow();
        }
    }

    private CaptureBundle gatherNow(AssistRequest request) {
        AccessibilityService service = A11yBridge.service();
        if (service == null) throw new CaptureException("开启无障碍以读取屏幕");
        UiBox fallbackScreen = A11yBridge.screenBounds(service);
        Capture capture = null;
        ScreenTextExtractor.Result tree = new ScreenTextExtractor.Result("", false);
        try {
            try {
                capture = TreeCapture.capture(service, fallbackScreen, context.getPackageName(),
                        TREE_BUDGET_MS);
                tree = ScreenTextExtractor.extract(capture.windows());
            } catch (RuntimeException ignored) {
                // The screenshot path below still gets a chance and reports its own explicit error.
            }

            boolean needsImage = ScreenCapturePolicy.needsScreenshot(
                    request.task().needsVision(), tree.text().length());
            ShotImage shot = null;
            String screenshotError = null;
            if (needsImage) {
                UiBox screen = capture == null ? fallbackScreen : capture.screen();
                List<UiBox> secrets = capture == null ? List.of()
                        : ScreenTextExtractor.sensitiveBounds(capture.windows());
                UiWindow window = screenshotWindow(capture);
                ScreenShooter shooter = new ScreenShooter(service, A11yBridge.captureVisibility());
                try {
                    try {
                        if (!A11yBridge.setCaptureOverlayHidden(true)) {
                            throw new CaptureException("无法隐藏悬浮层，已取消截图");
                        }
                        settleOverlayFrame();
                        Shot result = shooter.shoot(window, screen, List.of(), secrets,
                                SCREENSHOT_TIMEOUT_MS, true, false, false, () -> false);
                        if (result.secure()) {
                            throw new CaptureException("该页面禁止截图");
                        }
                        if (result.bytes() == null || result.bytes().length == 0) {
                            screenshotError = result.error() == null ? "截图失败" : result.error();
                        } else {
                            try {
                                ImageDownsampler.Encoded encoded = ImageDownsampler.downsample(
                                        result.bytes(), MAX_EDGE_PX, 85);
                                PromptImage image = new PromptImage(encoded.mediaType(),
                                        encoded.bytes(), "screen.jpg");
                                shot = new ShotImage(image, encoded.width(), encoded.height());
                            } catch (IOException error) {
                                screenshotError = "截图无法解析";
                            } catch (OutOfMemoryError error) {
                                screenshotError = "截图过大，无法处理";
                            }
                        }
                    } catch (CaptureException error) {
                        throw error;
                    } catch (RuntimeException error) {
                        screenshotError = "截图失败";
                    }
                } finally {
                    A11yBridge.setCaptureOverlayHidden(false);
                    shooter.close();
                }
            }

            if (shot == null && tree.text().isBlank()) {
                if (screenshotError != null) throw new CaptureException(screenshotError);
                throw new CaptureException("当前页面无法读取");
            }
            if (shot == null && request.task().needsVision()) {
                throw new CaptureException(screenshotError == null ? "当前页面无法读取" : screenshotError);
            }

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("source", "screen");
            meta.put("tree_chars", Integer.toString(tree.text().length()));
            meta.put("tree_truncated", Boolean.toString(tree.truncated()));
            if (capture != null && capture.packageName() != null
                    && !capture.packageName().isBlank()) {
                meta.put("package", capture.packageName());
            }
            meta.put("screenshot", Boolean.toString(shot != null));
            if (screenshotError != null) meta.put("screenshot_error", screenshotError);
            if (shot != null) {
                meta.put("image_width", Integer.toString(shot.width));
                meta.put("image_height", Integer.toString(shot.height));
            }
            return new CaptureBundle(tree.text(), shot == null ? List.of() : List.of(shot.image),
                    meta);
        } finally {
            if (capture != null) capture.close();
        }
    }

    private static UiWindow screenshotWindow(Capture capture) {
        if (capture == null) return null;
        Integer visual = capture.visualWindowId();
        for (UiWindow window : capture.windows()) {
            if (window.kind() == WindowKind.APP
                    && visual != null && window.id() == visual) return window;
        }
        for (UiWindow window : capture.windows()) {
            if (window.kind() == WindowKind.APP) return window;
        }
        return null;
    }

    private static void settleOverlayFrame() {
        try {
            Thread.sleep(OVERLAY_SETTLE_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private record ShotImage(PromptImage image, int width, int height) { }
}
