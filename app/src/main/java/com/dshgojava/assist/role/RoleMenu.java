package com.dshgojava.assist.role;

import com.dshgojava.assist.OutputKind;
import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.SourceKind;
import java.util.ArrayList;
import java.util.List;

/** Builds the menu selector rows for the role group. */
public final class RoleMenu {
    public static final String PREFIX = "role.";
    public static final String CREATE_ID = "role.create";
    public static final String NONE_ID = "role.none";

    private RoleMenu() { }

    public static List<QuickTask> tasks(List<Role> customRoles) {
        List<QuickTask> tasks = new ArrayList<>();
        for (Role role : RoleCatalog.DEFAULTS) {
            tasks.add(task(PREFIX + role.id(), role.name()));
        }
        if (customRoles != null) {
            for (Role role : customRoles) {
                tasks.add(task(PREFIX + role.id(), role.name()));
            }
        }
        tasks.add(task(NONE_ID, "无角色"));
        tasks.add(task(CREATE_ID, "新增角色"));
        return List.copyOf(tasks);
    }

    /** True when a task id belongs to the role group. */
    public static boolean isRoleTask(String taskId) {
        return taskId != null && taskId.startsWith(PREFIX);
    }

    /** Strips the selector prefix back to the persisted role id. */
    public static String roleId(String taskId) {
        return taskId == null || !taskId.startsWith(PREFIX) ? Role.NONE_ID : taskId.substring(PREFIX.length());
    }

    private static QuickTask task(String id, String title) {
        return new QuickTask(id, title, SourceKind.ROLE, OutputKind.TEXT_CARD, "{{text}}", false);
    }
}
