package com.dshgojava.assist;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal observer store for one-way snapshots. Listener notifications happen
 * on the thread that publishes the state, so Android callers must marshal to
 * the main thread before touching views.
 */
public final class StateStore<T> {
    public interface Listener<T> {
        void onStateChanged(T state);
    }

    private final List<Listener<T>> listeners = new CopyOnWriteArrayList<>();
    private volatile T state;

    public StateStore(T initialState) {
        state = Objects.requireNonNull(initialState, "initialState");
    }

    public T state() {
        return state;
    }

    /** Subscribes and immediately delivers the current snapshot. */
    public Runnable subscribe(Listener<T> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        listener.onStateChanged(state);
        return () -> listeners.remove(listener);
    }

    public void setState(T nextState) {
        state = Objects.requireNonNull(nextState, "nextState");
        for (Listener<T> listener : listeners) {
            listener.onStateChanged(nextState);
        }
    }
}
