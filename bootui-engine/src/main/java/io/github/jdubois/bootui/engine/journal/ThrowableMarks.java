package io.github.jdubois.bootui.engine.journal;

import java.util.Arrays;

/**
 * The identities of a logged or reported throwable, its causes, and their suppressed throwables ({@code docs/PLAN-v2.md}
 * M5-6a): the {@code System.identityHashCode} values the BootUI agent's {@code caught-exceptions} sensor records for an
 * exception application code caught, so the engine can tell that a caught exception was later logged or reached the
 * framework's error handling, without keeping the exception. Never rendered, exported, or persisted: no read or DTO
 * carries it, and two marks are equal when they hold the same identities.
 */
public final class ThrowableMarks {

    /** Throwables of the chain marked at most, the throwable itself included, as the agent walks it. */
    public static final int MAX = 8;

    private static final ThrowableMarks NONE = new ThrowableMarks(new int[0]);

    /**
     * How many routings of the caught-exceptions sensor's records take marks: none, so an application without it pays
     * nothing for a logged or reported throwable, outside the sensor's run.
     */
    private static final java.util.concurrent.atomic.AtomicInteger USERS =
            new java.util.concurrent.atomic.AtomicInteger();

    private final int[] identities;

    private ThrowableMarks(int[] identities) {
        this.identities = identities;
    }

    /**
     * The marks of {@code thrown}, breadth first over causes and suppressed throwables as the agent walks them, cycles
     * cut by identity; {@code null} for a {@code null} throwable. A {@code getCause()} or {@code getSuppressed()} that
     * throws ends its branch. {@code null} too while marks are not taken ({@link #enable}). Never throws but a
     * {@link VirtualMachineError}.
     */
    public static ThrowableMarks of(Throwable thrown) {
        if (thrown == null || USERS.get() <= 0) {
            return null;
        }
        return mark(thrown);
    }

    /**
     * Takes marks from now on, until every caller released it: each routing of the caught-exceptions sensor's records
     * retains it once, so an old run's release during a DevTools restart never stops the new run's marks.
     */
    public static void retain() {
        USERS.incrementAndGet();
    }

    /** Releases one {@link #retain()}. */
    public static void release() {
        USERS.decrementAndGet();
    }

    /** Whether marks are taken now. */
    public static boolean enabled() {
        return USERS.get() > 0;
    }

    /** The marks of {@code thrown}, whether marks are taken or not, for tests. */
    static ThrowableMarks mark(Throwable thrown) {
        Throwable[] seen = new Throwable[MAX];
        Throwable[] queue = new Throwable[MAX];
        int[] marks = new int[MAX];
        int count = 0;
        int head = 0;
        int tail = 0;
        queue[tail++] = thrown;
        while (head < tail && count < MAX) {
            Throwable next = queue[head++];
            boolean known = false;
            for (int i = 0; i < count; i++) {
                known |= seen[i] == next;
            }
            if (known) {
                continue;
            }
            seen[count] = next;
            marks[count++] = System.identityHashCode(next);
            try {
                Throwable cause = next.getCause();
                if (cause != null && cause != next && tail < MAX) {
                    queue[tail++] = cause;
                }
                Throwable[] suppressed = next.getSuppressed();
                for (int i = 0; suppressed != null && i < suppressed.length && tail < MAX; i++) {
                    if (suppressed[i] != null) {
                        queue[tail++] = suppressed[i];
                    }
                }
            } catch (VirtualMachineError ex) {
                throw ex;
            } catch (Throwable ex) {
                // An application's throwable whose chain cannot be read: its branch ends here.
            }
        }
        return new ThrowableMarks(Arrays.copyOf(marks, count));
    }

    /** Marks of the given identities, for tests and fixtures. */
    public static ThrowableMarks ofIdentities(int... identities) {
        return identities == null || identities.length == 0
                ? NONE
                : new ThrowableMarks(Arrays.copyOf(identities, Math.min(MAX, identities.length)));
    }

    /** Whether {@code identity} is one of the marked throwables'. */
    public boolean contains(int identity) {
        for (int mark : identities) {
            if (mark == identity) {
                return true;
            }
        }
        return false;
    }

    /** The marked identities. */
    public int size() {
        return identities.length;
    }

    /** The bytes the marks retain. */
    public int estimatedBytes() {
        return 32 + 4 * identities.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ThrowableMarks marks && Arrays.equals(identities, marks.identities);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(identities);
    }

    /** Names only how many throwables are marked. */
    @Override
    public String toString() {
        return "ThrowableMarks[" + identities.length + "]";
    }
}
