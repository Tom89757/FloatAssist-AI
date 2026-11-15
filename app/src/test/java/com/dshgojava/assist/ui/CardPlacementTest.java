package com.dshgojava.assist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.platform.BallGeometry;
import org.junit.jupiter.api.Test;

class CardPlacementTest {
    private static final BallGeometry.SafeArea AREA =
            new BallGeometry.SafeArea(1080, 2400, 0, 72, 0, 120);

    @Test
    void rightCardLeavesSixteenDpOnBothSides() {
        CardPlacement.Placement at = CardPlacement.place(
                AREA, BallGeometry.Side.RIGHT, 32, 1200);

        assertEquals(1080 - 64, at.width());
        assertEquals(32, at.x());
        assertEquals(1048 + 32, at.hiddenX());
        assertEquals(1200, at.height());
        assertTrue(at.y() >= AREA.top() + 32);
    }

    @Test
    void leftCardEntersFromLeft() {
        CardPlacement.Placement at = CardPlacement.place(
                AREA, BallGeometry.Side.LEFT, 32, 1200);

        assertEquals(32, at.x());
        assertEquals(32 - at.width() - 32, at.hiddenX());
    }

    @Test
    void heightClampsInsideSafeArea() {
        CardPlacement.Placement at = CardPlacement.place(
                AREA, BallGeometry.Side.RIGHT, 32, 99_999);

        assertEquals(AREA.screenH() - AREA.top() - AREA.bottom() - 64, at.height());
        assertTrue(at.y() + at.height() <= AREA.screenH() - AREA.bottom() - 32);
    }

    @Test
    void dragDismissUsesDistanceOrFastDownwardVelocity() {
        assertFalse(CardPlacement.shouldDismiss(20f, 300f, 100));
        assertTrue(CardPlacement.shouldDismiss(100f, 0f, 100));
        assertTrue(CardPlacement.shouldDismiss(10f, 1_600f, 100));
        assertFalse(CardPlacement.shouldDismiss(10f, -2_000f, 100));
    }

    @Test
    void imeLiftMovesAboveKeyboardAndClampsAtTop() {
        assertEquals(600, CardPlacement.liftedY(600, 800, 72, 2280, 0));
        assertEquals(880, CardPlacement.liftedY(1_900, 800, 72, 2280, 600));
        assertEquals(480, CardPlacement.liftedY(600, 800, 72, 2280, 1_000));
        assertEquals(72, CardPlacement.liftedY(600, 800, 72, 900, 2_000));
    }
}
