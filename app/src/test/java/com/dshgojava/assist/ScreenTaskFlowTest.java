package com.dshgojava.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.model.ModelChannel;
import com.dshgojava.assist.model.PromptImage;
import com.dshgojava.assist.source.AssistSource;
import com.dshgojava.assist.source.CaptureBundle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ScreenTaskFlowTest {
    @Test
    void allFourScreenTasksReachDoneThroughScreenSource() {
        for (String id : List.of(
                "screen.summarize", "screen.extract", "screen.translate", "screen.what")) {
            QuickTask task = TaskRegistry.require(id);
            RecordingSource source = new RecordingSource();
            RecordingModel model = new RecordingModel(true, true);
            AssistRunner.AssistRun run = new DefaultAssistRunner(
                    model, Map.of(SourceKind.SCREEN, source))
                    .start(task, AssistRequest.of(task));

            assertEquals(Stage.GATHERING, run.state().state().stage(), id);
            CaptureBundle bundle = task.needsVision()
                    ? new CaptureBundle("屏幕里的文字", List.of(image()), Map.of())
                    : CaptureBundle.text("屏幕里的文字", Map.of());
            source.result.complete(bundle);

            assertEquals(Stage.THINKING, run.state().state().stage(), id);
            assertEquals(1, source.gathers.get(), id);
            assertEquals(1, model.calls.get(), id);
            assertTrue(model.prompt.contains("屏幕里的文字"), id);
            assertFalse(model.prompt.contains(PromptTemplate.TEXT_PLACEHOLDER), id);
            assertEquals(task.needsVision() ? 1 : 0, model.images.size(), id);

            model.emit("结果");
            assertEquals(Stage.STREAMING, run.state().state().stage(), id);
            model.finish("结果");

            assertEquals(Stage.DONE, run.state().state().stage(), id);
            assertEquals("结果", run.state().state().text(), id);
            assertEquals(1, source.gathers.get(), id);
            assertEquals(1, model.calls.get(), id);
            run.close();
        }
    }

    @Test
    void screenWhatFailsBeforeGatherWhenModelCannotReadImages() {
        QuickTask task = TaskRegistry.require("screen.what");
        RecordingSource source = new RecordingSource();
        RecordingModel model = new RecordingModel(true, false);

        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.SCREEN, source))
                .start(task, AssistRequest.of(task));

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("当前模型不支持读图", run.state().state().error());
        assertEquals(0, source.gathers.get());
        assertEquals(0, model.calls.get());
    }

    @Test
    void textTaskDoesNotSendFallbackScreenshotToNonVisionModel() {
        QuickTask task = TaskRegistry.require("screen.summarize");
        RecordingSource source = new RecordingSource();
        RecordingModel model = new RecordingModel(true, false);
        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.SCREEN, source))
                .start(task, AssistRequest.of(task));

        source.result.complete(new CaptureBundle(
                "较短的屏幕文本", List.of(image()), Map.of("screenshot", "true")));

        assertEquals(Stage.THINKING, run.state().state().stage());
        assertEquals(1, model.calls.get());
        assertEquals(List.of(), model.images);
        assertTrue(model.prompt.contains("较短的屏幕文本"));
        model.finish("摘要");
        assertEquals(Stage.DONE, run.state().state().stage());
    }

    @Test
    void sparseScreenWithoutTextGivesActionableFailureInsteadOfCallingModel() {
        QuickTask task = TaskRegistry.require("screen.translate");
        RecordingSource source = new RecordingSource();
        RecordingModel model = new RecordingModel(true, true);
        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.SCREEN, source))
                .start(task, AssistRequest.of(task));

        source.result.complete(new CaptureBundle("", List.of(image()), Map.of()));

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("当前页面没有可读文字，请改用“这是什么”", run.state().state().error());
        assertEquals(0, model.calls.get());
    }

    private static PromptImage image() {
        return PromptImage.jpeg(new byte[]{1, 2, 3}, "screen.jpg");
    }

    private static final class RecordingSource implements AssistSource {
        private final AtomicInteger gathers = new AtomicInteger();
        private final CompletableFuture<CaptureBundle> result = new CompletableFuture<>();

        @Override
        public SourceKind kind() {
            return SourceKind.SCREEN;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CompletableFuture<CaptureBundle> gather(AssistRequest request) {
            gathers.incrementAndGet();
            return result;
        }
    }

    private static final class RecordingModel implements ModelChannel {
        private final boolean ready;
        private final boolean vision;
        private final AtomicInteger calls = new AtomicInteger();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Consumer<String> onDelta;
        private String prompt = "";
        private List<PromptImage> images = List.of();

        private RecordingModel(boolean ready, boolean vision) {
            this.ready = ready;
            this.vision = vision;
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public boolean supportsVision() {
            return vision;
        }

        @Override
        public CompletableFuture<String> complete(
                String prompt,
                List<PromptImage> images,
                Consumer<String> onDelta) {
            calls.incrementAndGet();
            this.prompt = prompt;
            this.images = images == null ? List.of() : List.copyOf(images);
            this.onDelta = onDelta;
            return result;
        }

        private void emit(String delta) {
            onDelta.accept(delta);
        }

        private void finish(String text) {
            assertTrue(result.complete(text));
        }
    }
}
