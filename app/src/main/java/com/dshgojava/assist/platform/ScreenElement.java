package com.dshgojava.assist.platform;

/** One numbered action target for the current snapshot only. */
public record ScreenElement(
        int id,
        int windowId,
        String packageName,
        int ref,
        ElementKind kind,
        String role,
        String label,
        String fullText,
        UiBox bounds,
        boolean clickable,
        boolean longClickable,
        boolean editable,
        boolean password,
        boolean secret,
        boolean checkable,
        int scroll,
        boolean horizontal,
        String viewId,
        String sensitiveWord,
        Integer listId,
        Integer parentId,
        String fingerprint,
        String key,
        String observationKey) { }
