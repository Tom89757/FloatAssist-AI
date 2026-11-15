package com.dshgojava.assist.platform;

import android.graphics.Bitmap;

import java.util.function.BooleanSupplier;

/** No local OCR backend has been approved. This is not a successful empty recognition. */
public final class NoLocalOcr implements LocalOcr {
    public static final NoLocalOcr INSTANCE = new NoLocalOcr();

    private NoLocalOcr() { }

    @Override
    public LocalOcrResult.Reason unavailableReason() {
        return LocalOcrResult.Reason.NOT_INSTALLED;
    }

    @Override
    public LocalOcrResult recognize(Bitmap bitmap, long deadlineUptimeMs,
                                    BooleanSupplier cancelled) {
        return new LocalOcrResult.Unavailable(unavailableReason());
    }
}
