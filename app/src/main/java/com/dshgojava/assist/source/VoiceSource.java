package com.dshgojava.assist.source;

import android.content.Context;
import com.dshgojava.assist.AssistRequest;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.voice.SystemVoiceCapture;
import com.dshgojava.assist.voice.VoiceCapture;
import com.dshgojava.assist.voice.VoicePolicy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hold-to-talk source. The recognizer owns the microphone and produces text;
 * this class keeps the finalized transcript as a one-shot capture result.
 */
public final class VoiceSource implements AssistSource {
    public static final String CONTEXT_TEXT = "voice_text";

    private final VoiceCapture capture;
    private final Executor executor;
    private final AtomicReference<String> captured = new AtomicReference<>();
    private final AtomicBoolean listening = new AtomicBoolean();

    public VoiceSource(Context context) {
        this(new SystemVoiceCapture(context));
    }

    VoiceSource(VoiceCapture capture) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.executor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "assist-voice"));
    }

    @Override
    public SourceKind kind() {
        return SourceKind.VOICE;
    }

    @Override
    public boolean available() {
        try {
            return capture.available();
        } catch (RuntimeException error) {
            return false;
        }
    }

    public boolean isListening() {
        return listening.get();
    }

    /** Starts a fresh hold-to-talk capture; false when another capture is active or unavailable. */
    public boolean start(VoiceCapture.Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!listening.compareAndSet(false, true)) return false;
        captured.set(null);
        boolean started;
        try {
            started = capture.start(new VoiceCapture.Listener() {
                @Override
                public void onPartial(String text) {
                    if (listening.get()) listener.onPartial(text);
                }

                @Override
                public void onFinal(String text) {
                    listening.set(false);
                    String normalized = VoicePolicy.normalize(text);
                    if (!VoicePolicy.usable(normalized)) {
                        captured.set(null);
                        listener.onError("没有识别到语音，请重试");
                        return;
                    }
                    captured.set(normalized);
                    listener.onFinal(normalized);
                }

                @Override
                public void onError(String message) {
                    listening.set(false);
                    captured.set(null);
                    listener.onError(message == null || message.isBlank()
                            ? "语音识别失败，请重试" : message);
                }
            });
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) listening.set(false);
        return started;
    }

    public void stop() {
        if (listening.get()) capture.stop();
    }

    public void cancel() {
        listening.set(false);
        captured.set(null);
        try {
            capture.cancel();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public CompletableFuture<CaptureBundle> gather(AssistRequest request) {
        String supplied = request == null ? null : request.context().get(CONTEXT_TEXT);
        String text = VoicePolicy.normalize(
                supplied == null ? captured.getAndSet(null) : supplied);
        if (supplied != null) captured.set(null);
        if (!VoicePolicy.usable(text)) {
            return SourceFutures.failed(new CaptureException("没有识别到语音"));
        }
        try {
            return SourceFutures.supply(executor, () -> CaptureBundle.text(text, Map.of(
                    "source", "voice",
                    "engine", "system",
                    "chars", Integer.toString(text.length()))));
        } catch (RejectedExecutionException error) {
            return SourceFutures.failed(new CaptureException("语音取料已停止"));
        }
    }

    public void close() {
        cancel();
        try {
            capture.release();
        } catch (RuntimeException ignored) {
        }
        if (executor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdownNow();
        }
    }
}
