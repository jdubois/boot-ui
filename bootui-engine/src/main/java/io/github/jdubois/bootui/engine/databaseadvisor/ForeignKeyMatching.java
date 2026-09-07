package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared logic for matching a mapped {@code @JoinColumn}/{@code @JoinColumns} association against a table's
 * physical foreign key constraints ({@code DatabaseMetaData.getImportedKeys()}).
 *
 * <p>Column order needs different tolerance in each direction. A constraint's own DDL column order does not
 * decide whether it exists — {@code FOREIGN KEY (b, a) REFERENCES parent (pb, pa)} is the same constraint as
 * one declared {@code (a, b) REFERENCES parent (pa, pb)} — but the <em>pairing</em> between a child column and
 * the parent column it actually references does matter: reordering so a child column pairs with a different
 * parent column is a genuinely different (and broken) mapping, not a formatting difference.</p>
 */
final class ForeignKeyMatching {

    private ForeignKeyMatching() {}

    enum Status {
        MATCHED,
        NOT_FOUND,
        UNKNOWN
    }

    record Assessment(Status status, ForeignKeyModel foreignKey, String reason) {}

    static Assessment assess(
            DatabaseAdvisorContext context,
            SchemaSnapshot sourceSchema,
            TableModel source,
            MappedForeignKeyFacts mapped) {
        if (!mapped.targetTableResolved()
                || mapped.columns().isEmpty()
                || mapped.referencedColumns().size() != mapped.columns().size()
                || mapped.columns().stream().anyMatch(ForeignKeyMatching::blank)
                || mapped.referencedColumns().stream().anyMatch(ForeignKeyMatching::blank)) {
            return unknown(null, "The declaration does not establish complete child-to-parent column pairing.");
        }
        MappedTableResolution targetResolution = MappedTableResolution.resolveDeclared(
                context, mapped.targetCatalog(), mapped.targetSchema(), mapped.targetTableName());
        if (!targetResolution.resolved()) {
            return unknown(
                    null,
                    targetResolution.detail() == null
                            ? "The declared target relation was not uniquely observed."
                            : targetResolution.detail());
        }
        if (!sourceSchema.equals(targetResolution.schema())) {
            return unknown(
                    null, "The target declaration resolves to another datasource; attribution is not established.");
        }
        TableModel target = targetResolution.table();
        if (!baseTable(source) || !baseTable(target)) {
            return unknown(
                    null, "Physical foreign-key coverage is not established for views or unknown relation kinds.");
        }
        List<String> childColumns = new ArrayList<>();
        List<String> parentColumns = new ArrayList<>();
        for (int i = 0; i < mapped.columns().size(); i++) {
            String child = mapped.columns().get(i);
            String parent = mapped.referencedColumns().get(i);
            if (!sourceSchema.declarationCaseKnown(child, parent)) {
                return unknown(null, "Identifier folding for a declared join column is unknown.");
            }
            ColumnModel actualChild = sourceSchema.declaredColumn(source, child);
            ColumnModel actualParent = sourceSchema.declaredColumn(target, parent);
            if (actualChild == null || actualParent == null) {
                return unknown(null, "A declared join column was not uniquely observed on its resolved relation.");
            }
            childColumns.add(actualChild.name());
            parentColumns.add(actualParent.name());
        }
        if (childColumns.stream().distinct().count() != childColumns.size()
                || parentColumns.stream().distinct().count() != parentColumns.size()) {
            return unknown(null, "The declared join repeats a child or parent column; pairing is not established.");
        }
        boolean incompleteCandidate = false;
        ForeignKeyModel observed = null;
        for (ForeignKeyModel physical : source.foreignKeys()) {
            if (!sameColumnSet(physical.columns(), childColumns)) {
                continue;
            }
            if (!physical.consistent()) {
                incompleteCandidate = true;
                continue;
            }
            if (!Objects.equals(physical.referencedTable(), target.name())) {
                continue;
            }
            if (!Objects.equals(physical.referencedCatalog(), target.catalog())
                    || !Objects.equals(physical.referencedSchema(), target.schema())) {
                incompleteCandidate |= (physical.referencedCatalog() == null && target.catalog() != null)
                        || (physical.referencedSchema() == null && target.schema() != null);
                continue;
            }
            boolean pairsMatch = true;
            for (int i = 0; i < childColumns.size(); i++) {
                int position = physical.columns().indexOf(childColumns.get(i));
                pairsMatch &= position >= 0
                        && Objects.equals(physical.referencedColumns().get(position), parentColumns.get(i));
            }
            if (!pairsMatch) {
                continue;
            }
            if (Boolean.TRUE.equals(physical.enforced())) {
                return new Assessment(Status.MATCHED, physical, null);
            }
            observed = physical;
        }
        if (observed != null) {
            return unknown(
                    observed,
                    Boolean.FALSE.equals(observed.enforced())
                            ? "A matching physical foreign-key definition is reported disabled/not enforced."
                            : "A matching physical foreign-key definition is observed, but enforcement is unknown.");
        }
        if (!source.metadata().foreignKeysRead() || incompleteCandidate) {
            return unknown(
                    null, "The foreign-key inventory or a potentially matching qualified relationship is incomplete.");
        }
        return new Assessment(Status.NOT_FOUND, null, null);
    }

    private static Assessment unknown(ForeignKeyModel foreignKey, String reason) {
        return new Assessment(Status.UNKNOWN, foreignKey, reason);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean baseTable(TableModel table) {
        return "TABLE".equalsIgnoreCase(table.type()) || "PARTITIONED TABLE".equalsIgnoreCase(table.type());
    }

    /**
     * True when some physical foreign key on {@code table} covers exactly {@code columns} (same size, same
     * column names, order-independent). Used only to tell whether {@code DB-SCHEMA-002} already independently
     * evaluates this foreign key's index coverage from the physical side, so {@code DB-HIB-001} does not
     * double-count the same missing index: every physical foreign key is evaluated by {@code DB-SCHEMA-002}
     * regardless of any Hibernate mapping, so skipping here can only remove a duplicate, never a gap.
     */
    static boolean anyForeignKeyCoversColumnSet(TableModel table, List<String> columns) {
        return table.foreignKeys().stream().anyMatch(foreignKey -> sameColumnSet(foreignKey.columns(), columns));
    }

    /**
     * Checks already-canonicalized physical column pairs and qualified target identity exactly.
     * A false result does not establish absence; declaration-facing callers should use {@link #assess}
     * to distinguish incomplete evidence from a complete no-match inventory.
     */
    static boolean hasMatchingPhysicalForeignKey(TableModel table, MappedForeignKeyFacts foreignKey) {
        return table.foreignKeys().stream().anyMatch(physical -> matches(physical, foreignKey));
    }

    private static boolean matches(ForeignKeyModel physical, MappedForeignKeyFacts mapped) {
        if (!physical.consistent()
                || !mapped.targetTableResolved()
                || mapped.referencedColumns().size() != mapped.columns().size()
                || mapped.referencedColumns().stream().anyMatch(ForeignKeyMatching::blank)) {
            return false;
        }
        List<String> mappedColumns = mapped.columns();
        if (physical.columns().size() != mappedColumns.size() || !sameColumnSet(physical.columns(), mappedColumns)) {
            return false;
        }
        if (!referencesTarget(physical, mapped)) {
            return false;
        }
        List<String> referencedColumns = mapped.referencedColumns();
        for (int i = 0; i < mappedColumns.size(); i++) {
            String expectedParent = i < referencedColumns.size() ? referencedColumns.get(i) : null;
            int physicalIndex = indexOfExact(physical.columns(), mappedColumns.get(i));
            String actualParent = physicalIndex >= 0
                            && physicalIndex < physical.referencedColumns().size()
                    ? physical.referencedColumns().get(physicalIndex)
                    : null;
            if (!equalsExact(actualParent, expectedParent)) {
                return false;
            }
        }
        return true;
    }

    private static boolean referencesTarget(ForeignKeyModel physical, MappedForeignKeyFacts mapped) {
        if (!equalsExact(physical.referencedTable(), mapped.targetTableName())) {
            return false;
        }
        return Objects.equals(physical.referencedSchema(), mapped.targetSchema())
                && Objects.equals(physical.referencedCatalog(), mapped.targetCatalog());
    }

    private static boolean sameColumnSet(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        List<String> remaining = new ArrayList<>(left);
        for (String column : right) {
            int position = column == null ? -1 : remaining.indexOf(column);
            if (position < 0) {
                return false;
            }
            remaining.remove(position);
        }
        return true;
    }

    private static int indexOfExact(List<String> columns, String value) {
        for (int i = 0; i < columns.size(); i++) {
            if (equalsExact(columns.get(i), value)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equalsExact(String left, String right) {
        return left != null && right != null && left.equals(right);
    }
}
