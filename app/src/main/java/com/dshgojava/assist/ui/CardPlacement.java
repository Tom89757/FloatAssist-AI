package com.dshgojava.assist.ui;

import com.dshgojava.assist.platform.BallGeometry;

/** Pure card geometry: safe-area width, side placement and drag-to-dismiss rule. */
public final class CardPlacement {
    public record Placement(int width, int height, int x, int y, int hiddenX) { }

    private CardPlacement() { }

    public static Placement place(BallGeometry.SafeArea area, BallGeometry.Side side,
                                  int gap, int preferredHeight) {
        int top = Math.max(area.top(), 0) + Math.max(gap, 0);
        int bottom = area.screenH() - Math.max(area.bottom(), 0) - Math.max(gap, 0);
        int availableHeight = Math.max(1, bottom - top);
        int height = Math.max(1, Math.min(preferredHeight, availableHeight));
        int y = top + Math.max(0, (availableHeight - height) / 2);

        int width = Math.max(1,
                area.screenW() - Math.max(area.left(), 0) - Math.max(area.right(), 0)
                        - 2 * Math.max(gap, 0));
        int x = side == BallGeometry.Side.LEFT
                ? Math.max(area.left(), 0) + Math.max(gap, 0)
                : area.screenW() - Math.max(area.right(), 0) - Math.max(gap, 0) - width;
        int hiddenX = side == BallGeometry.Side.LEFT ? x - width - gap : x + width + gap;
        return new Placement(width, height, x, y, hiddenX);
    }

    public static boolean shouldDismiss(float distance, float velocity, int threshold) {
        return distance >= threshold || velocity >= 1_500f;
    }

    /** Moves the card above the keyboard, clamped inside the safe area. */
    public static int liftedY(int baseY, int height, int minY, int maxBottom, int imeBottom) {
        int maxY = Math.max(minY, maxBottom - Math.max(0, imeBottom) - height);
        return Math.max(minY, Math.min(baseY, maxY));
    }
}
