package com.dshgojava.assist.platform;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import com.dshgojava.assist.A11yBridge;

/** Minimal accessibility endpoint for user-triggered screen reads; it never drives other apps. */
public final class AssistA11yService extends AccessibilityService {
    private static final String TAG = "DshAssist";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "AssistA11yService connected");
        A11yBridge.attach(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Reads are performed only when the person explicitly starts a quick task.
    }

    @Override
    public void onInterrupt() {
        // No long-running accessibility feedback is owned by this service.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "AssistA11yService unbind");
        A11yBridge.detach(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "AssistA11yService destroy");
        A11yBridge.detach(this);
        super.onDestroy();
    }
}
