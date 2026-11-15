package com.dshgojava.assist.ui;

import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.TaskRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure menu projection: four source groups, their tasks and the reason each
 * entry is blocked. Android views only translate this into labels and colors.
 */
public final class AssistMenuModel {
    public enum Group {
        SCREEN(SourceKind.SCREEN),
        WEB(SourceKind.WEB),
        TEXT(SourceKind.CLIP),
        IMAGE(SourceKind.IMAGE);

        private final SourceKind source;

        Group(SourceKind source) {
            this.source = source;
        }

        public SourceKind source() {
            return source;
        }
    }

    /** Why an entry cannot run; NONE means it is available. */
    public enum Block {
        NONE,
        ACCESSIBILITY,
        MODEL,
        VISION,
        SOURCE,
        COMING_SOON
    }

    @FunctionalInterface
    public interface TaskGate {
        Block blockFor(QuickTask task);

        static TaskGate allowAll() {
            return task -> Block.NONE;
        }
    }

    public record TaskRow(QuickTask task, Block block) {
        public TaskRow {
            task = Objects.requireNonNull(task, "task");
            block = Objects.requireNonNull(block, "block");
        }

        public boolean enabled() {
            return block == Block.NONE;
        }
    }

    public record GroupRow(Group group, List<TaskRow> tasks, Block block) {
        public GroupRow {
            group = Objects.requireNonNull(group, "group");
            tasks = List.copyOf(tasks == null ? List.of() : tasks);
            block = Objects.requireNonNull(block, "block");
        }

        public int availableCount() {
            int count = 0;
            for (TaskRow task : tasks) {
                if (task.enabled()) count++;
            }
            return count;
        }

        public boolean available() {
            return block == Block.NONE;
        }
    }

    private final List<QuickTask> tasks;

    public AssistMenuModel(List<QuickTask> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        this.tasks = List.copyOf(tasks);
    }

    public static AssistMenuModel p0() {
        return new AssistMenuModel(TaskRegistry.p0());
    }

    public static AssistMenuModel all() {
        List<QuickTask> tasks = new ArrayList<>(TaskRegistry.all());
        tasks.add(TaskRegistry.imagePicker());
        return new AssistMenuModel(tasks);
    }

    public List<GroupRow> groups(TaskGate gate) {
        Objects.requireNonNull(gate, "gate");
        List<GroupRow> rows = new ArrayList<>(Group.values().length);
        for (Group group : Group.values()) {
            rows.add(group(group, gate));
        }
        return List.copyOf(rows);
    }

    public GroupRow group(Group group, TaskGate gate) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(gate, "gate");
        List<TaskRow> rows = new ArrayList<>();
        for (QuickTask task : tasks) {
            if (task.source() == group.source()) {
                rows.add(new TaskRow(task, Objects.requireNonNull(
                        gate.blockFor(task), "gate returned null")));
            }
        }
        return new GroupRow(group, rows, combine(rows));
    }

    public static Block combine(List<TaskRow> rows) {
        if (rows == null || rows.isEmpty()) return Block.COMING_SOON;
        for (TaskRow row : rows) {
            if (row.block() == Block.NONE) return Block.NONE;
        }
        for (Block candidate : new Block[] {
                Block.ACCESSIBILITY, Block.MODEL, Block.VISION,
                Block.SOURCE, Block.COMING_SOON}) {
            for (TaskRow row : rows) {
                if (row.block() == candidate) return candidate;
            }
        }
        return Block.SOURCE;
    }
}
