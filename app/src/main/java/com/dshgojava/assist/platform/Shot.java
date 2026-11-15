package com.dshgojava.assist.platform;

/** One screenshot result; local OCR text never leaves the process. */
public record Shot(byte[] bytes, String mime, ImageGeometry geometry, boolean secure,
                   String error, Long takenAtMillis, ImageObservation observation,
                   LocalOcrResult localText) {
    public Shot(byte[] bytes, String mime, ImageGeometry geometry, boolean secure,
                String error, Long takenAtMillis, ImageObservation observation) {
        this(bytes, mime, geometry, secure, error, takenAtMillis, observation,
                new LocalOcrResult.Unavailable(LocalOcrResult.Reason.NO_FRAME));
    }
}
