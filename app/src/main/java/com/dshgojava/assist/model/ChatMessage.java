package com.dshgojava.assist.model;

import java.util.List;
import java.util.Objects;

/** One conversation turn sent to a model; roles follow the Chat Completions contract. */
public record ChatMessage(String role, String text, List<PromptImage> images) {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    public ChatMessage {
        role = role == null || role.isBlank() ? ROLE_USER : role.strip();
        text = text == null ? "" : text;
        images = images == null ? List.of() : List.copyOf(images);
        for (PromptImage image : images) {
            Objects.requireNonNull(image, "image");
        }
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(ROLE_SYSTEM, text, List.of());
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(ROLE_USER, text, List.of());
    }

    public static ChatMessage user(String text, List<PromptImage> images) {
        return new ChatMessage(ROLE_USER, text, images);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(ROLE_ASSISTANT, text, List.of());
    }
}
