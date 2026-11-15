package com.dshgojava.assist.action;

import java.util.regex.Pattern;

/** Conservative phone-number normalisation for dial intents. */
public final class PhoneNumbers {
    private static final Pattern ALLOWED = Pattern.compile("^\\+?[0-9][0-9 ()\\-]{1,30}$");

    private PhoneNumbers() { }

    /** Returns a dial-safe number, or null for service codes and malformed input. */
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!ALLOWED.matcher(value).matches()) return null;
        String number = value.replace(" ", "")
                .replace("(", "")
                .replace(")", "")
                .replace("-", "");
        if (number.length() < 5 || number.length() > 24) return null;
        return number;
    }
}
