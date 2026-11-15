package com.dshgojava.assist.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChatCompletionJsonTest {
    @Test
    void readsStreamingDeltaContent() {
        String chunk = """
                {"id":"x","choices":[{"delta":{"content":"你好"},"finish_reason":null}]}
                """;

        assertEquals("你好", ChatCompletionJson.deltaText(chunk));
    }

    @Test
    void usageOnlyChunkAndEmptyDeltaHaveNoText() {
        assertNull(ChatCompletionJson.deltaText(
                "{\"choices\":[],\"usage\":{\"total_tokens\":3}}"));
        assertNull(ChatCompletionJson.deltaText(
                "{\"choices\":[{\"delta\":{}}]}"));
    }

    @Test
    void readsNonStreamingMessageContent() {
        String response = """
                {"choices":[{"message":{"role":"assistant","content":"完整回答"}}]}
                """;

        assertEquals("完整回答", ChatCompletionJson.completedText(response));
    }

    @Test
    void readsArrayContentParts() {
        String response = """
                {"choices":[{"message":{"content":[
                  {"type":"text","text":"a"},
                  {"type":"text","text":"b"}
                ]}}]}
                """;

        assertEquals("ab", ChatCompletionJson.completedText(response));
    }

    @Test
    void reportsProtocolErrorsExplicitly() {
        ModelException invalid = assertThrows(ModelException.class,
                () -> ChatCompletionJson.deltaText("not-json"));
        assertEquals(ModelException.Kind.PROTOCOL, invalid.kind());

        ModelException remote = assertThrows(ModelException.class,
                () -> ChatCompletionJson.deltaText(
                        "{\"error\":{\"message\":\"model overloaded\"}}"));
        assertEquals(ModelException.Kind.PROTOCOL, remote.kind());
        assertEquals("模型返回错误：model overloaded", remote.getMessage());
    }

    @Test
    void extractsApiErrorMessageWithoutThrowing() {
        assertEquals("bad key", ChatCompletionJson.errorMessage(
                "{\"error\":{\"message\":\"bad key\"}}"));
        assertEquals("plain", ChatCompletionJson.errorMessage(
                "{\"error\":\"plain\"}"));
        assertNull(ChatCompletionJson.errorMessage("<html>no json</html>"));
    }
}
