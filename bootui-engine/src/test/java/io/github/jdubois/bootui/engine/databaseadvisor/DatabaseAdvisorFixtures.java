package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.PoolSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact builders for the physical-schema model, so a rule test reads as the schema it describes rather than
 * as a wall of record constructor arguments.
 */
final class DatabaseAdvisorFixtures {

    private DatabaseAdvisorFixtures() {}

    static SchemaSnapshot schema(String name, Dialect dialect, List<TableModel> tables) {
        return schema(name, dialect, tables, VendorFindings.EMPTY);
    }

    static SchemaSnapshot schema(String name, Dialect dialect, List<TableModel> tables, VendorFindings findings) {
        return new SchemaSnapshot(
                name,
                dialect,
                dialect.label(),
                DatabaseVersion.of(15, 0, "15.0"),
                "LOWER",
                tables,
                findings,
                List.of(),
                false,
                null);
    }

    static SchemaSnapshot vendorSchema(String name, Dialect dialect, VendorAugmentation<?> augmentation) {
        return schema(
                name,
                dialect,
                List.of(),
                VendorFindings.builder().add(augmentation).build());
    }

    /** A readable, table-less schema carrying driver evidence. */
    static SchemaSnapshot driverSchema(String name, Dialect dialect, DriverEvidence driver) {
        return new SchemaSnapshot(
                name,
                dialect,
                dialect.label(),
                DatabaseVersion.of(15, 0, "15.0"),
                "LOWER",
                List.of(),
                VendorFindings.EMPTY,
                List.of(),
                false,
                null,
                true,
                driver);
    }

    static DriverEvidence driverEvidence(String driverName, Map<String, DriverSetting> settings) {
        return new DriverEvidence(driverName, "1.0", settings);
    }

    /** Settings configured in the URL, e.g. {@code urlSettings("reWriteBatchedInserts", "true")}. */
    static Map<String, DriverSetting> urlSettings(String... namesAndValues) {
        return settings(DriverSetting.Source.URL, DriverSetting.State.KNOWN, namesAndValues);
    }

    static Map<String, DriverSetting> poolSettings(String... namesAndValues) {
        return settings(DriverSetting.Source.POOL, DriverSetting.State.KNOWN, namesAndValues);
    }

    static Map<String, DriverSetting> conflictingSettings(String... namesAndValues) {
        return settings(DriverSetting.Source.URL, DriverSetting.State.CONFLICTING, namesAndValues);
    }

    private static Map<String, DriverSetting> settings(
            DriverSetting.Source source, DriverSetting.State state, String... namesAndValues) {
        Map<String, DriverSetting> settings = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            settings.put(namesAndValues[index], new DriverSetting(namesAndValues[index + 1], source, state));
        }
        return settings;
    }

    static DatabaseAdvisorContext context(List<SchemaSnapshot> schemas, Map<String, JdbcBatching> batching) {
        return new DatabaseAdvisorContext(schemas, false, List.of(), List.of(), List.of(), null, batching);
    }

    static DatabaseAdvisorContext contextWithPools(List<SchemaSnapshot> schemas, Map<String, PoolSettings> pools) {
        return new DatabaseAdvisorContext(schemas, false, List.of(), List.of(), List.of(), null, Map.of(), pools);
    }

    /** A readable, table-less schema of {@code dialect} at {@code major}.0 whose server settings were read. */
    static SchemaSnapshot serverSchema(
            String name, Dialect dialect, int major, VendorFindingKind<ServerSetting> kind, ServerSetting... settings) {
        return serverSchema(name, dialect, major, VendorAugmentation.available(kind, List.of(settings), false));
    }

    static SchemaSnapshot serverSchema(
            String name, Dialect dialect, int major, VendorAugmentation<ServerSetting> augmentation) {
        return new SchemaSnapshot(
                name,
                dialect,
                dialect.label(),
                DatabaseVersion.of(major, 0, major + ".0"),
                "LOWER",
                List.of(),
                VendorFindings.builder().add(augmentation).build(),
                List.of(),
                false,
                null);
    }

    static ServerSetting setting(String name, String value) {
        return new ServerSetting(name, value, null);
    }

    static ServerSetting setting(String name, String value, String unit) {
        return new ServerSetting(name, value, unit);
    }

    /** Hikari pool numbers in milliseconds; {@code 0} means disabled, as the pool library reports it. */
    static PoolSettings hikari(
            int maximumPoolSize, int minimumIdle, long idleTimeout, long maxLifetime, long keepalive) {
        return new PoolSettings(
                ConnectionPoolInfo.HIKARI, maximumPoolSize, minimumIdle, idleTimeout, maxLifetime, keepalive, 30_000L);
    }

    static PoolSettings agroal(int maximumPoolSize, int minimumIdle, long idleTimeout, long maxLifetime) {
        return new PoolSettings(
                ConnectionPoolInfo.AGROAL, maximumPoolSize, minimumIdle, idleTimeout, maxLifetime, null, 30_000L);
    }

    static DatabaseAdvisorContext context(List<SchemaSnapshot> schemas) {
        return new DatabaseAdvisorContext(schemas, false, List.of());
    }

    static DatabaseAdvisorContext context(SchemaSnapshot schema) {
        return context(List.of(schema));
    }

    static TableModel table(
            String name,
            List<ColumnModel> columns,
            List<String> primaryKeyColumns,
            List<ForeignKeyModel> foreignKeys,
            List<IndexModel> indexes) {
        return TableModel.of("app", "public", name, columns, primaryKeyColumns, foreignKeys, indexes);
    }

    static ColumnModel column(String name, String typeName, int jdbcType) {
        return new ColumnModel(name, typeName, jdbcType, ColumnModel.Nullability.NULLABLE, null, null, false);
    }

    static ColumnModel column(String name, String typeName, int jdbcType, Integer size) {
        return new ColumnModel(name, typeName, jdbcType, ColumnModel.Nullability.NULLABLE, size, null, false);
    }

    static ColumnModel notNullColumn(String name, String typeName, int jdbcType) {
        return new ColumnModel(name, typeName, jdbcType, ColumnModel.Nullability.NOT_NULL, null, null, false);
    }

    static IndexModel index(String name, List<String> columns) {
        return IndexModel.of(name, columns, false);
    }

    static IndexModel uniqueIndex(String name, List<String> columns) {
        return IndexModel.of(name, columns, true);
    }

    static IndexModel prefixIndex(String name, String column, int prefixLength, boolean unique) {
        return new IndexModel(
                name,
                List.of(new IndexKeyPart(column, null, true, prefixLength, null)),
                unique,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
    }

    static IndexModel partialIndex(String name, List<String> columns, String predicate) {
        return new IndexModel(
                name,
                columns.stream()
                        .map(column -> IndexKeyPart.column(column, true))
                        .toList(),
                false,
                "btree",
                predicate,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
    }

    static IndexModel invisibleIndex(String name, List<String> columns) {
        return new IndexModel(
                name,
                columns.stream()
                        .map(column -> IndexKeyPart.column(column, true))
                        .toList(),
                false,
                "btree",
                null,
                IndexModel.Visibility.INVISIBLE,
                IndexModel.Validity.VALID);
    }

    static IndexModel invalidIndex(String name, List<String> columns) {
        return new IndexModel(
                name,
                columns.stream()
                        .map(column -> IndexKeyPart.column(column, true))
                        .toList(),
                false,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.INVALID);
    }

    static IndexModel expressionIndex(String name, String expression) {
        return new IndexModel(
                name,
                List.of(IndexKeyPart.expression(expression)),
                false,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
    }

    static ForeignKeyModel foreignKey(
            String name, List<String> columns, String referencedTable, List<String> referencedColumns) {
        return new ForeignKeyModel(name, columns, "app", "public", referencedTable, referencedColumns);
    }
}
