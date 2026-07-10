package io.github.jdubois.bootui.engine.vulnerabilities;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compares the Maven version forms used by OSV fixed-version events.
 *
 * <p>The recognized qualifiers and aliases follow Maven {@code ComparableVersion}: {@code alpha < beta <
 * milestone < rc < snapshot < release < sp}, with {@code a}/{@code b}/{@code m} shorthand before a number,
 * {@code cr} as an alias for {@code rc}, and {@code ga}/{@code final}/{@code release} as aliases for the
 * unqualified release. Unknown qualifiers sort after the recognized qualifiers, case-insensitively.
 */
final class MavenVersionComparator {

    private static final List<String> QUALIFIERS = List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");

    private static final Map<String, String> ALIASES = Map.of("ga", "", "final", "", "release", "", "cr", "rc");

    private MavenVersionComparator() {}

    /**
     * @return a negative number, zero, or a positive number as {@code left} is older, equal to, or newer
     *     than {@code right}; {@code null} if either version is blank (comparison not possible)
     */
    static Integer compare(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return null;
        }
        return parse(left).compareTo(parse(right));
    }

    private static ListItem parse(String version) {
        String normalized = version.trim().toLowerCase(Locale.ROOT);
        ListItem root = new ListItem();
        ListItem current = root;
        Deque<ListItem> hierarchy = new ArrayDeque<>();
        hierarchy.push(current);
        int start = 0;
        boolean digits = false;

        for (int i = 0; i < normalized.length(); i++) {
            char value = normalized.charAt(i);
            if (value == '.') {
                current.add(i == start ? NumericItem.ZERO : item(digits, normalized.substring(start, i), false));
                start = i + 1;
            } else if (value == '-') {
                current.add(i == start ? NumericItem.ZERO : item(digits, normalized.substring(start, i), false));
                start = i + 1;
                current = nested(current, hierarchy);
            } else if (Character.isDigit(value)) {
                if (!digits && i > start) {
                    if (!current.isEmpty()) {
                        current = nested(current, hierarchy);
                    }
                    current.add(item(false, normalized.substring(start, i), true));
                    start = i;
                    current = nested(current, hierarchy);
                }
                digits = true;
            } else {
                if (digits && i > start) {
                    current.add(item(true, normalized.substring(start, i), false));
                    start = i;
                    current = nested(current, hierarchy);
                }
                digits = false;
            }
        }
        if (normalized.length() > start) {
            if (!digits && !current.isEmpty()) {
                current = nested(current, hierarchy);
            }
            current.add(item(digits, normalized.substring(start), false));
        }
        while (!hierarchy.isEmpty()) {
            hierarchy.pop().normalize();
        }
        return root;
    }

    private static ListItem nested(ListItem parent, Deque<ListItem> hierarchy) {
        ListItem child = new ListItem();
        parent.add(child);
        hierarchy.push(child);
        return child;
    }

    private static Item item(boolean digits, String value, boolean followedByNumber) {
        if (digits) {
            return new NumericItem(new BigInteger(value));
        }
        if (followedByNumber) {
            value = switch (value) {
                case "a" -> "alpha";
                case "b" -> "beta";
                case "m" -> "milestone";
                default -> value;
            };
        }
        return new StringItem(ALIASES.getOrDefault(value, value));
    }

    private sealed interface Item extends Comparable<Item> permits NumericItem, StringItem, ListItem {

        int compareToNull();
    }

    private record NumericItem(BigInteger value) implements Item {

        private static final NumericItem ZERO = new NumericItem(BigInteger.ZERO);

        @Override
        public int compareTo(Item other) {
            if (other == null) {
                return compareToNull();
            }
            if (other instanceof NumericItem number) {
                return value.compareTo(number.value);
            }
            return 1;
        }

        @Override
        public int compareToNull() {
            return value.signum();
        }
    }

    private record StringItem(String value) implements Item {

        @Override
        public int compareTo(Item other) {
            if (other == null) {
                return compareToNull();
            }
            if (other instanceof StringItem string) {
                return comparableQualifier(value).compareTo(comparableQualifier(string.value));
            }
            return -1;
        }

        @Override
        public int compareToNull() {
            return comparableQualifier(value).compareTo(comparableQualifier(""));
        }

        private static String comparableQualifier(String qualifier) {
            int index = QUALIFIERS.indexOf(qualifier);
            return index < 0 ? QUALIFIERS.size() + "-" + qualifier : String.valueOf(index);
        }
    }

    private static final class ListItem extends ArrayList<Item> implements Item {

        @Override
        public int compareTo(Item other) {
            if (other == null) {
                return compareToNull();
            }
            if (!(other instanceof ListItem list)) {
                return other instanceof NumericItem ? -1 : 1;
            }
            int length = Math.max(size(), list.size());
            for (int i = 0; i < length; i++) {
                Item left = i < size() ? get(i) : null;
                Item right = i < list.size() ? list.get(i) : null;
                int compared = left == null ? (right == null ? 0 : -right.compareToNull()) : left.compareTo(right);
                if (compared != 0) {
                    return compared;
                }
            }
            return 0;
        }

        @Override
        public int compareToNull() {
            for (Item item : this) {
                int compared = item.compareToNull();
                if (compared != 0) {
                    return compared;
                }
            }
            return 0;
        }

        private void normalize() {
            for (int i = size() - 1; i >= 0; i--) {
                Item item = get(i);
                if (item.compareToNull() == 0) {
                    remove(i);
                } else if (!(item instanceof ListItem)) {
                    break;
                }
            }
        }
    }
}
