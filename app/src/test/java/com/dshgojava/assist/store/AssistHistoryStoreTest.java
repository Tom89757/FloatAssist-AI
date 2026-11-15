package com.dshgojava.assist.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssistHistoryStoreTest {
    @TempDir
    Path temp;

    @Test
    void recordsNewestFirstAndReloadsFromDisk() {
        Path file = temp.resolve("history.json");
        AssistHistoryStore store = new AssistHistoryStore(file);

        store.record("screen.summarize", "总结当前屏幕", "first answer", false, 100L);
        store.record("clip.polish", "润色/纠错", "second answer", true, 200L);

        List<AssistHistoryEntry> entries = store.list();
        assertEquals(2, entries.size());
        assertEquals("second answer", entries.get(0).text());
        assertTrue(entries.get(0).followUp());
        assertEquals("first answer", entries.get(1).text());
        assertFalse(entries.get(1).followUp());

        List<AssistHistoryEntry> reloaded = new AssistHistoryStore(file).list();
        assertEquals(entries, reloaded);
    }

    @Test
    void keepsOnlyTheNewestTwoHundredEntries() {
        AssistHistoryStore store = new AssistHistoryStore(temp.resolve("history.json"),
                AssistHistoryStore.DEFAULT_MAX_ENTRIES);

        for (int index = 1; index <= 205; index++) {
            store.record("task", "Task", "answer-" + index, false, index);
        }

        List<AssistHistoryEntry> entries = store.list();
        assertEquals(200, entries.size());
        assertEquals("answer-205", entries.get(0).text());
        assertEquals("answer-6", entries.get(199).text());
    }

    @Test
    void deletesOneEntryAndClearsEverything() {
        Path file = temp.resolve("history.json");
        AssistHistoryStore store = new AssistHistoryStore(file);
        AssistHistoryEntry first = store.record("a", "A", "one", false, 1L);
        store.record("b", "B", "two", false, 2L);

        assertTrue(store.delete(first.id()));
        assertEquals(1, store.list().size());
        assertFalse(store.delete("missing"));

        store.clear();
        assertTrue(store.list().isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void treatsCorruptFilesAsEmptyAndCanReplaceThem() throws IOException {
        Path file = temp.resolve("history.json");
        Files.write(file, "{not json".getBytes(StandardCharsets.UTF_8));
        AssistHistoryStore store = new AssistHistoryStore(file);

        assertTrue(store.list().isEmpty());
        store.record("task", "Task", "recovered", false, 10L);

        assertEquals(1, store.list().size());
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .contains("\"version\":1"));
    }

    @Test
    void rejectsBlankHistoryContent() {
        AssistHistoryStore store = new AssistHistoryStore(temp.resolve("history.json"));

        assertThrows(IllegalArgumentException.class,
                () -> store.record("task", "Task", "   ", false, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> store.record("", "Task", "answer", false, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> store.record("task", "Task", "answer", false, 0L));
    }

    @Test
    void capsOversizedAnswerText() {
        AssistHistoryStore store = new AssistHistoryStore(temp.resolve("history.json"));
        String oversized = "x".repeat(AssistHistoryEntry.MAX_TEXT_CHARS + 20);

        store.record("task", "Task", oversized, false, 1L);

        assertEquals(AssistHistoryEntry.MAX_TEXT_CHARS, store.list().get(0).text().length());
    }
}
