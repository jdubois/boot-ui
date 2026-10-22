package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.advisor.AdvisorFindingIdentity;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical identities of Database Advisor findings, derived from the physical object a finding is about: the
 * datasource first, then the table (catalog, schema and name as separate parts, so a dotted name cannot alias a
 * qualified one), then the index, constraint, column, sequence or setting. Never from display text.
 *
 * <p>Version {@value #VERSION} names these key semantics; change it whenever a key changes meaning.</p>
 */
final class DatabaseIdentities {

    static final String VERSION = "v1";

    private DatabaseIdentities() {}

    /** A table of {@code schema}, followed by the structural {@code extra} parts such as {@code index=ix_orders}. */
    static String table(SchemaSnapshot schema, TableModel table, String... extra) {
        return table(schema.dataSourceName(), table.catalog(), table.schema(), table.name(), extra);
    }

    static String table(String dataSource, String catalog, String schema, String name, String... extra) {
        List<String> parts = new ArrayList<>();
        parts.add("ds=" + dataSource);
        if (catalog != null && !catalog.isBlank()) parts.add("catalog=" + catalog);
        if (schema != null && !schema.isBlank()) parts.add("schema=" + schema);
        parts.add("table=" + name);
        parts.addAll(List.of(extra));
        return AdvisorFindingIdentity.of(VERSION, parts.toArray(String[]::new));
    }

    /** A mapped attribute and the physical column it declares, on a resolved table. */
    static String mapped(SchemaSnapshot schema, TableModel table, String attribute, String column) {
        return table(schema, table, "attribute=" + attribute, "column=" + column);
    }

    /**
     * A foreign key, by its name, or, when the catalog reported none and the name is a numbered placeholder that
     * shifts when another key changes, by its child columns and the table it references.
     */
    static String[] foreignKey(ForeignKeyModel foreignKey) {
        String name = foreignKey.name();
        if (name != null && !name.startsWith("fk#")) {
            return new String[] {"fk=" + name};
        }
        List<String> parts = new ArrayList<>();
        foreignKey.columns().forEach(column -> parts.add("fkcolumn=" + column));
        parts.add("references=" + foreignKey.referencedTable());
        return parts.toArray(String[]::new);
    }

    /** The parts of two foreign keys in sorted order, so the pair has one identity whichever is listed first. */
    static String[] foreignKeyPair(ForeignKeyModel first, ForeignKeyModel second) {
        String[] a = foreignKey(first);
        String[] b = foreignKey(second);
        boolean ordered = String.join("|", a).compareTo(String.join("|", b)) <= 0;
        return concat(ordered ? a : b, ordered ? b : a);
    }

    static String[] concat(String[]... arrays) {
        List<String> all = new ArrayList<>();
        for (String[] array : arrays) all.addAll(List.of(array));
        return all.toArray(String[]::new);
    }

    /** One part for each name, so a name that contains a separator cannot alias a different set of names. */
    static String[] each(String key, List<String> names) {
        return names.stream().map(name -> key + "=" + name).toArray(String[]::new);
    }

    /** A finding keyed on the given parts alone, for one that has no resolved physical object. */
    static String parts(String... parts) {
        return AdvisorFindingIdentity.of(VERSION, parts);
    }

    /** An object of a datasource that has no owning table of its own, such as a sequence or a setting. */
    static String of(String dataSource, String... parts) {
        List<String> all = new ArrayList<>();
        all.add("ds=" + dataSource);
        all.addAll(List.of(parts));
        return AdvisorFindingIdentity.of(VERSION, all.toArray(String[]::new));
    }

    /** The two names of a pair finding in sorted order, so the pair has one identity whichever is listed first. */
    static String[] pair(String key, String first, String second) {
        String a = first == null ? "" : first;
        String b = second == null ? "" : second;
        return a.compareTo(b) <= 0
                ? new String[] {key + "=" + a, key + "=" + b}
                : new String[] {key + "=" + b, key + "=" + a};
    }
}
