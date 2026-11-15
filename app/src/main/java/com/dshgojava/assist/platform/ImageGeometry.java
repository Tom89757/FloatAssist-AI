package com.dshgojava.assist.platform;

/** Maps captured-image pixels back to screen pixels for a window or display viewport. */
public record ImageGeometry(int originX, int originY, float scaleX, float scaleY,
                            int width, int height) {
    public int[] toScreen(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height || scaleX <= 0f || scaleY <= 0f) {
            return null;
        }
        return new int[]{
                Math.round(originX + x / scaleX),
                Math.round(originY + y / scaleY)
        };
    }
}
