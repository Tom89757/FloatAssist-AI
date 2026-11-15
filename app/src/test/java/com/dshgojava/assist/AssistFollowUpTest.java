package com.dshgojava.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void promptContainsPreviousResultAndQuestion() {
        String prompt = AssistFollowUp.prompt("总结当前屏幕", "上一轮答案", "再短一点");

        assertTrue(prompt.contains("总结当前屏幕"));
        assertTrue(prompt.contains("上一轮答案"));
        assertTrue(prompt.contains("再短一点"));
        assertTrue(prompt.contains("只回答追问"));
    }

    @Test
    void streamsOneFollowUpThenCompletes() {
        FakeModel model = new FakeModel(true);
        List<AssistResult> states = new ArrayList<>();
        AssistFollowUp followUp = new AssistFollowUp(model, states::add);

        AssistResult started = followUp.begin("总结", "完整答案", "  再短一点  ");
        assertEquals(Stage.THINKING, started.stage());
        assertTrue(followUp.used());
        assertTrue(followUp.busy());
        assertEquals(1, model.calls.size());
        assertEquals(List.of(), model.images.get(0));
        assertTrue(model.prompts.get(0).contains("完整答案"));
        assertTrue(model.prompts.get(0).contains("再短一点"));

        model.delta.accept("短版");
        assertEquals(Stage.STREAMING, states.get(states.size() - 1).stage());
        assertEquals("短版", states.get(states.size() - 1).text());

        model.pending.complete("短版答案");
        AssistResult done = states.get(states.size() - 1);
        assertEquals(Stage.DONE, done.stage());
        assertEquals("短版答案", done.text());
        assertFalse(followUp.busy());
    }

    @Test
    void rejectsSecondRoundWithoutAnotherModelCall() {
        FakeModel model = new FakeModel(true);
        List<AssistResult> states = new ArrayList<>();
        AssistFollowUp followUp = new AssistFollowUp(model, states::add);
        followUp.begin("总结", "答案", "第一问");
        model.pending.complete("第一答");

        AssistResult rejected = followUp.begin("总结", "第一答", "第二问");

        assertEquals(Stage.FAILED, rejected.stage());
        assertEquals("本轮仅支持一次追问", rejected.error());
        assertEquals(1, model.calls.size());
        assertTrue(followUp.used());
    }

    @Test
    void blankQuestionDoesNotConsumeTheRound() {
        FakeModel model = new FakeModel(true);
        AssistFollowUp followUp = new AssistFollowUp(model, state -> { });

        AssistResult blank = followUp.begin("总结", "答案", "   ");
        assertFalse(followUp.used());
        assertEquals("请输入追问内容", blank.error());

        AssistResult valid = followUp.begin("总结", "答案", "有效问题");
        assertEquals(Stage.THINKING, valid.stage());
        assertTrue(followUp.used());
        assertEquals(1, model.calls.size());
    }

    @Test
    void unavailableModelFailsExplicitlyWithoutConsumingRound() {
        FakeModel model = new FakeModel(false);
        AssistFollowUp followUp = new AssistFollowUp(model, state -> { });

        AssistResult failed = followUp.begin("总结", "答案", "问题");

        assertEquals(Stage.FAILED, failed.stage());
        assertEquals("未配置模型", failed.error());
        assertFalse(followUp.used());
        assertTrue(model.calls.isEmpty());
    }

    @Test
    void retryRepeatsSameRoundAndUsesModelError() {
        FakeModel model = new FakeModel(true);
        List<AssistResult> states = new ArrayList<>();
        AssistFollowUp followUp = new AssistFollowUp(model, states::add);
        followUp.begin("总结", "答案", "问题");
        model.pending.completeExceptionally(
                new ModelException(ModelException.Kind.TRANSPORT, "网络失败"));
        assertEquals(Stage.FAILED, states.get(states.size() - 1).stage());

        AssistResult retried = followUp.retry();
        assertEquals(Stage.THINKING, retried.stage());
        assertEquals(2, model.calls.size());
        assertTrue(followUp.used());
        assertTrue(model.prompts.get(1).contains("问题"));
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
        final List<String> calls = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        final List<List<PromptImage>> images = new ArrayList<>();
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
            prompts.add(prompt);
            calls.add(prompt);
            this.images.add(List.copyOf(images));
            pending = new CompletableFuture<>();
            delta = onDelta;
            return pending;
        }
    }
}
