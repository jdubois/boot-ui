package io.github.jdubois.bootui.agent.bridge;

/**
 * Per-thread state the bridge's recording entry points share (PLAN-v2 M5-3): a re-entrancy guard held around every call
 * to the claim's {@code capture} that recording makes, so capture is never re-entered on a thread (a class it loads, or
 * an instrumented method it runs, records without capturing again), and the engine's "BootUI work" flag, set around
 * BootUI's own scans and class-presence checks so that what they load is not counted as the application's. The
 * side-effect sensors also skip the agent's own transformations, which the agent's transformer listener marks while it
 * transforms a class, and BootUI's and the agent's threads, marked once when they start ({@link SideEffects}). The holder
 * is an {@code int[]}, a JDK type, so the thread-local pins no class loader.
 */
final class Reentrancy {

    private static final int GUARD = 0;
    private static final int BOOTUI_WORK = 1;
    /** The agent's own transformations open on this thread, nested ({@link #agentWork(boolean)}). */
    private static final int AGENT_WORK = 2;
    /** Set once for the thread's life: one of BootUI's or the agent's own threads ({@link #markBootUiThread()}). */
    private static final int BOOTUI_THREAD = 3;

    static final ThreadLocal<int[]> STATE = new ThreadLocal<int[]>();

    private Reentrancy() {}

    private static int[] state() {
        int[] state = STATE.get();
        if (state == null) {
            state = new int[4];
            STATE.set(state);
        }
        return state;
    }

    /** Takes the guard: false when this thread already holds it, in which case {@link #exit} must not be called. */
    static boolean enter() {
        int[] state = state();
        if (state[GUARD] != 0) {
            return false;
        }
        state[GUARD] = 1;
        return true;
    }

    /** Releases the guard taken by a successful {@link #enter}. */
    static void exit() {
        int[] state = STATE.get();
        if (state != null) {
            state[GUARD] = 0;
        }
    }

    /** Whether this thread holds the guard: it is inside a capture made by recording. */
    static boolean guarded() {
        int[] state = STATE.get();
        return state != null && state[GUARD] != 0;
    }

    /** Whether the engine marked this thread's current work as BootUI's own. */
    static boolean bootUiWork() {
        int[] state = STATE.get();
        return state != null && state[BOOTUI_WORK] != 0;
    }

    /** Marks or unmarks this thread's current work as BootUI's own; returns the previous mark. */
    static boolean bootUiWork(boolean on) {
        int[] state = on ? state() : STATE.get();
        if (state == null) {
            return false;
        }
        boolean previous = state[BOOTUI_WORK] != 0;
        state[BOOTUI_WORK] = on ? 1 : 0;
        return previous;
    }

    /**
     * The agent starts (true) or ends (false) transforming a class on this thread: nested, as a class loaded while
     * another is transformed, and never below zero.
     */
    static void agentWork(boolean on) {
        int[] state = on ? state() : STATE.get();
        if (state == null) {
            return;
        }
        if (on) {
            state[AGENT_WORK]++;
        } else if (state[AGENT_WORK] > 0) {
            state[AGENT_WORK]--;
        }
    }

    /** Marks the calling thread, for its life, as one of BootUI's or the agent's own. */
    static void markBootUiThread() {
        state()[BOOTUI_THREAD] = 1;
    }

    /**
     * Whether a side-effect sensor skips what this thread does now: BootUI's own work or thread, the agent's own
     * transformation, or a capture made by recording.
     */
    static boolean sideEffectsSkipped() {
        int[] state = STATE.get();
        return state != null
                && (state[GUARD] != 0
                        || state[BOOTUI_WORK] != 0
                        || state[AGENT_WORK] != 0
                        || state[BOOTUI_THREAD] != 0);
    }
}
