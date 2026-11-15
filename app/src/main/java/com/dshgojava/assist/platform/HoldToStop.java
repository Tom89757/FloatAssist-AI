package com.dshgojava.assist.platform;

/** The ball's press-and-hold stop: tap opens, holding still for a second stops. */
public final class HoldToStop {
    public static final long ARM_MS = 300L;
    public static final long FILL_MS = 700L;

    public enum State { IDLE, PRESSED, FILLING, FIRED, DRAGGING }

    public enum Release { TAP, CANCELLED, DRAG, STOP, STOPPED, NONE }

    private final long armMs;
    private final long fillMs;
    private State state = State.IDLE;
    private long downAt;

    public HoldToStop() {
        this(ARM_MS, FILL_MS);
    }

    public HoldToStop(long armMs, long fillMs) {
        this.armMs = armMs;
        this.fillMs = fillMs;
    }

    public State state() { return state; }
    public long armMs() { return armMs; }
    public long fillMs() { return fillMs; }

    public void down(long now) {
        state = State.PRESSED;
        downAt = now;
    }

    public void moved() {
        if (state == State.PRESSED || state == State.FILLING) state = State.DRAGGING;
    }

    /** True exactly once, when the hold has lasted long enough to stop. */
    public boolean tick(long now) {
        if (state != State.PRESSED && state != State.FILLING) return false;
        long held = now - downAt;
        if (held >= armMs + fillMs) {
            state = State.FIRED;
            return true;
        }
        if (held >= armMs) state = State.FILLING;
        return false;
    }

    /** The ring, 0–1 (0 until {@link #armMs()}). */
    public float progress(long now) {
        return switch (state) {
            case PRESSED, FILLING -> clamp((float) (now - downAt - armMs) / fillMs);
            case FIRED -> 1f;
            default -> 0f;
        };
    }

    /** When {@link #tick(long)} next has work, or null when nothing is pressed. */
    public Long nextAt() {
        return switch (state) {
            case PRESSED -> downAt + armMs;
            case FILLING -> downAt + armMs + fillMs;
            default -> null;
        };
    }

    public Release up(long now) {
        boolean closedNow = tick(now);
        Release release = switch (state) {
            case PRESSED -> Release.TAP;
            case FILLING -> Release.CANCELLED;
            case DRAGGING -> Release.DRAG;
            case FIRED -> closedNow ? Release.STOP : Release.STOPPED;
            case IDLE -> Release.NONE;
        };
        state = State.IDLE;
        return release;
    }

    public void cancel() {
        state = State.IDLE;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
