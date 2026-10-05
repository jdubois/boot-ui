package bootuicaughtapp;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.function.IntSupplier;

/**
 * The caught-exceptions sensor's fixture (PLAN-v2 M5-6a): one method per handler shape the visit must keep valid and
 * report, compiled by javac, so its frames and exception tables are the ones applications have.
 */
public class Handlers {

    private int counter;
    private final int built;

    public Handlers() {
        int value;
        try {
            value = Integer.parseInt("not a number");
        } catch (NumberFormatException ex) {
            value = 3;
        }
        built = value;
    }

    public int built() {
        return built;
    }

    /**
     * Sleeps and waits inside a try whose handler names a type, and inside a synchronized block: the blocking sensor's
     * call-site rewrite (M5-5c) and this sensor's visit on one method.
     */
    public int sleepsInTry() throws InterruptedException {
        Object lock = new Object();
        try {
            Thread.sleep(1);
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(1);
            synchronized (lock) {
                lock.wait(1);
            }
            throw new IllegalStateException("slept");
        } catch (IllegalStateException ex) {
            return 11;
        }
    }

    /** Caught and neither rethrown nor logged. */
    public int swallowed() {
        try {
            throw new IOException("swallowed");
        } catch (IOException ex) {
            return 1;
        }
    }

    /** Caught and rethrown as is. */
    public int rethrows() {
        try {
            fail("rethrown");
            return 0;
        } catch (IllegalStateException ex) {
            throw ex;
        }
    }

    /** Caught and rethrown wrapped. */
    public int wraps() {
        try {
            throw new IOException("wrapped");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Caught and rethrown by a helper, as library helpers do: the throw leaves from the helper. */
    public void helper() {
        try {
            throw new IllegalArgumentException("helper");
        } catch (IllegalArgumentException ex) {
            Rethrow.sneaky(ex);
        }
    }

    /** Rethrown inside the method, then caught again by an outer handler of the same method. */
    public int nested() {
        try {
            try {
                fail("nested");
                return 0;
            } catch (IllegalStateException inner) {
                throw inner;
            }
        } catch (IllegalStateException outer) {
            return 2;
        }
    }

    /** Only a {@code finally}: no handler of its own to report. */
    public int finallyOnly() {
        try {
            return counter + 4;
        } finally {
            counter++;
        }
    }

    public int multi(int which) {
        try {
            if (which == 0) {
                throw new IllegalStateException("state");
            }
            throw new UnsupportedOperationException("operation");
        } catch (IllegalStateException | UnsupportedOperationException ex) {
            return 5 + which;
        }
    }

    /** Caught {@code times} times at one site. */
    public int loop(int times) {
        int caught = 0;
        for (int i = 0; i < times; i++) {
            try {
                throw new IllegalStateException("loop");
            } catch (IllegalStateException ex) {
                caught++;
            }
        }
        return caught;
    }

    /** Wide parameters, each reassigned with a value of its own kind. */
    public static long wide(long count, double ratio, String name, int extra) {
        try {
            count = count + 1L;
            ratio = ratio * 2.0d;
            name = name.trim();
            extra++;
            if (name.isEmpty()) {
                throw new IOException("empty");
            }
            return count + (long) ratio + extra;
        } catch (IOException ex) {
            return -1L;
        }
    }

    public int lambda() {
        IntSupplier supplier = () -> {
            try {
                throw new IllegalStateException("lambda");
            } catch (IllegalStateException ex) {
                return 7;
            }
        };
        return supplier.getAsInt();
    }

    public int resources() {
        try (StringReader reader = new StringReader("fixture")) {
            if (reader.read() != 'f') {
                throw new IOException("unexpected");
            }
            return 8;
        } catch (IOException ex) {
            return -8;
        }
    }

    public synchronized int locked() {
        synchronized (this) {
            try {
                throw new IllegalStateException("locked");
            } catch (IllegalStateException ex) {
                return 9;
            }
        }
    }

    /** An interface default method's handler, through {@link Defaults}. */
    public int defaults() {
        return new Defaults() {}.parse("10");
    }

    static void fail(String message) {
        throw new IllegalStateException(message);
    }

    /** An interface with a default method holding a handler. */
    public interface Defaults {
        default int parse(String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                return -1;
            }
        }
    }
}
