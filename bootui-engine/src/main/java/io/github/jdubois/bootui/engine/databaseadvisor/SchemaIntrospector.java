package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * Reads the physical schema of one {@code DataSource} through plain {@code java.sql.DatabaseMetaData} —
 * tables, columns, primary keys, foreign keys, and indexes — and augments it, for PostgreSQL and
 * MySQL/MariaDB, with the read-only catalog facts the generic JDBC API cannot answer.
 *
 * <p>This is purely read-only: it never executes DDL and never queries application data, only driver catalog
 * metadata and system-catalog rows. Three properties matter as much as the data itself:</p>
 *
 * <ul>
 *   <li><strong>Bounded.</strong> Every list is read one row past its bound so truncation is detected
 *       deterministically, every catalog statement carries a query timeout clamped to the remaining scan
 *       budget, and the scan stops between tables once the budget is spent — keeping whatever it already
 *       read.</li>
 *   <li><strong>Honest.</strong> A failure introspecting one datasource, one table, or one catalog
 *       augmentation is recorded as a diagnostic and leaves the rest of the scan intact; nothing that failed
 *       is ever presented as a clean result.</li>
 *   <li><strong>Non-invasive.</strong> The connection's original read-only flag is restored before it goes
 *       back to the pool, and no other connection state is touched.</li>
 * </ul>
 */
final class SchemaIntrospector {

    private static final String[] TABLE_TYPES = {"TABLE", "PARTITIONED TABLE"};
    private static final String[] FALLBACK_TABLE_TYPES = {"TABLE"};

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema",
            "pg_catalog",
            "pg_toast",
            "mysql",
            "performance_schema",
            "sys",
            "sys_config",
            "innodb");

    private SchemaIntrospector() {}

    static SchemaSnapshot introspect(String dataSourceName, DataSource dataSource) {
        return introspect(
                dataSourceName,
                dataSource,
                ScanBudget.of(DatabaseAdvisorLimits.DEFAULTS.scanBudget()),
                DatabaseAdvisorLimits.DEFAULTS);
    }

    static SchemaSnapshot introspect(
            String dataSourceName, DataSource dataSource, ScanBudget budget, DatabaseAdvisorLimits limits) {
        if (dataSource == null) {
            return SchemaSnapshot.failed(dataSourceName, "DataSource bean is not available.");
        }
        return introspect(dataSourceName, dataSource::getConnection, budget, limits);
    }

    /** Test seam: the same introspection over any source of a JDBC {@link Connection}. */
    @FunctionalInterface
    interface ConnectionSource {
        Connection get() throws SQLException;
    }

    static SchemaSnapshot introspect(
            String dataSourceName, ConnectionSource connectionSource, ScanBudget budget, DatabaseAdvisorLimits limits) {
        if (budget.exhausted()) {
            return SchemaSnapshot.failed(
                    dataSourceName, "The Database Advisor scan budget ran out before this datasource was read.");
        }
        try (Connection connection = connectionSource.get()) {
            if (connection == null) {
                return SchemaSnapshot.failed(dataSourceName, "The DataSource returned no connection.");
            }
            return introspect(dataSourceName, connection, budget, limits);
        } catch (SQLException | RuntimeException ex) {
            return SchemaSnapshot.failed(dataSourceName, describe(ex));
        }
    }

    private static SchemaSnapshot introspect(
            String dataSourceName, Connection connection, ScanBudget budget, DatabaseAdvisorLimits limits)
            throws SQLException {
        if (budget.exhausted()) {
            return SchemaSnapshot.failed(
                    dataSourceName, "The scan budget ran out during connection acquisition; no metadata was read.");
        }
        Boolean originalReadOnly = currentReadOnly(connection);
        boolean readOnlyApplied = Boolean.FALSE.equals(originalReadOnly) && trySetReadOnly(connection);
        SchemaSnapshot snapshot;
        String restorationFailure = null;
        try {
            snapshot = read(dataSourceName, connection, budget, limits);
        } catch (SQLException | RuntimeException ex) {
            snapshot = SchemaSnapshot.failed(dataSourceName, describe(ex));
        } finally {
            restorationFailure = restoreReadOnly(connection, originalReadOnly, readOnlyApplied);
        }
        List<SchemaDiagnostic> diagnostics = new ArrayList<>(snapshot.diagnostics());
        if (originalReadOnly == null) {
            diagnostics.add(
                    SchemaDiagnostic.info(
                            dataSourceName,
                            "The original read-only state is unknown; it was not changed. Only read-only metadata queries were issued."));
        }
        if (restorationFailure != null) {
            diagnostics.add(SchemaDiagnostic.warning(dataSourceName, restorationFailure));
        }
        return new SchemaSnapshot(
                snapshot.dataSourceName(),
                snapshot.dialect(),
                snapshot.databaseProductName(),
                snapshot.version(),
                snapshot.identifierCase(),
                snapshot.tables(),
                snapshot.vendorFindings(),
                diagnostics,
                snapshot.truncated(),
                snapshot.error(),
                snapshot.relationInventoryComplete());
    }

    private static SchemaSnapshot read(
            String dataSourceName, Connection connection, ScanBudget budget, DatabaseAdvisorLimits limits)
            throws SQLException {
        List<SchemaDiagnostic> diagnostics = new ArrayList<>();
        requireBudget(budget);
        DatabaseMetaData metaData = connection.getMetaData();
        String productName = safeString(metaData::getDatabaseProductName, budget);
        if (productName == null || productName.isBlank()) {
            diagnostics.add(SchemaDiagnostic.warning(
                    dataSourceName,
                    "Database product could not be identified; vendor-check applicability is unknown."));
        }
        String productVersion = safeString(metaData::getDatabaseProductVersion, budget);
        String url = safeString(metaData::getURL, budget);
        Dialect dialect = resolveOracle(
                connection,
                Dialect.detect(productName, productVersion, url),
                dataSourceName,
                diagnostics,
                budget,
                limits);
        DatabaseVersion version = readVersion(metaData, productVersion, budget);
        DialectCapabilities capabilities = DialectCapabilities.of(dialect, version);

        String oracleSchema = dialect == Dialect.ORACLE
                ? oracleCurrentSchema(connection, dataSourceName, diagnostics, budget, limits)
                : null;
        if (dialect == Dialect.ORACLE && oracleSchema == null) {
            return SchemaSnapshot.failed(
                    dataSourceName, "Oracle CURRENT_SCHEMA could not be established; the scoped scan was not widened.");
        }

        TableReadResult tableResult =
                readTables(dataSourceName, connection, metaData, oracleSchema, budget, limits, diagnostics);

        VendorFindings.Builder vendorFindings = VendorFindings.builder();
        if (dialect == Dialect.POSTGRESQL) {
            PostgresCatalogReader.read(connection, version, capabilities, budget, limits, vendorFindings);
        } else if (dialect.isMySqlFamily()) {
            MySqlCatalogReader.read(connection, dialect, capabilities, budget, limits, vendorFindings);
        } else if (dialect == Dialect.ORACLE) {
            OracleCatalogReader.read(connection, oracleSchema, version, capabilities, budget, limits, vendorFindings);
        }
        VendorFindings findings = vendorFindings.build();
        for (VendorAugmentation<?> failure : findings.failures()) {
            diagnostics.add(SchemaDiagnostic.warning(dataSourceName, failure.reason()));
        }
        for (VendorAugmentation<?> truncation : findings.truncations()) {
            diagnostics.add(SchemaDiagnostic.warning(
                    dataSourceName,
                    truncation.kind().label() + " was truncated at " + limits.maxVendorFindings()
                            + " rows; some findings may be missing."));
        }

        List<TableModel> tables = VendorSchemaMerge.merge(tableResult.tables(), dialect, findings);
        return new SchemaSnapshot(
                dataSourceName,
                dialect,
                productName,
                version,
                readIdentifierCase(metaData, budget),
                tables,
                findings,
                diagnostics,
                tableResult.truncated() || !findings.truncations().isEmpty(),
                null,
                tableResult.inventoryComplete());
    }

    /**
     * A driver-reported {@code "Oracle"} product name is not proof of a genuine Oracle Database server: some
     * Oracle-compatible databases (OceanBase's driver, in its 2.2.x default or with
     * {@code useCompatibleMetadata=true}) report it too. {@code v$version.banner} (falling back to
     * {@code product_component_version.product} for a role locked out of {@code v$} views, which are not
     * always granted to {@code PUBLIC}) is Oracle's own self-identification: a genuine server's banner has
     * "Oracle" appearing before "Database" (matching both the long-standing "Oracle Database 19c ..." banner
     * and the newer "Oracle AI Database 26ai ..." rebrand). OceanBase's own banner reads
     * {@code "OceanBase Database ... (Oracle Compatible Mode)"} — "Database" appears <em>before</em> "Oracle"
     * there, so the order-sensitive check still tells them apart. Tibero and EDB Postgres Advanced Server
     * report their own product names and never collide on the product name check at all. A server that
     * cannot confirm itself this way is treated as {@link Dialect#GENERIC}: it still gets the full generic
     * JDBC ruleset, just not Oracle-specific augmentation it may not actually support.
     */
    static Dialect resolveOracle(
            Connection connection, Dialect detected, String dataSourceName, List<SchemaDiagnostic> diagnostics) {
        return resolveOracle(
                connection,
                detected,
                dataSourceName,
                diagnostics,
                ScanBudget.of(DatabaseAdvisorLimits.DEFAULTS.scanBudget()),
                DatabaseAdvisorLimits.DEFAULTS);
    }

    private static Dialect resolveOracle(
            Connection connection,
            Dialect detected,
            String dataSourceName,
            List<SchemaDiagnostic> diagnostics,
            ScanBudget budget,
            DatabaseAdvisorLimits limits) {
        if (detected != Dialect.ORACLE) {
            return detected;
        }
        if (budget.exhausted()) {
            diagnostics.add(SchemaDiagnostic.warning(
                    dataSourceName, "The scan budget ran out before Oracle Database could be confirmed."));
            return Dialect.GENERIC;
        }
        if (reportsAnOracleBanner(connection, "select banner from v$version", budget, limits)
                || reportsAnOracleBanner(connection, "select product from product_component_version", budget, limits)) {
            return Dialect.ORACLE;
        }
        diagnostics.add(SchemaDiagnostic.info(
                dataSourceName,
                "The driver reported an Oracle-compatible product name, but neither v$version nor "
                        + "product_component_version confirmed a genuine Oracle Database/Oracle AI Database "
                        + "server; Oracle-specific catalog augmentation was not attempted."));
        return Dialect.GENERIC;
    }

    private static boolean reportsAnOracleBanner(
            Connection connection, String sql, ScanBudget budget, DatabaseAdvisorLimits limits) {
        if (budget.exhausted()) {
            return false;
        }
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(budget.remainingSecondsAtMost(limits.statementTimeoutSeconds()));
            statement.setMaxRows(limits.maxVendorFindings() + 1);
            try (ResultSet rs = statement.executeQuery(sql)) {
                int rows = 0;
                while (rs.next()) {
                    if (++rows > limits.maxVendorFindings() || budget.exhausted()) {
                        return false;
                    }
                    String banner = normalize(rs.getString(1));
                    int oracleIndex = banner.indexOf("oracle");
                    int databaseIndex = banner.indexOf("database");
                    if (oracleIndex >= 0 && databaseIndex > oracleIndex) {
                        return true;
                    }
                }
            }
        } catch (SQLException | RuntimeException ex) {
            return false;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * The connected session's {@code CURRENT_SCHEMA}, so the generic table scan and every Oracle-specific
     * {@code ALL_*} query stay scoped to it — never to every schema the connected user can merely see.
     */
    private static String oracleCurrentSchema(
            Connection connection,
            String dataSourceName,
            List<SchemaDiagnostic> diagnostics,
            ScanBudget budget,
            DatabaseAdvisorLimits limits) {
        try {
            if (budget.exhausted()) {
                diagnostics.add(SchemaDiagnostic.warning(
                        dataSourceName, "The scan budget ran out before Oracle's CURRENT_SCHEMA could be read."));
                return null;
            }
            String schema =
                    OracleSessionContext.read(connection, budget, limits).currentSchema();
            if (schema == null || schema.isBlank()) {
                diagnostics.add(SchemaDiagnostic.warning(
                        dataSourceName,
                        "Oracle's CURRENT_SCHEMA could not be determined; the scan may not be scoped to the "
                                + "connected schema."));
                return null;
            }
            return schema;
        } catch (SQLException | RuntimeException ex) {
            diagnostics.add(SchemaDiagnostic.warning(
                    dataSourceName,
                    "Oracle session context (CURRENT_SCHEMA) could not be read (" + describe(ex) + "); the scan "
                            + "may not be scoped to the connected schema."));
            return null;
        }
    }

    private record TableReadResult(List<TableModel> tables, boolean truncated, boolean inventoryComplete) {}

    private record RelationTypes(String[] types, boolean complete) {}

    private static RelationTypes relationTypes(DatabaseMetaData metadata, ScanBudget budget) {
        List<String> types = new ArrayList<>(List.of(TABLE_TYPES));
        boolean view = false;
        boolean table = false;
        try {
            requireBudget(budget);
            try (ResultSet rows = metadata.getTableTypes()) {
                int examined = 0;
                while (rows.next()) {
                    requireBudget(budget);
                    if (++examined > 128) {
                        return new RelationTypes(types.toArray(String[]::new), false);
                    }
                    String type = rows.getString("TABLE_TYPE");
                    table |= "TABLE".equalsIgnoreCase(type);
                    if ("VIEW".equalsIgnoreCase(type) || "MATERIALIZED VIEW".equalsIgnoreCase(type)) {
                        types.add(type);
                        view |= "VIEW".equalsIgnoreCase(type);
                    }
                }
            }
        } catch (SQLException | RuntimeException ex) {
            return new RelationTypes(types.toArray(String[]::new), false);
        }
        return new RelationTypes(types.toArray(String[]::new), view && table);
    }

    private static TableReadResult readTables(
            String dataSourceName,
            Connection connection,
            DatabaseMetaData metaData,
            String schemaPattern,
            ScanBudget budget,
            DatabaseAdvisorLimits limits,
            List<SchemaDiagnostic> diagnostics)
            throws SQLException {
        String catalog = safeString(connection::getCatalog, budget);
        RelationTypes types = relationTypes(metaData, budget);
        RefReadResult result =
                readTableRefs(dataSourceName, metaData, catalog, schemaPattern, limits, diagnostics, budget, types);
        List<TableRef> refs = result.refs();
        boolean truncated = result.truncated();
        boolean inventoryTruncated = result.truncated();
        if (truncated) {
            refs = refs.subList(0, Math.min(refs.size(), limits.maxTables()));
            diagnostics.add(SchemaDiagnostic.warning(
                    dataSourceName,
                    "The table metadata row bound or cooperative deadline was reached; only " + refs.size()
                            + " scoped relations were retained and coverage is incomplete."));
        }
        String escape = searchStringEscape(metaData, budget);
        List<TableModel> tables = new ArrayList<>();
        for (TableRef ref : refs) {
            if (budget.exhausted()) {
                truncated = true;
                inventoryTruncated = true;
                diagnostics.add(SchemaDiagnostic.warning(
                        dataSourceName,
                        "The scan budget ran out after " + tables.size()
                                + " tables; the remaining tables were not analyzed."));
                break;
            }
            TableModel table = readTable(metaData, ref, escape, limits, budget);
            tables.add(table);
            truncated |= table.metadata().truncated();
            for (String issue : table.metadata().issues()) {
                diagnostics.add(SchemaDiagnostic.warning(dataSourceName + "/" + table.qualifiedName(), issue));
            }
        }
        return new TableReadResult(
                tables, truncated, types.complete() && result.complete() && !inventoryTruncated && !tables.isEmpty());
    }

    private record TableRef(String catalog, String schema, String name, String type) {}

    private record RefReadResult(List<TableRef> refs, boolean truncated, boolean complete) {}

    private static RefReadResult readTableRefs(
            String dataSourceName,
            DatabaseMetaData metaData,
            String catalog,
            String schemaPattern,
            DatabaseAdvisorLimits limits,
            List<SchemaDiagnostic> diagnostics,
            ScanBudget budget,
            RelationTypes types)
            throws SQLException {
        String escapedSchemaPattern = escapePattern(schemaPattern, searchStringEscape(metaData, budget));
        try {
            return readTableRefs(metaData, catalog, escapedSchemaPattern, schemaPattern, types.types(), limits, budget);
        } catch (SQLException ex) {
            // Not every driver accepts a table type it does not know; retry with the universal "TABLE" type
            // rather than losing the whole datasource over PostgreSQL's partitioned-table type.
            diagnostics.add(SchemaDiagnostic.info(
                    dataSourceName,
                    "The driver rejected the PARTITIONED TABLE type filter; retried with TABLE only (" + describe(ex)
                            + ")."));
            RefReadResult fallback = readTableRefs(
                    metaData, catalog, escapedSchemaPattern, schemaPattern, FALLBACK_TABLE_TYPES, limits, budget);
            return new RefReadResult(fallback.refs(), fallback.truncated(), false);
        }
    }

    private static RefReadResult readTableRefs(
            DatabaseMetaData metaData,
            String catalog,
            String schemaPattern,
            String exactSchema,
            String[] types,
            DatabaseAdvisorLimits limits,
            ScanBudget budget)
            throws SQLException {
        List<TableRef> refs = new ArrayList<>();
        boolean truncated = false;
        requireBudget(budget);
        try (ResultSet rs = metaData.getTables(catalog, schemaPattern, "%", types)) {
            // One row past the bound: seeing max + 1 candidates is what makes truncation observable.
            int examined = 0;
            while (rs.next()) {
                if (++examined > limits.maxTables() || budget.exhausted()) {
                    truncated = true;
                    break;
                }
                String tableSchema = rs.getString("TABLE_SCHEM");
                String tableName = rs.getString("TABLE_NAME");
                if (tableName == null
                        || isSystemSchema(tableSchema)
                        || (catalog != null && !Objects.equals(catalog, rs.getString("TABLE_CAT")))
                        || (exactSchema != null && !exactSchema.equals(tableSchema))) {
                    continue;
                }
                refs.add(new TableRef(rs.getString("TABLE_CAT"), tableSchema, tableName, rs.getString("TABLE_TYPE")));
            }
        }
        return new RefReadResult(refs, truncated, true);
    }

    private static TableModel readTable(
            DatabaseMetaData metaData, TableRef ref, String escape, DatabaseAdvisorLimits limits, ScanBudget budget) {
        List<String> issues = new ArrayList<>();
        boolean truncated = false;

        List<ColumnModel> columns = List.of();
        boolean columnsRead = true;
        try {
            ColumnReadResult result = readColumns(metaData, ref, escape, limits, budget);
            columns = result.columns();
            columnsRead = !result.truncated() && !columns.isEmpty();
            if (columns.isEmpty()) {
                issues.add("No scoped column metadata was reported for " + qualified(ref)
                        + "; driver/privilege coverage is unknown.");
            }
            truncated |= result.truncated();
            if (result.truncated()) {
                issues.add("Column metadata for " + qualified(ref) + " reached its raw-row bound or deadline; "
                        + columns.size() + " scoped columns were retained.");
            }
        } catch (SQLException | RuntimeException ex) {
            columnsRead = false;
            truncated |= ex instanceof MetadataBoundException;
            issues.add("Columns of " + qualified(ref) + " could not be read: " + describe(ex));
        }

        String primaryKeyName = null;
        List<String> primaryKeyColumns = List.of();
        boolean primaryKeyRead = true;
        try {
            PrimaryKey primaryKey = readPrimaryKey(metaData, ref, budget, limits);
            primaryKeyName = primaryKey.name();
            primaryKeyColumns = primaryKey.columns();
        } catch (SQLException | RuntimeException ex) {
            primaryKeyRead = false;
            truncated |= ex instanceof MetadataBoundException;
            issues.add("The primary key of " + qualified(ref) + " could not be read: " + describe(ex));
        }

        List<ForeignKeyModel> foreignKeys = List.of();
        boolean foreignKeysRead = true;
        try {
            foreignKeys = readForeignKeys(metaData, ref.catalog(), ref.schema(), ref.name(), budget, limits);
        } catch (SQLException | RuntimeException ex) {
            foreignKeysRead = false;
            truncated |= ex instanceof MetadataBoundException;
            if (ex instanceof ForeignKeyMetadataException partial) {
                foreignKeys = partial.readableKeys;
            }
            issues.add("Foreign keys of " + qualified(ref) + " could not be read: " + describe(ex));
        }

        List<IndexModel> indexes = List.of();
        boolean indexesRead = true;
        try {
            IndexReadResult result = readIndexes(metaData, ref, limits, budget);
            indexes = result.indexes();
            indexesRead = !result.truncated();
            truncated |= result.truncated();
            if (result.truncated()) {
                issues.add("Index metadata for " + qualified(ref) + " reached its row/index bound or deadline; "
                        + indexes.size() + " complete indexes were retained.");
            }
        } catch (SQLException | RuntimeException ex) {
            indexesRead = false;
            truncated |= ex instanceof MetadataBoundException;
            issues.add("Indexes of " + qualified(ref) + " could not be read: " + describe(ex));
        }

        TableMetadata metadata =
                new TableMetadata(columnsRead, primaryKeyRead, foreignKeysRead, indexesRead, truncated, issues);
        return new TableModel(
                ref.catalog(),
                ref.schema(),
                ref.name(),
                ref.type(),
                columns,
                primaryKeyName,
                primaryKeyColumns,
                foreignKeys,
                indexes,
                "PARTITIONED TABLE".equalsIgnoreCase(ref.type()),
                false,
                false,
                metadata);
    }

    private record ColumnReadResult(List<ColumnModel> columns, boolean truncated) {}

    private static ColumnReadResult readColumns(
            DatabaseMetaData metaData, TableRef ref, String escape, DatabaseAdvisorLimits limits, ScanBudget budget)
            throws SQLException {
        List<ColumnModel> columns = new ArrayList<>();
        boolean truncated = false;
        requireBudget(budget);
        try (ResultSet rs = metaData.getColumns(
                ref.catalog(), escapePattern(ref.schema(), escape), escapePattern(ref.name(), escape), "%")) {
            int rows = 0;
            while (rs.next()) {
                if (++rows > limits.maxColumnsPerTable() || budget.exhausted()) {
                    truncated = true;
                    break;
                }
                String tableName = rs.getString("TABLE_NAME");
                if (!Objects.equals(tableName, ref.name())
                        || !Objects.equals(rs.getString("TABLE_CAT"), ref.catalog())
                        || !Objects.equals(rs.getString("TABLE_SCHEM"), ref.schema())) {
                    // getColumns takes patterns, so an escaped-but-still-matching sibling table can appear.
                    continue;
                }
                if (columns.size() >= limits.maxColumnsPerTable()) {
                    truncated = true;
                    break;
                }
                columns.add(readColumn(rs));
            }
        }
        return new ColumnReadResult(columns, truncated);
    }

    private static ColumnModel readColumn(ResultSet rs) throws SQLException {
        Integer size = nullableInt(rs, "COLUMN_SIZE");
        Integer decimalDigits = nullableInt(rs, "DECIMAL_DIGITS");
        return new ColumnModel(
                rs.getString("COLUMN_NAME"),
                rs.getString("TYPE_NAME"),
                java.util.Objects.requireNonNullElse(nullableInt(rs, "DATA_TYPE"), java.sql.Types.OTHER),
                nullability(nullableInt(rs, "NULLABLE")),
                size,
                decimalDigits,
                "YES".equalsIgnoreCase(safeColumn(rs, "IS_AUTOINCREMENT")));
    }

    private record PrimaryKey(String name, List<String> columns) {}

    private static PrimaryKey readPrimaryKey(
            DatabaseMetaData metaData, TableRef ref, ScanBudget budget, DatabaseAdvisorLimits limits)
            throws SQLException {
        Map<Integer, String> byPosition = new TreeMap<>();
        String name = null;
        requireBudget(budget);
        try (ResultSet rs = metaData.getPrimaryKeys(ref.catalog(), ref.schema(), ref.name())) {
            int rows = 0;
            while (rs.next()) {
                checkRows(++rows, limits.maxColumnsPerTable(), budget);
                requireScope(rs, "", ref.catalog(), ref.schema(), ref.name());
                Integer position = nullableInt(rs, "KEY_SEQ");
                String column = rs.getString("COLUMN_NAME");
                if (position == null || position <= 0 || column == null || byPosition.put(position, column) != null) {
                    throw new SQLException("Primary-key positions are missing or ambiguous.");
                }
                if (name != null && !Objects.equals(name, rs.getString("PK_NAME"))) {
                    throw new SQLException("Primary-key rows disagree on the constraint identity.");
                }
                if (name == null) {
                    name = rs.getString("PK_NAME");
                }
            }
        }
        requireConsecutive(byPosition);
        List<String> columns = byPosition.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        return new PrimaryKey(name, columns);
    }

    static List<ForeignKeyModel> readForeignKeys(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        return readForeignKeys(
                metaData,
                catalog,
                schema,
                table,
                ScanBudget.of(DatabaseAdvisorLimits.DEFAULTS.scanBudget()),
                DatabaseAdvisorLimits.DEFAULTS);
    }

    private record ForeignKeyIdentity(String name, String catalog, String schema, String table) {}

    private record ForeignKeyRow(String child, String parent, Integer update, Integer delete, Integer deferrability) {}

    private static final class ForeignKeyMetadataException extends SQLException {
        private final List<ForeignKeyModel> readableKeys;

        ForeignKeyMetadataException(List<ForeignKeyModel> readableKeys) {
            super(
                    "Some foreign-key grouping is ambiguous, positions are not consecutive, or relationship semantics disagree.");
            this.readableKeys = List.copyOf(readableKeys);
        }
    }

    private static List<ForeignKeyModel> readForeignKeys(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            String table,
            ScanBudget budget,
            DatabaseAdvisorLimits limits)
            throws SQLException {
        Map<ForeignKeyIdentity, Map<Integer, ForeignKeyRow>> grouped = new LinkedHashMap<>();
        Set<ForeignKeyIdentity> invalid = new java.util.HashSet<>();
        requireBudget(budget);
        try (ResultSet rs = metaData.getImportedKeys(catalog, schema, table)) {
            int rows = 0;
            while (rs.next()) {
                checkRows(++rows, rawKeyLimit(limits), budget);
                requireScope(rs, "FK", catalog, schema, table);
                String fkName = rs.getString("FK_NAME");
                Integer keySeq = nullableInt(rs, "KEY_SEQ");
                ForeignKeyIdentity identity = new ForeignKeyIdentity(
                        fkName,
                        rs.getString("PKTABLE_CAT"),
                        rs.getString("PKTABLE_SCHEM"),
                        rs.getString("PKTABLE_NAME"));
                ForeignKeyRow row = new ForeignKeyRow(
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKCOLUMN_NAME"),
                        action(nullableInt(rs, "UPDATE_RULE")),
                        action(nullableInt(rs, "DELETE_RULE")),
                        deferrability(nullableInt(rs, "DEFERRABILITY")));
                if (keySeq == null
                        || keySeq <= 0
                        || identity.table() == null
                        || row.child() == null
                        || row.parent() == null) {
                    invalid.add(identity);
                    continue;
                }
                if (grouped.computeIfAbsent(identity, ignored -> new TreeMap<>())
                                .put(keySeq, row)
                        != null) {
                    invalid.add(identity);
                }
            }
        }
        List<ForeignKeyModel> foreignKeys = new ArrayList<>();
        int unnamed = 0;
        Map<String, Integer> named = new LinkedHashMap<>();
        for (ForeignKeyIdentity identity : grouped.keySet()) {
            if (identity.name() != null) {
                named.merge(identity.name(), 1, Integer::sum);
            }
        }
        for (var entry : grouped.entrySet()) {
            ForeignKeyIdentity identity = entry.getKey();
            if (invalid.contains(identity)) {
                continue;
            }
            try {
                requireConsecutive(entry.getValue());
            } catch (SQLException ex) {
                invalid.add(identity);
                continue;
            }
            if (identity.name() != null && named.get(identity.name()) > 1) {
                invalid.add(identity);
                continue;
            }
            ForeignKeyRow first = entry.getValue().get(1);
            if (entry.getValue().values().stream()
                    .anyMatch(row -> !Objects.equals(row.update(), first.update())
                            || !Objects.equals(row.delete(), first.delete())
                            || !Objects.equals(row.deferrability(), first.deferrability()))) {
                invalid.add(identity);
                continue;
            }
            ForeignKeyModel foreignKey = new ForeignKeyModel(
                    identity.name() == null ? "fk#" + unnamed++ : identity.name(),
                    entry.getValue().values().stream().map(ForeignKeyRow::child).toList(),
                    identity.catalog(),
                    identity.schema(),
                    identity.table(),
                    entry.getValue().values().stream()
                            .map(ForeignKeyRow::parent)
                            .toList(),
                    first.update(),
                    first.delete(),
                    first.deferrability());
            if (foreignKey.consistent()) {
                foreignKeys.add(foreignKey);
            } else {
                invalid.add(identity);
            }
        }
        if (!invalid.isEmpty()) {
            throw new ForeignKeyMetadataException(foreignKeys);
        }
        return foreignKeys;
    }

    private record IndexReadResult(List<IndexModel> indexes, boolean truncated) {}

    private static IndexReadResult readIndexes(
            DatabaseMetaData metaData, TableRef ref, DatabaseAdvisorLimits limits, ScanBudget budget)
            throws SQLException {
        Map<String, Map<Integer, IndexKeyPart>> partsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();
        Map<String, String> filterByIndex = new LinkedHashMap<>();
        Map<String, String> methodByIndex = new LinkedHashMap<>();
        Map<String, String> qualifierByIndex = new LinkedHashMap<>();
        Set<String> completed = new java.util.HashSet<>();
        String currentIndex = null;
        boolean truncated = false;
        // approximate = true keeps this off the table-statistics path some drivers take otherwise; the index
        // definitions themselves are exact either way.
        requireBudget(budget);
        try (ResultSet rs = metaData.getIndexInfo(ref.catalog(), ref.schema(), ref.name(), false, true)) {
            int rows = 0;
            while (rs.next()) {
                if (++rows > rawKeyLimit(limits) || budget.exhausted()) {
                    truncated = true;
                    break;
                }
                requireScope(rs, "", ref.catalog(), ref.schema(), ref.name());
                Integer type = nullableInt(rs, "TYPE");
                if (type != null && type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String indexName = rs.getString("INDEX_NAME");
                if (indexName == null) {
                    continue;
                }
                String qualifier = rs.getString("INDEX_QUALIFIER");
                if (qualifierByIndex.containsKey(indexName)
                        && !Objects.equals(qualifierByIndex.get(indexName), qualifier)) {
                    throw new SQLException("Same-named indexes have ambiguous qualified identities.");
                }
                qualifierByIndex.put(indexName, qualifier);
                if (currentIndex != null && !currentIndex.equals(indexName)) {
                    completed.add(currentIndex);
                    if (completed.contains(indexName)) {
                        throw new SQLException("Index groups are interleaved contrary to JDBC ordering.");
                    }
                }
                currentIndex = indexName;
                if (!partsByIndex.containsKey(indexName) && partsByIndex.size() >= limits.maxIndexesPerTable()) {
                    truncated = true;
                    break;
                }
                String columnName = rs.getString("COLUMN_NAME");
                String ascOrDesc = rs.getString("ASC_OR_DESC");
                Boolean ascending = "A".equalsIgnoreCase(ascOrDesc)
                        ? Boolean.TRUE
                        : "D".equalsIgnoreCase(ascOrDesc) ? Boolean.FALSE : null;
                Integer position = nullableInt(rs, "ORDINAL_POSITION");
                if (position == null || position <= 0) {
                    throw new SQLException("Index positions are missing or invalid.");
                }
                if (partsByIndex
                                .computeIfAbsent(indexName, ignored -> new TreeMap<>())
                                .put(
                                        position,
                                        columnName == null
                                                ? IndexKeyPart.expression(null)
                                                : IndexKeyPart.column(columnName, ascending))
                        != null) {
                    throw new SQLException("Index positions are ambiguous.");
                }
                boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                Boolean unique = rs.wasNull() ? null : !nonUnique;
                if (uniqueByIndex.containsKey(indexName) && !Objects.equals(uniqueByIndex.get(indexName), unique)) {
                    throw new SQLException("Index rows disagree on uniqueness.");
                }
                uniqueByIndex.put(indexName, unique);
                String filterCondition = rs.getString("FILTER_CONDITION");
                if (filterCondition != null && !filterCondition.isBlank()) {
                    filterByIndex.putIfAbsent(indexName, filterCondition);
                }
                methodByIndex.putIfAbsent(indexName, type == null ? null : indexMethod(type.shortValue()));
            }
        }
        List<IndexModel> indexes = new ArrayList<>();
        for (var entry : partsByIndex.entrySet()) {
            if (truncated && !completed.contains(entry.getKey())) {
                // JDBC orders rows by index identity and position; only earlier closed groups are complete.
                continue;
            }
            requireConsecutive(entry.getValue());
            indexes.add(new IndexModel(
                    entry.getKey(),
                    List.copyOf(entry.getValue().values()),
                    Boolean.TRUE.equals(uniqueByIndex.get(entry.getKey())),
                    methodByIndex.get(entry.getKey()),
                    filterByIndex.get(entry.getKey()),
                    IndexModel.Visibility.UNKNOWN,
                    IndexModel.Validity.UNKNOWN,
                    false,
                    false,
                    false,
                    false,
                    List.of(),
                    false,
                    uniqueByIndex.get(entry.getKey()) != null,
                    null));
        }
        return new IndexReadResult(indexes, truncated);
    }

    private static String indexMethod(short type) {
        return switch (type) {
            case DatabaseMetaData.tableIndexClustered -> "clustered";
            case DatabaseMetaData.tableIndexHashed -> "hashed";
            case DatabaseMetaData.tableIndexOther -> null;
            default -> null;
        };
    }

    private static ColumnModel.Nullability nullability(Integer reported) {
        if (reported == null) {
            return ColumnModel.Nullability.UNKNOWN;
        }
        return switch (reported) {
            case DatabaseMetaData.columnNullable -> ColumnModel.Nullability.NULLABLE;
            case DatabaseMetaData.columnNoNulls -> ColumnModel.Nullability.NOT_NULL;
            default -> ColumnModel.Nullability.UNKNOWN;
        };
    }

    private static DatabaseVersion readVersion(DatabaseMetaData metaData, String productVersion, ScanBudget budget) {
        try {
            requireBudget(budget);
            int major = metaData.getDatabaseMajorVersion();
            requireBudget(budget);
            int minor = metaData.getDatabaseMinorVersion();
            return DatabaseVersion.of(major, minor, productVersion);
        } catch (SQLException | RuntimeException ex) {
            return DatabaseVersion.UNKNOWN;
        }
    }

    private static String readIdentifierCase(DatabaseMetaData metaData, ScanBudget budget) {
        try {
            requireBudget(budget);
            if (metaData.storesUpperCaseIdentifiers()) {
                return "UPPER";
            }
            requireBudget(budget);
            if (metaData.storesLowerCaseIdentifiers()) {
                return "LOWER";
            }
            requireBudget(budget);
            if (metaData.supportsMixedCaseIdentifiers()) {
                return "MIXED";
            }
            requireBudget(budget);
            if (metaData.storesMixedCaseIdentifiers()) {
                return "INSENSITIVE";
            }
        } catch (SQLException | RuntimeException ex) {
            return null;
        }
        return null;
    }

    private static String searchStringEscape(DatabaseMetaData metaData, ScanBudget budget) {
        try {
            requireBudget(budget);
            return metaData.getSearchStringEscape();
        } catch (SQLException | RuntimeException ex) {
            return null;
        }
    }

    /** Escapes JDBC metadata pattern wildcards so a table named {@code user_data} matches only itself. */
    private static String escapePattern(String value, String escape) {
        if (value == null || escape == null || escape.isEmpty()) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            if (value.startsWith(escape, i)) {
                escaped.append(escape).append(escape);
                i += escape.length() - 1;
                continue;
            }
            char character = value.charAt(i);
            if (character == '_' || character == '%') {
                escaped.append(escape);
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) {
            return false;
        }
        String normalized = schema.toLowerCase(Locale.ROOT);
        return SYSTEM_SCHEMAS.contains(normalized)
                || normalized.startsWith("pg_temp")
                || normalized.startsWith("pg_toast");
    }

    private static Boolean currentReadOnly(Connection connection) {
        try {
            return connection.isReadOnly();
        } catch (SQLException | RuntimeException ex) {
            return null;
        }
    }

    private static boolean trySetReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
            return true;
        } catch (SQLException | RuntimeException ex) {
            // Not every driver supports read-only mode; the scanner never issues a write regardless.
            return false;
        }
    }

    /**
     * Puts the connection back exactly as it was found. Pooled connections are reused by the application, so
     * leaving one flipped to read-only would break the next writer that borrows it.
     */
    private static String restoreReadOnly(Connection connection, Boolean originalReadOnly, boolean applied) {
        if (!applied || originalReadOnly == null || originalReadOnly) {
            return null;
        }
        try {
            connection.setReadOnly(false);
        } catch (SQLException | RuntimeException ex) {
            return "The connection's original read-only state could not be restored: " + describe(ex);
        }
        return null;
    }

    private static final class MetadataBoundException extends SQLException {
        MetadataBoundException(String message) {
            super(message);
        }
    }

    private static void requireBudget(ScanBudget budget) throws SQLException {
        if (budget.exhausted()) {
            throw new MetadataBoundException("The cooperative metadata scan budget ran out.");
        }
    }

    private static void checkRows(int rows, int maximum, ScanBudget budget) throws SQLException {
        requireBudget(budget);
        if (rows > maximum) {
            throw new MetadataBoundException("Metadata exceeded its raw-row bound of " + maximum + ".");
        }
    }

    private static int rawKeyLimit(DatabaseAdvisorLimits limits) {
        return (int) Math.min(Integer.MAX_VALUE - 1L, (long) limits.maxIndexesPerTable() * limits.maxColumnsPerTable());
    }

    private static void requireConsecutive(Map<Integer, ?> positions) throws SQLException {
        int expected = 1;
        for (Integer position : positions.keySet()) {
            if (position != expected++) {
                throw new SQLException("Metadata key positions are not consecutive from one.");
            }
        }
    }

    private static void requireScope(ResultSet rows, String prefix, String catalog, String schema, String table)
            throws SQLException {
        if (!Objects.equals(catalog, rows.getString(prefix + "TABLE_CAT"))
                || !Objects.equals(schema, rows.getString(prefix + "TABLE_SCHEM"))
                || !Objects.equals(table, rows.getString(prefix + "TABLE_NAME"))) {
            throw new SQLException("Metadata rows do not establish the requested exact qualified table scope.");
        }
    }

    private static Integer action(Integer value) {
        return value != null
                        && value >= DatabaseMetaData.importedKeyCascade
                        && value <= DatabaseMetaData.importedKeySetDefault
                ? value
                : null;
    }

    private static Integer deferrability(Integer value) {
        return value != null
                        && value >= DatabaseMetaData.importedKeyInitiallyDeferred
                        && value <= DatabaseMetaData.importedKeyNotDeferrable
                ? value
                : null;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String safeColumn(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ex) {
            return null;
        }
    }

    @FunctionalInterface
    private interface MetaDataString {
        String get() throws SQLException;
    }

    private static String safeString(MetaDataString supplier, ScanBudget budget) {
        try {
            requireBudget(budget);
            return supplier.get();
        } catch (SQLException | RuntimeException ex) {
            return null;
        }
    }

    private static String qualified(TableRef ref) {
        return ref.schema() == null || ref.schema().isBlank() ? ref.name() : ref.schema() + "." + ref.name();
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        return CredentialRedaction.redact(message);
    }
}
