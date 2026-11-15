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

class ClipImageTaskFlowTest {
    @Test
    void clipboardAndImageTasksReachDoneWithTheRightPayload() {
        for (String id : List.of(
                "clip.summarize", "clip.polish", "image.ocr", "image.explain")) {
            QuickTask task = TaskRegistry.require(id);
            RecordingSource source = new RecordingSource(task.source());
            RecordingModel model = new RecordingModel(true, true);
            AssistRunner.AssistRun run = new DefaultAssistRunner(
                    model, Map.of(task.source(), source))
                    .start(task, AssistRequest.of(task).withUserInput("用户补充"));

            assertEquals(Stage.GATHERING, run.state().state().stage(), id);
            CaptureBundle bundle = task.needsVision()
                    ? new CaptureBundle("", List.of(image()), Map.of("source", "image"))
                    : CaptureBundle.text("待处理文本", Map.of("source", "clipboard"));
            source.result.complete(bundle);

            assertEquals(Stage.THINKING, run.state().state().stage(), id);
            assertEquals(1, source.gathers.get(), id);
            assertEquals(1, model.calls.get(), id);
            assertEquals(task.needsVision() ? 1 : 0, model.images.size(), id);
            if (task.needsVision()) {
                assertTrue(model.prompt.contains("用户补充"), id);
                assertFalse(model.prompt.contains(PromptTemplate.TEXT_PLACEHOLDER), id);
            } else {
                assertTrue(model.prompt.contains("待处理文本"), id);
                assertFalse(model.prompt.contains("用户补充"), id);
            }

            model.emit("完成");
            assertEquals(Stage.STREAMING, run.state().state().stage(), id);
            model.finish("完成");

            assertEquals(Stage.DONE, run.state().state().stage(), id);
            assertEquals("完成", run.state().state().text(), id);
            run.close();
        }
    }

    @Test
    void bothImageTasksFailBeforeGatherWhenVisionIsUnavailable() {
        for (String id : List.of("image.ocr", "image.explain")) {
            QuickTask task = TaskRegistry.require(id);
            RecordingSource source = new RecordingSource(SourceKind.IMAGE);
            RecordingModel model = new RecordingModel(true, false);

            AssistRunner.AssistRun run = new DefaultAssistRunner(
                    model, Map.of(SourceKind.IMAGE, source))
                    .start(task, AssistRequest.of(task));

            assertEquals(Stage.FAILED, run.state().state().stage(), id);
            assertEquals("当前模型不支持读图", run.state().state().error(), id);
            assertEquals(0, source.gathers.get(), id);
            assertEquals(0, model.calls.get(), id);
        }
    }

    @Test
    void imageTasksRejectTextOnlyCaptureWithoutCallingTheModel() {
        for (String id : List.of("image.ocr", "image.explain")) {
            QuickTask task = TaskRegistry.require(id);
            RecordingSource source = new RecordingSource(SourceKind.IMAGE);
            RecordingModel model = new RecordingModel(true, true);
            AssistRunner.AssistRun run = new DefaultAssistRunner(
                    model, Map.of(SourceKind.IMAGE, source))
                    .start(task, AssistRequest.of(task));

            source.result.complete(CaptureBundle.text("用户补充", Map.of()));

            assertEquals(Stage.FAILED, run.state().state().stage(), id);
            assertEquals("尚未选择图片", run.state().state().error(), id);
            assertEquals(0, model.calls.get(), id);
        }
    }

    @Test
    void clipboardTaskFailsExplicitlyWhenCaptureIsEmpty() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        RecordingSource source = new RecordingSource(SourceKind.CLIP);
        RecordingModel model = new RecordingModel(true, false);
        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(task));

        source.result.completeExceptionally(
                new com.dshgojava.assist.source.CaptureException("剪贴板为空"));

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("剪贴板为空", run.state().state().error());
        assertEquals(0, model.calls.get());
    }

    private static PromptImage image() {
        return PromptImage.jpeg(new byte[]{1, 2, 3}, "selected.jpg");
    }

    private static final class RecordingSource implements AssistSource {
        private final SourceKind kind;
        private final AtomicInteger gathers = new AtomicInteger();
        private final CompletableFuture<CaptureBundle> result = new CompletableFuture<>();

        private RecordingSource(SourceKind kind) {
            this.kind = kind;
        }

        @Override
        public SourceKind kind() {
            return kind;
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
        private CompletableFuture<String> result = new CompletableFuture<>();
        private Consumer<String> deltas;
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
                String prompt, List<PromptImage> images, Consumer<String> onDelta) {
            calls.incrementAndGet();
            this.prompt = prompt;
            this.images = List.copyOf(images);
            this.deltas = onDelta;
            return result;
        }

        private void emit(String text) {
            deltas.accept(text);
        }

        private void finish(String text) {
            result.complete(text);
        }
    }
}
