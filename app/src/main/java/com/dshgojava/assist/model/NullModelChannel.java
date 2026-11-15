package com.dshgojava.assist.model;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Explicit "not connected" channel; it never pretends a model is available. */
public final class NullModelChannel implements ModelChannel {
    public static final NullModelChannel INSTANCE = new NullModelChannel();

    public NullModelChannel() {
    }

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public boolean supportsVision() {
        return false;
    }

    @Override
    public CompletableFuture<String> complete(
            String prompt,
            List<PromptImage> images,
            Consumer<String> onDelta) {
        return CompletableFuture.failedFuture(new IllegalStateException("未配置模型"));
    }
}
