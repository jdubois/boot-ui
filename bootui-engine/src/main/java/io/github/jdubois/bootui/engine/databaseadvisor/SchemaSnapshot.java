package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * The physical schema read from one {@code DataSource}, plus every vendor catalog augmentation attempted for
 * it, the diagnostics collected on the way, and whether a bound cut the scan short.
 *
 * <p>A datasource that could not be introspected at all is represented with an empty {@link #tables()} and a
 * non-null {@link #error()} (already credential-redacted). A datasource that was introspected but whose
 * metadata is incomplete keeps its readable tables and reports the gaps through {@link #diagnostics()} —
 * partial data is still useful, silence is not.</p>
 */
record SchemaSnapshot(
        String dataSourceName,
        Dialect dialect,
        String databaseProductName,
        DatabaseVersion version,
        String identifierCase,
        List<TableModel> tables,
        VendorFindings vendorFindings,
        List<SchemaDiagnostic> diagnostics,
        boolean truncated,
        String error,
        boolean relationInventoryComplete) {

    SchemaSnapshot(
            String dataSourceName,
            Dialect dialect,
            String databaseProductName,
            DatabaseVersion version,
            String identifierCase,
            List<TableModel> tables,
            VendorFindings vendorFindings,
            List<SchemaDiagnostic> diagnostics,
            boolean truncated,
            String error) {
        this(
                dataSourceName,
                dialect,
                databaseProductName,
                version,
                identifierCase,
                tables,
                vendorFindings,
                diagnostics,
                truncated,
                error,
                error == null && !truncated);
    }

    SchemaSnapshot {
        tables = List.copyOf(tables);
        diagnostics = List.copyOf(diagnostics);
        truncated |= tables.stream().anyMatch(table -> table.metadata().truncated())
                || !vendorFindings.truncations().isEmpty();
    }

    static SchemaSnapshot failed(String dataSourceName, String error) {
        SchemaDiagnostic diagnostic = SchemaDiagnostic.error(dataSourceName, error);
        return new SchemaSnapshot(
                dataSourceName,
                Dialect.GENERIC,
                null,
                DatabaseVersion.UNKNOWN,
                null,
                List.of(),
                VendorFindings.EMPTY,
                List.of(diagnostic),
                false,
                diagnostic.message());
    }

    boolean available() {
        return error == null;
    }

    /** True when this snapshot is complete: nothing truncated, nothing that failed to read. */
    boolean complete() {
        return available()
                && !truncated
                && tables.stream().allMatch(table -> table.metadata().complete())
                && vendorFindings.failures().isEmpty()
                && diagnostics.stream()
                        .noneMatch(diagnostic -> SchemaDiagnostic.ERROR.equals(diagnostic.level())
                                || SchemaDiagnostic.WARNING.equals(diagnostic.level()));
    }

    String describeProduct() {
        if (databaseProductName == null || databaseProductName.isBlank()) {
            return dialect.label();
        }
        return version.known()
                ? databaseProductName + " " + version.major() + "." + version.minor()
                : databaseProductName;
    }

    /** The unique exact-name table across schemas, or {@code null} when absent or ambiguous. */
    TableModel table(String tableName) {
        return table(null, null, tableName);
    }

    /** The unique exact match with optional qualifiers, or {@code null} when absent or ambiguous. */
    TableModel table(String catalog, String schema, String tableName) {
        List<TableModel> matches = tables.stream()
                .filter(table -> table.matches(catalog, schema, tableName))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /** Every table matching {@code tableName}, across schemas — used to detect ambiguous matches. */
    List<TableModel> tablesNamed(String catalog, String schema, String tableName) {
        return tables.stream()
                .filter(table -> table.matches(catalog, schema, tableName))
                .toList();
    }

    TableModel exactTable(String catalog, String schema, String tableName) {
        List<TableModel> candidates = tables.stream()
                .filter(table -> java.util.Objects.equals(table.catalog(), catalog)
                        && java.util.Objects.equals(table.schema(), schema)
                        && java.util.Objects.equals(table.name(), tableName))
                .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    List<TableModel> declaredTablesNamed(String catalog, String schema, String tableName) {
        return tables.stream()
                .filter(table -> declaredMatches(table.name(), tableName)
                        && (catalog == null || catalog.isBlank() || declaredMatches(table.catalog(), catalog))
                        && (schema == null || schema.isBlank() || declaredMatches(table.schema(), schema)))
                .toList();
    }

    ColumnModel declaredColumn(TableModel table, String declaredName) {
        List<ColumnModel> candidates = table.columns().stream()
                .filter(column -> declaredMatches(column.name(), declaredName))
                .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    boolean declaredMatches(String actual, String declared) {
        if (actual == null || declared == null) {
            return false;
        }
        if (declared.length() >= 2
                && ((declared.startsWith("\"") && declared.endsWith("\""))
                        || (declared.startsWith("`") && declared.endsWith("`")))) {
            String quote = declared.substring(0, 1);
            return actual.equals(declared.substring(1, declared.length() - 1).replace(quote + quote, quote));
        }
        if ("INSENSITIVE".equals(identifierCase)) {
            return actual.equalsIgnoreCase(declared);
        }
        String folded = "UPPER".equals(identifierCase)
                ? declared.toUpperCase(java.util.Locale.ROOT)
                : "LOWER".equals(identifierCase) ? declared.toLowerCase(java.util.Locale.ROOT) : declared;
        return actual.equals(folded);
    }

    boolean declarationCaseKnown(String... names) {
        if (identifierCase != null) {
            return true;
        }
        for (String name : names) {
            if (name != null
                    && !name.isBlank()
                    && !(name.length() >= 2
                            && ((name.startsWith("\"") && name.endsWith("\""))
                                    || (name.startsWith("`") && name.endsWith("`"))))) {
                return false;
            }
        }
        return true;
    }
}
