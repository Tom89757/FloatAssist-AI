package com.dshgojava.assist.voice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Uses Android's installed speech recognition engine. Audio is handled by the
 * engine and is never written to app storage by this class.
 */
public final class SystemVoiceCapture implements VoiceCapture {
    private static final long STOP_TIMEOUT_MS = 6_000L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable stopTimeout = this::timeout;

    private SpeechRecognizer recognizer;
    private Listener listener;
    private boolean stopping;
    private int generation;

    public SystemVoiceCapture(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean available() {
        try {
            return SpeechRecognizer.isRecognitionAvailable(context);
        } catch (RuntimeException error) {
            return false;
        }
    }

    @Override
    public boolean start(Listener nextListener) {
        if (nextListener == null || Looper.myLooper() != Looper.getMainLooper()) return false;
        clearSession(false);
        if (!available() || !hasMicrophonePermission()) return false;

        int mine = ++generation;
        SpeechRecognizer created;
        try {
            created = SpeechRecognizer.createSpeechRecognizer(context);
        } catch (RuntimeException error) {
            return false;
        }
        if (created == null) return false;

        recognizer = created;
        listener = nextListener;
        stopping = false;
        try {
            created.setRecognitionListener(new Callback(mine, created));
            created.startListening(requestIntent());
        } catch (RuntimeException error) {
            clearSession(false);
            return false;
        }
        return true;
    }

    @Override
    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::stop);
            return;
        }
        SpeechRecognizer current = recognizer;
        if (current == null || listener == null) return;
        stopping = true;
        main.removeCallbacks(stopTimeout);
        try {
            current.stopListening();
            main.postDelayed(stopTimeout, STOP_TIMEOUT_MS);
        } catch (RuntimeException error) {
            finishError(VoicePolicy.errorMessage(4));
        }
    }

    @Override
    public void cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::cancel);
            return;
        }
        clearSession(true);
    }

    @Override
    public void release() {
        cancel();
    }

    private Intent requestIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        String language = Locale.getDefault().toLanguageTag();
        if (!language.isBlank()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        return intent;
    }

    private boolean hasMicrophonePermission() {
        try {
            return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void timeout() {
        if (recognizer != null && listener != null) finishError("语音转写超时，请重试");
    }

    private void finishFinal(String text) {
        Listener current = listener;
        if (current == null) return;
        String normalized = VoicePolicy.normalize(text);
        clearSession(false);
        if (!VoicePolicy.usable(normalized)) {
            current.onError("没有识别到语音，请重试");
        } else {
            current.onFinal(normalized);
        }
    }

    private void finishError(String message) {
        Listener current = listener;
        if (current == null) return;
        clearSession(false);
        current.onError(message);
    }

    private void clearSession(boolean cancelRecognizer) {
        main.removeCallbacks(stopTimeout);
        generation++;
        SpeechRecognizer current = recognizer;
        recognizer = null;
        listener = null;
        stopping = false;
        if (current == null) return;
        if (cancelRecognizer) {
            try {
                current.cancel();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            current.destroy();
        } catch (RuntimeException ignored) {
        }
    }

    private final class Callback implements RecognitionListener {
        private final int mine;
        private final SpeechRecognizer owner;

        private Callback(int mine, SpeechRecognizer owner) {
            this.mine = mine;
            this.owner = owner;
        }

        private boolean current() {
            return mine == generation && recognizer == owner && listener != null;
        }

        @Override
        public void onReadyForSpeech(Bundle params) { }

        @Override
        public void onBeginningOfSpeech() { }

        @Override
        public void onRmsChanged(float rmsdB) { }

        @Override
        public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() { }

        @Override
        public void onError(int error) {
            if (!current()) return;
            finishError(VoicePolicy.errorMessage(error));
        }

        @Override
        public void onResults(Bundle results) {
            if (!current()) return;
            finishFinal(firstResult(results));
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (!current()) return;
            String text = VoicePolicy.normalize(firstResult(partialResults));
            if (!text.isBlank()) listener.onPartial(text);
        }

        @Override
        public void onEvent(int eventType, Bundle params) { }
    }

    private static String firstResult(Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> values = bundle.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (values == null || values.isEmpty()) return "";
        return values.get(0) == null ? "" : values.get(0);
    }
}
