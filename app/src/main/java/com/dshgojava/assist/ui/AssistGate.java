package com.dshgojava.assist.ui;

import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.TaskRegistry;
import java.util.Objects;

/** Pure task availability rules shared by the service and unit tests. */
public final class AssistGate {
    /** Capabilities are sampled on the main thread before the menu is rebuilt. */
    public record Capabilities(
            boolean accessibility,
            boolean modelReady,
            boolean visionSupported,
            boolean clipboardAvailable,
            boolean imageSelected,
            boolean voiceAvailable
    ) {
        public Capabilities(
                boolean accessibility,
                boolean modelReady,
                boolean visionSupported,
                boolean clipboardAvailable,
                boolean imageSelected) {
            this(accessibility, modelReady, visionSupported, clipboardAvailable,
                    imageSelected, false);
        }
    }

    private AssistGate() { }

    public static AssistMenuModel.Block blockFor(QuickTask task, Capabilities capabilities) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(capabilities, "capabilities");
        boolean needsAccessibility = task.source() == SourceKind.SCREEN
                || (task.source() == SourceKind.WEB && !TaskRegistry.isClipboardWeb(task));
        if (needsAccessibility && !capabilities.accessibility()) {
            return AssistMenuModel.Block.ACCESSIBILITY;
        }
        if (!capabilities.modelReady()) {
            return AssistMenuModel.Block.MODEL;
        }
        if (task.needsVision() && !capabilities.visionSupported()) {
            return AssistMenuModel.Block.VISION;
        }
        if (task.source() == SourceKind.CLIP && !capabilities.clipboardAvailable()) {
            return AssistMenuModel.Block.SOURCE;
        }
        if (task.source() == SourceKind.IMAGE && !capabilities.imageSelected()) {
            return AssistMenuModel.Block.SOURCE;
        }
        if (task.source() == SourceKind.VOICE && !capabilities.voiceAvailable()) {
            return AssistMenuModel.Block.SOURCE;
        }
        return AssistMenuModel.Block.NONE;
    }
}
