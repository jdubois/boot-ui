package io.github.jdubois.bootui.agent;

import java.io.IOException;
import java.io.StringReader;
import java.util.function.IntSupplier;

/**
 * The caught-exceptions sensor's self-test probe (PLAN-v2 M5-6a): the one class outside the claimed packages its visit
 * instruments, loaded after the transformer is installed, so the JVM verifies the visit's output as the class is
 * defined. Its methods hold the shapes the visit must keep valid: a static method with wide and reassigned parameters,
 * an instance method, a constructor, a lambda, a multi-catch, a {@code finally}, and try-with-resources; one handler
 * rethrows through a helper, as library helpers do, so its method's exit handler must see the throw.
 */
final class CaughtProbe {

    /** What {@link #run()} answers when every shape ran. */
    static final int EXPECTED = 127;

    private final int base;

    CaughtProbe(String text) {
        int parsed;
        try {
            parsed = Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            parsed = 1;
        }
        base = parsed;
    }

    /** Runs every shape. */
    static int run() {
        int answer = new CaughtProbe("x").base;
        answer += wide(1L, 2.0d, "a", 3) == 7 ? 2 : 0;
        answer += new CaughtProbe("0").instance("y") ? 4 : 0;
        IntSupplier lambda = () -> {
            try {
                throw new IllegalStateException("lambda");
            } catch (IllegalStateException ex) {
                return 8;
            }
        };
        answer += lambda.getAsInt();
        answer += multi(0) + multi(1);
        answer += resources();
        try {
            rethrown();
        } catch (IllegalArgumentException expected) {
            answer += 64;
        }
        return answer;
    }

    /** Wide parameters, reassigned with values of their own kinds. */
    static int wide(long count, double ratio, String name, int extra) {
        try {
            count = count + 1L;
            ratio = ratio * 1.0d;
            name = name.trim();
            extra++;
            if (name.isEmpty()) {
                throw new IOException("empty");
            }
            return (int) (count + ratio) + extra - 1;
        } catch (IOException ex) {
            return -1;
        }
    }

    boolean instance(String value) {
        try {
            return Integer.parseInt(value) > base;
        } catch (NumberFormatException ex) {
            return true;
        } finally {
            value = null;
        }
    }

    static int multi(int which) {
        try {
            if (which == 0) {
                throw new IllegalStateException("state");
            }
            throw new UnsupportedOperationException("operation");
        } catch (IllegalStateException | UnsupportedOperationException ex) {
            return 8;
        }
    }

    static int resources() {
        try (StringReader reader = new StringReader("probe")) {
            if (reader.read() != 'p') {
                throw new IOException("unexpected");
            }
            return 32;
        } catch (IOException ex) {
            return 0;
        }
    }

    /** Catches, then rethrows through a helper: the throw leaves this method from the helper, not from here. */
    static void rethrown() {
        try {
            throw new IllegalArgumentException("probe");
        } catch (IllegalArgumentException ex) {
            sneaky(ex);
        }
    }

    static void sneaky(RuntimeException ex) {
        throw ex;
    }
}
