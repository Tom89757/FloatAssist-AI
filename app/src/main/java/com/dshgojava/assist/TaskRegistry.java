package com.dshgojava.assist;

import com.dshgojava.assist.source.CaptureBundle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The single registry of quick-assist tasks. Add a feature here first. */
public final class TaskRegistry {
    /** Reads a URL from the clipboard and downloads the page; no accessibility needed. */
    public static final String WEB_CLIP = "web.clip";

    private static final List<QuickTask> P0 = List.of(
            new QuickTask(
                    "screen.summarize",
                    "总结当前屏幕",
                    SourceKind.SCREEN,
                    OutputKind.TEXT_CARD,
                    "请总结当前屏幕的主要内容。保留关键结论、数字和待办事项，使用简洁的中文分段输出。\n\n"
                            + "若随附截图，请结合截图理解。\n\n屏幕内容：\n{{text}}",
                    false),
            new QuickTask(
                    "screen.extract",
                    "提取屏幕文字",
                    SourceKind.SCREEN,
                    OutputKind.COPY_TEXT,
                    "请逐字提取当前屏幕中可见的文字，保持原始顺序与换行；忽略重复的导航和装饰文字。"
                            + "若随附截图，请从截图补足文字。只输出提取结果，不要解释。\n\n"
                            + "屏幕内容：\n{{text}}",
                    false),
            new QuickTask(
                    "screen.translate",
                    "翻译当前屏幕",
                    SourceKind.SCREEN,
                    OutputKind.TRANSLATE_CARD,
                    "请把当前屏幕的主要内容翻译成简体中文；若原文已是中文，则翻译成英文。"
                            + "保留专有名词、数字、链接和换行。若随附截图，请结合截图理解。"
                            + "只输出译文。\n\n屏幕内容：\n{{text}}",
                    false),
            new QuickTask(
                    "screen.what",
                    "这是什么",
                    SourceKind.SCREEN,
                    OutputKind.TEXT_CARD,
                    "请根据当前屏幕的截图和可读文本，说明画面里是什么、正在发生什么，并指出用户可能关注的"
                            + "重点。不要臆测看不清的内容。\n\n屏幕文本：\n{{text}}",
                    true),
            new QuickTask(
                    "clip.summarize",
                    "总结剪贴板",
                    SourceKind.CLIP,
                    OutputKind.TEXT_CARD,
                    "请总结下面的剪贴板内容，保留关键结论、数字和待办事项，使用简洁的中文分段输出。\n\n"
                            + "剪贴板内容：\n{{text}}",
                    false),
            new QuickTask(
                    "clip.polish",
                    "润色/纠错",
                    SourceKind.CLIP,
                    OutputKind.TEXT_CARD,
                    "请润色并纠正下面的文本：修正错别字、标点和语法，保持原意与语气，不添加事实。"
                            + "先输出修改后的文本；如有必要，再用一句话说明主要修改。\n\n原文：\n{{text}}",
                    false),
            new QuickTask(
                    "image.ocr",
                    "图片取字",
                    SourceKind.IMAGE,
                    OutputKind.COPY_TEXT,
                    "请逐字提取图片中的全部可见文字，保持原始阅读顺序与换行，忽略装饰性文字。"
                            + "只输出提取结果，不要解释。\n\n用户补充：\n{{text}}",
                    true),
            new QuickTask(
                    "image.explain",
                    "图片解说",
                    SourceKind.IMAGE,
                    OutputKind.TEXT_CARD,
                    "请解说这张图片：说明主体、场景、可见文字和值得注意的细节；不确定的内容要明确说明，"
                            + "不要臆测。\n\n用户补充：\n{{text}}",
                    true));

    private static final List<QuickTask> P1 = List.of(
            new QuickTask(
                    "screen.actions",
                    "查找可执行信息",
                    SourceKind.SCREEN,
                    OutputKind.ACTION_SHEET,
                    "请从当前屏幕内容中抽取可以直接执行的地址、时间和电话号码。"
                            + "只输出一个 JSON 对象，不要 Markdown 代码块或解释："
                            + "{\"summary\":\"一句话概括\",\"address\":\"完整地址或 null\","
                            + "\"time\":\"ISO 8601 时间，例如 2026-11-15T19:00 或 null\","
                            + "\"title\":\"日程标题或 null\",\"location\":\"日程地点或 null\","
                            + "\"phone\":\"电话号码或 null\"}。"
                            + "没有的信息写 null，不要编造；同一字段有多个值时只保留最明确的一个。"
                            + "\n\n屏幕内容：\n{{text}}",
                    false),
            new QuickTask(
                    "web.summarize",
                    "总结网页正文",
                    SourceKind.WEB,
                    OutputKind.TEXT_CARD,
                    "请总结下面的网页正文，保留关键结论、数字和待办事项，使用简洁的中文分段输出。\n\n"
                            + "网页正文：\n{{text}}",
                    false),
            new QuickTask(
                    "web.ask",
                    "就本文提问",
                    SourceKind.WEB,
                    OutputKind.TEXT_CARD,
                    "请基于下面的网页正文回答用户的问题。若正文不足以回答，请明确说明依据不足，"
                            + "不要编造。\n\n{{text}}",
                    false),
            new QuickTask(
                    WEB_CLIP,
                    "总结剪贴板网址",
                    SourceKind.WEB,
                    OutputKind.TEXT_CARD,
                    "请总结下面这个网页的正文，保留关键结论、数字和待办事项，使用简洁的中文分段输出。\n\n"
                            + "网页正文：\n{{text}}",
                    false),
            new QuickTask(
                    "voice.transcribe",
                    "语音转写",
                    SourceKind.VOICE,
                    OutputKind.COPY_TEXT,
                    "下面是系统语音识别得到的原始转写。请只修正明显的同音字、标点和断句，"
                            + "不增删信息，不回答其中的问题。只输出整理后的文本，不要解释。"
                            + "\n\n原始转写：\n{{text}}",
                    false));

    private static final List<QuickTask> ALL = concat(P0, P1);
    private static final Map<String, QuickTask> BY_ID = index(ALL);

    private TaskRegistry() { }

    public static List<QuickTask> p0() {
        return P0;
    }

    public static List<QuickTask> p1() {
        return P1;
    }

    public static List<QuickTask> all() {
        return ALL;
    }

    public static Optional<QuickTask> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(BY_ID.get(id.strip()));
    }

    public static QuickTask require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("unknown task id: " + id));
    }

    public static List<QuickTask> bySource(SourceKind source) {
        Objects.requireNonNull(source, "source");
        return ALL.stream().filter(task -> task.source() == source).toList();
    }

    /** True for the clipboard-URL task, which must not require accessibility. */
    public static boolean isClipboardWeb(QuickTask task) {
        return task != null && WEB_CLIP.equals(task.id());
    }

    public static String fillPrompt(QuickTask task, CaptureBundle bundle) {
        return PromptTemplate.fill(task, bundle);
    }

    public static String fillPrompt(QuickTask task, String text) {
        Objects.requireNonNull(task, "task");
        return PromptTemplate.fill(task.promptTemplate(), text);
    }

    private static Map<String, QuickTask> index(List<QuickTask> tasks) {
        Map<String, QuickTask> indexed = new LinkedHashMap<>();
        for (QuickTask task : tasks) {
            if (!PromptTemplate.hasTextPlaceholder(task.promptTemplate())) {
                throw new IllegalStateException("task template lacks {{text}}: " + task.id());
            }
            QuickTask previous = indexed.putIfAbsent(task.id(), task);
            if (previous != null) {
                throw new IllegalStateException("duplicate task id: " + task.id());
            }
        }
        return Map.copyOf(indexed);
    }

    private static List<QuickTask> concat(List<QuickTask> first, List<QuickTask> second) {
        List<QuickTask> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }
}
