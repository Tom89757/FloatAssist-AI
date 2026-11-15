package com.dshgojava.assist.source;

import com.dshgojava.assist.model.PromptImage;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable source output; at least one of text or images is present. */
public record CaptureBundle(
        String text,
        List<PromptImage> images,
        Map<String, String> meta
) {
    public CaptureBundle {
        text = text == null ? "" : text;
        images = images == null ? List.of() : List.copyOf(images);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
        if (text.isBlank() && images.isEmpty()) {
            throw new IllegalArgumentException("capture must contain text or an image");
        }
        for (PromptImage image : images) {
            Objects.requireNonNull(image, "image");
        }
    }

    public static CaptureBundle text(String text, Map<String, String> meta) {
        return new CaptureBundle(text, List.of(), meta);
    }

    public boolean hasText() {
        return !text.isBlank();
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }
}
