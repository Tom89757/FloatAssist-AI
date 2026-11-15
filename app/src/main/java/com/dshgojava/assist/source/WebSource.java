package com.dshgojava.assist.source;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import com.dshgojava.assist.A11yBridge;
import com.dshgojava.assist.AssistRequest;
import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.TaskRegistry;
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
    /** 直接抓取正文低于此长度时视为失败（多为登录墙、JS 空壳），改读无障碍树。 */
    private static final int MIN_FETCHED_CHARS = 200;

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

    /** 剪贴板网址任务直接联网抓取，不需要无障碍。 */
    @Override
    public boolean availableFor(QuickTask task) {
        if (TaskRegistry.isClipboardWeb(task)) {
            return context.getSystemService(ClipboardManager.class) != null;
        }
        return available();
    }

    @Override
    public CompletableFuture<CaptureBundle> gather(AssistRequest request) {
        boolean clipboardTask = TaskRegistry.isClipboardWeb(request.task());
        if (!clipboardTask && !A11yBridge.isConnected()) {
            return SourceFutures.failed(new CaptureException("开启无障碍以读取网页"));
        }
        try {
            return SourceFutures.supply(executor,
                    () -> clipboardTask ? gatherClipboardUrl(request) : gatherNow(request));
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
            boolean addressBar = false;
            boolean webView = false;
            if (capture != null) {
                for (UiWindow window : capture.windows()) {
                    String found = extractUrl(window.root());
                    if (found != null) {
                        url = found;
                        break;
                    }
                    addressBar = addressBar || hasAddressBar(window.root());
                    webView = webView || hasWebView(window.root());
                }
            }
            // 四级判定：白名单 / 地址栏 URL / 地址栏输入框 / 树里存在 WebView 容器。
            // 小米浏览器地址栏只显示标题，Via 等第三方浏览器甚至不暴露地址栏，靠 WebView 兜底。
            boolean browser = BROWSERS.contains(pkg) || url != null || addressBar || webView;
            if (!browser) throw new CaptureException("当前不是网页");

            String body = capture == null
                    ? "" : ScreenTextExtractor.extract(capture.windows()).text();
            // 已知地址时优先按网址直接下载正文；抓取失败或正文过短再回退到无障碍树。
            if (url != null) {
                String fetched = fetchBody(url);
                if (fetched.length() >= MIN_FETCHED_CHARS || body.isBlank()) body = fetched;
            }
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

    /** 按网址直接下载正文；任何失败都返回空串，由调用方回退到无障碍树。 */
    private static String fetchBody(String url) {
        try {
            return HtmlText.toText(WebClip.fetch(url), ScreenTextExtractor.DEFAULT_MAX_CHARS);
        } catch (RuntimeException error) {
            return "";
        }
    }

    /** 剪贴板网址任务：读网址 → 联网下载 → 抽正文，不依赖无障碍树。 */
    private CaptureBundle gatherClipboardUrl(AssistRequest request) {
        String text = request.context().get(ClipSource.CONTEXT_TEXT);
        if (text == null) text = readClipboard();
        String url = WebClip.firstUrl(text);
        if (url == null) throw new CaptureException("剪贴板里没有网址");
        String html = WebClip.fetch(url);
        String body = HtmlText.toText(html, ScreenTextExtractor.DEFAULT_MAX_CHARS);
        if (body.isBlank()) throw new CaptureException("网页正文无法读取");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("source", "web");
        meta.put("url", url);
        meta.put("mode", "clipboard");
        return new CaptureBundle("网页地址：" + url + "\n\n" + body, List.of(), meta);
    }

    private String readClipboard() {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) throw new CaptureException("剪贴板不可用");
        ClipData clip;
        try {
            clip = clipboard.getPrimaryClip();
        } catch (SecurityException error) {
            throw new CaptureException("系统限制了剪贴板读取，请回到应用后重试", error);
        }
        if (clip == null || clip.getItemCount() == 0) {
            throw new CaptureException("剪贴板为空或系统限制读取");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item == null) continue;
            CharSequence value = item.coerceToText(context);
            if (value != null && value.length() > 0) {
                if (out.length() > 0) out.append('\n');
                out.append(value);
            }
        }
        if (out.length() == 0) throw new CaptureException("剪贴板为空或系统限制读取");
        return out.toString();
    }

    /** 页面容器特征：第三方浏览器（Via 等）不暴露地址栏，但一定暴露 WebView 节点。 */
    private static boolean hasWebView(UiNode node) {
        if (node == null) return false;
        if (node.className().contains("WebView")) return true;
        for (UiNode child : node.children()) {
            if (hasWebView(child)) return true;
        }
        return false;
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

    /** 地址栏输入框特征：可编辑且 viewId 带 url/address/omnibox。 */
    private static boolean hasAddressBar(UiNode node) {
        if (node == null) return false;
        String id = node.viewId() == null ? "" : node.viewId().toLowerCase(Locale.ROOT);
        if (node.editable() && (id.contains("url") || id.contains("address")
                || id.contains("omnibox"))) {
            return true;
        }
        for (UiNode child : node.children()) {
            if (hasAddressBar(child)) return true;
        }
        return false;
    }

    private static boolean looksLikeUrl(String text) {
        if (text == null) return false;
        String value = text.strip();
        return value.startsWith("http://") || value.startsWith("https://");
    }
}
