package com.dshgojava.assist.model;

import java.util.Objects;

/** Immutable image payload sent to a model channel. */
public record PromptImage(String mediaType, byte[] data, String name) {
    public PromptImage {
        mediaType = requireText(mediaType, "mediaType");
        data = Objects.requireNonNull(data, "data").clone();
        if (data.length == 0) {
            throw new IllegalArgumentException("image data must not be empty");
        }
        name = name == null || name.isBlank() ? "image" : name.strip();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public int size() {
        return data.length;
    }

    public static PromptImage jpeg(byte[] data, String name) {
        return new PromptImage("image/jpeg", data, name);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
