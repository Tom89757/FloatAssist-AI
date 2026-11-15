package com.dshgojava.assist.action;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic fallback extraction for plain screen text. */
public final class ActionExtractor {
    public record Found(String address, String timeText, ActionTime.Parsed time, String phone) {
        public boolean empty() {
            return address == null && timeText == null && phone == null;
        }
    }

    private static final Pattern PHONE_MOBILE = Pattern.compile(
            "(?<![0-9+])(?:\\+?86[\\s-]?)?1[3-9][0-9]{9}(?![0-9])");
    private static final Pattern PHONE_LANDLINE = Pattern.compile(
            "(?<![0-9])0[0-9]{2,3}[\\s-]?[0-9]{7,8}(?![0-9])");
    private static final Pattern PHONE_INTERNATIONAL = Pattern.compile(
            "(?<![0-9])\\+[0-9][0-9\\s-]{5,20}[0-9](?![0-9])");
    private static final Pattern TIME_FULL = Pattern.compile(
            "\\d{4}(?:[-/年]\\d{1,2}[-/月]\\d{1,2}日?)"
                    + "(?:[ T]+(?:上午|下午|晚上|中午|凌晨)?\\s*\\d{1,2}"
                    + "(?:[:：点时](?:\\d{1,2})?)?)?");
    private static final Pattern TIME_RELATIVE = Pattern.compile(
            "(?:今天|明天|后天|大后天)\\s*(?:上午|下午|晚上|中午|凌晨)?\\s*"
                    + "\\d{1,2}(?:[:：点时](?:\\d{1,2})?)?");
    private static final Pattern TIME_MONTH_DAY = Pattern.compile(
            "\\d{1,2}[-/月]\\d{1,2}日?"
                    + "(?:[ T]+(?:上午|下午|晚上|中午|凌晨)?\\s*\\d{1,2}"
                    + "(?:[:：点时](?:\\d{1,2})?)?)?");
    private static final Pattern TIME_ONLY = Pattern.compile(
            "(?:上午|下午|晚上|中午|凌晨)?\\s*\\d{1,2}"
                    + "(?:[:：]\\d{1,2}|[点时](?:\\d{1,2})?)");
    private static final Pattern LABELED_ADDRESS = Pattern.compile(
            "^(?:地址|地点|位置|address|location)\\s*[:：]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    private ActionExtractor() { }

    public static Found extract(String text, ZoneId zone, Instant now) {
        if (text == null || text.isBlank()) return new Found(null, null, null, null);
        String phone = firstPhone(text);
        TimeMatch time = firstTime(text, zone, now);
        String address = firstAddress(text);
        return new Found(address, time == null ? null : time.text(),
                time == null ? null : time.parsed(), phone);
    }

    private static String firstPhone(String text) {
        for (Pattern pattern : new Pattern[] {
                PHONE_MOBILE, PHONE_LANDLINE, PHONE_INTERNATIONAL}) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String number = PhoneNumbers.normalize(matcher.group());
                if (number != null) return number;
            }
        }
        return null;
    }

    private static TimeMatch firstTime(String text, ZoneId zone, Instant now) {
        for (Pattern pattern : new Pattern[] {
                TIME_FULL, TIME_RELATIVE, TIME_MONTH_DAY, TIME_ONLY}) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String candidate = matcher.group().strip();
                ActionTime.Parsed parsed = ActionTime.parse(candidate, zone, now);
                if (parsed != null) return new TimeMatch(candidate, parsed);
            }
        }
        return null;
    }

    private static String firstAddress(String text) {
        String[] lines = text.split("\\r?\\n|\\r");
        for (String line : lines) {
            String candidate = line.strip();
            Matcher labeled = LABELED_ADDRESS.matcher(candidate);
            if (labeled.matches()) {
                String address = cleanAddress(labeled.group(1));
                if (address != null) return address;
            }
        }
        for (String line : lines) {
            String candidate = cleanAddress(line);
            if (candidate != null && looksLikeAddress(candidate)) return candidate;
        }
        return null;
    }

    private static String cleanAddress(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        value = value.replaceFirst("^[-•·*\\s]+", "");
        value = value.replaceFirst(
                "^.*?(?:地址|地点|位置|address|location)\\s*[:：]\\s*", "");
        int cut = firstIndexOf(value, "电话", "手机", "联系方式",
                "营业时间", "时间:", "时间：");
        if (cut > 0) value = value.substring(0, cut).strip();
        value = value.replaceAll("[\\s,，。;；]+$", "").strip();
        if (value.length() < 3 || value.length() > 120) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || value.contains("{{") || value.contains("}}")) return null;
        return value;
    }

    private static boolean looksLikeAddress(String value) {
        if (value.length() < 6 || value.length() > 120) return false;
        String[] markers = {"省", "市", "区", "县", "镇", "乡", "路", "街", "道", "巷",
                "弄", "号", "大厦", "广场", "中心", "酒店", "机场", "车站", "医院",
                "学校", "小区", "园区", "楼", "室", "村"};
        for (String marker : markers) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static int firstIndexOf(String value, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int index = value.indexOf(needle);
            if (index >= 0 && (best < 0 || index < best)) best = index;
        }
        return best;
    }

    private record TimeMatch(String text, ActionTime.Parsed parsed) { }
}
