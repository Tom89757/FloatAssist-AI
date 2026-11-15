package com.dshgojava.assist.platform;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One bounded read of all readable windows and the native nodes their refs point to. */
public final class TreeCapture {
    private static final int MAX_NODES = 2_000;
    private static final int MAX_DEPTH = 80;
    private static final int MAX_WINDOWS = 4;
    private static final Map<Integer, Integer> SCROLL_IDS = scrollIds();

    private TreeCapture() { }

    private static final class State {
        final long deadline;
        final List<AccessibilityNodeInfo> natives = new ArrayList<>(256);
        final Rect rect = new Rect();
        int count;
        boolean truncated;
        boolean visualComplete = true;

        State(long deadline) {
            this.deadline = deadline;
        }
    }

    private record VisualFacts(int windowId, boolean complete, List<UiBox> occlusions) { }

    public static Capture capture(AccessibilityService service, UiBox screen, String ownPackage,
                                  long budgetMs) {
        State state = new State(SystemClock.uptimeMillis() + budgetMs);
        List<AccessibilityWindowInfo> all = null;
        try {
            all = service.getWindows();
        } catch (Throwable ignored) { }
        List<AccessibilityWindowInfo> sorted = all == null ? List.of() : new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
        Boolean keyboard = WindowContent.keyboardShown(windowTypes(all),
                AccessibilityWindowInfo.TYPE_INPUT_METHOD);
        VisualFacts visual = visualFacts(all, screen, ownPackage, state.rect);

        List<UiWindow> chosen = new ArrayList<>();
        String topPackage = null;
        boolean ownFront = false;
        boolean systemFront = false;
        String hidden = null;
        String unreadable = null;
        boolean partial = false;
        long screenArea = Math.max(1L, screen.area());
        try {
            for (AccessibilityWindowInfo window : sorted) {
                int type = window.getType();
                if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION
                        && type != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                if (chosen.size() >= MAX_WINDOWS) break;
                window.getBoundsInScreen(state.rect);
                UiBox bounds = new UiBox(state.rect.left, state.rect.top,
                        state.rect.right, state.rect.bottom);
                double share = (double) bounds.intersect(screen).area() / screenArea;
                if (share <= 0) continue;
                if (type == AccessibilityWindowInfo.TYPE_SYSTEM && share < 0.5) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null && (window.isActive() || window.isFocused())) {
                    root = activeRoot(service, window.getId());
                }
                if (root == null) {
                    if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        if (WindowContent.rootGap(chosen) == WindowContent.RootGap.FRONT) {
                            String title = Build.VERSION.SDK_INT >= 24 ? string(window.getTitle()) : null;
                            hidden = title == null ? "" : title;
                        } else {
                            partial = true;
                        }
                        break;
                    }
                    continue;
                }
                String pkg = string(root.getPackageName());
                if (chosen.isEmpty()) {
                    topPackage = pkg;
                    ownFront = ownPackage.equals(pkg);
                    systemFront = type == AccessibilityWindowInfo.TYPE_SYSTEM;
                }
                String title = Build.VERSION.SDK_INT >= 24 ? string(window.getTitle()) : null;
                UiNode node = convert(root, 0, state);
                boolean emptyFront = chosen.isEmpty()
                        && type == AccessibilityWindowInfo.TYPE_APPLICATION
                        && !state.truncated && !WindowContent.usable(node);
                if (emptyFront) unreadable = title == null ? "" : title;
                chosen.add(new UiWindow(window.getId(),
                        type == AccessibilityWindowInfo.TYPE_SYSTEM
                                ? WindowKind.SYSTEM : WindowKind.APP,
                        pkg, title, bounds, node));
                if (emptyFront || share >= 0.8) break;
            }
        } finally {
            if (Build.VERSION.SDK_INT < 33) recycleWindows(all);
        }

        boolean visualComplete = visual != null && visual.complete() && state.visualComplete
                && !state.truncated && !partial && hidden == null && unreadable == null
                && chosen.size() == 1 && chosen.get(0).id() == visual.windowId();
        return new Capture(chosen, state.natives, keyboard, screen, topPackage, ownFront,
                systemFront, state.truncated, partial, hidden, unreadable,
                SystemClock.uptimeMillis(), visual == null ? null : visual.windowId(),
                visualComplete, visual == null ? List.of() : visual.occlusions());
    }

    public static String foregroundPackage(AccessibilityService service, UiBox screen) {
        List<AccessibilityWindowInfo> all;
        try {
            all = service.getWindows();
        } catch (Throwable error) {
            return null;
        }
        if (all == null) return null;
        List<AccessibilityWindowInfo> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
        Rect rect = new Rect();
        try {
            for (AccessibilityWindowInfo window : sorted) {
                int type = window.getType();
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION
                        && type != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                window.getBoundsInScreen(rect);
                double share = (double) new UiBox(rect.left, rect.top, rect.right, rect.bottom)
                        .intersect(screen).area() / Math.max(1L, screen.area());
                if (type == AccessibilityWindowInfo.TYPE_SYSTEM && share < 0.5) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null && (window.isActive() || window.isFocused())) {
                    root = activeRoot(service, window.getId());
                }
                if (root == null) continue;
                try {
                    return string(root.getPackageName());
                } finally {
                    release(root);
                }
            }
        } finally {
            if (Build.VERSION.SDK_INT < 33) recycleWindows(all);
        }
        return null;
    }

    private static VisualFacts visualFacts(List<AccessibilityWindowInfo> all, UiBox screen,
                                           String ownPackage, Rect rect) {
        if (all == null) return null;
        List<Integer> apps = new ArrayList<>();
        List<UiBox> occlusions = new ArrayList<>();
        boolean complete = true;
        try {
            for (AccessibilityWindowInfo window : all) {
                window.getBoundsInScreen(rect);
                UiBox box = new UiBox(rect.left, rect.top, rect.right, rect.bottom).intersect(screen);
                if (box.isEmpty()) continue;
                switch (window.getType()) {
                    case AccessibilityWindowInfo.TYPE_APPLICATION -> apps.add(window.getId());
                    case AccessibilityWindowInfo.TYPE_SYSTEM -> {
                        if ((double) box.area() / Math.max(1L, screen.area()) >= 0.5) {
                            complete = false;
                        }
                        occlusions.add(box);
                    }
                    case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> {
                        AccessibilityNodeInfo root = window.getRoot();
                        try {
                            if (root == null || !ownPackage.equals(string(root.getPackageName()))) {
                                complete = false;
                            }
                        } finally {
                            release(root);
                        }
                    }
                    default -> complete = false;
                }
            }
            Integer single = apps.size() == 1 ? apps.get(0) : null;
            return new VisualFacts(single == null ? -1 : single,
                    complete && apps.size() == 1, List.copyOf(occlusions));
        } catch (Throwable error) {
            return null;
        }
    }

    private static UiNode convert(AccessibilityNodeInfo node, int depth, State state) {
        if (!node.isVisibleToUser()) {
            release(node);
            return null;
        }
        node.getBoundsInScreen(state.rect);
        UiBox bounds = new UiBox(state.rect.left, state.rect.top,
                state.rect.right, state.rect.bottom);
        int ref = state.natives.size();
        state.natives.add(node);
        state.count++;

        int rawChildren = node.getChildCount();
        List<UiNode> children = new ArrayList<>(Math.max(0, rawChildren));
        if (depth < MAX_DEPTH) {
            for (int i = 0; i < rawChildren; i++) {
                if (state.count >= MAX_NODES || SystemClock.uptimeMillis() > state.deadline) {
                    state.truncated = true;
                    break;
                }
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                } catch (Throwable ignored) { }
                if (child == null) {
                    state.visualComplete = false;
                    continue;
                }
                UiNode converted = convert(child, depth + 1, state);
                if (converted != null) children.add(converted);
            }
        } else if (rawChildren > 0) {
            state.truncated = true;
        }

        int scroll = 0;
        if (node.isScrollable()) {
            for (AccessibilityAction action : node.getActionList()) {
                Integer bit = SCROLL_IDS.get(action.getId());
                if (bit != null) scroll |= bit;
            }
        }
        Bundle extras = null;
        try {
            extras = node.getExtras();
        } catch (Throwable ignored) { }
        CharSequence role = extras == null ? null
                : extras.getCharSequence("AccessibilityNodeInfo.roleDescription");
        if (role == null && extras != null) {
            role = extras.getCharSequence("AccessibilityNodeInfo.chromeRole");
        }
        RangeInfo range = null;
        try {
            range = node.getRangeInfo();
        } catch (Throwable ignored) { }
        boolean actionClick = false;
        for (AccessibilityAction action : node.getActionList()) {
            if (action.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                actionClick = true;
                break;
            }
        }
        return new UiNode(ref, string(node.getClassName()), string(node.getText()),
                string(node.getContentDescription()),
                Build.VERSION.SDK_INT >= 26 ? string(node.getHintText()) : null,
                node.getViewIdResourceName(), bounds, node.isClickable(), node.isLongClickable(),
                node.isEditable(), node.isScrollable(), node.isCheckable(), node.isChecked(),
                node.isSelected(), node.isFocused(), node.isEnabled(), node.isPassword(),
                Build.VERSION.SDK_INT >= 28 && node.isHeading(),
                Build.VERSION.SDK_INT >= 26 && node.isShowingHintText(), scroll,
                role == null ? null : role.toString().toLowerCase(java.util.Locale.ROOT),
                Build.VERSION.SDK_INT >= 30 && node.getStateDescription() != null
                        ? node.getStateDescription().toString() : null,
                range == null ? null : range.getCurrent(),
                range == null ? null : range.getMax(), children, rawChildren, actionClick);
    }

    private static List<Integer> windowTypes(List<AccessibilityWindowInfo> windows) {
        if (windows == null) return null;
        List<Integer> out = new ArrayList<>(windows.size());
        for (AccessibilityWindowInfo window : windows) out.add(window.getType());
        return out;
    }

    private static AccessibilityNodeInfo activeRoot(AccessibilityService service, int windowId) {
        AccessibilityNodeInfo root;
        try {
            root = service.getRootInActiveWindow();
        } catch (Throwable error) {
            return null;
        }
        if (root == null) return null;
        if (root.getWindowId() == windowId) return root;
        release(root);
        return null;
    }

    private static void recycleWindows(List<AccessibilityWindowInfo> windows) {
        if (windows == null) return;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            try {
                window.recycle();
            } catch (RuntimeException ignored) { }
        }
    }

    private static void release(AccessibilityNodeInfo node) {
        if (node == null || Build.VERSION.SDK_INT >= 33) return;
        try {
            node.recycle();
        } catch (RuntimeException ignored) { }
    }

    private static String string(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private static Map<Integer, Integer> scrollIds() {
        Map<Integer, Integer> out = new HashMap<>();
        out.put(AccessibilityAction.ACTION_SCROLL_FORWARD.getId(), Scroll.FORWARD);
        out.put(AccessibilityAction.ACTION_SCROLL_BACKWARD.getId(), Scroll.BACKWARD);
        out.put(AccessibilityAction.ACTION_SCROLL_UP.getId(), Scroll.UP);
        out.put(AccessibilityAction.ACTION_SCROLL_DOWN.getId(), Scroll.DOWN);
        out.put(AccessibilityAction.ACTION_SCROLL_LEFT.getId(), Scroll.LEFT);
        out.put(AccessibilityAction.ACTION_SCROLL_RIGHT.getId(), Scroll.RIGHT);
        return Map.copyOf(out);
    }
}
