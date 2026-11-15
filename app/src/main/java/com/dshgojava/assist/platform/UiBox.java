package com.dshgojava.assist.platform;

/** Rectangle in screen pixels; right and bottom are exclusive. */
public record UiBox(int left, int top, int right, int bottom) {
    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public int centerX() {
        return (left + right) / 2;
    }

    public int centerY() {
        return (top + bottom) / 2;
    }

    public boolean isEmpty() {
        return width() <= 0 || height() <= 0;
    }

    public long area() {
        return isEmpty() ? 0L : (long) width() * height();
    }

    public boolean contains(int x, int y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    public double rowOverlap(UiBox other) {
        int overlap = Math.min(bottom, other.bottom) - Math.max(top, other.top);
        int shorter = Math.min(height(), other.height());
        return overlap <= 0 || shorter <= 0 ? 0.0 : (double) overlap / shorter;
    }

    public UiBox intersect(UiBox other) {
        return new UiBox(Math.max(left, other.left), Math.max(top, other.top),
                Math.min(right, other.right), Math.min(bottom, other.bottom));
    }

    @Override
    public String toString() {
        return left + "," + top + "," + right + "," + bottom;
    }
}
