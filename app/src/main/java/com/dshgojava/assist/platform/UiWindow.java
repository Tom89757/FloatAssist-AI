package com.dshgojava.assist.platform;

/** One readable window; input order is top-most first. */
public record UiWindow(int id, WindowKind kind, String packageName, String title,
                       UiBox bounds, UiNode root) { }
