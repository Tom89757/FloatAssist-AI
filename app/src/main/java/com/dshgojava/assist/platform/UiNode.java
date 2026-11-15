package com.dshgojava.assist.platform;

import java.util.ArrayList;
import java.util.List;

/** Immutable accessibility-node facts; {@code ref} indexes the capture's native node list. */
public record UiNode(
        int ref,
        String className,
        String text,
        String desc,
        String hint,
        String viewId,
        UiBox bounds,
        boolean clickable,
        boolean longClickable,
        boolean editable,
        boolean scrollable,
        boolean checkable,
        boolean checked,
        boolean selected,
        boolean focused,
        boolean enabled,
        boolean password,
        boolean heading,
        boolean showingHint,
        int scroll,
        String roleHint,
        String stateDesc,
        Float rangeCurrent,
        Float rangeMax,
        List<UiNode> children,
        int rawChildCount,
        boolean actionClick) {

    public UiNode {
        className = className == null ? "" : className;
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static Builder builder(UiBox bounds) {
        return new Builder(bounds);
    }

    public static final class Builder {
        private int ref = -1;
        private String className = "";
        private String text;
        private String desc;
        private String hint;
        private String viewId;
        private final UiBox bounds;
        private boolean clickable;
        private boolean longClickable;
        private boolean editable;
        private boolean scrollable;
        private boolean checkable;
        private boolean checked;
        private boolean selected;
        private boolean focused;
        private boolean enabled = true;
        private boolean password;
        private boolean heading;
        private boolean showingHint;
        private int scroll;
        private String roleHint;
        private String stateDesc;
        private Float rangeCurrent;
        private Float rangeMax;
        private final List<UiNode> children = new ArrayList<>();
        private int rawChildCount = -1;
        private boolean actionClick;

        private Builder(UiBox bounds) {
            this.bounds = bounds;
        }

        public Builder ref(int value) { ref = value; return this; }
        public Builder className(String value) { className = value; return this; }
        public Builder text(String value) { text = value; return this; }
        public Builder desc(String value) { desc = value; return this; }
        public Builder hint(String value) { hint = value; return this; }
        public Builder viewId(String value) { viewId = value; return this; }
        public Builder clickable(boolean value) { clickable = value; return this; }
        public Builder longClickable(boolean value) { longClickable = value; return this; }
        public Builder editable(boolean value) { editable = value; return this; }
        public Builder scrollable(boolean value) { scrollable = value; return this; }
        public Builder checkable(boolean value) { checkable = value; return this; }
        public Builder checked(boolean value) { checked = value; return this; }
        public Builder selected(boolean value) { selected = value; return this; }
        public Builder focused(boolean value) { focused = value; return this; }
        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder password(boolean value) { password = value; return this; }
        public Builder heading(boolean value) { heading = value; return this; }
        public Builder showingHint(boolean value) { showingHint = value; return this; }
        public Builder scroll(int value) { scroll = value; return this; }
        public Builder roleHint(String value) { roleHint = value; return this; }
        public Builder stateDesc(String value) { stateDesc = value; return this; }
        public Builder rangeCurrent(Float value) { rangeCurrent = value; return this; }
        public Builder rangeMax(Float value) { rangeMax = value; return this; }
        public Builder child(UiNode value) { children.add(value); return this; }
        public Builder children(List<UiNode> value) { children.addAll(value); return this; }
        public Builder rawChildCount(int value) { rawChildCount = value; return this; }
        public Builder actionClick(boolean value) { actionClick = value; return this; }

        public UiNode build() {
            return new UiNode(ref, className, text, desc, hint, viewId, bounds, clickable,
                    longClickable, editable, scrollable, checkable, checked, selected, focused,
                    enabled, password, heading, showingHint, scroll, roleHint, stateDesc,
                    rangeCurrent, rangeMax, children, rawChildCount, actionClick);
        }
    }
}
