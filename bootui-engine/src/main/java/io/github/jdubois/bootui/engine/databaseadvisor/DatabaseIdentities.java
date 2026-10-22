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
        return a.compareTo(b) <= 0 ? new String[] {key + "=" + a, key + "=" + b} : new String[] {key + "=" + b, key + "=" + a};
    }
}
