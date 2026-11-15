package com.dshgojava.assist;

import com.dshgojava.assist.model.ModelChannel;
import com.dshgojava.assist.model.ModelException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Runs the single follow-up allowed after a result. The original capture is not
 * repeated; the previous answer and the follow-up question are sent as one new
 * completion request, matching the stateless P0 model channel.
 *
 * <p>Listener callbacks happen on whichever thread the model uses. Android views
 * must marshal them to the main thread.</p>
 */
public final class AssistFollowUp {
    public interface Listener {
        void onStateChanged(AssistResult state);
    }

    private static final String BLANK_QUESTION = "请输入追问内容";
    private static final String USED = "本轮仅支持一次追问";
    private static final String NOT_CONFIGURED = "未配置模型";
    private static final String EMPTY_ANSWER = "模型返回为空";
    private static final String FAILED = "追问失败";

    private final ModelChannel model;
    private final Listener listener;
    private final StringBuilder streamed = new StringBuilder();
    private final Object lock = new Object();

    private String taskTitle = "";
    private String previousAnswer = "";
    private String question = "";
    private CompletableFuture<String> pending;
    private long generation;
    private boolean used;
    private boolean busy;
    private boolean failed;

    public AssistFollowUp(ModelChannel model, Listener listener) {
        this.model = Objects.requireNonNull(model, "model");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public boolean used() {
        synchronized (lock) {
            return used;
        }
    }

    public boolean busy() {
        synchronized (lock) {
            return busy;
        }
    }

    /**
     * Starts the only follow-up round. A blank question or an unavailable model
     * fails explicitly without consuming the round.
     */
    public AssistResult begin(String taskTitle, String previousAnswer, String question) {
        String clean = question == null ? "" : question.strip();
        synchronized (lock) {
            if (clean.isEmpty()) return publish(AssistResult.failed(BLANK_QUESTION));
            if (used) return publish(AssistResult.failed(USED));
            if (!model.ready()) return publish(AssistResult.failed(NOT_CONFIGURED));

            this.taskTitle = taskTitle == null ? "" : taskTitle.strip();
            this.previousAnswer = previousAnswer == null ? "" : previousAnswer.strip();
            this.question = clean;
            used = true;
            failed = false;
            return launchLocked();
        }
    }

    /** Retries the same model call after a failure; it does not add a second round. */
    public AssistResult retry() {
        synchronized (lock) {
            if (!used || busy || !failed) {
                return publish(AssistResult.failed(FAILED));
            }
            failed = false;
            return launchLocked();
        }
    }

    public void cancel() {
        CompletableFuture<String> toCancel;
        synchronized (lock) {
            if (!busy) return;
            generation++;
            busy = false;
            failed = false;
            toCancel = pending;
            pending = null;
            publish(AssistResult.cancelled());
        }
        if (toCancel != null) toCancel.cancel(true);
    }

    public String prompt() {
        synchronized (lock) {
            return prompt(taskTitle, previousAnswer, question);
        }
    }

    static String prompt(String taskTitle, String previousAnswer, String question) {
        return "请基于上一轮结果回答用户的追问。\n\n"
                + "【上一轮任务】\n" + safe(taskTitle) + "\n\n"
                + "【上一轮结果】\n" + safe(previousAnswer) + "\n\n"
                + "【用户追问】\n" + safe(question) + "\n\n"
                + "只回答追问，不要重复上一轮的全部内容。";
    }

    private AssistResult launchLocked() {
        streamed.setLength(0);
        busy = true;
        long request = ++generation;
        AssistResult thinking = AssistResult.thinking();
        notifyState(thinking);

        CompletableFuture<String> future;
        try {
            future = model.complete(promptLocked(), List.of(), delta -> onDelta(request, delta));
        } catch (RuntimeException error) {
            return failLocked(error);
        }
        if (future == null) {
            return failLocked(new ModelException(ModelException.Kind.PROTOCOL, FAILED));
        }
        pending = future;
        future.whenComplete((text, error) -> onCompleted(request, text, error));
        return thinking;
    }

    private String promptLocked() {
        return prompt(taskTitle, previousAnswer, question);
    }

    private void onDelta(long request, String delta) {
        if (delta == null || delta.isEmpty()) return;
        AssistResult next;
        synchronized (lock) {
            if (request != generation || !busy) return;
            streamed.append(delta);
            next = AssistResult.streaming(streamed.toString());
        }
        notifyState(next);
    }

    private void onCompleted(long request, String text, Throwable error) {
        AssistResult next;
        synchronized (lock) {
            if (request != generation || !busy) return;
            pending = null;
            if (error != null) {
                busy = false;
                next = failureLocked(error);
                failed = next.stage() == Stage.FAILED;
            } else {
                String answer = text == null || text.isBlank() ? streamed.toString() : text;
                if (answer.isBlank()) {
                    busy = false;
                    next = failureLocked(new ModelException(
                            ModelException.Kind.EMPTY_RESPONSE, EMPTY_ANSWER));
                    failed = next.stage() == Stage.FAILED;
                } else {
                    busy = false;
                    failed = false;
                    next = AssistResult.done(answer, List.of());
                }
            }
        }
        notifyState(next);
    }

    private AssistResult failLocked(Throwable error) {
        busy = false;
        failed = true;
        pending = null;
        AssistResult next = failureLocked(error);
        notifyState(next);
        return next;
    }

    private AssistResult failureLocked(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof CancellationException) {
            busy = false;
            failed = false;
            return AssistResult.cancelled();
        }
        String message = cause.getMessage();
        if (cause instanceof ModelException && message != null && !message.isBlank()) {
            return AssistResult.failed(message);
        }
        return AssistResult.failed(FAILED);
    }

    private AssistResult publish(AssistResult state) {
        notifyState(state);
        return state;
    }

    private void notifyState(AssistResult state) {
        listener.onStateChanged(state);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null
                ? new ModelException(ModelException.Kind.PROTOCOL, FAILED) : current;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
