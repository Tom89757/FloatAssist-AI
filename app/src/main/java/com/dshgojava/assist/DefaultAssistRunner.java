package com.dshgojava.assist;

import com.dshgojava.assist.action.ActionSheetParser;
import com.dshgojava.assist.model.ModelChannel;
import com.dshgojava.assist.model.ModelException;
import com.dshgojava.assist.model.PromptImage;
import com.dshgojava.assist.source.AssistSource;
import com.dshgojava.assist.source.CaptureBundle;
import com.dshgojava.assist.source.CaptureException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Clock;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * One-shot scheduler: preflight, gather once, fill the task prompt, call the
 * model once, and publish immutable snapshots. Retry policy and agent loops do
 * not live here; this class only performs one attempt.
 */
public final class DefaultAssistRunner implements AssistRunner {
    private final ModelChannel model;
    private final Function<SourceKind, AssistSource> sourceLookup;
    private final Clock clock;

    public DefaultAssistRunner(ModelChannel model, Map<SourceKind, AssistSource> sources) {
        this(model, lookupFrom(sources), Clock.systemDefaultZone());
    }

    public DefaultAssistRunner(
            ModelChannel model,
            Function<SourceKind, AssistSource> sourceLookup) {
        this(model, sourceLookup, Clock.systemDefaultZone());
    }

    public DefaultAssistRunner(
            ModelChannel model,
            Map<SourceKind, AssistSource> sources,
            Clock clock) {
        this(model, lookupFrom(sources), clock);
    }

    public DefaultAssistRunner(
            ModelChannel model,
            Function<SourceKind, AssistSource> sourceLookup,
            Clock clock) {
        this.model = Objects.requireNonNull(model, "model");
        this.sourceLookup = Objects.requireNonNull(sourceLookup, "sourceLookup");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AssistRun start(QuickTask task, AssistRequest request) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(request, "request");
        Run run = new Run(task, request);
        run.begin();
        return run;
    }

    private static Function<SourceKind, AssistSource> lookupFrom(
            Map<SourceKind, AssistSource> sources) {
        Objects.requireNonNull(sources, "sources");
        Map<SourceKind, AssistSource> copy = new EnumMap<>(SourceKind.class);
        sources.forEach((kind, source) -> {
            if (kind != null && source != null) copy.put(kind, source);
        });
        return copy::get;
    }

    private final class Run implements AssistRun, ModelRetry {
        private final QuickTask task;
        private final AssistRequest request;
        private final boolean matchingTask;
        private final StateStore<AssistResult> state =
                new StateStore<>(AssistResult.idle());
        private final Object lock = new Object();
        private final StringBuilder streamed = new StringBuilder();

        private boolean terminal;
        private CompletableFuture<?> pending;
        private String modelPrompt = "";
        private List<PromptImage> modelImages = List.of();
        private boolean modelFailed;

        private Run(QuickTask task, AssistRequest request) {
            this.task = task;
            this.matchingTask = task.id().equals(request.task().id());
            this.request = new AssistRequest(task, request.context(), request.userInput());
        }

        @Override
        public StateStore<AssistResult> state() {
            return state;
        }

        private void begin() {
            if (!matchingTask) {
                fail("请求与任务不匹配");
                return;
            }

            AssistSource source;
            try {
                source = sourceLookup.apply(task.source());
            } catch (RuntimeException error) {
                fail(failureMessage(error));
                return;
            }

            boolean sourceAvailable = false;
            if (source != null) {
                try {
                    sourceAvailable = source.availableFor(task);
                } catch (RuntimeException error) {
                    fail(failureMessage(error));
                    return;
                }
            }

            TaskPreflight.Result preflight = TaskPreflight.beforeStart(
                    task, sourceAvailable, model.ready(), model.supportsVision());
            if (preflight.failed()) {
                fail(preflight.message());
                return;
            }
            if (source.kind() != task.source()) {
                fail("取料源与任务不匹配");
                return;
            }

            CompletableFuture<CaptureBundle> gathering;
            try {
                gathering = beginGather(source);
            } catch (RuntimeException error) {
                fail(failureMessage(error));
                return;
            }
            if (gathering == null) return;
            gathering.whenComplete(this::onGathered);
        }

        private CompletableFuture<CaptureBundle> beginGather(AssistSource source) {
            synchronized (lock) {
                if (terminal) return null;
                state.setState(AssistResult.gathering());
                if (terminal) return null;

                CompletableFuture<CaptureBundle> future = source.gather(request);
                if (future == null) {
                    failLocked("取料失败");
                    return null;
                }
                pending = future;
                return future;
            }
        }

        private void onGathered(CaptureBundle bundle, Throwable error) {
            if (error != null) {
                handleFailure(error);
                return;
            }
            if (bundle == null) {
                fail("取料失败");
                return;
            }

            TaskPreflight.Result check = TaskPreflight.afterCapture(task, bundle);
            if (check.failed()) {
                fail(check.message());
                return;
            }

            String prompt = PromptTemplate.fill(
                    task.promptTemplate(), promptText(bundle, request));
            CompletableFuture<String> completion;
            try {
                List<PromptImage> images = model.supportsVision()
                        ? bundle.images() : List.of();
                completion = beginModel(prompt, images);
            } catch (RuntimeException modelError) {
                synchronized (lock) {
                    modelFailed = true;
                }
                fail(failureMessage(modelError));
                return;
            }
            if (completion == null) return;
            completion.whenComplete(this::onCompleted);
        }

        private CompletableFuture<String> beginModel(
                String prompt,
                List<PromptImage> images) {
            synchronized (lock) {
                if (terminal) return null;
                state.setState(AssistResult.thinking());
                if (terminal) return null;

                modelPrompt = prompt;
                modelImages = List.copyOf(images);
                modelFailed = false;
                CompletableFuture<String> future = model.complete(prompt, images, this::onDelta);
                if (future == null) {
                    modelFailed = true;
                    failLocked("模型未返回结果");
                    return null;
                }
                pending = future;
                return future;
            }
        }

        private void onDelta(String delta) {
            if (delta == null || delta.isEmpty()) return;
            synchronized (lock) {
                if (terminal) return;
                streamed.append(delta);
                state.setState(AssistResult.streaming(streamed.toString()));
            }
        }

        private void onCompleted(String fullText, Throwable error) {
            if (error != null) {
                synchronized (lock) {
                    modelFailed = true;
                }
                handleFailure(error);
                return;
            }
            synchronized (lock) {
                if (terminal) return;
                String answer = fullText;
                if (answer == null || answer.isBlank()) {
                    answer = streamed.toString();
                }
                if (answer.isBlank()) {
                    modelFailed = true;
                    failLocked("模型返回为空");
                    return;
                }
                terminal = true;
                pending = null;
                state.setState(done(answer));
            }
        }

        private AssistResult done(String answer) {
            if (task.output() != OutputKind.ACTION_SHEET) {
                return AssistResult.done(answer, List.of());
            }
            ActionSheetParser.Result parsed = ActionSheetParser.parse(
                    answer, clock.getZone(), clock.instant());
            return AssistResult.done(parsed.text(), parsed.actions());
        }

        private void handleFailure(Throwable error) {
            Throwable cause = unwrap(error);
            if (cause instanceof CancellationException) {
                cancel();
                return;
            }
            fail(failureMessage(cause));
        }

        @Override
        public void cancel() {
            CompletableFuture<?> toCancel;
            synchronized (lock) {
                if (terminal) return;
                terminal = true;
                toCancel = pending;
                pending = null;
                state.setState(AssistResult.cancelled());
            }
            if (toCancel != null) toCancel.cancel(true);
        }

        @Override
        public void close() {
            cancel();
        }

        @Override
        public boolean canRetryModel() {
            synchronized (lock) {
                return terminal && modelFailed && !modelPrompt.isEmpty()
                        && state.state().stage() == Stage.FAILED;
            }
        }

        @Override
        public void retryModel() {
            CompletableFuture<String> future;
            synchronized (lock) {
                if (!terminal || !modelFailed || modelPrompt.isEmpty()
                        || state.state().stage() != Stage.FAILED) {
                    return;
                }
                terminal = false;
                modelFailed = false;
                streamed.setLength(0);
                try {
                    future = beginModel(modelPrompt, modelImages);
                } catch (RuntimeException error) {
                    modelFailed = true;
                    failLocked(failureMessage(error));
                    return;
                }
                if (future == null) return;
            }
            future.whenComplete(this::onCompleted);
        }

        private void fail(String message) {
            synchronized (lock) {
                failLocked(message);
            }
        }

        private void failLocked(String message) {
            if (terminal) return;
            terminal = true;
            pending = null;
            state.setState(AssistResult.failed(message));
        }
    }

    private static String promptText(CaptureBundle bundle, AssistRequest request) {
        return bundle.hasText() ? bundle.text() : request.userInput();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String failureMessage(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof CaptureException || cause instanceof ModelException) {
            String message = cause.getMessage();
            if (message != null && !message.isBlank()) return message;
        }
        return "任务执行失败";
    }
}
