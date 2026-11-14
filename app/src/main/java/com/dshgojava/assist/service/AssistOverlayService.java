package com.dshgojava.assist.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Manifest-declared foreground service for the floating quick-assist overlay.
 * QA05 wires the ball, menu and result layer; this skeleton only keeps the
 * declared component resolvable until then.
 */
public final class AssistOverlayService extends Service {
    private static final String TAG = "DshAssist";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.w(TAG, "Assist overlay is not wired yet; stopping the declared service");
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
