package com.dshgojava.assist.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ActionTimeTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-11-14T02:00:00Z");

    @Test
    void parsesIsoChineseAndRelativeDateTimes() {
        long expected = LocalDateTime.of(2026, 11, 15, 19, 0)
                .atZone(ZONE).toInstant().toEpochMilli();

        assertEquals(expected, ActionTime.parse("2026-11-15T19:00", ZONE, NOW).epochMs());
        assertEquals(expected, ActionTime.parse("2026年11月15日 19:00", ZONE, NOW).epochMs());
        assertEquals(expected, ActionTime.parse("明天 19:00", ZONE, NOW).epochMs());
        assertEquals(expected, ActionTime.parse("11月15日 19:00", ZONE, NOW).epochMs());
        assertFalse(ActionTime.parse("2026-11-15T19:00", ZONE, NOW).dateOnly());
    }

    @Test
    void recognisesDateOnlyAndChineseMeridiem() {
        ActionTime.Parsed date = ActionTime.parse("2026-11-15", ZONE, NOW);
        assertTrue(date.dateOnly());
        assertEquals(LocalDateTime.of(2026, 11, 15, 0, 0)
                .atZone(ZONE).toInstant().toEpochMilli(), date.epochMs());

        ActionTime.Parsed afternoon = ActionTime.parse("明天下午3点", ZONE, NOW);
        assertEquals(LocalDateTime.of(2026, 11, 15, 15, 0)
                .atZone(ZONE).toInstant().toEpochMilli(), afternoon.epochMs());
    }

    @Test
    void timeOnlyRollsForwardWhenItHasAlreadyPassed() {
        ActionTime.Parsed next = ActionTime.parse("09:00", ZONE, NOW);
        assertEquals(LocalDateTime.of(2026, 11, 15, 9, 0)
                .atZone(ZONE).toInstant().toEpochMilli(), next.epochMs());
    }

    @Test
    void rejectsMissingAndImpossibleValues() {
        assertNull(ActionTime.parse(null, ZONE, NOW));
        assertNull(ActionTime.parse("", ZONE, NOW));
        assertNull(ActionTime.parse("不是时间", ZONE, NOW));
        assertNull(ActionTime.parse("2026-02-30 10:00", ZONE, NOW));
        assertNull(ActionTime.parse("25:99", ZONE, NOW));
    }
}
