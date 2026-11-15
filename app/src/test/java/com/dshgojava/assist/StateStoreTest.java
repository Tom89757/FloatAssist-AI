package com.dshgojava.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StateStoreTest {
    @Test
    void subscribeDeliversSnapshotAndUnsubscribeStopsUpdates() {
        StateStore<String> store = new StateStore<>("initial");
        List<String> values = new ArrayList<>();

        Runnable unsubscribe = store.subscribe(values::add);
        store.setState("next");
        unsubscribe.run();
        store.setState("ignored");

        assertEquals("ignored", store.state());
        assertEquals(List.of("initial", "next"), values);
    }
}
