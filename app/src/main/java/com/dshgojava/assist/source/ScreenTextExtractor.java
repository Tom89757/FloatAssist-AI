package com.dshgojava.assist.source;

import com.dshgojava.assist.platform.UiBox;
import com.dshgojava.assist.platform.UiNode;
import com.dshgojava.assist.platform.UiWindow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Converts the bounded accessibility tree into compact, model-facing text. */
public final class ScreenTextExtractor {
    public static final int DEFAULT_MAX_CHARS = 12_000;
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final String[] SECRET_ID_FRAGMENTS = {
            "password", "passwd", "pwd", "passcode", "otp", "cvv", "cvc",
            "smscode", "verifycode", "authcode"
    };

    public record Result(String text, boolean truncated) {
        public Result {
            text = text == null ? "" : text;
        }
    }

    private ScreenTextExtractor() { }

    public static Result extract(List<UiWindow> windows) {
        return extract(windows, DEFAULT_MAX_CHARS);
    }

    public static Result extract(List<UiWindow> windows, int maxChars) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars must be positive");
        if (windows == null || windows.isEmpty()) return new Result("", false);

        List<UiWindow> readable = new ArrayList<>();
        for (UiWindow window : windows) {
            if (window != null && window.root() != null) readable.add(window);
        }
        if (readable.isEmpty()) return new Result("", false);

        StringBuilder out = new StringBuilder(Math.min(maxChars, 4_096));
        boolean multiWindow = readable.size() > 1;
        boolean truncated = false;
        String last = "";
        for (UiWindow window : readable) {
            if (multiWindow && out.length() < maxChars) {
                String heading = windowHeading(window);
                if (!heading.isBlank()) {
                    Append appended = append(out, heading, last, maxChars);
                    last = appended.last;
                    truncated |= appended.overflow;
                }
            }
            Collect collect = collect(window.root(), out, last, maxChars);
            last = collect.last;
            truncated |= collect.truncated;
            if (truncated) break;
        }
        return new Result(out.toString().strip(), truncated);
    }

    /** Bounds that should be greyed out before any screenshot leaves the process. */
    public static List<UiBox> sensitiveBounds(List<UiWindow> windows) {
        List<UiBox> out = new ArrayList<>();
        if (windows == null) return List.of();
        for (UiWindow window : windows) {
            if (window != null && window.root() != null) {
                collectSensitive(window.root(), out);
            }
        }
        return List.copyOf(out);
    }

    private static Collect collect(UiNode node, StringBuilder out, String previous, int maxChars) {
        if (node == null || isSecret(node)) return new Collect(previous, false);
        String last = previous;
        boolean truncated = false;
        for (String candidate : candidates(node)) {
            String value = normalize(candidate);
            if (value.isBlank() || value.equals(last)) continue;
            Append appended = append(out, value, last, maxChars);
            last = appended.last;
            truncated |= appended.overflow;
        }
        List<UiNode> children = new ArrayList<>(node.children());
        children.sort(Comparator
                .comparingInt((UiNode child) -> child.bounds() == null ? Integer.MAX_VALUE
                        : child.bounds().top())
                .thenComparingInt(child -> child.bounds() == null ? Integer.MAX_VALUE
                        : child.bounds().left())
                .thenComparingInt(UiNode::ref));
        for (UiNode child : children) {
            if (truncated) break;
            Collect nested = collect(child, out, last, maxChars);
            last = nested.last;
            truncated |= nested.truncated;
        }
        return new Collect(last, truncated);
    }

    private static List<String> candidates(UiNode node) {
        List<String> out = new ArrayList<>(3);
        add(out, node.text());
        add(out, node.desc());
        if (node.showingHint()) add(out, node.hint());
        return out;
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value.strip())) {
            values.add(value.strip());
        }
    }

    private static Append append(StringBuilder out, String value, String previous, int maxChars) {
        if (value.equals(previous)) return new Append(previous, false);
        int separator = out.length() == 0 ? 0 : 1;
        int remaining = maxChars - out.length() - separator;
        if (remaining <= 0) return new Append(previous, true);
        if (separator == 1) out.append('\n');
        if (value.length() <= remaining) {
            out.append(value);
            return new Append(value, false);
        } else {
            out.append(value, 0, remaining);
            return new Append(value, true);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : SPACE.matcher(value).replaceAll(" ").strip();
    }

    private static String windowHeading(UiWindow window) {
        String title = normalize(window.title());
        if (!title.isBlank()) return "— " + title + " —";
        String pkg = normalize(window.packageName());
        return pkg.isBlank() ? "" : "— " + pkg + " —";
    }

    private static void collectSensitive(UiNode node, List<UiBox> out) {
        if (node == null) return;
        if (node.bounds() != null && !node.bounds().isEmpty() && isSecret(node)) {
            out.add(node.bounds());
        }
        for (UiNode child : node.children()) collectSensitive(child, out);
    }

    private static boolean isSecret(UiNode node) {
        if (node.password()) return true;
        String id = node.viewId() == null ? "" : node.viewId().toLowerCase(Locale.ROOT);
        for (String fragment : SECRET_ID_FRAGMENTS) {
            if (id.contains(fragment)) return true;
        }
        String hint = (node.hint() == null ? "" : node.hint()) + " "
                + (node.desc() == null ? "" : node.desc());
        String lower = hint.toLowerCase(Locale.ROOT);
        return lower.contains("密码") || lower.contains("验证码")
                || lower.contains("one-time") || lower.contains("otp");
    }

    private record Collect(String last, boolean truncated) { }

    private record Append(String last, boolean overflow) { }
}
