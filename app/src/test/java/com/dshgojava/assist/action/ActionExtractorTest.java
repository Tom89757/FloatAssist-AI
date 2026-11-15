package com.dshgojava.assist.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ActionExtractorTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-11-14T02:00:00Z");

    @Test
    void extractsLabelledAddressTimeAndMobile() {
        ActionExtractor.Found found = ActionExtractor.extract(
                "活动地点：上海市黄浦区人民大道200号 时间：2026-11-15 19:00 电话：13800138000",
                ZONE, NOW);

        assertEquals("上海市黄浦区人民大道200号", found.address());
        assertEquals("2026-11-15 19:00", found.timeText());
        assertNotNull(found.time());
        assertEquals("13800138000", found.phone());
    }

    @Test
    void extractsLandlineAndInternationalNumbersWithoutServiceCodes() {
        assertEquals("02112345678", ActionExtractor.extract(
                "客服 021-12345678", ZONE, NOW).phone());
        assertNull(ActionExtractor.extract("服务码 *100#", ZONE, NOW).phone());
    }

    @Test
    void ignoresOrdinaryConversationAndEmptyInput() {
        ActionExtractor.Found found = ActionExtractor.extract("今天天气不错，我们晚点再聊。", ZONE, NOW);
        assertNull(found.address());
        assertNull(found.phone());
        assertEquals(null, found.timeText());
        assertEquals(null, ActionExtractor.extract("", ZONE, NOW).address());
    }
}
