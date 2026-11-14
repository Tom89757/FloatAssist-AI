package com.dshgojava.assist;

import java.util.Objects;

/** Immutable declaration of one quick-assist task. */
public record QuickTask(
        String id,
        String title,
        SourceKind source,
        OutputKind output,
        String promptTemplate,
        boolean needsVision
) {
    public QuickTask {
        id = requireText(id, "id");
        title = requireText(title, "title");
        source = Objects.requireNonNull(source, "source");
        output = Objects.requireNonNull(output, "output");
        promptTemplate = Objects.requireNonNull(promptTemplate, "promptTemplate");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
