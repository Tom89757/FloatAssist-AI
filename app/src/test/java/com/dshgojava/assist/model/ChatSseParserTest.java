package com.dshgojava.assist.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ChatSseParserTest {
    @Test
    void dispatchesMultilineDataAndIgnoresComments() {
        ChatSseParser parser = new ChatSseParser();

        assertNull(parser.accept(": keep-alive"));
        assertNull(parser.accept("event: delta"));
        assertNull(parser.accept("data: first"));
        assertNull(parser.accept("data: second"));

        ChatSseParser.Event event = parser.accept("");

        assertEquals("delta", event.event());
        assertEquals("first\nsecond", event.data());
    }

    @Test
    void stripsCarriageReturnsAndLeadingDataSpace() {
        ChatSseParser parser = new ChatSseParser();

        assertNull(parser.accept("data:  two spaces\r"));

        ChatSseParser.Event event = parser.flush();

        assertEquals("message", event.event());
        assertEquals(" two spaces", event.data());
    }

    @Test
    void flushEmitsPendingDataWhenStreamEndsWithoutBlankLine() {
        ChatSseParser parser = new ChatSseParser();

        assertNull(parser.accept("data: [DONE]"));

        ChatSseParser.Event event = parser.flush();

        assertEquals("[DONE]", event.data());
    }

    @Test
    void blankLineWithoutDataResetsCustomEventName() {
        ChatSseParser parser = new ChatSseParser();

        assertNull(parser.accept("event: chunk"));
        assertNull(parser.accept(""));
        assertNull(parser.accept("data: value"));

        ChatSseParser.Event event = parser.accept("");

        assertEquals("message", event.event());
        assertEquals("value", event.data());
    }

    @Test
    void stripsBomFromTheFirstDataLine() {
        ChatSseParser parser = new ChatSseParser();

        assertNull(parser.accept("\uFEFFdata: hello"));

        assertEquals("hello", parser.flush().data());
    }
}
