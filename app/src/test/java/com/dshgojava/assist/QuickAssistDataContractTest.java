package com.dshgojava.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuickAssistDataContractTest {

    @Test
    void enumsExposeTheContractValues() {
        assertEquals(
                List.of("SCREEN", "WEB", "CLIP", "IMAGE", "VOICE", "ROLE", "NONE"),
                names(SourceKind.values()));
        assertEquals(
                List.of("TEXT_CARD", "COPY_TEXT", "TRANSLATE_CARD", "ACTION_SHEET"),
                names(OutputKind.values()));
        assertEquals(
                List.of("IDLE", "GATHERING", "THINKING", "STREAMING", "DONE", "FAILED", "CANCELLED"),
                names(Stage.values()));
    }

    @Test
    void quickTaskValidatesRequiredFields() {
        QuickTask task = new QuickTask(
                "screen.what",
                "What is this",
                SourceKind.SCREEN,
                OutputKind.TEXT_CARD,
                "Explain {{text}}",
                true);

        assertEquals("screen.what", task.id());
        assertEquals(SourceKind.SCREEN, task.source());
        assertTrue(task.needsVision());
        assertThrows(IllegalArgumentException.class, () -> new QuickTask(
                " ", "Title", SourceKind.SCREEN, OutputKind.TEXT_CARD, "{{text}}", false));
        assertThrows(NullPointerException.class, () -> new QuickTask(
                "id", "Title", SourceKind.SCREEN, OutputKind.TEXT_CARD, null, false));
    }

    @Test
    void assistRequestCopiesContextAndDefaultsUserInput() {
        Map<String, String> context = new HashMap<>();
        context.put("locale", "zh-CN");
        QuickTask task = new QuickTask(
                "clip.polish", "Polish", SourceKind.CLIP, OutputKind.TEXT_CARD, "{{text}}", false);

        AssistRequest request = new AssistRequest(task, context, null);
        context.put("locale", "en-US");

        assertEquals(Map.of("locale", "zh-CN"), request.context());
        assertEquals("", request.userInput());
        assertFalse(request.hasUserInput());
        assertThrows(UnsupportedOperationException.class, () -> request.context().put("x", "y"));
        assertEquals("Extra", request.withUserInput("Extra").userInput());
    }

    @Test
    void assistResultIsAnImmutableSnapshot() {
        List<AssistAction> actions = new ArrayList<>();
        actions.add(AssistAction.copy("Copy", "result"));

        AssistResult result = AssistResult.done("result", actions);
        actions.clear();

        assertEquals(Stage.DONE, result.stage());
        assertEquals(1, result.actions().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.actions().add(AssistAction.copy("Copy", "other")));
        assertTrue(result.terminal());
        assertTrue(AssistResult.failed("network").terminal());
        assertTrue(AssistResult.cancelled().terminal());
        assertFalse(AssistResult.streaming("part").terminal());
        assertFalse(AssistResult.idle().terminal());
        assertNull(AssistResult.failed("  ").error());
    }

    @Test
    void assistActionsAreStructuredAndValidated() {
        AssistAction action = AssistAction.navigate("Navigate", "geo:0,0?q=Shanghai");

        assertEquals(AssistAction.Kind.NAVIGATE, action.kind());
        assertEquals("geo:0,0?q=Shanghai", action.value());
        assertEquals("", new AssistAction(AssistAction.Kind.COPY, null, "text").label());
        assertThrows(IllegalArgumentException.class,
                () -> new AssistAction(AssistAction.Kind.COPY, "Copy", " "));
        assertThrows(NullPointerException.class,
                () -> new AssistAction(null, "Copy", "text"));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
