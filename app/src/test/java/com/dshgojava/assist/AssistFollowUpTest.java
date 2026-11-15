package com.dshgojava.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.model.ChatMessage;
import com.dshgojava.assist.model.ModelChannel;
import com.dshgojava.assist.model.ModelException;
import com.dshgojava.assist.model.PromptImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AssistFollowUpTest {
    @Test
    void firstPromptContainsTaskResultAndQuestion() {
        String prompt = AssistFollowUp.firstPrompt("总结当前屏幕", "上一轮答案", "再短一点");

        assertTrue(prompt.contains("总结当前屏幕"));
        assertTrue(prompt.contains("上一轮答案"));
        assertTrue(prompt.contains("再短一点"));
    }

    @Test
    void beginThenContinueCarriesRollingHistory() {
        FakeModel model = new FakeModel(true);
        List<AssistResult> states = new ArrayList<>();
        AssistFollowUp followUp = new AssistFollowUp(model, states::add);

        AssistResult first = followUp.begin("总结", "完整答案", "第一问");
        assertEquals(Stage.THINKING, first.stage());
        assertTrue(followUp.started());
        assertTrue(followUp.busy());
        assertEquals(1, model.prompts.size());
        assertTrue(model.histories.get(0).isEmpty());
        assertTrue(model.prompts.get(0).contains("完整答案"));
        assertTrue(model.prompts.get(0).contains("第一问"));

        model.pending.complete("第一答");
        assertEquals(Stage.DONE, states.get(states.size() - 1).stage());
        assertEquals("第一答", states.get(states.size() - 1).text());
        assertFalse(followUp.busy());

        AssistResult second = followUp.continueFollowUp("第二问");
        assertEquals(Stage.THINKING, second.stage());
        assertEquals(2, model.prompts.size());
        assertEquals("第二问", model.prompts.get(1));
        List<ChatMessage> history = model.histories.get(1);
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
        assertEquals("第一答", history.get(1).text());

        model.pending.complete("第二答");
        assertEquals(Stage.DONE, states.get(states.size() - 1).stage());
        assertEquals("第二答", states.get(states.size() - 1).text());
    }

    @Test
    void blankQuestionDoesNotStartTheConversation() {
        FakeModel model = new FakeModel(true);
        AssistFollowUp followUp = new AssistFollowUp(model, state -> { });

        AssistResult blank = followUp.begin("总结", "答案", "   ");
        assertFalse(followUp.started());
        assertEquals("请输入追问内容", blank.error());

        AssistResult valid = followUp.begin("总结", "答案", "有效问题");
        assertEquals(Stage.THINKING, valid.stage());
        assertTrue(followUp.started());
        assertEquals(1, model.prompts.size());
    }

    @Test
    void continueBeforeBeginFailsExplicitly() {
        FakeModel model = new FakeModel(true);
        AssistFollowUp followUp = new AssistFollowUp(model, state -> { });

        AssistResult failed = followUp.continueFollowUp("问题");

        assertEquals(Stage.FAILED, failed.stage());
        assertFalse(followUp.started());
        assertTrue(model.prompts.isEmpty());
    }

    @Test
    void unavailableModelFailsExplicitlyWithoutStarting() {
        FakeModel model = new FakeModel(false);
        AssistFollowUp followUp = new AssistFollowUp(model, state -> { });

        AssistResult failed = followUp.begin("总结", "答案", "问题");

        assertEquals(Stage.FAILED, failed.stage());
        assertEquals("未配置模型", failed.error());
        assertFalse(followUp.started());
        assertTrue(model.prompts.isEmpty());
    }

    @Test
    void retryRepeatsLastRoundWithoutAppendingFailedAnswer() {
        FakeModel model = new FakeModel(true);
        List<AssistResult> states = new ArrayList<>();
        AssistFollowUp followUp = new AssistFollowUp(model, states::add);
        followUp.begin("总结", "答案", "问题");
        model.pending.completeExceptionally(
                new ModelException(ModelException.Kind.TRANSPORT, "网络失败"));
        assertEquals(Stage.FAILED, states.get(states.size() - 1).stage());
        assertTrue(followUp.hasFailed());

        AssistResult retried = followUp.retry();
        assertEquals(Stage.THINKING, retried.stage());
        assertEquals(2, model.prompts.size());
        assertEquals(model.prompts.get(0), model.prompts.get(1));
        assertTrue(model.histories.get(1).isEmpty());
    }

    @Test
    void cancelIgnoresLateDeltaAndCompletion() {
        FakeModel model = new FakeModel(true);
        List<AssistResult> states = new ArrayList<>();
        AssistFollowUp followUp = new AssistFollowUp(model, states::add);
        followUp.begin("总结", "答案", "问题");
        int beforeCancel = states.size();

        followUp.cancel();
        assertEquals(Stage.CANCELLED, states.get(states.size() - 1).stage());
        assertFalse(followUp.busy());
        model.delta.accept("迟到内容");
        model.pending.complete("迟到答案");

        assertEquals(beforeCancel + 1, states.size());
        assertEquals(Stage.CANCELLED, states.get(states.size() - 1).stage());
    }

    @Test
    void emptyModelAnswerFailsInsteadOfShowingBlankCard() {
        FakeModel model = new FakeModel(true);
        List<AssistResult> states = new ArrayList<>();
        AssistFollowUp followUp = new AssistFollowUp(model, states::add);
        followUp.begin("总结", "答案", "问题");

        model.pending.complete("   ");

        AssistResult failed = states.get(states.size() - 1);
        assertEquals(Stage.FAILED, failed.stage());
        assertEquals("模型返回为空", failed.error());
    }

    private static final class FakeModel implements ModelChannel {
        final boolean ready;
        final List<String> prompts = new ArrayList<>();
        final List<List<ChatMessage>> histories = new ArrayList<>();
        CompletableFuture<String> pending;
        Consumer<String> delta;

        FakeModel(boolean ready) {
            this.ready = ready;
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public CompletableFuture<String> complete(
                String prompt, List<PromptImage> images, Consumer<String> onDelta) {
            return complete(prompt, images, List.of(), onDelta);
        }

        @Override
        public CompletableFuture<String> complete(
                String prompt, List<PromptImage> images, List<ChatMessage> history,
                Consumer<String> onDelta) {
            prompts.add(prompt);
            histories.add(List.copyOf(history));
            pending = new CompletableFuture<>();
            delta = onDelta;
            return pending;
        }
    }
}
