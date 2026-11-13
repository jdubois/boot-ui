package io.github.jdubois.bootui.engine.databaseadvisor;

import java.sql.Types;
import java.util.Locale;
import java.util.Map;

/**
 * Compares a foreign-key column against the column it actually references, beyond a coarse type family: an
 * {@code INT} child referencing a {@code BIGINT} parent has the same family but a narrower range, which is
 * exactly the mismatch that silently breaks once the parent's ids grow past 2^31.
 *
 * <p>Every comparison is conservative. A type this class cannot classify — a domain, an enum, a vendor type,
 * an unknown width — produces no finding at all rather than a guess, because the cost of a wrong "your
 * foreign key is broken" is far higher than the cost of a missed one.</p>
 */
final class ColumnTypeCompatibility {

    /** Integer rank so a narrower child column can be told from a merely differently-named one. */
    private static final Map<String, Integer> INTEGER_RANKS = Map.ofEntries(
            Map.entry("tinyint", 1),
            Map.entry("smallint", 2),
            Map.entry("int2", 2),
            Map.entry("smallserial", 2),
            Map.entry("serial2", 2),
            Map.entry("mediumint", 3),
            Map.entry("int", 4),
            Map.entry("integer", 4),
            Map.entry("int4", 4),
            Map.entry("serial", 4),
            Map.entry("serial4", 4),
            Map.entry("bigint", 5),
            Map.entry("int8", 5),
            Map.entry("bigserial", 5),
            Map.entry("serial8", 5));

    /** pgjdbc and other drivers report sentinel sizes of 1000 or more for an unconstrained numeric. */
    private static final int MAX_BOUNDED_DECIMAL_PRECISION = 1000;

    private ColumnTypeCompatibility() {}

    /**
     * Whether {@link #mismatch} can give a complete answer for this pair. Identical declarations and fixed-width
     * UUID pairs are always complete; other date/time, boolean and vendor pairs stay unknown because precision,
     * {@code date} vs {@code timestamp}, or {@code BIT(n)} width may still differ in ways not modeled here.
     */
    static boolean comparable(ColumnModel child, ColumnModel parent) {
        if (child == null || parent == null || JdbcTypeFamily.of(child) != JdbcTypeFamily.of(parent)) {
            return false;
        }
        if (identicalDeclarations(child, parent)) {
            return true;
        }
        return switch (JdbcTypeFamily.of(child)) {
            case UUID -> true;
            case NUMERIC ->
                (integerRank(child) != null && integerRank(parent) != null)
                        || ((isBoundedDecimal(child) || isUnconstrainedDecimal(child))
                                && (isBoundedDecimal(parent) || isUnconstrainedDecimal(parent)));
            case STRING, BINARY ->
                child.size() != null && parent.size() != null && child.size() > 0 && parent.size() > 0;
            default -> false;
        };
    }

    /**
     * Two identical declarations cannot narrow one another, so the comparison is complete even for a family
     * {@link #mismatch} has no width rules for. A size or scale the driver did not report is unknown, not equal,
     * so it never counts as identical.
     */
    private static boolean identicalDeclarations(ColumnModel child, ColumnModel parent) {
        return child.typeName() != null
                && parent.typeName() != null
                && child.typeName().trim().equalsIgnoreCase(parent.typeName().trim())
                && child.jdbcType() == parent.jdbcType()
                && child.size() != null
                && child.size() > 0
                && child.decimalDigits() != null
                && child.size().equals(parent.size())
                && child.decimalDigits().equals(parent.decimalDigits());
    }

    /**
     * Describes how {@code child} disagrees with {@code parent}, or {@code null} when they are compatible or
     * cannot be compared confidently.
     */
    static String mismatch(ColumnModel child, ColumnModel parent) {
        if (child == null || parent == null) {
            return null;
        }
        JdbcTypeFamily childFamily = JdbcTypeFamily.of(child);
        JdbcTypeFamily parentFamily = JdbcTypeFamily.of(parent);
        if (childFamily == JdbcTypeFamily.OTHER || parentFamily == JdbcTypeFamily.OTHER) {
            return null;
        }
        if (childFamily != parentFamily) {
            return null;
        }
        return switch (childFamily) {
            case NUMERIC -> numericMismatch(child, parent);
            case STRING, BINARY -> widthMismatch(child, parent);
            default -> null;
        };
    }

    private static String numericMismatch(ColumnModel child, ColumnModel parent) {
        Integer childRank = integerRank(child);
        Integer parentRank = integerRank(parent);
        if (childRank != null && parentRank != null) {
            if (childRank < parentRank) {
                return "a narrower integer type that cannot hold every referenced value";
            }
            if ((child.unsigned() && !parent.unsigned())
                    || (!child.unsigned() && parent.unsigned() && childRank.equals(parentRank))) {
                return "a signedness mismatch (" + (child.unsigned() ? "unsigned" : "signed") + " vs "
                        + (parent.unsigned() ? "unsigned" : "signed") + ")";
            }
            return null;
        }
        if (isDecimal(child) && isDecimal(parent)) {
            return decimalMismatch(child, parent);
        }
        return null;
    }

    private static String decimalMismatch(ColumnModel child, ColumnModel parent) {
        boolean childBounded = isBoundedDecimal(child);
        boolean parentBounded = isBoundedDecimal(parent);
        boolean childUnconstrained = isUnconstrainedDecimal(child);
        boolean parentUnconstrained = isUnconstrainedDecimal(parent);
        if (childUnconstrained && (parentUnconstrained || parentBounded)) {
            return null;
        }
        if (childBounded && parentUnconstrained) {
            return "a bounded numeric precision/scale (" + child.size() + "," + child.decimalDigits()
                    + " vs unconstrained)";
        }
        if (!childBounded || !parentBounded) {
            return null;
        }
        int childScale = child.decimalDigits();
        int parentScale = parent.decimalDigits();
        if (childScale < parentScale) {
            return "less fractional capacity (" + childScale + " vs " + parentScale + ")";
        }
        if ((long) child.size() - childScale < (long) parent.size() - parentScale) {
            return "less integer-digit capacity (" + ((long) child.size() - childScale) + " vs "
                    + ((long) parent.size() - parentScale) + ")";
        }
        return null;
    }

    private static String widthMismatch(ColumnModel child, ColumnModel parent) {
        if (child.size() == null || parent.size() == null || child.size() <= 0 || parent.size() <= 0) {
            return null;
        }
        if (child.size() < parent.size()) {
            if (parent.size() == Integer.MAX_VALUE) {
                return "a shorter declared length (" + child.size() + " vs unbounded)";
            }
            return "a shorter declared length (" + child.size() + " vs " + parent.size() + ")";
        }
        return null;
    }

    private static boolean isBoundedDecimal(ColumnModel column) {
        return isDecimal(column)
                && column.size() != null
                && column.size() > 0
                && column.size() <= MAX_BOUNDED_DECIMAL_PRECISION
                && column.decimalDigits() != null
                && column.decimalDigits() != -127;
    }

    private static boolean isUnconstrainedDecimal(ColumnModel column) {
        if (!isDecimal(column) || column.size() == null || column.decimalDigits() == null) {
            return false;
        }
        if (column.size() > MAX_BOUNDED_DECIMAL_PRECISION && column.decimalDigits() == 0) {
            return true;
        }
        String typeName =
                column.typeName() == null ? "" : column.typeName().trim().toLowerCase(Locale.ROOT);
        return ("number".equals(typeName) || "numeric".equals(typeName) || "decimal".equals(typeName))
                && ((column.size() == 0 && (column.decimalDigits() == 0 || column.decimalDigits() == -127))
                        || (column.size() == 38 && column.decimalDigits() == -127));
    }

    private static boolean isDecimal(ColumnModel column) {
        return column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC;
    }

    private static Integer integerRank(ColumnModel column) {
        if (column.typeName() == null) {
            return rankFromJdbcType(column.jdbcType());
        }
        String normalized = column.typeName().toLowerCase(Locale.ROOT).trim();
        int parenthesis = normalized.indexOf('(');
        if (parenthesis >= 0) {
            normalized = normalized.substring(0, parenthesis).trim();
        }
        normalized =
                normalized.replace(" unsigned", "").replace(" zerofill", "").trim();
        Integer rank = INTEGER_RANKS.get(normalized);
        return rank;
    }

    private static Integer rankFromJdbcType(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT -> 1;
            case Types.SMALLINT -> 2;
            case Types.INTEGER -> 4;
            case Types.BIGINT -> 5;
            default -> null;
        };
    }
}
