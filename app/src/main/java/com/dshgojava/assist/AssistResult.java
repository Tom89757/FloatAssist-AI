package com.dshgojava.assist;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of one quick-assist run. {@code text} is never null,
 * {@code actions} is an immutable copy, and {@code error} is null unless the
 * run failed.
 */
public record AssistResult(
        Stage stage,
        String text,
        List<AssistAction> actions,
        String error
) {
    public AssistResult {
        stage = Objects.requireNonNull(stage, "stage");
        text = text == null ? "" : text;
        actions = actions == null ? List.of() : List.copyOf(actions);
        error = error == null || error.isBlank() ? null : error;
    }

    public static AssistResult idle() {
        return new AssistResult(Stage.IDLE, "", List.of(), null);
    }

    public static AssistResult gathering() {
        return new AssistResult(Stage.GATHERING, "", List.of(), null);
    }

    public static AssistResult thinking() {
        return new AssistResult(Stage.THINKING, "", List.of(), null);
    }

    public static AssistResult streaming(String text) {
        return new AssistResult(Stage.STREAMING, text, List.of(), null);
    }

    public static AssistResult done(String text, List<AssistAction> actions) {
        return new AssistResult(Stage.DONE, text, actions, null);
    }

    public static AssistResult failed(String error) {
        return new AssistResult(Stage.FAILED, "", List.of(), error);
    }

    public static AssistResult cancelled() {
        return new AssistResult(Stage.CANCELLED, "", List.of(), null);
    }

    public boolean terminal() {
        return stage == Stage.DONE || stage == Stage.FAILED || stage == Stage.CANCELLED;
    }
}
