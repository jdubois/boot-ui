package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One ordered key part of a physical index.
 *
 * <p>A key part is either a plain column ({@code columnName} set) or an expression/functional key part
 * ({@code expression} set, {@code columnName} {@code null}). {@code prefixLength} carries MySQL/MariaDB's
 * {@code SUB_PART} — an index on {@code name(10)} indexes only the first ten characters. Lookup applicability
 * needs review; a positively known unique value prefix is stronger than full-value uniqueness.</p>
 *
 * @param columnName the indexed column, or {@code null} for an expression key part
 * @param expression the indexed expression, or {@code null} for a plain column key part
 * @param ascending {@code TRUE}/{@code FALSE} when the catalog reports a direction, {@code null} otherwise
 * @param prefixLength the indexed prefix length, or {@code null} when the whole value is indexed
 * @param collation the key part's collation when the catalog reports one, or {@code null}
 */
record IndexKeyPart(String columnName, String expression, Boolean ascending, Integer prefixLength, String collation) {

    static IndexKeyPart column(String columnName, Boolean ascending) {
        return new IndexKeyPart(columnName, null, ascending, null, null);
    }

    static IndexKeyPart expression(String expression) {
        return new IndexKeyPart(null, expression, null, null, null);
    }

    boolean isExpression() {
        return columnName == null;
    }

    /** True when this key part indexes only a prefix of the column value (MySQL/MariaDB {@code SUB_PART}). */
    boolean isPrefix() {
        return prefixLength != null && prefixLength > 0;
    }

    boolean matchesColumn(String candidate) {
        return columnName != null && candidate != null && columnName.equals(candidate);
    }

    String describe() {
        if (isExpression()) {
            return "(" + (expression == null ? "expression" : expression) + ")";
        }
        return isPrefix() ? columnName + "(" + prefixLength + ")" : columnName;
    }
}
