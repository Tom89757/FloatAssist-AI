package com.dshgojava.assist.source;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches a page from a clipboard URL. Unlike {@link WebSource} this path needs
 * no accessibility tree: the HTML is downloaded directly, so it also works in
 * browsers that expose no address bar.
 */
public final class WebClip {
    private static final int MAX_HTML_CHARS = 3 * 1024 * 1024;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final Pattern BARE_HOST =
            Pattern.compile("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(/\\S*)?$");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    private WebClip() { }

    /** Returns the first URL in clipboard text, prefixing {@code https://} for bare hosts. */
    public static String firstUrl(String clipboardText) {
        if (clipboardText == null) return null;
        String[] tokens = clipboardText.trim().split("\\s+");
        for (String token : tokens) {
            String candidate = trimTrailing(token);
            if (startsWithScheme(candidate)) return candidate;
        }
        for (String token : tokens) {
            String candidate = trimTrailing(token);
            if (BARE_HOST.matcher(candidate).matches()) return "https://" + candidate;
        }
        return null;
    }

    /** Downloads the page body; failures surface as user-visible {@link CaptureException}. */
    static String fetch(String url) {
        Request request;
        try {
            request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build();
        } catch (RuntimeException error) {
            throw new CaptureException("网页地址无效");
        }
        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful()) {
                throw new CaptureException("网页请求失败（HTTP " + response.code() + "）");
            }
            if (body == null) throw new CaptureException("网页内容为空");
            String html = body.string();
            return html.length() > MAX_HTML_CHARS ? html.substring(0, MAX_HTML_CHARS) : html;
        } catch (IOException error) {
            throw new CaptureException("网页加载失败，请检查网络", error);
        }
    }

    private static boolean startsWithScheme(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static String trimTrailing(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            char first = value.charAt(start);
            if (first == '(' || first == '（' || first == '「' || first == '【'
                    || first == '<' || first == '[' || first == '"' || first == '\'') {
                start++;
            } else {
                break;
            }
        }
        while (end > start) {
            char last = value.charAt(end - 1);
            if (last == '.' || last == ',' || last == ';' || last == ')' || last == '）'
                    || last == '。' || last == '，' || last == '"' || last == '\''
                    || last == '>' || last == ']' || last == '」' || last == '】') {
                end--;
            } else {
                break;
            }
        }
        return value.substring(start, end);
    }
}
