package com.dshgojava.assist.model;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Stateless model seam used by one quick-assist task. */
public interface ModelChannel {
    /** False means the UI must offer a settings path instead of pretending to run. */
    boolean ready();

    /**
     * Whether the configured model accepts image content. The default is false so
     * a new channel cannot accidentally advertise vision support.
     */
    default boolean supportsVision() {
        return false;
    }

    /**
     * Streams one completion. {@code onDelta} is invoked on the model's background
     * thread and may be invoked many times; the future completes with the full text.
     */
    CompletableFuture<String> complete(
            String prompt,
            List<PromptImage> images,
            Consumer<String> onDelta);
}
