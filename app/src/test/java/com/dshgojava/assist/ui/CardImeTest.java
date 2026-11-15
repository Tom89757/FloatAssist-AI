package com.dshgojava.assist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CardImeTest {
    @Test
    void ignoresTransientZeroWhileImeIsMarkedVisible() {
        CardIme ime = new CardIme();

        assertEquals(720, ime.resolve(720, true));
        assertEquals(720, ime.resolve(0, true));
        assertEquals(720, ime.cachedBottom());
    }

    @Test
    void hiddenImeResetsTheCache() {
        CardIme ime = new CardIme();
        ime.resolve(720, true);

        assertEquals(0, ime.resolve(0, false));
        assertEquals(0, ime.resolve(0, true));
    }

    @Test
    void newNonZeroInsetReplacesTheCache() {
        CardIme ime = new CardIme();
        ime.resolve(400, true);

        assertEquals(900, ime.resolve(900, true));
        assertEquals(900, ime.resolve(0, true));
    }
}
