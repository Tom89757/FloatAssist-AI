package com.dshgojava.assist.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoleGeneratorTest {
    @Test
    void parseFillsAllFields() {
        String json = "{\"expertise\":\"财报\",\"tone\":\"克制\","
                + "\"output\":\"先结论\",\"guardrails\":\"不编造\"}";
        RoleGenerator.Draft draft = RoleGenerator.parse("股票投资", json);

        assertEquals("财报", draft.expertise());
        assertEquals("克制", draft.tone());
        assertEquals("先结论", draft.output());
        assertEquals("不编造", draft.guardrails());
    }

    @Test
    void parseToleratesFencedCodeBlock() {
        String raw = "好的，以下是角色卡：\n```json\n{\"expertise\":\"翻译\",\"tone\":\"忠实\","
                + "\"output\":\"只输出译文\",\"guardrails\":\"不增删\"}\n```";
        RoleGenerator.Draft draft = RoleGenerator.parse("翻译", raw);

        assertEquals("翻译", draft.expertise());
        assertEquals("不增删", draft.guardrails());
    }

    @Test
    void parseFallsBackToEmptyFieldsOnBadJson() {
        RoleGenerator.Draft draft = RoleGenerator.parse("x", "not json at all");
        assertTrue(draft.expertise().isEmpty());
        assertTrue(draft.tone().isEmpty());
    }
}
