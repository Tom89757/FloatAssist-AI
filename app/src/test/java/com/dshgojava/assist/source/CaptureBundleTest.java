package com.dshgojava.assist.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.model.PromptImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaptureBundleTest {
    @Test
    void promptImageCopiesBytesAndValidatesPayload() {
        byte[] bytes = {1, 2, 3};
        PromptImage image = new PromptImage("image/jpeg", bytes, " shot.jpg ");
        bytes[0] = 9;

        assertEquals("image/jpeg", image.mediaType());
        assertEquals("shot.jpg", image.name());
        assertEquals(3, image.size());
        assertArrayEquals(new byte[]{1, 2, 3}, image.data());
        byte[] exposed = image.data();
        exposed[1] = 8;
        assertArrayEquals(new byte[]{1, 2, 3}, image.data());
        assertThrows(IllegalArgumentException.class,
                () -> new PromptImage("image/jpeg", new byte[0], "empty"));
        assertThrows(IllegalArgumentException.class,
                () -> new PromptImage(" ", new byte[]{1}, "blank"));
    }

    @Test
    void captureBundleCopiesCollectionsAndRejectsEmptyPayloads() {
        List<PromptImage> images = new ArrayList<>();
        images.add(PromptImage.jpeg(new byte[]{1}, "screen.jpg"));
        Map<String, String> meta = new HashMap<>();
        meta.put("source", "screen");

        CaptureBundle bundle = new CaptureBundle("text", images, meta);
        images.clear();
        meta.put("source", "changed");

        assertEquals(1, bundle.images().size());
        assertEquals("screen", bundle.meta().get("source"));
        assertTrue(bundle.hasText());
        assertTrue(bundle.hasImages());
        assertThrows(UnsupportedOperationException.class,
                () -> bundle.meta().put("other", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureBundle("  ", List.of(), Map.of()));
        CaptureBundle imageOnly = new CaptureBundle("", List.of(PromptImage.jpeg(
                new byte[]{7}, "image.jpg")), Map.of());
        assertFalse(imageOnly.hasText());
        assertTrue(imageOnly.hasImages());
    }

    @Test
    void textFactoryAndNullDefaultsRemainUsable() {
        CaptureBundle bundle = CaptureBundle.text("hello", null);
        assertEquals("hello", bundle.text());
        assertTrue(bundle.meta().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureBundle(null, null, null));
    }

}
