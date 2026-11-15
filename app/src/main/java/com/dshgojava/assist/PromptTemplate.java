package com.dshgojava.assist;

import com.dshgojava.assist.source.CaptureBundle;
import java.util.Objects;

/** Fills quick-task prompt templates without regular-expression replacement. */
public final class PromptTemplate {
    public static final String TEXT_PLACEHOLDER = "{{text}}";

    private PromptTemplate() { }

    public static String fill(String template, String text) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(text, "text");
        return template.replace(TEXT_PLACEHOLDER, text);
    }

    public static String fill(QuickTask task, CaptureBundle bundle) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(bundle, "bundle");
        return fill(task.promptTemplate(), bundle.text());
    }

    public static boolean hasTextPlaceholder(String template) {
        Objects.requireNonNull(template, "template");
        return template.contains(TEXT_PLACEHOLDER);
    }
}
