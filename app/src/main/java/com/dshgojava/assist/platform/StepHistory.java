package com.dshgojava.assist.platform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The task's last actions; the newest action above a reading line is listed once. */
public final class StepHistory {
    public static final int CAPACITY = 3;

    private final ArrayDeque<String> steps = new ArrayDeque<>();
    private final int capacity;

    public StepHistory() {
        this(CAPACITY);
    }

    public StepHistory(int capacity) {
        this.capacity = capacity;
    }

    public void add(StatusLine line) {
        if (line == null || !line.acting()) return;
        if (line.text().equals(steps.peekLast())) return;
        steps.addLast(line.text());
        while (steps.size() > capacity + 1) steps.removeFirst();
    }

    /** Up to {@code limit} steps before {@code current}, newest first. */
    public List<String> before(StatusLine current, int limit) {
        List<String> list = new ArrayList<>(steps);
        if (current != null && current.acting() && !list.isEmpty()
                && current.text().equals(list.get(list.size() - 1))) {
            list.remove(list.size() - 1);
        }
        Collections.reverse(list);
        int count = Math.min(Math.max(0, limit), list.size());
        return List.copyOf(list.subList(0, count));
    }

    public void clear() {
        steps.clear();
    }
}
