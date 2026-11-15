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
        CardUiState.State state = CardUiState.from(AssistResult.gathering(), "", false);

        assertTrue(state.skeleton());
        assertFalse(state.textVisible());
        assertTrue(state.stopEnabled());
        assertFalse(state.copyEnabled());
        assertFalse(state.askEnabled());
        assertEquals(CardUiState.Status.GATHERING, state.status());
    }

    @Test
    void thinkingShowsThinkingStatus() {
        CardUiState.State state = CardUiState.from(AssistResult.thinking(), "", false);

        assertTrue(state.skeleton());
        assertEquals(CardUiState.Status.THINKING, state.status());
    }

    @Test
    void streamingShowsDisplayTextAndEnablesCopyShareStop() {
        CardUiState.State state = CardUiState.from(
                AssistResult.streaming("部分答案"), "部分答案", false);

        assertFalse(state.skeleton());
        assertTrue(state.textVisible());
        assertEquals("部分答案", state.text());
        assertTrue(state.copyEnabled());
        assertTrue(state.shareEnabled());
        assertTrue(state.stopEnabled());
        assertFalse(state.askEnabled());
        assertEquals(CardUiState.Status.NONE, state.status());
    }

    @Test
    void doneShowsTranscriptAndAllowsFollowUp() {
        String transcript = "首轮答案\n\n【追问】第一问\n\n第一答";
        CardUiState.State state = CardUiState.from(
                AssistResult.done("第一答", java.util.List.of()), transcript, false);

        assertTrue(state.textVisible());
        assertEquals(transcript, state.text());
        assertTrue(state.askEnabled());
        assertTrue(state.copyEnabled());
        assertFalse(state.stopEnabled());
    }

    @Test
    void failedCardOnlyShowsRetryWhenHostSupportsIt() {
        AssistResult failed = AssistResult.failed("网络失败");

        CardUiState.State withoutRetry = CardUiState.from(failed, "", false);
        assertFalse(withoutRetry.retryEnabled());
        assertEquals("网络失败", withoutRetry.error());
        assertFalse(withoutRetry.stopEnabled());

        CardUiState.State withRetry = CardUiState.from(failed, "", true);
        assertTrue(withRetry.retryEnabled());
    }

    @Test
    void cancelledCardIsTerminalAndHasNoActions() {
        CardUiState.State state = CardUiState.from(AssistResult.cancelled(), "", false);

        assertTrue(state.error().isEmpty());
        assertFalse(state.stopEnabled());
        assertFalse(state.copyEnabled());
        assertFalse(state.askEnabled());
        assertEquals(Stage.CANCELLED, AssistResult.cancelled().stage());
    }
}
