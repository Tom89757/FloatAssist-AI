package com.dshgojava.assist.platform;

/** A 48x48 luminance sample; never retains a complete bitmap. */
public final class VisualFrame {
    public enum Change { CHANGED, UNCHANGED, UNAVAILABLE }

    @FunctionalInterface
    public interface RowReader {
        void readRow(int y, int[] row);
    }

    private static final int GRID = 48;
    private final int width;
    private final int height;
    private final int[] samples;

    private VisualFrame(int width, int height, int[] samples) {
        this.width = width;
        this.height = height;
        this.samples = samples;
    }

    public Change changeFrom(VisualFrame other) {
        if (other == null || width != other.width || height != other.height) {
            return Change.UNAVAILABLE;
        }
        int changed = 0;
        for (int i = 0; i < samples.length; i++) {
            if (Math.abs(samples[i] - other.samples[i]) >= 16) changed++;
        }
        return changed * 100 >= samples.length ? Change.CHANGED : Change.UNCHANGED;
    }

    public static VisualFrame capture(int width, int height, RowReader reader) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bitmap dimensions must be positive");
        }
        int[] row = new int[width];
        int[] samples = new int[GRID * GRID];
        int lastY = -1;
        for (int sampleY = 0; sampleY < GRID; sampleY++) {
            int y = sampleY * height / GRID;
            if (y != lastY) {
                reader.readRow(y, row);
                lastY = y;
            }
            for (int x = 0; x < GRID; x++) {
                int pixel = row[x * width / GRID];
                samples[sampleY * GRID + x] = (((pixel >>> 16) & 255) * 77
                        + ((pixel >>> 8) & 255) * 150 + (pixel & 255) * 29) >>> 8;
            }
        }
        return new VisualFrame(width, height, samples);
    }
}
