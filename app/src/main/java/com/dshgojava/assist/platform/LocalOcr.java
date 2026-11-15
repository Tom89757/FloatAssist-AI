package com.dshgojava.assist.platform;

import android.graphics.Bitmap;

/** Borrows the existing screenshot synchronously; implementations must not retain or recycle it. */
public interface LocalOcr {
    LocalOcrResult.Reason unavailableReason();

    LocalOcrResult recognize(Bitmap bitmap, long deadlineUptimeMs,
                             java.util.function.BooleanSupplier cancelled);
}
