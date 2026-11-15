package com.dshgojava.assist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CardImeTest {
    @Test
    void recoversStableFullKeyboardHeightFromOverlap() {
        CardIme ime = new CardIme();

        assertEquals(1059, ime.resolve(532, true, 2772, 526, 1719));
        assertEquals(1059, ime.resolve(322, true, 2772, 316, 1719));
        assertEquals(1059, ime.cachedKeyboard());
    }

    @Test
    void ignoresTransientZeroWhileImeIsMarkedVisible() {
        CardIme ime = new CardIme();
        ime.resolve(532, true, 2772, 526, 1719);

        assertEquals(1059, ime.resolve(0, true, 2772, 204, 1304));
    }

    @Test
    void hiddenImeResetsTheCache() {
        CardIme ime = new CardIme();
        ime.resolve(532, true, 2772, 526, 1719);

        assertEquals(0, ime.resolve(0, false, 2772, 526, 1719));
        assertEquals(0, ime.resolve(0, true, 2772, 526, 1719));
    }
}
