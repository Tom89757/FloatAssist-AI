package com.dshgojava.assist.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.SourceKind;
import com.google.gson.Gson;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoleTest {
    @Test
    void personaRendersOnlyPresentFields() {
        Role role = new Role("x", "代码专家", "工程师", "Java", "", "先给代码", "", true);

        String persona = role.persona();
        assertTrue(persona.contains("【身份】工程师"));
        assertTrue(persona.contains("【专长】Java"));
        assertTrue(persona.contains("【输出约定】先给代码"));
        assertFalse(persona.contains("【口吻】"));
        assertFalse(persona.contains("【边界】"));
    }

    @Test
    void noneRoleRendersEmptyPersona() {
        assertEquals("", RoleCatalog.none().persona());
        assertTrue(RoleCatalog.none().none());
    }

    @Test
    void catalogHasFiveDefaultsAndResolvesById() {
        assertEquals(5, RoleCatalog.DEFAULTS.size());
        assertEquals("股票投资", RoleCatalog.find("stock", List.of()).orElseThrow().name());
        assertTrue(RoleCatalog.find("missing", List.of()).isEmpty());
    }

    @Test
    void menuBuildsSelectorsIncludingCustomAndReset() {
        Role custom = new Role("custom-1", "我的角色", "身份", "", "", "", "", false);
        List<com.dshgojava.assist.QuickTask> tasks = RoleMenu.tasks(List.of(custom));

        assertTrue(tasks.stream().allMatch(task -> task.source() == SourceKind.ROLE));
        assertTrue(tasks.stream().anyMatch(task -> task.id().equals("role.stock")));
        assertTrue(tasks.stream().anyMatch(task -> task.id().equals("role.custom-1")));
        assertTrue(tasks.stream().anyMatch(task -> task.id().equals(RoleMenu.NONE_ID)
                && task.title().equals("默认角色")));
        assertFalse(tasks.stream().anyMatch(task -> task.id().equals(RoleMenu.CREATE_ID)));
    }

    @Test
    void noneRoleIsNamedDefaultRole() {
        assertEquals("默认角色", RoleCatalog.none().name());
    }

    @Test
    void roleIdStripsPrefix() {
        assertEquals("stock", RoleMenu.roleId("role.stock"));
        assertEquals(Role.NONE_ID, RoleMenu.roleId("other"));
    }

    @Test
    void customRoleTaskIsDeletable() {
        assertTrue(RoleMenu.isCustomRoleTask("role.custom-1"));
        assertFalse(RoleMenu.isCustomRoleTask("role.stock"));
        assertFalse(RoleMenu.isCustomRoleTask(RoleMenu.NONE_ID));
    }

    @Test
    void gsonRoundTripsRoleForPersistence() {
        Role role = new Role("custom-1", "我的角色", "身份", "专长", "口吻", "输出", "边界", false);
        Gson gson = new Gson();
        Role restored = gson.fromJson(gson.toJson(role), Role.class);

        assertEquals(role, restored);
        assertEquals("我的角色", restored.name());
        assertEquals("边界", restored.guardrails());
    }
}
