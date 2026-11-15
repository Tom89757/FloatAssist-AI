package com.dshgojava.assist.source;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import com.dshgojava.assist.A11yBridge;
import com.dshgojava.assist.AssistRequest;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.platform.Capture;
import com.dshgojava.assist.platform.TreeCapture;
import com.dshgojava.assist.platform.UiBox;
import com.dshgojava.assist.platform.UiNode;
import com.dshgojava.assist.platform.UiWindow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Reads the current web page: it confirms the foreground app is a browser, finds
 * the address-bar URL in the accessibility tree, and reuses ScreenTextExtractor
 * for the body. WebView body text is exposed to the accessibility service, so no
 * network fetch is needed and the logged-in, JS-rendered content is read as-is.
 */
public final class WebSource implements AssistSource {
    private static final long TREE_BUDGET_MS = 1_500L;

    // 已知浏览器 / 内置 WebView 容器；白名单不命中时还会靠地址栏特征动态判定。
    private static final Set<String> BROWSERS = Set.of(
            "com.android.chrome",           // Chrome
            "com.microsoft.emmx",           // Edge
            "org.mozilla.firefox",          // Firefox
            "com.UCMobile",                 // UC
            "com.tencent.mtt",              // QQ 浏览器
            "com.quark.browser",            // 夸克
            "com.mi.globalbrowser",         // 小米浏览器（国际版）
            "com.android.browser",          // AOSP / 小米浏览器
            "com.qihoo.browser",            // 360 浏览器
            "com.tencent.mm",               // 微信（内置 WebView）
            "com.tencent.mobileqq",         // QQ（内置 WebView）
            "com.opera.browser",            // Opera
            "com.sec.android.app.sbrowser", // 三星浏览器
            "com.duckduckgo.mobile.android" // DuckDuckGo
    );

    private final Context context;
    private final Executor executor;

    public WebSource(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "assist-web"));
    }

    @Override
    public SourceKind kind() {
        return SourceKind.WEB;
    }

    @Override
    public boolean available() {
        return A11yBridge.isConnected();
    }

    @Override
    public CompletableFuture<CaptureBundle> gather(AssistRequest request) {
        if (!A11yBridge.isConnected()) {
            return SourceFutures.failed(new CaptureException("开启无障碍以读取网页"));
        }
        try {
            return SourceFutures.supply(executor, () -> gatherNow(request));
        } catch (RejectedExecutionException error) {
            return SourceFutures.failed(new CaptureException("网页读取已停止"));
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
        if (service == null) throw new CaptureException("开启无障碍以读取网页");
        UiBox screen = A11yBridge.screenBounds(service);
        Capture capture = TreeCapture.capture(service, screen,
                context.getPackageName(), TREE_BUDGET_MS);
        try {
            String pkg = capture == null ? null : capture.packageName();
            String url = null;
            if (capture != null) {
                for (UiWindow window : capture.windows()) {
                    url = extractUrl(window.root());
                    if (url != null) break;
                }
            }
            // 两级判定：白名单命中，或树里找到了地址栏 URL。
            boolean browser = BROWSERS.contains(pkg) || url != null;
            if (!browser) throw new CaptureException("当前不是网页");

            String body = capture == null
                    ? "" : ScreenTextExtractor.extract(capture.windows()).text();
            if (body.isBlank()) throw new CaptureException("网页正文无法读取");

            // web.ask 场景：把用户问题合并进正文，prompt 模板只需 {{text}}。
            String text = body;
            if (request.hasUserInput()) {
                text = "网页正文：\n" + body + "\n\n用户问题：\n" + request.userInput();
            }

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("source", "web");
            meta.put("package", pkg == null ? "" : pkg);
            if (url != null) meta.put("url", url);
            return new CaptureBundle(text, List.of(), meta);
        } finally {
            if (capture != null) capture.close();
        }
    }

    /** 优先找可编辑且文本是 URL 的地址栏节点，再按 viewId 特征兜底。 */
    private static String extractUrl(UiNode node) {
        if (node == null) return null;
        if (node.editable() && looksLikeUrl(node.text())) return node.text();
        String id = node.viewId() == null ? "" : node.viewId().toLowerCase(Locale.ROOT);
        if ((id.contains("url") || id.contains("address") || id.contains("omnibox"))
                && looksLikeUrl(node.text())) {
            return node.text();
        }
        for (UiNode child : node.children()) {
            String found = extractUrl(child);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean looksLikeUrl(String text) {
        if (text == null) return false;
        String value = text.strip();
        return value.startsWith("http://") || value.startsWith("https://");
    }
}
