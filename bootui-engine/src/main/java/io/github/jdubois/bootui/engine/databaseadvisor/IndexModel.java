package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One physical index read from {@code DatabaseMetaData.getIndexInfo}, enriched (where the vendor catalog
 * can answer) with validity, partial-index predicate, expression key parts, access method and visibility.
 *
 * <p>Optimizer usability and uniqueness enforcement are separate: invisibility does not disable
 * uniqueness. Unknown definitions cannot prove equivalent indexes.</p>
 *
 * @param name the index name
 * @param keyParts the ordered key parts
 * @param unique whether the index enforces uniqueness
 * @param method the access method/type (e.g. {@code btree}, {@code hash}), or {@code null} when unknown
 * @param filterCondition the partial-index predicate, or {@code null} when the index covers every row
 * @param visibility whether the optimizer may use the index
 * @param validity whether the catalog reports the index as valid/usable
 * @param nullsNotDistinct whether a unique index was declared {@code NULLS NOT DISTINCT} (PostgreSQL 15+), so
 *     it rejects more than one {@code NULL} instead of treating every {@code NULL} as distinct
 * @param automatic known automatic ownership, never inferred from a generated index name
 * @param partitioned whether the index itself is partitioned (Oracle {@code all_indexes.partitioned = 'YES'});
 *     always {@code false} elsewhere
 * @param specialized whether the index has a type this advisor does not model comparison semantics for
 *     (Oracle function-based, domain, bitmap, LOB, or index-organized-table indexes) — excluded from
 *     redundancy comparisons rather than compared as if it were an ordinary B-tree index
 * @param includedColumns non-key payload, kept separate from search keys
 * @param comparisonComplete whether all semantics needed for ordinary-index comparison are known
 * @param uniquenessKnown whether the unique flag was reported rather than defaulted from SQL NULL
 * @param backingConstraint actual catalog constraint linkage, or null when unavailable
 * @param comparisonSemantics exact vendor comparison facts such as operator classes and null ordering
 */
record IndexModel(
        String name,
        List<IndexKeyPart> keyParts,
        boolean unique,
        String method,
        String filterCondition,
        Visibility visibility,
        Validity validity,
        boolean nullsNotDistinct,
        boolean automatic,
        boolean partitioned,
        boolean specialized,
        List<String> includedColumns,
        boolean comparisonComplete,
        Boolean uniquenessKnown,
        String backingConstraint,
        List<String> comparisonSemantics) {

    enum Visibility {
        VISIBLE,
        INVISIBLE,
        UNKNOWN
    }

    enum Validity {
        VALID,
        INVALID,
        UNKNOWN
    }

    enum UniquenessCoverage {
        ENFORCED,
        NOT_ENFORCED,
        UNKNOWN
    }

    IndexModel {
        keyParts = List.copyOf(keyParts);
        visibility = visibility == null ? Visibility.UNKNOWN : visibility;
        validity = validity == null ? Validity.UNKNOWN : validity;
        includedColumns = List.copyOf(includedColumns);
        comparisonSemantics = List.copyOf(comparisonSemantics);
    }

    IndexModel(
            String name,
            List<IndexKeyPart> keyParts,
            boolean unique,
            String method,
            String filterCondition,
            Visibility visibility,
            Validity validity,
            boolean nullsNotDistinct,
            boolean automatic,
            boolean partitioned,
            boolean specialized,
            List<String> includedColumns,
            boolean comparisonComplete,
            Boolean uniquenessKnown,
            String backingConstraint) {
        this(
                name,
                keyParts,
                unique,
                method,
                filterCondition,
                visibility,
                validity,
                nullsNotDistinct,
                automatic,
                partitioned,
                specialized,
                includedColumns,
                comparisonComplete,
                uniquenessKnown,
                backingConstraint,
                List.of());
    }

    IndexModel(
            String name,
            List<IndexKeyPart> keyParts,
            boolean unique,
            String method,
            String filterCondition,
            Visibility visibility,
            Validity validity,
            boolean nullsNotDistinct,
            boolean automatic,
            boolean partitioned,
            boolean specialized) {
        this(
                name,
                keyParts,
                unique,
                method,
                filterCondition,
                visibility,
                validity,
                nullsNotDistinct,
                automatic,
                partitioned,
                specialized,
                List.of(),
                false,
                true,
                null);
    }

    /** Convenience constructor for callers with no Oracle-only/PostgreSQL-15-only information. */
    IndexModel(
            String name,
            List<IndexKeyPart> keyParts,
            boolean unique,
            String method,
            String filterCondition,
            Visibility visibility,
            Validity validity) {
        this(name, keyParts, unique, method, filterCondition, visibility, validity, false, false, false, false);
    }

    /** A plain index with no vendor augmentation, as read from generic JDBC metadata. */
    static IndexModel of(String name, List<String> columns, boolean unique) {
        List<IndexKeyPart> parts = new ArrayList<>();
        for (String column : columns) {
            parts.add(IndexKeyPart.column(column, null));
        }

        return new IndexModel(name, parts, unique, null, null, Visibility.UNKNOWN, Validity.UNKNOWN);
    }

    IndexModel withBackingConstraint(String constraint) {
        return new IndexModel(
                name,
                keyParts,
                unique,
                method,
                filterCondition,
                visibility,
                validity,
                nullsNotDistinct,
                automatic,
                partitioned,
                specialized,
                includedColumns,
                comparisonComplete,
                uniquenessKnown,
                constraint,
                comparisonSemantics);
    }

    List<String> columnNames() {
        return keyParts.stream().map(IndexKeyPart::columnName).toList();
    }

    /** The index's leading key part's column, or {@code null} for an empty or expression-led index. */
    String leadingColumn() {
        return keyParts.isEmpty() ? null : keyParts.get(0).columnName();
    }

    boolean partial() {
        return filterCondition != null && !filterCondition.isBlank();
    }

    boolean hasExpressionKeyPart() {
        return keyParts.stream().anyMatch(IndexKeyPart::isExpression);
    }

    boolean hasPrefixKeyPart() {
        return keyParts.stream().anyMatch(IndexKeyPart::isPrefix);
    }

    boolean invisible() {
        return visibility == Visibility.INVISIBLE;
    }

    boolean invalid() {
        return validity == Validity.INVALID;
    }

    /**
     * True when the optimizer can rely on this index for equality lookups on {@code columns} in that exact
     * order as the index's leading key parts.
     *
     * <p>Deliberately conservative: an invalid, invisible, partial or expression-led index is never counted,
     * and neither is a MySQL/MariaDB prefix key part, which indexes only the first N characters and so
     * cannot answer a full-value equality lookup on its own.</p>
     */
    boolean supportsLeadingEquality(List<String> columns) {
        if (columns.isEmpty() || keyParts.size() < columns.size() || !usable()) {
            return false;
        }
        for (int i = 0; i < columns.size(); i++) {
            IndexKeyPart part = keyParts.get(i);
            if (part.isExpression() || part.isPrefix() || !part.matchesColumn(columns.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the first {@code columns.size()} key parts of this index are exactly {@code columns}, as a
     * set, in any order — Oracle's own documented guidance for what supports a composite foreign key: a pure
     * multi-column equality lookup (the shape every FK-support query, join, and cascading delete/update uses)
     * does not care which of the leading key parts binds to which column, since every one of them is bound by
     * equality at once. Every usability caveat {@link #supportsLeadingEquality} applies still applies:
     * invalid, invisible, partial, expression and prefix key parts are never counted.
     */
    boolean supportsLeadingEqualityAnyOrder(List<String> columns) {
        if (columns.isEmpty() || keyParts.size() < columns.size() || !usable()) {
            return false;
        }
        List<IndexKeyPart> leading = keyParts.subList(0, columns.size());
        if (leading.stream().anyMatch(part -> part.isExpression() || part.isPrefix())) {
            return false;
        }
        List<String> remaining = new ArrayList<>(columns);
        for (IndexKeyPart part : leading) {
            int position = remaining.indexOf(part.columnName());
            if (position < 0) {
                return false;
            }
            remaining.remove(position);
        }
        return remaining.isEmpty();
    }

    /**
     * True when known index enforcement covers the requested tuple, including a stronger unique subset.
     */
    boolean enforcesUniquenessOver(List<String> columns) {
        return uniquenessCoverage(columns) == UniquenessCoverage.ENFORCED;
    }

    /** Unique subsets need known NULL semantics; deterministic value prefixes can enforce full values. */
    UniquenessCoverage uniquenessCoverage(List<String> columns) {
        return uniquenessCoverage(columns, false);
    }

    UniquenessCoverage uniquenessCoverage(List<String> columns, boolean subsetColumnsNotNull) {
        if (columns.isEmpty() || columns.stream().anyMatch(Objects::isNull) || !Boolean.TRUE.equals(uniquenessKnown)) {
            return UniquenessCoverage.UNKNOWN;
        }
        if (!unique) {
            return backingConstraint == null ? UniquenessCoverage.NOT_ENFORCED : UniquenessCoverage.UNKNOWN;
        }
        if (validity != Validity.VALID
                || partial()
                || specialized
                || partitioned
                || hasExpressionKeyPart()
                || keyParts.isEmpty()
                || keyParts.stream()
                        .anyMatch(part -> part.collation() != null
                                || (part.prefixLength() != null && part.prefixLength() <= 0))) {
            return UniquenessCoverage.UNKNOWN;
        }
        if (!columns.containsAll(columnNames())) {
            return UniquenessCoverage.NOT_ENFORCED;
        }
        if (columns.stream().distinct().count()
                        > columnNames().stream().distinct().count()
                && !nullsNotDistinct
                && !subsetColumnsNotNull) {
            return UniquenessCoverage.UNKNOWN;
        }
        return UniquenessCoverage.ENFORCED;
    }

    /** True when the index covers exactly {@code columns} in the same order, with plain key parts only. */
    boolean coversExactlyInOrder(List<String> columns) {
        if (keyParts.size() != columns.size()) {
            return false;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (!keyParts.get(i).matchesColumn(columns.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** True when nothing in the catalog marks this index as unusable by the optimizer. */
    boolean usable() {
        return !invalid() && !invisible() && !partial();
    }

    /**
     * True when both ordinary definitions have known and equal non-key semantics.
     */
    boolean sameSemanticsAs(IndexModel other) {
        return comparable()
                && other.comparable()
                && unique == other.unique
                && nullsNotDistinct == other.nullsNotDistinct
                && Objects.equals(normalizedMethod(), other.normalizedMethod())
                && Objects.equals(filterCondition, other.filterCondition)
                && includedColumns.equals(other.includedColumns)
                && comparisonSemantics.equals(other.comparisonSemantics)
                && visibility == other.visibility
                && validity == other.validity
                && automatic == other.automatic
                && partitioned == other.partitioned
                && specialized == other.specialized;
    }

    boolean comparable() {
        return comparisonComplete
                && Boolean.TRUE.equals(uniquenessKnown)
                && method != null
                && List.of("btree", "b-tree", "normal", "clustered").contains(normalizedMethod())
                && visibility != Visibility.UNKNOWN
                && validity == Validity.VALID
                && !partial()
                && !partitioned
                && !specialized
                && !hasExpressionKeyPart()
                && !hasPrefixKeyPart()
                && !keyParts.isEmpty()
                && keyParts.stream().allMatch(part -> part.ascending() != null);
    }

    boolean exactDuplicateOf(IndexModel other) {
        return sameSemanticsAs(other) && keyParts.equals(other.keyParts());
    }

    /** True when {@code this} index's key parts are a leading prefix of {@code other}'s (or identical). */
    boolean isKeyPrefixOf(IndexModel other) {
        if (keyParts.isEmpty() || keyParts.size() > other.keyParts.size()) {
            return false;
        }
        for (int i = 0; i < keyParts.size(); i++) {
            if (!sameKeyPart(keyParts.get(i), other.keyParts.get(i))) {
                return false;
            }
        }
        return true;
    }

    String describeKeyParts() {
        return keyParts.stream().map(IndexKeyPart::describe).toList().toString();
    }

    private static boolean sameKeyPart(IndexKeyPart left, IndexKeyPart right) {
        if (left.isExpression() != right.isExpression()) {
            return false;
        }
        if (left.isExpression()) {
            return left.expression() != null && Objects.equals(left.expression(), right.expression());
        }
        return left.matchesColumn(right.columnName())
                && Objects.equals(left.prefixLength(), right.prefixLength())
                && Objects.equals(left.ascending(), right.ascending())
                && Objects.equals(left.collation(), right.collation());
    }

    private String normalizedMethod() {
        return normalize(method);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
