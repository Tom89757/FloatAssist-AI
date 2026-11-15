package com.dshgojava.assist.role;

import com.dshgojava.assist.model.ModelChannel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Turns a user-supplied identity into the other four persona fields via the model. */
public final class RoleGenerator {
    private RoleGenerator() { }

    public record Draft(String expertise, String tone, String output, String guardrails) {
        public Draft {
            expertise = blank(expertise);
            tone = blank(tone);
            output = blank(output);
            guardrails = blank(guardrails);
        }
    }

    public static String prompt(String identity) {
        return "请为「" + identity + "」这个角色生成一张结构化角色卡，用于在对话中让助手扮演该角色。"
                + "只输出一个 JSON 对象，不要 Markdown 代码块或多余解释。字段：\n"
                + "{\"expertise\":\"专长领域\",\"tone\":\"口吻风格\","
                + "\"output\":\"输出约定\",\"guardrails\":\"边界红线\"}\n"
                + "每个字段用一句简洁的中文；guardrails 必须包含“不编造不确定内容”这类红线。";
    }

    /** Generates the four persona fields; requires an already-configured model. */
    public static CompletableFuture<Draft> generate(ModelChannel model, String identity) {
        Objects.requireNonNull(model, "model");
        return model.complete(prompt(identity), List.of(), ignored -> { })
                .thenApply(json -> parse(identity, json));
    }

    /** Parses model output, tolerating fenced code blocks and stray prose. */
    public static Draft parse(String identity, String json) {
        Objects.requireNonNull(identity, "identity");
        String cleaned = extractJson(json);
        if (cleaned.isEmpty()) return new Draft("", "", "", "");
        try {
            JsonObject root = JsonParser.parseString(cleaned).getAsJsonObject();
            return new Draft(
                    text(root, "expertise"),
                    text(root, "tone"),
                    text(root, "output"),
                    text(root, "guardrails"));
        } catch (RuntimeException error) {
            return new Draft("", "", "", "");
        }
    }

    private static String extractJson(String raw) {
        if (raw == null) return "";
        String value = raw.strip();
        int open = value.indexOf('{');
        int close = value.lastIndexOf('}');
        if (open < 0 || close <= open) return "";
        return value.substring(open, close + 1);
    }

    private static String text(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) return "";
        return blank(object.get(field).getAsString());
    }

    private static String blank(String value) {
        return value == null ? "" : value.strip();
    }
}
