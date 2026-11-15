package com.dshgojava.assist.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.AssistRequest;
import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.TaskRegistry;
import com.dshgojava.assist.voice.VoiceCapture;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VoiceSourceTest {
    private VoiceSource source;

    @AfterEach
    void closeSource() {
        if (source != null) source.close();
    }

    @Test
    void unavailableEngineCannotStart() throws Exception {
        FakeCapture capture = new FakeCapture(false);
        source = new VoiceSource(capture);
        RecordingListener listener = new RecordingListener();

        assertFalse(source.available());
        assertFalse(source.start(listener));
        assertThrows(ExecutionException.class,
                () -> gather(TaskRegistry.require("voice.transcribe"), Map.of()));
    }

    @Test
    void finalTranscriptIsStoredOnceAndTaggedInCaptureMeta() throws Exception {
        FakeCapture capture = new FakeCapture(true);
        source = new VoiceSource(capture);
        RecordingListener listener = new RecordingListener();

        assertTrue(source.start(listener));
        capture.partial("帮我看");
        assertEquals("帮我看", listener.partial);
        capture.finish("  帮我看一下明天的安排  ");

        assertEquals("帮我看一下明天的安排", listener.finalText);
        CaptureBundle bundle = gather(TaskRegistry.require("voice.transcribe"), Map.of());
        assertEquals("帮我看一下明天的安排", bundle.text());
        assertEquals("voice", bundle.meta().get("source"));
        assertEquals("system", bundle.meta().get("engine"));
        assertEquals("10", bundle.meta().get("chars"));
        assertThrows(ExecutionException.class,
                () -> gather(TaskRegistry.require("voice.transcribe"), Map.of()));
    }

    @Test
    void requestContextSuppliesTranscriptAndWinsOverStoredText() throws Exception {
        FakeCapture capture = new FakeCapture(true);
        source = new VoiceSource(capture);
        RecordingListener listener = new RecordingListener();
        source.start(listener);
        capture.finish("旧转写");

        CaptureBundle bundle = gather(TaskRegistry.require("voice.transcribe"),
                Map.of(VoiceSource.CONTEXT_TEXT, "  新转写  "));

        assertEquals("新转写", bundle.text());
        assertThrows(ExecutionException.class,
                () -> gather(TaskRegistry.require("voice.transcribe"), Map.of()));
    }

    @Test
    void recognizerErrorIsForwardedAndClearsTranscript() {
        FakeCapture capture = new FakeCapture(true);
        source = new VoiceSource(capture);
        RecordingListener listener = new RecordingListener();

        assertTrue(source.start(listener));
        capture.fail("没有识别到语音，请重试");

        assertEquals("没有识别到语音，请重试", listener.error);
        assertFalse(source.isListening());
    }

    @Test
    void stoppingIsForwardedOnlyWhileListening() {
        FakeCapture capture = new FakeCapture(true);
        source = new VoiceSource(capture);
        RecordingListener listener = new RecordingListener();

        source.stop();
        assertFalse(capture.stopped);
        source.start(listener);
        source.stop();
        assertTrue(capture.stopped);
    }

    @Test
    void cancelDropsTheActiveCapture() {
        FakeCapture capture = new FakeCapture(true);
        source = new VoiceSource(capture);
        RecordingListener listener = new RecordingListener();

        source.start(listener);
        source.cancel();

        assertFalse(source.isListening());
        assertTrue(capture.cancelled);
        assertThrows(ExecutionException.class,
                () -> gather(TaskRegistry.require("voice.transcribe"), Map.of()));
    }

    private CaptureBundle gather(QuickTask task, Map<String, String> context)
            throws Exception {
        return source.gather(new AssistRequest(task, context, ""))
                .get(2, TimeUnit.SECONDS);
    }

    private static final class RecordingListener implements VoiceCapture.Listener {
        private String partial;
        private String finalText;
        private String error;

        @Override
        public void onPartial(String text) {
            partial = text;
        }

        @Override
        public void onFinal(String text) {
            finalText = text;
        }

        @Override
        public void onError(String message) {
            error = message;
        }
    }

    private static final class FakeCapture implements VoiceCapture {
        private final boolean available;
        private Listener listener;
        private boolean cancelled;
        private boolean stopped;

        private FakeCapture(boolean available) {
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean start(Listener listener) {
            if (!available) return false;
            this.listener = listener;
            stopped = false;
            cancelled = false;
            return true;
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void cancel() {
            cancelled = true;
            listener = null;
        }

        @Override
        public void release() {
            listener = null;
        }

        private void finish(String text) {
            if (listener != null) listener.onFinal(text);
        }

        private void partial(String text) {
            if (listener != null) listener.onPartial(text);
        }

        private void fail(String message) {
            if (listener != null) listener.onError(message);
        }
    }
}
