package com.dshgojava.assist.source;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** CompletableFuture helpers that work on the app's API 26 floor. */
final class SourceFutures {
    private SourceFutures() { }

    static <T> CompletableFuture<T> supply(Executor executor, Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor);
    }

    static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
