package com.dshgojava.assist.source;

/** Pure decisions for when the accessibility tree is enough and when a screenshot is required. */
public final class ScreenCapturePolicy {
    public static final int MIN_USEFUL_TREE_CHARS = 40;

    private ScreenCapturePolicy() { }

    public static boolean hasUsefulTree(int textChars) {
        return textChars >= MIN_USEFUL_TREE_CHARS;
    }

    /** Vision tasks always need pixels; text tasks fall back to pixels for text-poor canvases. */
    public static boolean needsScreenshot(boolean needsVision, int treeTextChars) {
        return needsVision || !hasUsefulTree(treeTextChars);
    }

    public static boolean mayReturnTreeOnly(boolean needsVision, String text) {
        return !needsVision && text != null && !text.isBlank();
    }
}
