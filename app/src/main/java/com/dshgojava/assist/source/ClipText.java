package com.dshgojava.assist.source;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Normalizes clipboard items without silently sending an unbounded payload. */
final class ClipText {
    static final int MAX_CHARS = 100_000;

    private ClipText() { }

    static String join(List<? extends CharSequence> values) {
        if (values == null || values.isEmpty()) {
            throw new CaptureException("剪贴板为空或系统限制读取");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (CharSequence value : values) {
            if (value == null) continue;
            String normalized = value.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .strip();
            if (!normalized.isBlank()) unique.add(normalized);
        }
        String joined = String.join("\n\n", unique);
        if (joined.isBlank()) {
            throw new CaptureException("剪贴板为空或没有可发送的文本");
        }
        if (joined.length() > MAX_CHARS) {
            throw new CaptureException("剪贴板内容过长，无法安全处理");
        }
        return joined;
    }
}
