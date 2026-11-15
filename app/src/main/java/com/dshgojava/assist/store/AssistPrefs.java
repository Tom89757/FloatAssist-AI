package com.dshgojava.assist.store;

import android.content.Context;
import android.content.SharedPreferences;
import com.dshgojava.assist.model.DirectApiModelChannel;
import java.util.Objects;

/**
 * Private quick-assist preferences. Reads and writes touch disk, so Android
 * callers must use a background executor. The API key is stored only inside the
 * app-private preferences file, is never logged, and is never returned to the
 * settings UI for display.
 */
public final class AssistPrefs {
    public static final String FILE = "quick_assist_prefs";
    public static final String DEFAULT_CHANNEL = "direct";

    private static final String K_OVERLAY_ENABLED = "overlay_enabled";
    private static final String K_NOTICE_SEEN = "notice_seen";
    private static final String K_CHANNEL = "channel";
    private static final String K_BASE_URL = "model_base_url";
    private static final String K_API_KEY = "model_api_key";
    private static final String K_MODEL = "model_name";
    private static final String K_VISION = "model_supports_vision";

    private final SharedPreferences preferences;

    public AssistPrefs(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public record Snapshot(
            boolean overlayEnabled,
            boolean noticeSeen,
            String channel,
            DirectApiModelChannel.Config model,
            boolean hasApiKey
    ) {
        public Snapshot {
            channel = channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel.strip();
            model = Objects.requireNonNull(model, "model");
        }

        public boolean connected() {
            return hasApiKey && model.configured();
        }

        @Override
        public String toString() {
            return "Snapshot{overlayEnabled=" + overlayEnabled
                    + ", noticeSeen=" + noticeSeen
                    + ", channel='" + channel + '\''
                    + ", model=" + model
                    + ", hasApiKey=" + hasApiKey + '}';
        }
    }

    /** Performs disk-backed preference reads; call off the main thread. */
    public Snapshot read() {
        String key = strip(preferences.getString(K_API_KEY, ""));
        String baseUrl = strip(preferences.getString(K_BASE_URL, ""));
        String model = strip(preferences.getString(K_MODEL, ""));
        boolean vision = preferences.getBoolean(K_VISION, false);
        DirectApiModelChannel.Config config = new DirectApiModelChannel.Config(
                baseUrl, key, model, vision, "");
        return new Snapshot(
                preferences.getBoolean(K_OVERLAY_ENABLED, false),
                preferences.getBoolean(K_NOTICE_SEEN, false),
                strip(preferences.getString(K_CHANNEL, DEFAULT_CHANNEL)),
                config,
                !key.isEmpty());
    }

    public void setOverlayEnabled(boolean enabled) {
        preferences.edit().putBoolean(K_OVERLAY_ENABLED, enabled).apply();
    }

    public void setNoticeSeen(boolean seen) {
        preferences.edit().putBoolean(K_NOTICE_SEEN, seen).apply();
    }

    /**
     * Saves model settings. A blank key keeps the existing secret, allowing the
     * user to change the endpoint/model without ever displaying the saved key.
     */
    public void saveModel(String baseUrl, String apiKey, String model, boolean supportsVision) {
        String cleanKey = strip(apiKey);
        SharedPreferences.Editor edit = preferences.edit()
                .putString(K_CHANNEL, DEFAULT_CHANNEL)
                .putString(K_BASE_URL, strip(baseUrl))
                .putString(K_MODEL, strip(model))
                .putBoolean(K_VISION, supportsVision);
        if (!cleanKey.isEmpty()) edit.putString(K_API_KEY, cleanKey);
        edit.apply();
    }

    public void clearApiKey() {
        preferences.edit().remove(K_API_KEY).apply();
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
