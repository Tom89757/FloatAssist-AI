package com.dshgojava.assist.source;

/** Pure power-of-two sampling decisions used by the Android bitmap downsampler. */
public final class ImageSampling {
    public record Size(int width, int height) {
        public Size {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("dimensions must be positive");
            }
        }
    }

    private ImageSampling() { }

    public static int sampleSizeFor(int width, int height, int maxEdge) {
        validate(width, height, maxEdge);
        int sample = 1;
        while (Math.max(width / (sample * 2), height / (sample * 2)) >= maxEdge) {
            sample *= 2;
        }
        return sample;
    }

    public static Size scaledSize(int width, int height, int maxEdge) {
        validate(width, height, maxEdge);
        int longest = Math.max(width, height);
        if (longest <= maxEdge) return new Size(width, height);
        double ratio = (double) maxEdge / longest;
        return new Size(Math.max(1, (int) Math.round(width * ratio)),
                Math.max(1, (int) Math.round(height * ratio)));
    }

    private static void validate(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (maxEdge <= 0) throw new IllegalArgumentException("maxEdge must be positive");
    }
}
