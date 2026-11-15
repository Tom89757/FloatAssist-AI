package com.dshgojava.assist.platform;

/** A captured viewport plus a local-frame fingerprint, valid for 30 seconds. */
public record ImageObservation(ImageGeometry geometry, long capturedUptimeMs,
                               VisualFrame frame) {
    public static final long TTL_MS = 30_000L;

    public boolean isFresh(long nowUptimeMs) {
        long age = nowUptimeMs - capturedUptimeMs;
        return age >= 0 && age <= TTL_MS;
    }

    public boolean sameViewportAs(ImageObservation current, long nowUptimeMs) {
        return current != null && isFresh(nowUptimeMs) && current.isFresh(nowUptimeMs)
                && current.capturedUptimeMs() >= capturedUptimeMs
                && geometry.equals(current.geometry());
    }

    public VisualFrame.Change changeFrom(ImageObservation previous) {
        if (previous == null || !geometry.equals(previous.geometry())) {
            return VisualFrame.Change.UNAVAILABLE;
        }
        return frame.changeFrom(previous.frame());
    }
}
