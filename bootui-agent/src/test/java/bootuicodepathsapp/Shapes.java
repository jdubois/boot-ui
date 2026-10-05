package bootuicodepathsapp;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Methods for method probes' argument and return shapes (PLAN-v2 M5-8, D44): every argument kind, an application
 * collection and an application object whose every method counts its calls, so a test proves the agent never runs one.
 */
public class Shapes {

    /** Calls of any method of {@link Basket} or {@link Card}: a shapes probe must leave it at 0. */
    public static final AtomicInteger CALLS = new AtomicInteger();

    public enum Level {
        LOW,
        HIGH {
            @Override
            public String toString() {
                CALLS.incrementAndGet();
                return "high";
            }
        }
    }

    public int quote(
            String sku,
            List<Integer> items,
            Basket basket,
            Card card,
            Optional<String> coupon,
            Level level,
            int[] codes,
            Integer count,
            Object nothing,
            long beyond) {
        return sku.length() + codes.length;
    }

    public Basket basket(String id) {
        return new Basket();
    }

    public List<String> names(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("name-" + i);
        }
        return names;
    }

    public void touch(Card card) {}

    /** An application collection: its size() may run anything, so a shape names its class only. */
    public static final class Basket extends AbstractCollection<Object> {

        @Override
        public Iterator<Object> iterator() {
            CALLS.incrementAndGet();
            return Collections.emptyIterator();
        }

        @Override
        public int size() {
            CALLS.incrementAndGet();
            return 99;
        }

        @Override
        public String toString() {
            CALLS.incrementAndGet();
            return "basket";
        }

        @Override
        public int hashCode() {
            CALLS.incrementAndGet();
            return 1;
        }

        @Override
        public boolean equals(Object other) {
            CALLS.incrementAndGet();
            return false;
        }
    }

    /** An application object holding a secret: its getter, toString, hashCode, and equals count their calls. */
    public static final class Card {

        private final String number = "4111111111111111";

        public String getNumber() {
            CALLS.incrementAndGet();
            return number;
        }

        @Override
        public String toString() {
            CALLS.incrementAndGet();
            return number;
        }

        @Override
        public int hashCode() {
            CALLS.incrementAndGet();
            return 1;
        }

        @Override
        public boolean equals(Object other) {
            CALLS.incrementAndGet();
            return false;
        }
    }
}
