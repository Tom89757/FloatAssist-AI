package com.dshgojava.assist.platform;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/** One bounded window read; native nodes stay local and are released together. */
public final class Capture implements AutoCloseable {
    private final List<UiWindow> windows;
    private final List<AccessibilityNodeInfo> natives;
    private final Boolean keyboardShown;
    private final UiBox screen;
    private final String packageName;
    private final boolean ownAppInFront;
    private final boolean systemInFront;
    private final boolean truncated;
    private final boolean partial;
    private final String hiddenWindowTitle;
    private final String unreadableWindowTitle;
    private final long takenAt;
    private final Integer visualWindowId;
    private final boolean visualComplete;
    private final List<UiBox> visualOcclusions;

    public Capture(List<UiWindow> windows, List<AccessibilityNodeInfo> natives,
                   Boolean keyboardShown, UiBox screen, String packageName,
                   boolean ownAppInFront, boolean systemInFront, boolean truncated,
                   boolean partial, String hiddenWindowTitle, String unreadableWindowTitle,
                   long takenAt, Integer visualWindowId, boolean visualComplete,
                   List<UiBox> visualOcclusions) {
        this.windows = List.copyOf(windows);
        this.natives = List.copyOf(natives);
        this.keyboardShown = keyboardShown;
        this.screen = screen;
        this.packageName = packageName;
        this.ownAppInFront = ownAppInFront;
        this.systemInFront = systemInFront;
        this.truncated = truncated;
        this.partial = partial;
        this.hiddenWindowTitle = hiddenWindowTitle;
        this.unreadableWindowTitle = unreadableWindowTitle;
        this.takenAt = takenAt;
        this.visualWindowId = visualWindowId;
        this.visualComplete = visualComplete;
        this.visualOcclusions = List.copyOf(visualOcclusions);
    }

    public List<UiWindow> windows() { return windows; }
    public List<AccessibilityNodeInfo> natives() { return natives; }
    public Boolean keyboardShown() { return keyboardShown; }
    public UiBox screen() { return screen; }
    public String packageName() { return packageName; }
    public boolean ownAppInFront() { return ownAppInFront; }
    public boolean systemInFront() { return systemInFront; }
    public boolean truncated() { return truncated; }
    public boolean partial() { return partial; }
    public String hiddenWindowTitle() { return hiddenWindowTitle; }
    public String unreadableWindowTitle() { return unreadableWindowTitle; }
    public long takenAt() { return takenAt; }
    public Integer visualWindowId() { return visualWindowId; }
    public boolean visualComplete() { return visualComplete; }
    public List<UiBox> visualOcclusions() { return visualOcclusions; }

    public AccessibilityNodeInfo nativeNode(int ref) {
        return ref >= 0 && ref < natives.size() ? natives.get(ref) : null;
    }

    public UiBox windowBounds(int id) {
        for (UiWindow window : windows) if (window.id() == id) return window.bounds();
        return null;
    }

    @Override
    public void close() {
        if (Build.VERSION.SDK_INT < 33) {
            for (AccessibilityNodeInfo node : natives) {
                if (node == null) continue;
                try {
                    node.recycle();
                } catch (RuntimeException ignored) { }
            }
        }
    }
}
