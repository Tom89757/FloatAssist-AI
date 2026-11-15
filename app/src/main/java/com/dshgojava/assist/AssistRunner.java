package com.dshgojava.assist;

/** Starts and controls exactly one gather-to-model quick-assist run. */
public interface AssistRunner {
    AssistRun start(QuickTask task, AssistRequest request);

    interface AssistRun extends AutoCloseable {
        StateStore<AssistResult> state();

        /** Requests cancellation; late source or model callbacks must be ignored. */
        void cancel();

        /** Releases the run. Equivalent to {@link #cancel()} unless already terminal. */
        @Override
        void close();
    }
}
