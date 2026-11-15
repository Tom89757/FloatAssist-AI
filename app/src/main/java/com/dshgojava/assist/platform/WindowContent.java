package com.dshgojava.assist.platform;

import java.util.List;

/** Distinguishes an empty layout shell from a text-poor but usable view. */
public final class WindowContent {
    public enum RootGap { FRONT, PARTIAL }

    private WindowContent() { }

    public static RootGap rootGap(List<UiWindow> readableWindows) {
        return readableWindows.isEmpty() ? RootGap.FRONT : RootGap.PARTIAL;
    }

    public static boolean unavailable(boolean hidden, boolean unreadable, List<UiWindow> windows) {
        return hidden || unreadable || windows.isEmpty();
    }

    public static boolean usable(UiNode root) {
        if (root == null) return false;
        if (root.bounds().isEmpty()) {
            for (UiNode child : root.children()) if (usable(child)) return true;
            return false;
        }
        if (notBlank(root.text()) || notBlank(root.desc()) || notBlank(root.hint())
                || root.clickable() || root.longClickable() || root.editable()
                || root.scrollable() || root.checkable() || root.rangeCurrent() != null) {
            return true;
        }
        for (UiNode child : root.children()) if (usable(child)) return true;
        String cls = tail(root.className());
        if (!root.children().isEmpty() || cls.isEmpty() || cls.equals("View")
                || cls.equals("ViewGroup") || cls.equals("DecorView")
                || cls.equals("ComposeView") || cls.equals("AndroidComposeView")
                || cls.endsWith("Layout")) {
            return false;
        }
        if (cls.endsWith("SurfaceView") || cls.equals("TextureView")
                || cls.endsWith("WebView") || cls.equals("ImageView")) {
            return true;
        }
        return !root.className().startsWith("android.")
                && !root.className().startsWith("androidx.")
                && !root.className().startsWith("com.android.");
    }

    public static Boolean keyboardShown(List<Integer> windowTypes, int inputMethodType) {
        if (windowTypes == null || windowTypes.isEmpty()) return null;
        return windowTypes.contains(inputMethodType);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String tail(String className) {
        int dot = className == null ? -1 : className.lastIndexOf('.');
        return dot < 0 ? (className == null ? "" : className) : className.substring(dot + 1);
    }
}
