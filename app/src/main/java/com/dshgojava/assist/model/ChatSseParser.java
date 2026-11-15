package com.dshgojava.assist.model;

import java.util.ArrayList;
import java.util.List;

/** Incremental parser for server-sent events used by Chat Completions streams. */
public final class ChatSseParser {
    private final List<String> data = new ArrayList<>();
    private String event = "message";
    private boolean firstLine = true;

    /** A complete SSE event. Multiple data fields are joined with newlines. */
    public record Event(String event, String data) {
        public Event {
            event = event == null || event.isBlank() ? "message" : event;
            data = data == null ? "" : data;
        }
    }

    /**
     * Feeds one line, without its line terminator. Returns a complete event only
     * when a blank line dispatches it; otherwise returns null.
     */
    public Event accept(String rawLine) {
        String line = rawLine;
        if (line != null && line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line != null && firstLine && line.startsWith("\uFEFF")) {
            line = line.substring(1);
        }
        firstLine = false;
        if (line == null || line.isEmpty()) {
            return dispatch();
        }
        if (line.startsWith(":")) {
            return null;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if ("event".equals(field)) {
            event = value;
        } else if ("data".equals(field)) {
            data.add(value);
        }
        return null;
    }

    /** Dispatches a final event when a stream ends without a trailing blank line. */
    public Event flush() {
        return dispatch();
    }

    private Event dispatch() {
        if (data.isEmpty()) {
            event = "message";
            return null;
        }
        Event complete = new Event(event, String.join("\n", data));
        data.clear();
        event = "message";
        return complete;
    }
}
