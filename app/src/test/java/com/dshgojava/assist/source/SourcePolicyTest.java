package com.dshgojava.assist.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourcePolicyTest {
    @Test
    void screenshotPolicyHonorsVisionAndPoorTrees() {
        assertTrue(ScreenCapturePolicy.needsScreenshot(true, 5_000));
        assertFalse(ScreenCapturePolicy.needsScreenshot(false, 40));
        assertTrue(ScreenCapturePolicy.needsScreenshot(false, 39));
        assertTrue(ScreenCapturePolicy.mayReturnTreeOnly(false, "visible text"));
        assertFalse(ScreenCapturePolicy.mayReturnTreeOnly(true, "visible text"));
        assertFalse(ScreenCapturePolicy.mayReturnTreeOnly(false, " "));
    }

    @Test
    void imageSamplingUsesPowerOfTwoAndPreservesAspectRatio() {
        assertEquals(1, ImageSampling.sampleSizeFor(1_024, 768, 1_024));
        assertEquals(2, ImageSampling.sampleSizeFor(3_000, 2_000, 1_024));
        assertEquals(4, ImageSampling.sampleSizeFor(4_096, 3_072, 1_024));
        assertEquals(new ImageSampling.Size(1_024, 683),
                ImageSampling.scaledSize(3_000, 2_000, 1_024));
        assertEquals(new ImageSampling.Size(500, 400),
                ImageSampling.scaledSize(500, 400, 1_024));
        assertThrows(IllegalArgumentException.class,
                () -> ImageSampling.scaledSize(0, 10, 1_024));
        assertThrows(IllegalArgumentException.class,
                () -> ImageSampling.sampleSizeFor(10, 10, 0));
    }

    @Test
    void clipboardTextIsNormalizedDeduplicatedAndBounded() {
        assertEquals("第一行\n\n第二行", ClipText.join(List.of(
                " 第一行 \r\n", "第二行", "第一行")));
        assertThrows(CaptureException.class, () -> ClipText.join(List.of(" ")));
        assertThrows(CaptureException.class, () -> ClipText.join(List.of()));
        assertThrows(CaptureException.class,
                () -> ClipText.join(List.of("x".repeat(ClipText.MAX_CHARS + 1))));
    }

    @Test
    void foregroundClipboardBridgeTextUsesTheSameNormalization() {
        CaptureBundle bundle = ClipSource.bundleFromText("  first\r\n\r\nsecond  ");

        assertEquals("first\n\nsecond", bundle.text());
        assertEquals("clipboard", bundle.meta().get("source"));
        assertEquals("1", bundle.meta().get("items"));
        assertEquals("13", bundle.meta().get("chars"));
        assertThrows(CaptureException.class, () -> ClipSource.bundleFromText(null));
        assertThrows(CaptureException.class, () -> ClipSource.bundleFromText("  "));
    }
}
