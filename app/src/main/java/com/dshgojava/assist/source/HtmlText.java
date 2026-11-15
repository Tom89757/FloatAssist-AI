package com.dshgojava.assist.source;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal HTML-to-text conversion for pages fetched by URL; keeps block breaks only. */
public final class HtmlText {
    private static final Pattern HIDDEN = Pattern.compile(
            "(?is)<(script|style|noscript|template|svg)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern BREAK = Pattern.compile(
            "(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6]|/section|/article|/blockquote)\\b[^>]*>");
    private static final Pattern CELL = Pattern.compile("(?i)<\\s*/t[dh]\\s*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern HORIZONTAL = Pattern.compile("[\\t\\u000B\\f\\r ]+");
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private HtmlText() { }

    /** Converts HTML to plain text, capped at {@code maxChars} characters. */
    public static String toText(String html, int maxChars) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars must be positive");
        if (html == null || html.isEmpty()) return "";
        String text = HIDDEN.matcher(html).replaceAll(" ");
        text = COMMENT.matcher(text).replaceAll(" ");
        text = BREAK.matcher(text).replaceAll("\n");
        text = CELL.matcher(text).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        text = decode(text);

        StringBuilder out = new StringBuilder(Math.min(maxChars, 4_096));
        String last = "";
        for (String raw : text.split("\n")) {
            String line = HORIZONTAL.matcher(raw).replaceAll(" ").strip();
            if (line.isEmpty() || line.equals(last)) continue;
            int separator = out.length() == 0 ? 0 : 1;
            int remaining = maxChars - out.length() - separator;
            if (remaining <= 0) break;
            if (separator == 1) out.append('\n');
            if (line.length() <= remaining) {
                out.append(line);
                last = line;
            } else {
                out.append(line, 0, remaining);
                break;
            }
        }
        return out.toString();
    }

    private static String decode(String text) {
        if (text.indexOf('&') < 0) return text;
        String value = text
                .replace("&nbsp;", " ")
                .replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
        Matcher matcher = NUMERIC.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        while (matcher.find()) {
            String replacement = matcher.group(0);
            try {
                int radix = matcher.group(1).isEmpty() ? 10 : 16;
                int code = Integer.parseInt(matcher.group(2), radix);
                if (Character.isValidCodePoint(code)) {
                    replacement = new String(Character.toChars(code));
                }
            } catch (RuntimeException ignored) {
                // Malformed entity stays literal.
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
