package com.dshgojava.assist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.AssistResult;
import com.dshgojava.assist.Stage;
import org.junit.jupiter.api.Test;

class CardUiStateTest {
    @Test
    void gatheringShowsSkeletonAndStopWithoutFakeText() {
        CardUiState.State state = CardUiState.from(AssistResult.gathering(), false, false);

        assertTrue(state.skeleton());
        assertFalse(state.textVisible());
        assertTrue(state.stopEnabled());
        assertFalse(state.copyEnabled());
        assertFalse(state.askEnabled());
        assertEquals(CardUiState.Status.GATHERING, state.status());
    }

    @Test
    void thinkingShowsThinkingStatus() {
        CardUiState.State state = CardUiState.from(AssistResult.thinking(), false, false);

        assertTrue(state.skeleton());
        assertEquals(CardUiState.Status.THINKING, state.status());
    }

    @Test
    void streamingEnablesCopyShareAndStop() {
        CardUiState.State state = CardUiState.from(AssistResult.streaming("部分答案"), false, false);

        assertFalse(state.skeleton());
        assertTrue(state.copyEnabled());
        assertTrue(state.shareEnabled());
        assertTrue(state.stopEnabled());
        assertFalse(state.askEnabled());
        assertEquals(CardUiState.Status.NONE, state.status());
    }

    @Test
    void doneAllowsExactlyOneFollowUp() {
        AssistResult done = AssistResult.done("完整答案", java.util.List.of());

        CardUiState.State first = CardUiState.from(done, false, false);
        assertTrue(first.askEnabled());
        assertFalse(first.askHintVisible());
        assertTrue(first.copyEnabled());
        assertFalse(first.stopEnabled());

        CardUiState.State second = CardUiState.from(done, true, false);
        assertFalse(second.askEnabled());
        assertTrue(second.askHintVisible());
    }

    @Test
    void failedCardOnlyShowsRetryWhenHostSupportsIt() {
        AssistResult failed = AssistResult.failed("网络失败");

        CardUiState.State withoutRetry = CardUiState.from(failed, false, false);
        assertFalse(withoutRetry.retryEnabled());
        assertEquals("网络失败", withoutRetry.error());
        assertFalse(withoutRetry.stopEnabled());

        CardUiState.State withRetry = CardUiState.from(failed, false, true);
        assertTrue(withRetry.retryEnabled());
    }

    @Test
    void cancelledCardIsTerminalAndHasNoActions() {
        CardUiState.State state = CardUiState.from(AssistResult.cancelled(), false, false);

        assertTrue(state.error().isEmpty());
        assertFalse(state.stopEnabled());
        assertFalse(state.copyEnabled());
        assertFalse(state.askEnabled());
        assertEquals(Stage.CANCELLED, AssistResult.cancelled().stage());
    }
}
