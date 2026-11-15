package com.dshgojava.assist.model;

/** Explicit model failure with a stable kind for UI and retry decisions. */
public final class ModelException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        NOT_CONFIGURED,
        INVALID_CONFIG,
        VISION_UNSUPPORTED,
        HTTP,
        TRANSPORT,
        PROTOCOL,
        EMPTY_RESPONSE
    }

    private final Kind kind;
    private final int statusCode;

    public ModelException(Kind kind, String message) {
        this(kind, -1, message, null);
    }

    public ModelException(Kind kind, String message, Throwable cause) {
        this(kind, -1, message, cause);
    }

    public ModelException(Kind kind, int statusCode, String message) {
        this(kind, statusCode, message, null);
    }

    private ModelException(Kind kind, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.statusCode = statusCode;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status, or -1 when the failure was not an HTTP response. */
    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return kind == Kind.TRANSPORT
                || (kind == Kind.HTTP
                && (statusCode == 408 || statusCode == 429 || statusCode >= 500));
    }
}
