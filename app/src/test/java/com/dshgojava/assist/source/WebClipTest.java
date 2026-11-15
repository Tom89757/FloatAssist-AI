package com.dshgojava.assist.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WebClipTest {
    @Test
    void findsSchemeUrlInsideText() {
        assertEquals("https://example.com/a?b=1",
                WebClip.firstUrl("看这个 https://example.com/a?b=1 很不错"));
    }

    @Test
    void prefixesBareHostWithHttps() {
        assertEquals("https://example.com", WebClip.firstUrl("example.com"));
        assertEquals("https://www.example.com/path", WebClip.firstUrl("www.example.com/path"));
    }

    @Test
    void stripsTrailingPunctuation() {
        assertEquals("https://example.com", WebClip.firstUrl("https://example.com。"));
        assertEquals("https://example.com", WebClip.firstUrl("（https://example.com）"));
    }

    @Test
    void returnsNullWhenNoUrl() {
        assertNull(WebClip.firstUrl("今天天气不错"));
        assertNull(WebClip.firstUrl(""));
        assertNull(WebClip.firstUrl(null));
    }
}
