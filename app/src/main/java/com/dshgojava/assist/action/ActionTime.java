package com.dshgojava.assist.action;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the date/time forms commonly found in screen text or model output. */
public final class ActionTime {
    public record Parsed(long epochMs, boolean dateOnly) { }

    private static final Pattern DATE_TIME = Pattern.compile(
            "^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?:[\\sT]+"
                    + "(上午|下午|晚上|中午|凌晨)?\\s*(\\d{1,2})(?:[:点时](\\d{1,2})?)?)?$");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "^(\\d{1,2})[-/](\\d{1,2})(?:[\\sT]+"
                    + "(上午|下午|晚上|中午|凌晨)?\\s*(\\d{1,2})(?:[:点时](\\d{1,2})?)?)?$");
    private static final Pattern TIME_ONLY = Pattern.compile(
            "^(上午|下午|晚上|中午|凌晨)?\\s*(\\d{1,2})"
                    + "(?:[:：](\\d{1,2})|[点时](\\d{1,2})?)$");

    private ActionTime() { }

    public static Parsed parse(String raw, ZoneId zone, Instant now) {
        if (raw == null || zone == null || now == null) return null;
        String value = clean(raw);
        if (value.isEmpty()) return null;

        Parsed absolute = parseAbsolute(value, zone);
        if (absolute != null) return absolute;

        LocalDate baseDate = now.atZone(zone).toLocalDate();
        int relativeDays = 0;
        String rest = value;
        if (rest.startsWith("今天")) {
            rest = rest.substring(2).strip();
        } else if (rest.startsWith("明天")) {
            relativeDays = 1;
            rest = rest.substring(2).strip();
        } else if (rest.startsWith("后天")) {
            relativeDays = 2;
            rest = rest.substring(2).strip();
        } else if (rest.startsWith("大后天")) {
            relativeDays = 3;
            rest = rest.substring(3).strip();
        }
        LocalDate relativeDate = baseDate.plusDays(relativeDays);

        Matcher dateTime = DATE_TIME.matcher(rest);
        if (dateTime.matches()) {
            LocalDate date = dateOrNull(
                    Integer.parseInt(dateTime.group(1)),
                    Integer.parseInt(dateTime.group(2)),
                    Integer.parseInt(dateTime.group(3)));
            if (date == null) return null;
            return withTime(date, dateTime.group(4), dateTime.group(5), dateTime.group(6), zone);
        }

        Matcher monthDay = MONTH_DAY.matcher(rest);
        if (monthDay.matches()) {
            LocalDate date = dateOrNull(baseDate.getYear(),
                    Integer.parseInt(monthDay.group(1)),
                    Integer.parseInt(monthDay.group(2)));
            if (date == null) return null;
            return withTime(date, monthDay.group(3), monthDay.group(4), monthDay.group(5), zone);
        }

        Matcher timeOnly = TIME_ONLY.matcher(rest);
        if (timeOnly.matches()) {
            int hour = adjustedHour(timeOnly.group(1), Integer.parseInt(timeOnly.group(2)));
            String minuteText = timeOnly.group(3) == null ? timeOnly.group(4) : timeOnly.group(3);
            int minute = minuteText == null ? 0 : Integer.parseInt(minuteText);
            if (!validTime(hour, minute)) return null;
            LocalDate date = relativeDays == 0 ? baseDate : relativeDate;
            LocalDateTime local = date.atTime(hour, minute);
            if (relativeDays == 0 && !local.atZone(zone).toInstant().isAfter(now)) {
                local = local.plusDays(1);
            }
            return new Parsed(local.atZone(zone).toInstant().toEpochMilli(), false);
        }

        if (relativeDays > 0) {
            return new Parsed(relativeDate.atStartOfDay(zone).toInstant().toEpochMilli(), true);
        }
        return null;
    }

    private static Parsed parseAbsolute(String value, ZoneId zone) {
        String iso = value.replace(' ', 'T');
        try {
            return new Parsed(OffsetDateTime.parse(iso).toInstant().toEpochMilli(), false);
        } catch (DateTimeParseException ignored) { }
        try {
            return new Parsed(LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli(), false);
        } catch (DateTimeParseException ignored) { }
        try {
            return new Parsed(LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli(), true);
        } catch (DateTimeParseException ignored) { }
        return null;
    }

    private static Parsed withTime(
            LocalDate date, String period, String hourText, String minuteText, ZoneId zone) {
        if (hourText == null) {
            return new Parsed(date.atStartOfDay(zone).toInstant().toEpochMilli(), true);
        }
        int hour = adjustedHour(period, Integer.parseInt(hourText));
        int minute = minuteText == null ? 0 : Integer.parseInt(minuteText);
        if (!validTime(hour, minute)) return null;
        LocalDateTime local = date.atTime(LocalTime.of(hour, minute));
        return new Parsed(local.atZone(zone).toInstant().toEpochMilli(), false);
    }

    private static int adjustedHour(String period, int hour) {
        String marker = period == null ? "" : period;
        if (("下午".equals(marker) || "晚上".equals(marker))
                && hour >= 1 && hour < 12) {
            return hour + 12;
        }
        if ("中午".equals(marker) && hour >= 1 && hour < 11) {
            return hour + 12;
        }
        if ("凌晨".equals(marker) && hour == 12) {
            return 0;
        }
        return hour;
    }

    private static boolean validTime(int hour, int minute) {
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }

    private static LocalDate dateOrNull(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String clean(String raw) {
        String value = raw.strip()
                .replace('：', ':')
                .replace('／', '/')
                .replace('－', '-')
                .replace('年', '-')
                .replace('月', '-')
                .replace("日", "");
        value = halfWidthDigits(value);
        String lower = value.toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"时间", "开始时间", "time", "begin", "start"}) {
            if (lower.startsWith(prefix)) {
                value = value.substring(prefix.length()).strip();
                if (value.startsWith(":") || value.startsWith("=")) {
                    value = value.substring(1).strip();
                }
                break;
            }
        }
        while (!value.isEmpty() && ",，。;；".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1).strip();
        }
        return value;
    }

    private static String halfWidthDigits(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\uFF10' && c <= '\uFF19') {
                out.append((char) ('0' + (c - '\uFF10')));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
