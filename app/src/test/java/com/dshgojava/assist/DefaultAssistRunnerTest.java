package com.dshgojava.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.model.ModelChannel;
import com.dshgojava.assist.model.ModelException;
import com.dshgojava.assist.model.PromptImage;
import com.dshgojava.assist.source.AssistSource;
import com.dshgojava.assist.source.CaptureBundle;
import com.dshgojava.assist.source.CaptureException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DefaultAssistRunnerTest {
    @Test
    void successfulRunGathersFillsPromptStreamsAndCompletesOnce() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        CompletableFuture<CaptureBundle> captured = new CompletableFuture<>();
        FakeSource source = new FakeSource(SourceKind.CLIP, true, captured);
        FakeModel model = new FakeModel(true, false);
        DefaultAssistRunner runner = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source));

        AssistRunner.AssistRun run = runner.start(task, AssistRequest.of(task));
        List<Stage> stages = new ArrayList<>();
        run.state().subscribe(result -> stages.add(result.stage()));
        assertEquals(Stage.GATHERING, run.state().state().stage());

        captured.complete(CaptureBundle.text("原文内容", Map.of()));

        assertEquals(Stage.THINKING, run.state().state().stage());
        assertTrue(model.prompt.contains("原文内容"));
        assertFalse(model.prompt.contains(PromptTemplate.TEXT_PLACEHOLDER));

        model.emit("你好");
        assertEquals(Stage.STREAMING, run.state().state().stage());
        assertEquals("你好", run.state().state().text());

        model.finish("你好，世界");

        AssistResult result = run.state().state();
        assertEquals(Stage.DONE, result.stage());
        assertEquals("你好，世界", result.text());
        assertEquals(List.of(), result.actions());
        assertEquals(1, source.gathers.get());
        assertEquals(1, model.calls.get());
        assertIterableEquals(
                List.of(Stage.GATHERING, Stage.THINKING, Stage.STREAMING, Stage.DONE),
                stages);

        run.close();
        assertEquals(Stage.DONE, run.state().state().stage());
    }

    @Test
    void actionSheetRunConvertsModelJsonIntoSummaryAndActions() {
        QuickTask task = TaskRegistry.require("screen.actions");
        FakeSource source = new FakeSource(SourceKind.SCREEN, true,
                CompletableFuture.completedFuture(CaptureBundle.text(
                        "地址：上海市黄浦区人民大道200号 时间：2026-11-15 19:00 电话：13800138000",
                        Map.of())));
        FakeModel model = new FakeModel(true, false);
        model.result = CompletableFuture.completedFuture("""
                {"summary":"人民广场附近有一条日程和联系电话",
                 "address":"上海市黄浦区人民大道200号",
                 "time":"2026-11-15T19:00",
                 "title":"看展",
                 "phone":"13800138000"}
                """);
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.SCREEN, source), clock)
                .start(task, AssistRequest.of(task));

        AssistResult result = run.state().state();
        assertEquals(Stage.DONE, result.stage());
        assertEquals("人民广场附近有一条日程和联系电话", result.text());
        assertEquals(List.of(AssistAction.Kind.NAVIGATE, AssistAction.Kind.CALENDAR,
                        AssistAction.Kind.DIAL),
                result.actions().stream().map(AssistAction::kind).toList());
        assertTrue(result.actions().get(0).value().startsWith("geo:0,0?q="));
        assertEquals("13800138000", result.actions().get(2).value());
    }

    @Test
    void sourceUnavailableFailsBeforeGatherAndModel() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        FakeSource source = new FakeSource(
                SourceKind.CLIP, false, new CompletableFuture<>());
        FakeModel model = new FakeModel(true, false);

        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(task));

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("剪贴板不可用", run.state().state().error());
        assertEquals(0, source.gathers.get());
        assertEquals(0, model.calls.get());
    }

    @Test
    void unavailableModelAndUnsupportedVisionAreExplicitFailures() {
        QuickTask clipTask = TaskRegistry.require("clip.summarize");
        FakeSource clipSource = new FakeSource(
                SourceKind.CLIP, true, new CompletableFuture<>());
        FakeModel unready = new FakeModel(false, false);

        AssistRunner.AssistRun modelFailure = new DefaultAssistRunner(
                unready, Map.of(SourceKind.CLIP, clipSource))
                .start(clipTask, AssistRequest.of(clipTask));

        assertEquals(Stage.FAILED, modelFailure.state().state().stage());
        assertEquals("未配置模型", modelFailure.state().state().error());
        assertEquals(0, clipSource.gathers.get());

        QuickTask imageTask = TaskRegistry.require("image.ocr");
        FakeSource imageSource = new FakeSource(
                SourceKind.IMAGE, true, new CompletableFuture<>());
        FakeModel noVision = new FakeModel(true, false);
        AssistRunner.AssistRun visionFailure = new DefaultAssistRunner(
                noVision, Map.of(SourceKind.IMAGE, imageSource))
                .start(imageTask, AssistRequest.of(imageTask));

        assertEquals(Stage.FAILED, visionFailure.state().state().stage());
        assertEquals("当前模型不支持读图", visionFailure.state().state().error());
        assertEquals(0, imageSource.gathers.get());
    }

    @Test
    void afterCapturePreflightRejectsVisionTaskWithoutImage() {
        QuickTask task = TaskRegistry.require("screen.what");
        CompletableFuture<CaptureBundle> captured = new CompletableFuture<>();
        FakeSource source = new FakeSource(SourceKind.SCREEN, true, captured);
        FakeModel model = new FakeModel(true, true);

        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.SCREEN, source))
                .start(task, AssistRequest.of(task));
        captured.complete(CaptureBundle.text("只有文字树", Map.of()));

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("当前页面无法读取", run.state().state().error());
        assertEquals(0, model.calls.get());
    }

    @Test
    void imageTaskUsesUserInputForPromptTextAndForwardsImage() {
        QuickTask task = TaskRegistry.require("image.ocr");
        PromptImage image = PromptImage.jpeg(new byte[]{1, 2, 3}, "receipt.jpg");
        FakeSource source = new FakeSource(
                SourceKind.IMAGE,
                true,
                CompletableFuture.completedFuture(
                        new CaptureBundle("", List.of(image), Map.of("source", "image"))));
        FakeModel model = new FakeModel(true, true);
        model.result = CompletableFuture.completedFuture("识别结果");
        AssistRequest request = AssistRequest.of(task).withUserInput("请处理这张票据");

        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.IMAGE, source)).start(task, request);

        assertEquals(Stage.DONE, run.state().state().stage());
        assertEquals("识别结果", run.state().state().text());
        assertTrue(model.prompt.contains("请处理这张票据"));
        assertFalse(model.prompt.contains(PromptTemplate.TEXT_PLACEHOLDER));
        assertEquals(1, model.images.size());
        assertEquals("receipt.jpg", model.images.get(0).name());
    }

    @Test
    void modelFailureIsExposedWithoutRetryOrSecondGather() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        FakeSource source = new FakeSource(
                SourceKind.CLIP,
                true,
                CompletableFuture.completedFuture(CaptureBundle.text("text", Map.of())));
        FakeModel model = new FakeModel(true, false);
        model.result = CompletableFuture.failedFuture(
                new ModelException(ModelException.Kind.TRANSPORT, "网络失败"));

        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(task));

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("网络失败", run.state().state().error());
        assertEquals(1, source.gathers.get());
        assertEquals(1, model.calls.get());
    }

    @Test
    void captureFailureIsExposedAndSkipsModel() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        FakeSource source = new FakeSource(
                SourceKind.CLIP,
                true,
                CompletableFuture.failedFuture(new CaptureException("剪贴板为空")));
        FakeModel model = new FakeModel(true, false);

        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(task));

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("剪贴板为空", run.state().state().error());
        assertEquals(1, source.gathers.get());
        assertEquals(0, model.calls.get());
    }

    @Test
    void modelFailureCanRetryWithoutGatheringAgain() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        FakeSource source = new FakeSource(
                SourceKind.CLIP,
                true,
                CompletableFuture.completedFuture(CaptureBundle.text("原文", Map.of())));
        FakeModel model = new FakeModel(true, false);
        model.result = CompletableFuture.failedFuture(
                new ModelException(ModelException.Kind.TRANSPORT, "网络失败"));
        DefaultAssistRunner runner = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source));

        AssistRunner.AssistRun run = runner.start(task, AssistRequest.of(task));
        ModelRetry retry = (ModelRetry) run;
        assertEquals(Stage.FAILED, run.state().state().stage());
        assertTrue(retry.canRetryModel());
        assertEquals(1, source.gathers.get());
        assertEquals(1, model.calls.get());
        String firstPrompt = model.prompt;

        model.result = new CompletableFuture<>();
        retry.retryModel();

        assertEquals(Stage.THINKING, run.state().state().stage());
        assertEquals(1, source.gathers.get());
        assertEquals(2, model.calls.get());
        assertEquals(firstPrompt, model.prompt);
        assertFalse(retry.canRetryModel());

        model.finish("重试成功");
        assertEquals(Stage.DONE, run.state().state().stage());
        assertEquals("重试成功", run.state().state().text());
        assertEquals(1, source.gathers.get());
    }

    @Test
    void captureFailureCannotRetryModel() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        FakeSource source = new FakeSource(
                SourceKind.CLIP,
                true,
                CompletableFuture.failedFuture(new CaptureException("剪贴板为空")));
        FakeModel model = new FakeModel(true, false);
        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(task));
        ModelRetry retry = (ModelRetry) run;

        assertFalse(retry.canRetryModel());
        retry.retryModel();

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("剪贴板为空", run.state().state().error());
        assertEquals(1, source.gathers.get());
        assertEquals(0, model.calls.get());
    }

    @Test
    void blankModelResponseFailsExplicitly() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        FakeSource source = new FakeSource(
                SourceKind.CLIP,
                true,
                CompletableFuture.completedFuture(CaptureBundle.text("text", Map.of())));
        FakeModel model = new FakeModel(true, false);
        model.result = CompletableFuture.completedFuture("");

        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(task));

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("模型返回为空", run.state().state().error());
    }

    @Test
    void cancelDuringGatherCancelsFutureAndIgnoresLateCapture() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        CompletableFuture<CaptureBundle> captured = new CompletableFuture<>();
        FakeSource source = new FakeSource(SourceKind.CLIP, true, captured);
        FakeModel model = new FakeModel(true, false);
        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(task));

        run.cancel();

        assertEquals(Stage.CANCELLED, run.state().state().stage());
        assertTrue(captured.isCancelled());
        assertFalse(captured.complete(CaptureBundle.text("late", Map.of())));
        assertEquals(Stage.CANCELLED, run.state().state().stage());
        assertEquals(0, model.calls.get());
    }

    @Test
    void cancelDuringStreamingCancelsModelFutureAndIgnoresLateDeltas() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        FakeSource source = new FakeSource(
                SourceKind.CLIP,
                true,
                new CompletableFuture<>());
        FakeModel model = new FakeModel(true, false);
        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(task));

        source.result.complete(CaptureBundle.text("text", Map.of()));
        model.emit("部分");
        assertEquals(Stage.STREAMING, run.state().state().stage());

        run.cancel();
        assertEquals(Stage.CANCELLED, run.state().state().stage());
        assertTrue(model.result.isCancelled());

        model.emit("迟到");
        model.result.complete("迟到的完整答案");
        assertEquals(Stage.CANCELLED, run.state().state().stage());
        assertEquals("", run.state().state().text());
    }

    @Test
    void mismatchedRequestTaskFailsBeforeDoingWork() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        QuickTask other = TaskRegistry.require("screen.summarize");
        FakeSource source = new FakeSource(
                SourceKind.CLIP, true, new CompletableFuture<>());
        FakeModel model = new FakeModel(true, false);

        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(other));

        assertEquals(Stage.FAILED, run.state().state().stage());
        assertEquals("请求与任务不匹配", run.state().state().error());
        assertEquals(0, source.gathers.get());
        assertEquals(0, model.calls.get());
    }

    @Test
    void closeWhileActiveCancelsAndIsIdempotent() {
        QuickTask task = TaskRegistry.require("clip.summarize");
        FakeSource source = new FakeSource(
                SourceKind.CLIP, true, new CompletableFuture<>());
        FakeModel model = new FakeModel(true, false);
        AssistRunner.AssistRun run = new DefaultAssistRunner(
                model, Map.of(SourceKind.CLIP, source))
                .start(task, AssistRequest.of(task));

        run.close();
        run.close();

        assertEquals(Stage.CANCELLED, run.state().state().stage());
    }

    private static final class FakeSource implements AssistSource {
        private final SourceKind kind;
        private final boolean available;
        private final AtomicInteger gathers = new AtomicInteger();
        private final CompletableFuture<CaptureBundle> result;

        private FakeSource(
                SourceKind kind,
                boolean available,
                CompletableFuture<CaptureBundle> result) {
            this.kind = kind;
            this.available = available;
            this.result = result;
        }

        @Override
        public SourceKind kind() {
            return kind;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public CompletableFuture<CaptureBundle> gather(AssistRequest request) {
            gathers.incrementAndGet();
            return result;
        }
    }

    private static final class FakeModel implements ModelChannel {
        private final boolean ready;
        private final boolean vision;
        private final AtomicInteger calls = new AtomicInteger();
        private CompletableFuture<String> result = new CompletableFuture<>();
        private Consumer<String> onDelta;
        private String prompt = "";
        private List<PromptImage> images = List.of();

        private FakeModel(boolean ready, boolean vision) {
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
            assertNotNull(onDelta, "model has not been called");
            onDelta.accept(delta);
        }

        private void finish(String text) {
            assertTrue(result.complete(text), "model future already completed");
        }
    }
}
