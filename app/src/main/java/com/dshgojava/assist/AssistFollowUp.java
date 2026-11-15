package com.dshgojava.assist;

import com.dshgojava.assist.model.ChatMessage;
import com.dshgojava.assist.model.ModelChannel;
import com.dshgojava.assist.model.ModelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Multi-round follow-up over one result. The original capture is never repeated;
 * instead a rolling transcript of the conversation (initial context, each question
 * and each answer) is sent to the stateless model channel as an ordered message
 * array, so later questions can refer back to earlier answers.
 *
 * <p>Listener callbacks happen on whichever thread the model uses. Android views
 * must marshal them to the main thread.</p>
 */
public final class AssistFollowUp {
    public interface Listener {
        void onStateChanged(AssistResult state);
    }

    private static final String BLANK_QUESTION = "请输入追问内容";
    private static final String NOT_CONFIGURED = "未配置模型";
    private static final String EMPTY_ANSWER = "模型返回为空";
    private static final String BUSY = "正在处理上一轮追问";
    private static final String FAILED = "追问失败";

    private final ModelChannel model;
    private final Listener listener;
    private final List<ChatMessage> history = new ArrayList<>();
    private final StringBuilder streamed = new StringBuilder();
    private final Object lock = new Object();

    private String pendingQuestion = "";
    private CompletableFuture<String> pending;
    private long generation;
    private boolean busy;
    private boolean failed;
    private boolean started;

    public AssistFollowUp(ModelChannel model, Listener listener) {
        this.model = Objects.requireNonNull(model, "model");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public boolean started() {
        synchronized (lock) {
            return started;
        }
    }

    public boolean busy() {
        synchronized (lock) {
            return busy;
        }
    }

    public boolean hasFailed() {
        synchronized (lock) {
            return failed;
        }
    }

    /** Seeds the conversation with the task result and asks the first question. */
    public AssistResult begin(String taskTitle, String initialResult, String question) {
        String clean = question == null ? "" : question.strip();
        synchronized (lock) {
            if (clean.isEmpty()) return publish(AssistResult.failed(BLANK_QUESTION));
            if (busy) return publish(AssistResult.failed(BUSY));
            if (!model.ready()) return publish(AssistResult.failed(NOT_CONFIGURED));
            started = true;
            return launchLocked(firstPrompt(taskTitle, initialResult, clean));
        }
    }

    /** Asks another question, carrying the whole prior conversation as history. */
    public AssistResult continueFollowUp(String question) {
        String clean = question == null ? "" : question.strip();
        synchronized (lock) {
            if (clean.isEmpty()) return publish(AssistResult.failed(BLANK_QUESTION));
            if (busy) return publish(AssistResult.failed(BUSY));
            if (!started) return publish(AssistResult.failed(FAILED));
            if (!model.ready()) return publish(AssistResult.failed(NOT_CONFIGURED));
            return launchLocked(clean);
        }
    }

    /** Retries the last failed round without appending the failed answer. */
    public AssistResult retry() {
        synchronized (lock) {
            if (!started || busy || !failed) {
                return publish(AssistResult.failed(FAILED));
            }
            failed = false;
            return launchLocked(pendingQuestion);
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

    static String firstPrompt(String taskTitle, String initialResult, String question) {
        return "请基于下面的任务结果回答我的追问。\n\n"
                + "【任务】\n" + safe(taskTitle) + "\n\n"
                + "【结果】\n" + safe(initialResult) + "\n\n"
                + "【追问】\n" + safe(question) + "\n\n"
                + "直接回答追问，不要重复上一轮的全部内容。";
    }

    private AssistResult launchLocked(String prompt) {
        streamed.setLength(0);
        busy = true;
        failed = false;
        pendingQuestion = prompt;
        long request = ++generation;
        AssistResult thinking = AssistResult.thinking();
        notifyState(thinking);

        List<ChatMessage> prior = List.copyOf(history);
        CompletableFuture<String> future;
        try {
            future = model.complete(prompt, List.of(), prior, delta -> onDelta(request, delta));
        } catch (RuntimeException error) {
            return failLocked(error);
        }
        if (future == null) {
            return failLocked(new ModelException(ModelException.Kind.PROTOCOL, FAILED));
        }
        pending = future;
        future.whenComplete((text, error) -> onCompleted(request, prompt, text, error));
        return thinking;
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

    private void onCompleted(long request, String prompt, String text, Throwable error) {
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
                    history.add(ChatMessage.user(prompt));
                    history.add(ChatMessage.assistant(answer));
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
