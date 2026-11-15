package com.dshgojava.assist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dshgojava.assist.TaskRegistry;
import org.junit.jupiter.api.Test;

class AssistGateTest {
    private static AssistGate.Capabilities caps(
            boolean accessibility,
            boolean model,
            boolean vision,
            boolean clipboard,
            boolean image) {
        return new AssistGate.Capabilities(accessibility, model, vision, clipboard, image);
    }

    private static AssistGate.Capabilities caps(
            boolean accessibility,
            boolean model,
            boolean vision,
            boolean clipboard,
            boolean image,
            boolean voice) {
        return new AssistGate.Capabilities(
                accessibility, model, vision, clipboard, image, voice);
    }

    @Test
    void readyCapabilitiesEnableEveryP0Source() {
        AssistGate.Capabilities ready = caps(true, true, true, true, true);

        assertEquals(AssistMenuModel.Block.NONE,
                AssistGate.blockFor(TaskRegistry.require("screen.summarize"), ready));
        assertEquals(AssistMenuModel.Block.NONE,
                AssistGate.blockFor(TaskRegistry.require("clip.summarize"), ready));
        assertEquals(AssistMenuModel.Block.NONE,
                AssistGate.blockFor(TaskRegistry.require("image.ocr"), ready));
    }

    @Test
    void screenNeedsAccessibilityButClipboardAndImageDoNot() {
        AssistGate.Capabilities noA11y = caps(false, true, true, true, true);

        assertEquals(AssistMenuModel.Block.ACCESSIBILITY,
                AssistGate.blockFor(TaskRegistry.require("screen.summarize"), noA11y));
        assertEquals(AssistMenuModel.Block.NONE,
                AssistGate.blockFor(TaskRegistry.require("clip.summarize"), noA11y));
        assertEquals(AssistMenuModel.Block.NONE,
                AssistGate.blockFor(TaskRegistry.require("image.explain"), noA11y));
    }

    @Test
    void modelAndVisionAreReportedBeforeAnImageIsSelected() {
        assertEquals(AssistMenuModel.Block.MODEL, AssistGate.blockFor(
                TaskRegistry.require("image.ocr"), caps(true, false, false, true, false)));
        assertEquals(AssistMenuModel.Block.VISION, AssistGate.blockFor(
                TaskRegistry.require("image.ocr"), caps(true, true, false, true, false)));
    }

    @Test
    void missingImageAndClipboardAreSourceFailures() {
        assertEquals(AssistMenuModel.Block.SOURCE, AssistGate.blockFor(
                TaskRegistry.require("image.explain"), caps(true, true, true, true, false)));
        assertEquals(AssistMenuModel.Block.SOURCE, AssistGate.blockFor(
                TaskRegistry.require("clip.polish"), caps(true, true, true, false, true)));
    }

    @Test
    void accessibilityIsTheFirstActionableFailureForScreenTasks() {
        assertEquals(AssistMenuModel.Block.ACCESSIBILITY, AssistGate.blockFor(
                TaskRegistry.require("screen.what"), caps(false, false, false, false, false)));
    }

    @Test
    void voiceTaskRequiresASpeechEngine() {
        assertEquals(AssistMenuModel.Block.NONE, AssistGate.blockFor(
                TaskRegistry.require("voice.transcribe"),
                caps(true, true, false, true, true, true)));
        assertEquals(AssistMenuModel.Block.SOURCE, AssistGate.blockFor(
                TaskRegistry.require("voice.transcribe"),
                caps(true, true, false, true, true, false)));
    }
}
