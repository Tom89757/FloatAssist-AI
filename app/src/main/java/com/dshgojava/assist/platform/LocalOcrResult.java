package com.dshgojava.assist.platform;

import java.util.List;

/** Local-only OCR output; none of these records enter ScreenSnapshot. */
public sealed interface LocalOcrResult
        permits LocalOcrResult.Line, LocalOcrResult.Recognized,
                LocalOcrResult.Unavailable, LocalOcrResult.Failed {
    enum Reason { NOT_REQUESTED, NOT_INSTALLED, NO_FRAME, PRIVACY_UNVERIFIED, CANCELLED, DEADLINE }

    enum Failure { ENGINE, INVALID_RESULT, RESULT_LIMIT }

    record Line(String text, int left, int top, int right, int bottom, Float confidence)
            implements LocalOcrResult {
        public Line(String text, int left, int top, int right, int bottom) {
            this(text, left, top, right, bottom, null);
        }
    }

    record Recognized(List<Line> lines) implements LocalOcrResult {
        public Recognized {
            lines = List.copyOf(lines);
        }
    }

    record Unavailable(Reason reason) implements LocalOcrResult { }

    record Failed(Failure reason) implements LocalOcrResult { }
}
