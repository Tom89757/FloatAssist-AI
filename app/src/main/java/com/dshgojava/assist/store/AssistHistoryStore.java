package com.dshgojava.assist.store;

import android.content.Context;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Bounded local result history backed by one UTF-8 JSON file. It has no Android
 * view dependencies and all public methods perform file IO synchronously, so
 * callers must invoke them off the main thread on Android.
 */
public final class AssistHistoryStore {
    public static final int DEFAULT_MAX_ENTRIES = 200;
    public static final int FORMAT_VERSION = 1;

    private static volatile AssistHistoryStore appInstance;

    private final Path file;
    private final int maxEntries;
    private List<AssistHistoryEntry> entries = List.of();
    private boolean loaded;

    public AssistHistoryStore(Path file) {
        this(file, DEFAULT_MAX_ENTRIES);
    }

    public AssistHistoryStore(Path file, int maxEntries) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    /** Process-wide store so the overlay and settings page share one cache. */
    public static AssistHistoryStore forApp(Context context) {
        AssistHistoryStore current = appInstance;
        if (current != null) return current;
        synchronized (AssistHistoryStore.class) {
            if (appInstance == null) {
                File directory = new File(context.getFilesDir(), "assist");
                appInstance = new AssistHistoryStore(
                        new File(directory, "history.json").toPath(), DEFAULT_MAX_ENTRIES);
            }
            return appInstance;
        }
    }

    public Path file() {
        return file;
    }

    public int maxEntries() {
        return maxEntries;
    }

    /** Returns newest-first entries. Missing or malformed files mean empty history. */
    public synchronized List<AssistHistoryEntry> list() {
        loadIfNeeded();
        return List.copyOf(entries);
    }

    public synchronized AssistHistoryEntry record(
            String taskId, String taskTitle, String text, boolean followUp, long nowMillis) {
        AssistHistoryEntry entry = AssistHistoryEntry.create(
                taskId, taskTitle, text, followUp, nowMillis);
        loadIfNeeded();
        List<AssistHistoryEntry> next = new ArrayList<>(entries.size() + 1);
        next.add(entry);
        next.addAll(entries);
        List<AssistHistoryEntry> previous = entries;
        entries = trim(next);
        try {
            writeLocked();
        } catch (IOException error) {
            entries = previous;
            throw new HistoryException("无法写入历史记录", error);
        }
        return entry;
    }

    public synchronized boolean delete(String id) {
        if (id == null || id.isBlank()) return false;
        loadIfNeeded();
        List<AssistHistoryEntry> next = new ArrayList<>(entries.size());
        boolean removed = false;
        for (AssistHistoryEntry entry : entries) {
            if (entry.id().equals(id)) {
                removed = true;
            } else {
                next.add(entry);
            }
        }
        if (!removed) return false;
        List<AssistHistoryEntry> previous = entries;
        entries = List.copyOf(next);
        try {
            if (entries.isEmpty()) {
                deleteFileLocked();
            } else {
                writeLocked();
            }
        } catch (IOException error) {
            entries = previous;
            throw new HistoryException("无法更新历史记录", error);
        }
        return true;
    }

    public synchronized void clear() {
        loadIfNeeded();
        List<AssistHistoryEntry> previous = entries;
        entries = List.of();
        try {
            deleteFileLocked();
        } catch (IOException error) {
            entries = previous;
            throw new HistoryException("无法清空历史记录", error);
        }
    }

    private void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(file)) {
            entries = List.of();
            return;
        }
        try {
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonObject()) {
                entries = List.of();
                return;
            }
            JsonObject object = root.getAsJsonObject();
            JsonElement version = object.get("version");
            if (version == null || !version.isJsonPrimitive()
                    || version.getAsInt() != FORMAT_VERSION) {
                entries = List.of();
                return;
            }
            JsonElement values = object.get("entries");
            if (values == null || !values.isJsonArray()) {
                entries = List.of();
                return;
            }
            List<AssistHistoryEntry> decoded = new ArrayList<>();
            JsonArray array = values.getAsJsonArray();
            for (JsonElement value : array) {
                AssistHistoryEntry entry = decode(value);
                if (entry != null) decoded.add(entry);
            }
            decoded.sort(Comparator.comparingLong(AssistHistoryEntry::createdAtMillis).reversed());
            entries = trim(decoded);
        } catch (IOException | RuntimeException ignored) {
            entries = List.of();
        }
    }

    private List<AssistHistoryEntry> trim(List<AssistHistoryEntry> values) {
        if (values.size() <= maxEntries) return List.copyOf(values);
        return List.copyOf(values.subList(0, maxEntries));
    }

    private void writeLocked() throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);

        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonArray array = new JsonArray();
        for (AssistHistoryEntry entry : entries) {
            JsonObject value = new JsonObject();
            value.addProperty("id", entry.id());
            value.addProperty("createdAt", entry.createdAtMillis());
            value.addProperty("taskId", entry.taskId());
            value.addProperty("taskTitle", entry.taskTitle());
            value.addProperty("followUp", entry.followUp());
            value.addProperty("text", entry.text());
            array.add(value);
        }
        root.add("entries", array);

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, root.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteFileLocked() throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
    }

    private static AssistHistoryEntry decode(JsonElement value) {
        if (!value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        try {
            JsonElement id = object.get("id");
            JsonElement created = object.get("createdAt");
            JsonElement taskId = object.get("taskId");
            JsonElement taskTitle = object.get("taskTitle");
            JsonElement text = object.get("text");
            JsonElement followUp = object.get("followUp");
            if (id == null || created == null || taskId == null
                    || taskTitle == null || text == null) {
                return null;
            }
            return new AssistHistoryEntry(
                    id.getAsString(),
                    created.getAsLong(),
                    taskId.getAsString(),
                    taskTitle.getAsString(),
                    text.getAsString(),
                    followUp != null && followUp.getAsBoolean());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static final class HistoryException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public HistoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
