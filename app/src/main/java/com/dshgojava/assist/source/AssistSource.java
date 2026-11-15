package com.dshgojava.assist.source;

import com.dshgojava.assist.AssistRequest;
import com.dshgojava.assist.SourceKind;
import java.util.concurrent.CompletableFuture;

/** Material gathered for one quick-assist task. */
public interface AssistSource {
    SourceKind kind();

    /** False means the UI must show this source as unavailable, never run a fake task. */
    boolean available();

    /** Runs all IO and Android framework work away from the main thread. */
    CompletableFuture<CaptureBundle> gather(AssistRequest request);
}
