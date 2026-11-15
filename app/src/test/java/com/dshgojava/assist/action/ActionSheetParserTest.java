package com.dshgojava.assist.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.AssistAction;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionSheetParserTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-11-14T02:00:00Z");

    @Test
    void parsesSummaryAndDerivesNavigationCalendarAndDial() {
        String raw = """
                ```json
                {"summary":"人民广场附近有活动",
                 "address":"上海市黄浦区人民大道200号",
                 "time":"2026-11-15T19:00",
                 "title":"看展",
                 "phone":"+86 138-0013-8000"}
                ```
                """;

        ActionSheetParser.Result result = ActionSheetParser.parse(raw, ZONE, NOW);

        assertTrue(result.structured());
        assertEquals("人民广场附近有活动", result.text());
        assertEquals(List.of(AssistAction.Kind.NAVIGATE, AssistAction.Kind.CALENDAR,
                        AssistAction.Kind.DIAL),
                result.actions().stream().map(AssistAction::kind).toList());
        assertTrue(result.actions().get(0).value().startsWith("geo:0,0?q="));
        assertEquals("+8613800138000", result.actions().get(2).value());
        assertNotNull(CalendarDraft.fromPayload(result.actions().get(1).value()));
    }

    @Test
    void parsesExplicitActionArrayAndKeepsCoordinates() {
        String raw = """
                {"summary":"已找到",
                 "actions":[
                   {"kind":"navigate","label":"导航","destination":"外滩","lat":31.24,"lng":121.49},
                   {"kind":"calendar","label":"建日程","title":"晚餐",
                    "begin":"2026-11-15 18:30","end":"2026-11-15 20:00","location":"外滩"},
                   {"kind":"dial","label":"拨号","number":"10086"},
                   {"kind":"copy","label":"复制","text":"外滩"}
                 ]}
                """;

        ActionSheetParser.Result result = ActionSheetParser.parse(raw, ZONE, NOW);

        assertEquals(4, result.actions().size());
        assertEquals("geo:31.24,121.49?q=31.24,121.49(%E5%A4%96%E6%BB%A9)",
                result.actions().get(0).value());
        CalendarDraft calendar = CalendarDraft.fromPayload(result.actions().get(1).value());
        assertNotNull(calendar);
        assertEquals("晚餐", calendar.title());
        assertEquals("外滩", calendar.location());
        assertEquals(10086L, Long.parseLong(result.actions().get(2).value()));
        assertEquals("外滩", result.actions().get(3).value());
    }

    @Test
    void fallsBackToPlainTextExtraction() {
        String raw = "地址：上海市黄浦区人民大道200号\n时间：2026-11-15 19:00\n电话：13800138000";

        ActionSheetParser.Result result = ActionSheetParser.parse(raw, ZONE, NOW);

        assertFalse(result.structured());
        assertEquals(raw, result.text());
        assertEquals(3, result.actions().size());
    }

    @Test
    void malformedJsonDoesNotThrowOrInventActions() {
        ActionSheetParser.Result result = ActionSheetParser.parse(
                "{not json at all", ZONE, NOW);

        assertTrue(result.actions().isEmpty());
        assertEquals("{not json at all", result.text());
    }

    @Test
    void structuredObjectWithoutUsableFieldsIsExplicit() {
        ActionSheetParser.Result result = ActionSheetParser.parse(
                "{\"summary\":\"没有可执行信息\",\"address\":null,\"phone\":null}",
                ZONE, NOW);

        assertTrue(result.actions().isEmpty());
        assertEquals("没有可执行信息", result.text());
    }

    @Test
    void rejectsServiceCodesInsteadOfFabricatingDialActions() {
        ActionSheetParser.Result result = ActionSheetParser.parse(
                "{\"summary\":\"客服\",\"phone\":\"*100#\"}", ZONE, NOW);

        assertTrue(result.actions().isEmpty());
    }
}
