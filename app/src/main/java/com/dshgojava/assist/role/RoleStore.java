package com.dshgojava.assist.role;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** Persists the selected role id and user-created roles in app-private prefs. */
public final class RoleStore {
    public static final String FILE = "quick_assist_roles";

    private static final String K_SELECTED = "selected_role";
    private static final String K_CUSTOM = "custom_roles";
    private static final Type CUSTOM_TYPE = new TypeToken<List<Role>>() { }.getType();
    private static final Gson GSON = new Gson();

    private final SharedPreferences preferences;

    public RoleStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public Role selected() {
        String id = preferences.getString(K_SELECTED, Role.NONE_ID);
        return RoleCatalog.find(id, custom()).orElse(RoleCatalog.none());
    }

    public String selectedId() {
        return preferences.getString(K_SELECTED, Role.NONE_ID);
    }

    public void select(String id) {
        preferences.edit().putString(K_SELECTED, id == null ? Role.NONE_ID : id.strip()).apply();
    }

    public List<Role> custom() {
        String raw = preferences.getString(K_CUSTOM, "[]");
        try {
            List<Role> roles = GSON.fromJson(raw, CUSTOM_TYPE);
            return roles == null ? new ArrayList<>() : new ArrayList<>(roles);
        } catch (RuntimeException error) {
            return new ArrayList<>();
        }
    }

    public void addCustom(Role role) {
        List<Role> roles = custom();
        roles.removeIf(existing -> existing.id().equals(role.id()));
        roles.add(role);
        preferences.edit().putString(K_CUSTOM, GSON.toJson(roles)).apply();
    }

    /** Removes a user-created role; if it was selected, falls back to the default role. */
    public boolean delete(String id) {
        if (id == null || id.isBlank()) return false;
        List<Role> roles = custom();
        boolean removed = roles.removeIf(existing -> existing.id().equals(id));
        if (!removed) return false;
        preferences.edit().putString(K_CUSTOM, GSON.toJson(roles)).apply();
        if (id.equals(selectedId())) {
            select(Role.NONE_ID);
        }
        return removed;
    }

    /** New unique id for a user-created role. */
    public static String newCustomId() {
        return "custom-" + System.currentTimeMillis();
    }
}
