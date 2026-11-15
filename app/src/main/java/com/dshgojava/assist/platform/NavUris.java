package com.dshgojava.assist.platform;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Fixed navigation deep links; callers cannot supply a scheme or component. */
public final class NavUris {
    private NavUris() { }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /**
     * Builds a geo URI. Coordinates are used when both are present; otherwise
     * the destination is carried as a search query for any installed map app.
     */
    public static String geo(String destination, Double lat, Double lng) {
        if (lat != null && lng != null) {
            String name = destination == null || destination.isBlank()
                    ? "" : "(" + encode(destination) + ")";
            return "geo:" + lat + "," + lng + "?q=" + lat + "," + lng + name;
        }
        return "geo:0,0?q=" + encode(destination == null ? "" : destination);
    }
}
