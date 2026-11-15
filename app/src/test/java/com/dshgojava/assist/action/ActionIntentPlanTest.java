package com.dshgojava.assist.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.AssistAction;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ActionIntentPlanTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-11-14T02:00:00Z");

    @Test
    void plansAFixedGeoUriForPlainAddresses() {
        ActionIntents.Plan plan = ActionIntents.plan(
                AssistAction.navigate("导航", "上海市黄浦区人民大道200号"));

        assertNotNull(plan);
        assertEquals(AssistAction.Kind.NAVIGATE, plan.kind());
        assertEquals("geo:0,0?q=%E4%B8%8A%E6%B5%B7%E5%B8%82%E9%BB%84%E6%B5%A6%E5%8C%BA"
                + "%E4%BA%BA%E6%B0%91%E5%A4%A7%E9%81%93200%E5%8F%B7", plan.uri());
    }

    @Test
    void plansDialAndNormalisesSafeFormatting() {
        ActionIntents.Plan plan = ActionIntents.plan(
                AssistAction.dial("拨号", "+86 138-0013-8000"));

        assertNotNull(plan);
        assertEquals("tel:+8613800138000", plan.uri());
        assertNull(ActionIntents.plan(AssistAction.dial("拨号", "*100#")));
    }

    @Test
    void plansCalendarFromTheValidatedPayload() {
        ActionTime.Parsed begin = ActionTime.parse("2026-11-15 19:00", ZONE, NOW);
        CalendarDraft draft = CalendarDraft.create(
                "看展", begin, null, null, "人民广场", ZONE, NOW);
        assertNotNull(draft);

        ActionIntents.Plan plan = ActionIntents.plan(
                AssistAction.calendar("建日程", draft.payload()));

        assertNotNull(plan);
        assertEquals(AssistAction.Kind.CALENDAR, plan.kind());
        assertEquals("看展", plan.calendar().title());
        assertEquals(draft.beginMs(), plan.calendar().beginMs());
        assertEquals("人民广场", plan.calendar().location());
    }

    @Test
    void copyStaysInTheCardAndInvalidCalendarDoesNotLaunch() {
        ActionIntents.Plan copy = ActionIntents.plan(AssistAction.copy("复制", "hello"));
        assertNotNull(copy);
        assertEquals("hello", copy.copyText());

        assertNull(ActionIntents.plan(AssistAction.calendar("建日程", "not-a-payload")));
    }

    @Test
    void arbitrarySchemesBecomeHarmlessGeoQueries() {
        ActionIntents.Plan plan = ActionIntents.plan(
                AssistAction.navigate("导航", "intent://evil#Intent;end"));

        assertNotNull(plan);
        assertTrue(plan.uri().startsWith("geo:0,0?q="));
    }
}
