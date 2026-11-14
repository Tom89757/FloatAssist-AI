package com.dshgojava.assist;

import java.util.Objects;

/**
 * A structured action offered below a result card. {@code value} carries the
 * navigation URI, calendar payload, phone number or text to copy, depending on
 * {@link Kind}.
 */
public record AssistAction(Kind kind, String label, String value) {
    public enum Kind {
        NAVIGATE,
        CALENDAR,
        DIAL,
        COPY
    }

    public AssistAction {
        kind = Objects.requireNonNull(kind, "kind");
        label = label == null ? "" : label;
        value = value == null ? "" : value;
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static AssistAction navigate(String label, String uri) {
        return new AssistAction(Kind.NAVIGATE, label, uri);
    }

    public static AssistAction calendar(String label, String payload) {
        return new AssistAction(Kind.CALENDAR, label, payload);
    }

    public static AssistAction dial(String label, String number) {
        return new AssistAction(Kind.DIAL, label, number);
    }

    public static AssistAction copy(String label, String text) {
        return new AssistAction(Kind.COPY, label, text);
    }
}
