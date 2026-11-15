package com.dshgojava.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.model.PromptImage;
import com.dshgojava.assist.source.CaptureBundle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRegistryTest {
    @Test
    void p0ContainsExactlyTheEightDesignedTasksInMenuOrder() {
        assertEquals(List.of(
                "screen.summarize", "screen.extract", "screen.translate", "screen.what",
                "clip.summarize", "clip.polish", "image.ocr", "image.explain"),
                TaskRegistry.p0().stream().map(QuickTask::id).toList());

        assertTask("screen.summarize", "总结当前屏幕", SourceKind.SCREEN,
                OutputKind.TEXT_CARD, false);
        assertTask("screen.extract", "提取屏幕文字", SourceKind.SCREEN,
                OutputKind.COPY_TEXT, false);
        assertTask("screen.translate", "翻译当前屏幕", SourceKind.SCREEN,
                OutputKind.TRANSLATE_CARD, false);
        assertTask("screen.what", "这是什么", SourceKind.SCREEN,
                OutputKind.TEXT_CARD, true);
        assertTask("clip.summarize", "总结剪贴板", SourceKind.CLIP,
                OutputKind.TEXT_CARD, false);
        assertTask("clip.polish", "润色/纠错", SourceKind.CLIP,
                OutputKind.TEXT_CARD, false);
        assertTask("image.ocr", "图片取字", SourceKind.IMAGE,
                OutputKind.COPY_TEXT, true);
        assertTask("image.explain", "图片解说", SourceKind.IMAGE,
                OutputKind.TEXT_CARD, true);
        assertTrue(TaskRegistry.p0().stream()
                .allMatch(task -> PromptTemplate.hasTextPlaceholder(task.promptTemplate())));
    }

    @Test
    void lookupAndSourceGroupingAreStableAndDefensive() {
        QuickTask summarize = TaskRegistry.require("screen.summarize");

        assertSame(TaskRegistry.p0().get(0), summarize);
        assertSame(summarize, TaskRegistry.find(" screen.summarize ").orElseThrow());
        assertTrue(TaskRegistry.find(null).isEmpty());
        assertTrue(TaskRegistry.find("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TaskRegistry.require("missing"));
        assertEquals(List.of("screen.summarize", "screen.extract", "screen.translate",
                        "screen.what", "screen.actions"),
                ids(TaskRegistry.bySource(SourceKind.SCREEN)));
        assertEquals(List.of("clip.summarize", "clip.polish"),
                ids(TaskRegistry.bySource(SourceKind.CLIP)));
        assertEquals(List.of("image.ocr", "image.explain"),
                ids(TaskRegistry.bySource(SourceKind.IMAGE)));
        assertEquals(List.of("voice.transcribe"),
                ids(TaskRegistry.bySource(SourceKind.VOICE)));
        assertThrows(UnsupportedOperationException.class,
                () -> TaskRegistry.p0().add(TaskRegistry.p0().get(0)));
    }

    @Test
    void p1AddsStructuredActionsAndVoiceTranscription() {
        QuickTask actions = TaskRegistry.require("screen.actions");

        assertEquals("查找可执行信息", actions.title());
        assertEquals(SourceKind.SCREEN, actions.source());
        assertEquals(OutputKind.ACTION_SHEET, actions.output());
        assertFalse(actions.needsVision());
        assertTrue(actions.promptTemplate().contains("address"));
        assertTrue(actions.promptTemplate().contains("time"));
        assertTrue(actions.promptTemplate().contains("phone"));
        assertTrue(TaskRegistry.p0().stream().noneMatch(task -> task.id().equals("screen.actions")));
        assertEquals(List.of("screen.actions", "voice.transcribe"),
                TaskRegistry.p1().stream().map(QuickTask::id).toList());
        assertTrue(TaskRegistry.all().contains(actions));

        QuickTask voice = TaskRegistry.require("voice.transcribe");
        assertEquals("语音转写", voice.title());
        assertEquals(SourceKind.VOICE, voice.source());
        assertEquals(OutputKind.COPY_TEXT, voice.output());
        assertFalse(voice.needsVision());
        assertTrue(voice.promptTemplate().contains("原始转写"));
        assertTrue(TaskRegistry.all().contains(voice));
    }

    @Test
    void templateFillReplacesEveryTokenLiterally() {
        String template = "A {{text}} B {{text}}";
        String text = "$1 \\ path {{other}}";

        assertEquals("A $1 \\ path {{other}} B $1 \\ path {{other}}",
                PromptTemplate.fill(template, text));
        assertEquals("A  B ", TaskRegistry.fillPrompt(
                new QuickTask("id", "Title", SourceKind.NONE, OutputKind.TEXT_CARD,
                        template, false), ""));
        assertFalse(PromptTemplate.hasTextPlaceholder("plain text"));
    }

    @Test
    void registryPromptUsesCaptureTextWithoutChangingIt() {
        QuickTask task = TaskRegistry.require("clip.polish");
        CaptureBundle bundle = CaptureBundle.text("  原文 line 1\nline 2  ", Map.of());

        String prompt = TaskRegistry.fillPrompt(task, bundle);

        assertTrue(prompt.contains("  原文 line 1\nline 2  "));
        assertFalse(prompt.contains(PromptTemplate.TEXT_PLACEHOLDER));
        assertEquals(PromptTemplate.fill(task.promptTemplate(), "x"),
                TaskRegistry.fillPrompt(task, "x"));
    }

    @Test
    void beforeStartRejectsUnavailableSourceFirst() {
        TaskPreflight.Result result = TaskPreflight.beforeStart(
                TaskRegistry.require("screen.summarize"), false, false, false);

        assertFalse(result.allowed());
        assertTrue(result.failed());
        assertEquals(TaskPreflight.Failure.SOURCE_UNAVAILABLE, result.failure());
        assertEquals("开启无障碍以读取屏幕", result.message());
        assertEquals("尚未选择图片", TaskPreflight.beforeStart(
                TaskRegistry.require("image.ocr"), false, true, true).message());
    }

    @Test
    void beforeStartRejectsMissingModelAndUnsupportedVision() {
        TaskPreflight.Result model = TaskPreflight.beforeStart(
                TaskRegistry.require("clip.summarize"), true, false, false);
        TaskPreflight.Result vision = TaskPreflight.beforeStart(
                TaskRegistry.require("image.explain"), true, true, false);
        TaskPreflight.Result ready = TaskPreflight.beforeStart(
                TaskRegistry.require("image.explain"), true, true, true);

        assertEquals(TaskPreflight.Failure.MODEL_UNAVAILABLE, model.failure());
        assertEquals("未配置模型", model.message());
        assertEquals(TaskPreflight.Failure.VISION_UNSUPPORTED, vision.failure());
        assertEquals("当前模型不支持读图", vision.message());
        assertTrue(ready.allowed());
        assertNull(ready.failure());
        assertEquals("", ready.message());
    }

    @Test
    void afterCaptureRejectsEmptyTextForNonVisionTaskEvenWithAnImage() {
        CaptureBundle imageOnly = new CaptureBundle("", List.of(
                PromptImage.jpeg(new byte[]{1}, "screen.jpg")), Map.of());

        TaskPreflight.Result result = TaskPreflight.afterCapture(
                TaskRegistry.require("screen.summarize"), imageOnly);

        assertEquals(TaskPreflight.Failure.EMPTY_TEXT, result.failure());
        assertEquals("当前页面没有可读文字，请改用“这是什么”", result.message());
        assertTrue(TaskPreflight.afterCapture(
                TaskRegistry.require("screen.summarize"),
                CaptureBundle.text("visible text", Map.of())).allowed());
    }

    @Test
    void afterCaptureRequiresPixelsForVisionTasks() {
        TaskPreflight.Result screen = TaskPreflight.afterCapture(
                TaskRegistry.require("screen.what"), CaptureBundle.text("visible text", Map.of()));
        TaskPreflight.Result image = TaskPreflight.afterCapture(
                TaskRegistry.require("image.ocr"), CaptureBundle.text("user note", Map.of()));
        TaskPreflight.Result ready = TaskPreflight.afterCapture(
                TaskRegistry.require("image.ocr"), new CaptureBundle("", List.of(
                        PromptImage.jpeg(new byte[]{1}, "image.jpg")), Map.of()));

        assertEquals(TaskPreflight.Failure.MISSING_IMAGE, screen.failure());
        assertEquals("当前页面无法读取", screen.message());
        assertEquals(TaskPreflight.Failure.MISSING_IMAGE, image.failure());
        assertEquals("尚未选择图片", image.message());
        assertTrue(ready.allowed());
    }

    private static void assertTask(
            String id,
            String title,
            SourceKind source,
            OutputKind output,
            boolean needsVision) {
        QuickTask task = TaskRegistry.require(id);
        assertEquals(title, task.title());
        assertEquals(source, task.source());
        assertEquals(output, task.output());
        assertEquals(needsVision, task.needsVision());
    }

    private static List<String> ids(List<QuickTask> tasks) {
        return tasks.stream().map(QuickTask::id).toList();
    }
}
