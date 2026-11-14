package com.dshgojava.assist;

import java.util.Map;
import java.util.Objects;

/**
 * One quick-assist request: the selected task, optional source hints and any
 * user-supplied text. {@code context} and {@code userInput} are never null.
 */
public record AssistRequest(
        QuickTask task,
        Map<String, String> context,
        String userInput
) {
    public AssistRequest {
        task = Objects.requireNonNull(task, "task");
        context = context == null ? Map.of() : Map.copyOf(context);
        userInput = userInput == null ? "" : userInput;
    }

    public static AssistRequest of(QuickTask task) {
        return new AssistRequest(task, Map.of(), "");
    }

    public boolean hasUserInput() {
        return !userInput.isBlank();
    }

    public AssistRequest withUserInput(String value) {
        return new AssistRequest(task, context, value);
    }
}
