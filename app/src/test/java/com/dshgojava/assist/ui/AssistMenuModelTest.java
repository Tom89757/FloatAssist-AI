package com.dshgojava.assist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.TaskRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AssistMenuModelTest {
    private static final AssistMenuModel MODEL = AssistMenuModel.p0();

    @Test
    void p0HasFourGroupsAndAllEightTasks() {
        List<AssistMenuModel.GroupRow> groups = MODEL.groups(AssistMenuModel.TaskGate.allowAll());

        assertEquals(4, groups.size());
        assertEquals(List.of(
                        AssistMenuModel.Group.SCREEN,
                        AssistMenuModel.Group.WEB,
                        AssistMenuModel.Group.TEXT,
                        AssistMenuModel.Group.IMAGE),
                groups.stream().map(AssistMenuModel.GroupRow::group).toList());

        Set<String> ids = new HashSet<>();
        int total = 0;
        for (AssistMenuModel.GroupRow group : groups) {
            total += group.tasks().size();
            for (AssistMenuModel.TaskRow row : group.tasks()) {
                ids.add(row.task().id());
            }
        }
        assertEquals(8, total);
        assertEquals(TaskRegistry.p0().stream().map(QuickTask::id)
                        .collect(java.util.stream.Collectors.toSet()),
                ids);
    }

    @Test
    void allMenuIncludesTheP1ActionSheetTask() {
        AssistMenuModel model = AssistMenuModel.all();
        AssistMenuModel.GroupRow screen = model.group(
                AssistMenuModel.Group.SCREEN, AssistMenuModel.TaskGate.allowAll());

        assertEquals(5, screen.tasks().size());
        assertTrue(screen.tasks().stream()
                .anyMatch(row -> row.task().id().equals("screen.actions")));
        assertEquals(TaskRegistry.all().stream()
                        .filter(task -> task.source() != SourceKind.VOICE)
                        .map(QuickTask::id)
                        .collect(java.util.stream.Collectors.toSet()),
                model.groups(AssistMenuModel.TaskGate.allowAll()).stream()
                        .flatMap(row -> row.tasks().stream())
                        .map(row -> row.task().id())
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(model.groups(AssistMenuModel.TaskGate.allowAll()).stream()
                .flatMap(row -> row.tasks().stream())
                .noneMatch(row -> row.task().id().equals("voice.transcribe")));
    }

    @Test
    void groupRoutesTasksBySource() {
        AssistMenuModel.GroupRow screen = MODEL.group(
                AssistMenuModel.Group.SCREEN, AssistMenuModel.TaskGate.allowAll());
        AssistMenuModel.GroupRow text = MODEL.group(
                AssistMenuModel.Group.TEXT, AssistMenuModel.TaskGate.allowAll());
        AssistMenuModel.GroupRow image = MODEL.group(
                AssistMenuModel.Group.IMAGE, AssistMenuModel.TaskGate.allowAll());

        assertEquals(4, screen.tasks().size());
        assertTrue(screen.tasks().stream().allMatch(row -> row.task().source() == SourceKind.SCREEN));
        assertEquals(2, text.tasks().size());
        assertTrue(text.tasks().stream().allMatch(row -> row.task().source() == SourceKind.CLIP));
        assertEquals(2, image.tasks().size());
        assertTrue(image.tasks().stream().allMatch(row -> row.task().source() == SourceKind.IMAGE));
    }

    @Test
    void webGroupIsComingSoonUntilP1TasksExist() {
        AssistMenuModel.GroupRow web = MODEL.group(
                AssistMenuModel.Group.WEB, AssistMenuModel.TaskGate.allowAll());

        assertTrue(web.tasks().isEmpty());
        assertEquals(AssistMenuModel.Block.COMING_SOON, web.block());
        assertFalse(web.available());
        assertEquals(0, web.availableCount());
    }

    @Test
    void everyTaskIsEnabledWhenGateAllows() {
        for (AssistMenuModel.GroupRow group
                : MODEL.groups(AssistMenuModel.TaskGate.allowAll())) {
            if (group.group() == AssistMenuModel.Group.WEB) continue;
            assertTrue(group.available(), group.group().name());
            assertEquals(group.tasks().size(), group.availableCount());
            assertTrue(group.tasks().stream().allMatch(AssistMenuModel.TaskRow::enabled));
        }
    }

    @Test
    void gateMarksScreenTextAndImageIndependently() {
        AssistMenuModel.TaskGate gate = task -> switch (task.source()) {
            case SCREEN -> AssistMenuModel.Block.ACCESSIBILITY;
            case IMAGE -> AssistMenuModel.Block.SOURCE;
            default -> AssistMenuModel.Block.MODEL;
        };
        List<AssistMenuModel.GroupRow> groups = MODEL.groups(gate);

        assertEquals(AssistMenuModel.Block.ACCESSIBILITY, groups.get(0).block());
        assertEquals(AssistMenuModel.Block.COMING_SOON, groups.get(1).block());
        assertEquals(AssistMenuModel.Block.MODEL, groups.get(2).block());
        assertEquals(AssistMenuModel.Block.SOURCE, groups.get(3).block());
        assertTrue(groups.stream().flatMap(row -> row.tasks().stream())
                .noneMatch(AssistMenuModel.TaskRow::enabled));
    }

    @Test
    void groupRemainsAvailableWhenOnlySomeTasksAreBlocked() {
        AssistMenuModel.TaskGate gate = task -> task.id().equals("screen.summarize")
                ? AssistMenuModel.Block.NONE : AssistMenuModel.Block.MODEL;
        AssistMenuModel.GroupRow screen = MODEL.group(AssistMenuModel.Group.SCREEN, gate);

        assertTrue(screen.available());
        assertEquals(1, screen.availableCount());
        assertEquals(4, screen.tasks().size());
        assertTrue(screen.tasks().get(0).enabled());
        assertFalse(screen.tasks().get(1).enabled());
    }

    @Test
    void combineUsesActionableBlockPriority() {
        QuickTask screen = TaskRegistry.require("screen.summarize");
        QuickTask image = TaskRegistry.require("image.ocr");
        QuickTask clip = TaskRegistry.require("clip.polish");

        assertEquals(AssistMenuModel.Block.COMING_SOON,
                AssistMenuModel.combine(List.of()));
        assertEquals(AssistMenuModel.Block.ACCESSIBILITY,
                AssistMenuModel.combine(List.of(
                        new AssistMenuModel.TaskRow(screen, AssistMenuModel.Block.MODEL),
                        new AssistMenuModel.TaskRow(clip, AssistMenuModel.Block.ACCESSIBILITY))));
        assertEquals(AssistMenuModel.Block.MODEL,
                AssistMenuModel.combine(List.of(
                        new AssistMenuModel.TaskRow(screen, AssistMenuModel.Block.VISION),
                        new AssistMenuModel.TaskRow(clip, AssistMenuModel.Block.MODEL))));
        assertEquals(AssistMenuModel.Block.SOURCE,
                AssistMenuModel.combine(List.of(
                        new AssistMenuModel.TaskRow(image, AssistMenuModel.Block.SOURCE),
                        new AssistMenuModel.TaskRow(clip, AssistMenuModel.Block.COMING_SOON))));
        assertEquals(AssistMenuModel.Block.NONE,
                AssistMenuModel.combine(List.of(
                        new AssistMenuModel.TaskRow(image, AssistMenuModel.Block.SOURCE),
                        new AssistMenuModel.TaskRow(clip, AssistMenuModel.Block.NONE))));
    }

    @Test
    void menuRowsAreImmutableSnapshots() {
        AssistMenuModel.GroupRow screen = MODEL.group(
                AssistMenuModel.Group.SCREEN, AssistMenuModel.TaskGate.allowAll());

        assertThrows(UnsupportedOperationException.class,
                () -> screen.tasks().add(screen.tasks().get(0)));
    }
}
