package com.dshgojava.assist.voice;

/** A microphone-to-text session, kept independent of Android for unit testing. */
public interface VoiceCapture {
    boolean available();

    /** Starts listening on the main thread; false when the engine cannot start. */
    boolean start(Listener listener);

    /** Stops accepting speech and waits for the recognizer's final result. */
    void stop();

    /** Drops the current capture without delivering a result. */
    void cancel();

    void release();

    interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onError(String message);
    }
}
