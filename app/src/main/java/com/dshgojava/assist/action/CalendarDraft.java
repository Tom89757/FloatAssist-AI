package com.dshgojava.assist.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.ZoneId;

/** Validated calendar payload carried by an {@code AssistAction}. */
public record CalendarDraft(
        String title,
        long beginMs,
        long endMs,
        boolean allDay,
        String location
) {
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    public CalendarDraft {
        title = title == null ? "" : title.strip();
        if (title.isEmpty()) throw new IllegalArgumentException("title must not be blank");
        if (beginMs <= 0) throw new IllegalArgumentException("beginMs must be positive");
        if (endMs < beginMs) throw new IllegalArgumentException("endMs must not precede beginMs");
        location = location == null ? "" : location.strip();
    }

    public static CalendarDraft create(
            String title,
            ActionTime.Parsed begin,
            String endText,
            Boolean allDay,
            String location,
            ZoneId zone,
            Instant now) {
        if (begin == null) return null;
        boolean wholeDay = allDay == null ? begin.dateOnly() : allDay;
        long endMs = begin.epochMs() + (wholeDay ? DAY_MS : HOUR_MS);
        ActionTime.Parsed end = ActionTime.parse(endText, zone, now);
        if (end != null && end.epochMs() >= begin.epochMs()) endMs = end.epochMs();
        String safeTitle = title == null || title.isBlank() ? "提醒" : title.strip();
        try {
            return new CalendarDraft(safeTitle, begin.epochMs(), endMs, wholeDay, location);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public String payload() {
        JsonObject object = new JsonObject();
        object.addProperty("title", title);
        object.addProperty("beginMs", beginMs);
        object.addProperty("endMs", endMs);
        object.addProperty("allDay", allDay);
        object.addProperty("location", location);
        return object.toString();
    }

    public static CalendarDraft fromPayload(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) return null;
            JsonObject object = parsed.getAsJsonObject();
            String title = string(object, "title");
            Long begin = number(object, "beginMs");
            Long end = number(object, "endMs");
            if (title == null || begin == null || end == null) return null;
            Boolean allDay = bool(object, "allDay");
            String location = string(object, "location");
            return new CalendarDraft(title, begin, end, Boolean.TRUE.equals(allDay), location);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? null : value.getAsString();
    }

    private static Long number(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
        try {
            return value.getAsLong();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Boolean bool(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
        try {
            return value.getAsBoolean();
        } catch (RuntimeException error) {
            return null;
        }
    }
}
