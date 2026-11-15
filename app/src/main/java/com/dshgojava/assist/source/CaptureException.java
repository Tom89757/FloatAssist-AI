package com.dshgojava.assist.source;

/** A user-visible source failure; callers must surface the message instead of degrading silently. */
public final class CaptureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CaptureException(String message) {
        super(message);
    }

    public CaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
