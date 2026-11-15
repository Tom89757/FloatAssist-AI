package com.dshgojava.assist.store;

import java.util.Objects;
import java.util.UUID;

/**
 * One locally stored answer. History deliberately keeps only the task identity
 * and answer text: no screenshots, capture contents, prompts, or credentials
 * are persisted.
 */
public record AssistHistoryEntry(
        String id,
        long createdAtMillis,
        String taskId,
        String taskTitle,
        String text,
        boolean followUp
) {
    public static final int MAX_TEXT_CHARS = 100_000;

    public AssistHistoryEntry {
        id = required(id, "id");
        taskId = required(taskId, "taskId");
        taskTitle = required(taskTitle, "taskTitle");
        text = required(text, "text");
        if (createdAtMillis <= 0L) {
            throw new IllegalArgumentException("createdAtMillis must be positive");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
    }

    public static AssistHistoryEntry create(
            String taskId, String taskTitle, String text, boolean followUp, long nowMillis) {
        return new AssistHistoryEntry(UUID.randomUUID().toString(), nowMillis,
                taskId, taskTitle, text, followUp);
    }

    public String shortText(int maxChars) {
        int limit = Math.max(1, maxChars);
        String compact = text.replace('\r', ' ').replace('\n', ' ').strip();
        return compact.length() <= limit ? compact : compact.substring(0, limit) + "…";
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        String cleaned = value.strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return cleaned;
    }
}
