package com.dshgojava.assist.platform;

/** Pure geometry for the ball window and its dock. */
public final class BallGeometry {
    public static final float FLING_PX_PER_S = 1_200f;

    public enum Side { LEFT, RIGHT }

    public record WindowPos(int x, int y) { }

    public record SafeArea(int screenW, int screenH, int left, int top, int right, int bottom) { }

    public record Dock(Side side, float yFraction) {
        public static final Dock DEFAULT = new Dock(Side.RIGHT, 0.62f);
    }

    private BallGeometry() { }

    /** The window margin EdgeSnap keeps so the ball itself is {@code edge} inside the safe area. */
    public static int margin(int edge, int pad) {
        return edge - pad;
    }

    public static int centredX(int screenW, int windowW) {
        return (screenW - windowW) / 2;
    }

    public static int circleLeft(int left, int right, int ball, Side side) {
        return side == Side.RIGHT ? right - ball : left;
    }

    /** The ball window over a circle in the capsule surface. */
    public static WindowPos ballOver(int windowX, int windowY, int padX, int padTop,
                                     int circleLeft, int circleTop, int pad) {
        return new WindowPos(windowX + padX + circleLeft - pad, windowY + padTop + circleTop - pad);
    }

    public static int minX(SafeArea area, int margin) {
        return area.left() + margin;
    }

    public static int maxX(SafeArea area, int width, int margin) {
        return Math.max(minX(area, margin), area.screenW() - area.right() - margin - width);
    }

    public static int minY(SafeArea area, int margin) {
        return area.top() + margin;
    }

    public static int maxY(SafeArea area, int height, int margin) {
        return Math.max(minY(area, margin), area.screenH() - area.bottom() - margin - height);
    }

    public static Side sideFor(int x, int width, SafeArea area, float velocityX) {
        if (Math.abs(velocityX) >= FLING_PX_PER_S) {
            return velocityX < 0 ? Side.LEFT : Side.RIGHT;
        }
        float centre = x + width / 2f;
        return centre < area.screenW() / 2f ? Side.LEFT : Side.RIGHT;
    }

    public static Dock dockFor(int x, int y, int width, int height, SafeArea area,
                               int margin, float velocityX) {
        Side side = sideFor(x, width, area, velocityX);
        int low = minY(area, margin);
        int high = maxY(area, height, margin);
        float fraction = high <= low ? 0f : (float) (y - low) / (high - low);
        return new Dock(side, Math.max(0f, Math.min(1f, fraction)));
    }

    public static WindowPos position(Dock dock, int width, int height, SafeArea area, int margin) {
        int x = dock.side() == Side.LEFT ? minX(area, margin) : maxX(area, width, margin);
        int low = minY(area, margin);
        int high = maxY(area, height, margin);
        int y = low + Math.round((high - low) * Math.max(0f, Math.min(1f, dock.yFraction())));
        return new WindowPos(x, y);
    }

    public static WindowPos clampDrag(int x, int y, int width, int height, SafeArea area) {
        return new WindowPos(
                Math.max(0, Math.min(x, Math.max(0, area.screenW() - width))),
                Math.max(area.top(), Math.min(y, Math.max(area.top(), area.screenH() - area.bottom() - height))));
    }
}
