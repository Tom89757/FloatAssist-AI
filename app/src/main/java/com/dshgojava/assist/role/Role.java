package com.dshgojava.assist.role;

import java.util.Objects;

/**
 * A structured persona. The five fields are rendered into a compact system
 * prompt, so the model adopts the role without burying the task data.
 */
public record Role(
        String id,
        String name,
        String identity,
        String expertise,
        String tone,
        String output,
        String guardrails,
        boolean builtin
) {
    public static final String NONE_ID = "";

    public Role {
        id = id == null ? "" : id.strip();
        name = requireText(name, "name");
        identity = requireText(identity, "identity");
        expertise = blank(expertise);
        tone = blank(tone);
        output = blank(output);
        guardrails = blank(guardrails);
    }

    /** True for the "无角色" reset option; it renders the base system prompt only. */
    public boolean none() {
        return id.isEmpty();
    }

    /** Renders the role card as a system-prompt fragment, skipping empty fields. */
    public String persona() {
        if (none()) return "";
        StringBuilder out = new StringBuilder();
        out.append("本次以「").append(name).append("」身份工作。");
        append(out, "身份", identity);
        append(out, "专长", expertise);
        append(out, "口吻", tone);
        append(out, "输出约定", output);
        append(out, "边界", guardrails);
        return out.toString();
    }

    private static void append(StringBuilder out, String label, String value) {
        if (value == null || value.isBlank()) return;
        out.append('\n').append('【').append(label).append('】').append(value.strip());
    }

    private static String blank(String value) {
        return value == null ? "" : value.strip();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
