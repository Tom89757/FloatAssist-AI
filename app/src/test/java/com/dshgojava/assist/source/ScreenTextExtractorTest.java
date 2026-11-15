package com.dshgojava.assist.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dshgojava.assist.platform.UiBox;
import com.dshgojava.assist.platform.UiNode;
import com.dshgojava.assist.platform.UiWindow;
import com.dshgojava.assist.platform.WindowKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScreenTextExtractorTest {
    @Test
    void extractsVisibleTextInVisualOrderAndRemovesAdjacentDuplicates() {
        UiNode first = UiNode.builder(new UiBox(0, 100, 400, 140))
                .ref(1).text("Second").build();
        UiNode second = UiNode.builder(new UiBox(0, 20, 400, 60))
                .ref(2).text("First").desc("First").build();
        UiNode root = UiNode.builder(new UiBox(0, 0, 400, 800))
                .ref(0).child(first).child(second).build();
        UiWindow window = new UiWindow(1, WindowKind.APP, "com.example", "Example", root.bounds(),
                root);

        ScreenTextExtractor.Result result = ScreenTextExtractor.extract(List.of(window));

        assertEquals("First\nSecond", result.text());
        assertFalse(result.truncated());
    }

    @Test
    void neverLeaksPasswordTextButMasksItsBounds() {
        UiNode password = UiNode.builder(new UiBox(4, 8, 200, 48))
                .ref(1).text("hunter2").password(true).build();
        UiNode code = UiNode.builder(new UiBox(4, 52, 200, 92))
                .ref(2).text("123456").viewId("com.app:id/otp_code").build();
        UiNode root = UiNode.builder(new UiBox(0, 0, 400, 800))
                .ref(0).child(password).child(code).text("Sign in").build();
        UiWindow window = new UiWindow(1, WindowKind.APP, "com.example", "Example", root.bounds(),
                root);

        ScreenTextExtractor.Result result = ScreenTextExtractor.extract(List.of(window));
        List<UiBox> secrets = ScreenTextExtractor.sensitiveBounds(List.of(window));

        assertEquals("Sign in", result.text());
        assertEquals(2, secrets.size());
        assertTrue(secrets.contains(password.bounds()));
        assertTrue(secrets.contains(code.bounds()));
    }

    @Test
    void truncatesAtTheCharacterBudgetWithoutClaimingCompleteness() {
        UiNode one = UiNode.builder(new UiBox(0, 0, 100, 20)).ref(1).text("12345").build();
        UiNode two = UiNode.builder(new UiBox(0, 21, 100, 40)).ref(2).text("67890").build();
        UiNode root = UiNode.builder(new UiBox(0, 0, 100, 100)).ref(0)
                .child(one).child(two).build();
        UiWindow window = new UiWindow(1, WindowKind.APP, "com.example", null, root.bounds(), root);

        ScreenTextExtractor.Result result = ScreenTextExtractor.extract(List.of(window), 5);

        assertEquals("12345", result.text());
        assertTrue(result.truncated());
    }
}
