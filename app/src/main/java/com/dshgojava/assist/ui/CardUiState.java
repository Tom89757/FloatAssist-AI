package com.dshgojava.assist.ui;

import com.dshgojava.assist.AssistResult;
import com.dshgojava.assist.Stage;

/** Pure projection of one result snapshot into card controls and placeholders. */
public final class CardUiState {
    public enum Status {
        NONE,
        GATHERING,
        THINKING
    }

    public record State(
            boolean skeleton,
            boolean textVisible,
            String text,
            String error,
            boolean copyEnabled,
            boolean shareEnabled,
            boolean askEnabled,
            boolean stopEnabled,
            boolean retryEnabled,
            Status status
    ) {
    }

    private CardUiState() { }

    public static State from(AssistResult result, String displayText, boolean retryAvailable) {
        Stage stage = result.stage();
        String text = displayText == null ? "" : displayText;
        boolean hasText = !text.isBlank();
        boolean terminal = result.terminal();
        Status status;
        if (stage == Stage.GATHERING) {
            status = Status.GATHERING;
        } else if (stage == Stage.THINKING) {
            status = Status.THINKING;
        } else {
            status = Status.NONE;
        }
        return new State(
                !terminal && !hasText,
                hasText,
                text,
                result.error() == null ? "" : result.error(),
                hasText,
                hasText,
                stage == Stage.DONE && hasText,
                !terminal,
                stage == Stage.FAILED && retryAvailable,
                status);
    }
}
