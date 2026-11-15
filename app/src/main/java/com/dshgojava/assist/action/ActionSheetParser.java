package com.dshgojava.assist.action;

import com.dshgojava.assist.AssistAction;
import com.dshgojava.assist.platform.NavUris;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a model answer into a short display summary and safe action buttons.
 * It accepts a strict JSON object, a JSON array of actions, or plain text with
 * an address, time, and/or phone number. Malformed JSON never throws.
 */
public final class ActionSheetParser {
    public record Result(String text, List<AssistAction> actions, boolean structured) {
        public Result {
            text = text == null ? "" : text.strip();
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    private ActionSheetParser() { }

    public static Result parse(String raw) {
        return parse(raw, ZoneId.systemDefault(), Instant.now());
    }

    public static Result parse(String raw, ZoneId zone, Instant now) {
        String plain = raw == null ? "" : raw.strip();
        if (plain.isEmpty()) return new Result("", List.of(), false);

        JsonElement root = parseJson(plain);
        boolean structured = root != null;
        String summary = null;
        String address = null;
        String timeText = null;
        String phone = null;
        String title = null;
        String location = null;
        Double lat = null;
        Double lng = null;
        List<AssistAction> explicit = new ArrayList<>();

        if (root != null && root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            summary = firstText(object, "summary", "answer", "message");
            address = firstText(object, "address", "destination", "place");
            location = firstText(object, "location", "place", "address");
            timeText = firstText(object, "time", "begin", "start", "datetime");
            phone = firstText(object, "phone", "tel", "telephone", "number");
            title = firstText(object, "title", "event");
            lat = coordinate(object, "lat", "latitude");
            lng = coordinate(object, "lng", "lon", "longitude");
            parseActions(object.get("actions"), explicit, title, zone, now);
            if (object.has("kind")) parseAction(object, explicit, title, zone, now);
        } else if (root != null && root.isJsonArray()) {
            JsonArray array = root.getAsJsonArray();
            for (JsonElement item : array) {
                if (item != null && item.isJsonObject()) {
                    parseAction(item.getAsJsonObject(), explicit, null, zone, now);
                }
            }
        }

        String extractionSource = structured ? (summary == null ? "" : summary) : plain;
        ActionExtractor.Found found = ActionExtractor.extract(extractionSource, zone, now);
        if (isBlank(address)) address = found.address();
        if (isBlank(timeText)) timeText = found.timeText();
        if (isBlank(phone)) phone = found.phone();
        if (isBlank(location)) location = address;

        Map<String, AssistAction> actions = new LinkedHashMap<>();
        for (AssistAction action : explicit) add(actions, action);

        if (!hasKind(actions, AssistAction.Kind.NAVIGATE) && !isBlank(address)) {
            add(actions, AssistAction.navigate("", NavUris.geo(address, lat, lng)));
        }

        ActionTime.Parsed parsedTime = isBlank(timeText)
                ? null : ActionTime.parse(timeText, zone, now);
        if (!hasKind(actions, AssistAction.Kind.CALENDAR) && parsedTime != null) {
            String eventTitle = !isBlank(title)
                    ? title : (!isBlank(summary) ? summary : "提醒");
            CalendarDraft draft = CalendarDraft.create(
                    eventTitle, parsedTime, null, null, location, zone, now);
            if (draft != null) add(actions, AssistAction.calendar("", draft.payload()));
        }

        if (!hasKind(actions, AssistAction.Kind.DIAL) && !isBlank(phone)) {
            String number = PhoneNumbers.normalize(phone);
            if (number != null) add(actions, AssistAction.dial("", number));
        }

        List<AssistAction> resultActions = List.copyOf(actions.values());
        String text;
        if (!isBlank(summary)) {
            text = summary;
        } else if (structured) {
            text = "";
        } else {
            text = plain;
        }
        return new Result(text, resultActions, structured);
    }

    private static void parseActions(
            JsonElement element,
            List<AssistAction> out,
            String fallbackTitle,
            ZoneId zone,
            Instant now) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && item.isJsonObject()) {
                    parseAction(item.getAsJsonObject(), out, fallbackTitle, zone, now);
                }
            }
        } else if (element.isJsonObject()) {
            parseAction(element.getAsJsonObject(), out, fallbackTitle, zone, now);
        }
    }

    private static void parseAction(
            JsonObject action,
            List<AssistAction> out,
            String fallbackTitle,
            ZoneId zone,
            Instant now) {
        String kind = lower(firstText(action, "kind", "type"));
        String value = firstText(action, "value", "text");
        String label = firstText(action, "label", "name");
        if (kind == null) {
            if (!isBlank(firstText(action, "destination", "address", "lat", "lng"))) {
                kind = "navigate";
            } else if (!isBlank(firstText(action, "begin", "time", "start"))) {
                kind = "calendar";
            } else if (!isBlank(firstText(action, "number", "phone", "tel"))) {
                kind = "dial";
            } else if (!isBlank(value)) {
                kind = "copy";
            }
        }
        if (kind == null) return;

        switch (kind) {
            case "navigate", "navigation", "map" -> {
                String destination = firstText(
                        action, "destination", "address", "location", "place", "value", "uri");
                Double lat = coordinate(action, "lat", "latitude");
                Double lng = coordinate(action, "lng", "lon", "longitude");
                String uri = firstText(action, "uri", "url");
                if (!isBlank(uri) && safeNavigationUri(uri)) {
                    out.add(AssistAction.navigate(label, uri));
                } else if (!isBlank(destination) || (lat != null && lng != null)) {
                    out.add(AssistAction.navigate(label,
                            NavUris.geo(destination, lat, lng)));
                }
            }
            case "calendar", "schedule", "event" -> {
                String beginText = firstText(action, "begin", "time", "start", "datetime", "value");
                ActionTime.Parsed begin = ActionTime.parse(beginText, zone, now);
                if (begin == null) return;
                String eventTitle = firstText(action, "title", "event", "summary", "label");
                if (isBlank(eventTitle)) eventTitle = fallbackTitle;
                String endText = firstText(action, "end", "finish");
                Boolean allDay = booleanValue(action, "allDay", "all_day");
                String location = firstText(action, "location", "address", "place");
                CalendarDraft draft = CalendarDraft.create(
                        eventTitle, begin, endText, allDay, location, zone, now);
                if (draft != null) out.add(AssistAction.calendar(label, draft.payload()));
            }
            case "dial", "call", "phone" -> {
                String number = firstText(action, "number", "phone", "tel", "value");
                String normalized = PhoneNumbers.normalize(number);
                if (normalized != null) out.add(AssistAction.dial(label, normalized));
            }
            case "copy", "clipboard" -> {
                String copyText = firstText(action, "text", "value", "summary");
                if (!isBlank(copyText)) out.add(AssistAction.copy(label, copyText));
            }
            default -> { }
        }
    }

    private static void add(Map<String, AssistAction> actions, AssistAction action) {
        String key = action.kind().name() + "\u0000" + action.value();
        actions.putIfAbsent(key, action);
    }

    private static boolean hasKind(Map<String, AssistAction> actions, AssistAction.Kind kind) {
        for (AssistAction action : actions.values()) {
            if (action.kind() == kind) return true;
        }
        return false;
    }

    private static JsonElement parseJson(String raw) {
        int object = raw.indexOf('{');
        int array = raw.indexOf('[');
        int start;
        char close;
        if (object < 0 && array < 0) return null;
        if (object >= 0 && (array < 0 || object < array)) {
            start = object;
            close = '}';
        } else {
            start = array;
            close = ']';
        }
        int end = raw.lastIndexOf(close);
        if (end <= start) return null;
        try {
            return JsonParser.parseString(raw.substring(start, end + 1));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String firstText(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) continue;
            String text = value.getAsString().strip();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) return text;
        }
        return null;
    }

    private static Double coordinate(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) continue;
            try {
                double coordinate = value.getAsDouble();
                if (Double.isFinite(coordinate)) return coordinate;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static Boolean booleanValue(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) continue;
            try {
                return value.getAsBoolean();
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static boolean safeNavigationUri(String uri) {
        String lower = uri.toLowerCase(Locale.ROOT);
        return lower.startsWith("geo:") || lower.startsWith("http://")
                || lower.startsWith("https://") || lower.startsWith("amapuri://")
                || lower.startsWith("baidumap://");
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
