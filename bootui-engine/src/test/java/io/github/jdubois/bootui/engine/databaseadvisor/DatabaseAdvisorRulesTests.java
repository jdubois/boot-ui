package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseAdvisorRulesTests {

    private static final String PASS = DatabaseAdvisorRuleSupport.PASS;
    private static final String VIOLATION = DatabaseAdvisorRuleSupport.VIOLATION;
    private static final String SKIPPED = DatabaseAdvisorRuleSupport.SKIPPED;

    private static SchemaSnapshot snapshot(String name, Dialect dialect, List<TableModel> tables) {
        return new SchemaSnapshot(name, dialect, dialect.name(), tables, List.of(), List.of(), null);
    }

    private static DatabaseAdvisorContext context(List<SchemaSnapshot> schemas) {
        return new DatabaseAdvisorContext(schemas, false, List.of());
    }

    private static DatabaseAdvisorContext contextWithHibernate(
            List<SchemaSnapshot> schemas, List<MappedEntityFacts> entities) {
        return new DatabaseAdvisorContext(schemas, true, entities);
    }

    // --- MissingPrimaryKeyRule ---

    @Test
    void missingPrimaryKeyRulePassesWhenEveryTableHasAPrimaryKey() {
        TableModel table = new TableModel(
                null,
                null,
                "accounts",
                List.of(new ColumnModel("id", "int4", false, 10)),
                List.of("id"),
                List.of(),
                List.of());
        DatabaseAdvisorRuleResultDto result =
                new MissingPrimaryKeyRule().evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of(table)))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void missingPrimaryKeyRuleFlagsTablesWithNoPrimaryKeyColumns() {
        TableModel table = new TableModel(
                null,
                null,
                "audit_log",
                List.of(new ColumnModel("message", "varchar", true, 255)),
                List.of(),
                List.of(),
                List.of());
        DatabaseAdvisorRuleResultDto result =
                new MissingPrimaryKeyRule().evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of(table)))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0)).contains("audit_log").contains("no primary key");
    }

    // --- MissingForeignKeyIndexRule ---

    @Test
    void missingForeignKeyIndexRulePassesWhenForeignKeyHasALeadingIndex() {
        TableModel table = new TableModel(
                null,
                null,
                "orders",
                List.of(new ColumnModel("customer_id", "int4", false, 10)),
                List.of(),
                List.of(new ForeignKeyModel("fk_orders_customer", List.of("customer_id"), "customers")),
                List.of(new IndexModel("ix_orders_customer", List.of("customer_id"), false)));
        DatabaseAdvisorRuleResultDto result = new MissingForeignKeyIndexRule()
                .evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of(table)))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void missingForeignKeyIndexRuleFlagsAnUnindexedForeignKeyColumn() {
        TableModel table = new TableModel(
                null,
                null,
                "orders",
                List.of(new ColumnModel("customer_id", "int4", false, 10)),
                List.of(),
                List.of(new ForeignKeyModel("fk_orders_customer", List.of("customer_id"), "customers")),
                List.of());
        DatabaseAdvisorRuleResultDto result = new MissingForeignKeyIndexRule()
                .evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of(table)))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("orders.customer_id").contains("customers");
    }

    // --- DuplicateIndexRule ---

    @Test
    void duplicateIndexRulePassesWhenIndexesDoNotOverlap() {
        TableModel table = new TableModel(
                null,
                null,
                "products",
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new IndexModel("ix_sku", List.of("sku"), true),
                        new IndexModel("ix_name", List.of("name"), false)));
        DatabaseAdvisorRuleResultDto result =
                new DuplicateIndexRule().evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of(table)))));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void duplicateIndexRuleFlagsAPrefixOverlap() {
        TableModel table = new TableModel(
                null,
                null,
                "products",
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new IndexModel("ix_sku", List.of("sku"), false),
                        new IndexModel("ix_sku_name", List.of("sku", "name"), false)));
        DatabaseAdvisorRuleResultDto result =
                new DuplicateIndexRule().evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of(table)))));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("ix_sku").contains("ix_sku_name");
    }

    // --- PostgresInvalidIndexRule ---

    @Test
    void postgresInvalidIndexRuleSkipsWhenNoPostgresDatasourceIsPresent() {
        DatabaseAdvisorRuleResultDto result =
                new PostgresInvalidIndexRule().evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of()))));
        assertThat(result.status()).isEqualTo(SKIPPED);
    }

    @Test
    void postgresInvalidIndexRuleFlagsInvalidIndexes() {
        SchemaSnapshot schema = new SchemaSnapshot(
                "ds",
                Dialect.POSTGRESQL,
                "PostgreSQL",
                List.of(),
                List.of(new PostgresInvalidIndex("orders", "ix_broken")),
                List.of(),
                null);
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(List.of(schema)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("ix_broken").contains("orders");
    }

    @Test
    void postgresInvalidIndexRulePassesWhenPostgresHasNoInvalidIndexes() {
        SchemaSnapshot schema =
                new SchemaSnapshot("ds", Dialect.POSTGRESQL, "PostgreSQL", List.of(), List.of(), List.of(), null);
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(List.of(schema)));
        assertThat(result.status()).isEqualTo(PASS);
    }

    // --- MySqlNonInnodbEngineRule ---

    @Test
    void mySqlNonInnodbEngineRuleSkipsWhenNoMySqlDatasourceIsPresent() {
        DatabaseAdvisorRuleResultDto result =
                new MySqlNonInnodbEngineRule().evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of()))));
        assertThat(result.status()).isEqualTo(SKIPPED);
    }

    @Test
    void mySqlNonInnodbEngineRuleFlagsNonInnodbTables() {
        SchemaSnapshot schema = new SchemaSnapshot(
                "ds",
                Dialect.MYSQL,
                "MySQL",
                List.of(),
                List.of(),
                List.of(new MySqlNonInnodbTable("legacy_sessions", "MyISAM")),
                null);
        DatabaseAdvisorRuleResultDto result = new MySqlNonInnodbEngineRule().evaluate(context(List.of(schema)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("legacy_sessions").contains("MyISAM");
    }

    // --- HibernateMissingForeignKeyIndexRule ---

    @Test
    void hibernateMissingForeignKeyIndexRuleSkipsWhenHibernateIsUnavailable() {
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyIndexRule()
                .evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of()))));
        assertThat(result.status()).isEqualTo(SKIPPED);
    }

    @Test
    void hibernateMissingForeignKeyIndexRuleSkipsWhenNoPhysicalSchemaIsAvailable() {
        SchemaSnapshot failed = SchemaSnapshot.failed("ds", "boom");
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Order",
                "orders",
                List.of(new MappedForeignKeyFacts("Order.customer", List.of("customer_id"))),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyIndexRule()
                .evaluate(contextWithHibernate(List.of(failed), List.of(entity)));
        assertThat(result.status()).isEqualTo(SKIPPED);
    }

    @Test
    void hibernateMissingForeignKeyIndexRuleFlagsAMappedForeignKeyWithNoPhysicalIndex() {
        TableModel table = new TableModel(
                null,
                null,
                "orders",
                List.of(new ColumnModel("customer_id", "int4", false, 10)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Order",
                "orders",
                List.of(new MappedForeignKeyFacts("Order.customer", List.of("customer_id"))),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyIndexRule()
                .evaluate(contextWithHibernate(List.of(snapshot("ds", Dialect.GENERIC, List.of(table))), List.of(entity)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("Order.customer").contains("orders.customer_id");
    }

    @Test
    void hibernateMissingForeignKeyIndexRulePassesWhenThePhysicalIndexExists() {
        TableModel table = new TableModel(
                null,
                null,
                "orders",
                List.of(new ColumnModel("customer_id", "int4", false, 10)),
                List.of(),
                List.of(),
                List.of(new IndexModel("ix_orders_customer", List.of("customer_id"), false)));
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Order",
                "orders",
                List.of(new MappedForeignKeyFacts("Order.customer", List.of("customer_id"))),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyIndexRule()
                .evaluate(contextWithHibernate(List.of(snapshot("ds", Dialect.GENERIC, List.of(table))), List.of(entity)));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void hibernateMissingForeignKeyIndexRuleIgnoresEntitiesWithoutAnExplicitTableName() {
        TableModel table = new TableModel(
                null,
                null,
                "orders",
                List.of(new ColumnModel("customer_id", "int4", false, 10)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Order",
                null,
                List.of(new MappedForeignKeyFacts("Order.customer", List.of("customer_id"))),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyIndexRule()
                .evaluate(contextWithHibernate(List.of(snapshot("ds", Dialect.GENERIC, List.of(table))), List.of(entity)));
        assertThat(result.status()).isEqualTo(PASS);
    }

    // --- HibernateMissingTableRule ---

    @Test
    void hibernateMissingTableRuleSkipsWhenHibernateIsUnavailable() {
        DatabaseAdvisorRuleResultDto result =
                new HibernateMissingTableRule().evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of()))));
        assertThat(result.status()).isEqualTo(SKIPPED);
    }

    @Test
    void hibernateMissingTableRuleFlagsAMappedTableAbsentFromThePhysicalSchema() {
        MappedEntityFacts entity = new MappedEntityFacts("com.example.Order", "orders", List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingTableRule()
                .evaluate(contextWithHibernate(List.of(snapshot("ds", Dialect.GENERIC, List.of())), List.of(entity)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("com.example.Order").contains("orders");
    }

    @Test
    void hibernateMissingTableRulePassesWhenTheMappedTableExists() {
        TableModel table = new TableModel(null, null, "orders", List.of(), List.of(), List.of(), List.of());
        MappedEntityFacts entity = new MappedEntityFacts("com.example.Order", "orders", List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingTableRule()
                .evaluate(contextWithHibernate(List.of(snapshot("ds", Dialect.GENERIC, List.of(table))), List.of(entity)));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void hibernateMissingTableRuleIgnoresEntitiesWithoutAnExplicitTableName() {
        MappedEntityFacts entity = new MappedEntityFacts("com.example.Order", null, List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingTableRule()
                .evaluate(contextWithHibernate(List.of(snapshot("ds", Dialect.GENERIC, List.of())), List.of(entity)));
        assertThat(result.status()).isEqualTo(PASS);
    }

    // --- HibernateColumnMismatchRule ---

    @Test
    void hibernateColumnMismatchRuleSkipsWhenHibernateIsUnavailable() {
        DatabaseAdvisorRuleResultDto result =
                new HibernateColumnMismatchRule().evaluate(context(List.of(snapshot("ds", Dialect.GENERIC, List.of()))));
        assertThat(result.status()).isEqualTo(SKIPPED);
    }

    @Test
    void hibernateColumnMismatchRuleFlagsATypeFamilyMismatch() {
        TableModel table = new TableModel(
                null,
                null,
                "orders",
                List.of(new ColumnModel("total", "varchar", true, 50)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Order",
                "orders",
                List.of(),
                List.of(new MappedColumnFacts("Order.total", "total", true, "BigDecimal")));
        DatabaseAdvisorRuleResultDto result = new HibernateColumnMismatchRule()
                .evaluate(contextWithHibernate(List.of(snapshot("ds", Dialect.GENERIC, List.of(table))), List.of(entity)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("type-family mismatch");
    }

    @Test
    void hibernateColumnMismatchRuleFlagsANotNullColumnMappedAsNullable() {
        TableModel table = new TableModel(
                null,
                null,
                "orders",
                List.of(new ColumnModel("total", "numeric", false, 10)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Order",
                "orders",
                List.of(),
                List.of(new MappedColumnFacts("Order.total", "total", true, "BigDecimal")));
        DatabaseAdvisorRuleResultDto result = new HibernateColumnMismatchRule()
                .evaluate(contextWithHibernate(List.of(snapshot("ds", Dialect.GENERIC, List.of(table))), List.of(entity)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("NOT NULL").contains("nullable");
    }

    @Test
    void hibernateColumnMismatchRulePassesWhenTypeAndNullabilityAgree() {
        TableModel table = new TableModel(
                null,
                null,
                "orders",
                List.of(new ColumnModel("total", "numeric", true, 10)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Order",
                "orders",
                List.of(),
                List.of(new MappedColumnFacts("Order.total", "total", true, "BigDecimal")));
        DatabaseAdvisorRuleResultDto result = new HibernateColumnMismatchRule()
                .evaluate(contextWithHibernate(List.of(snapshot("ds", Dialect.GENERIC, List.of(table))), List.of(entity)));
        assertThat(result.status()).isEqualTo(PASS);
    }

    // --- Rules never throw ---

    @Test
    void everyActiveRuleReturnsAKnownStatusOnASparseEntityWithNoTableOrColumns() {
        DatabaseAdvisorContext sparse = contextWithHibernate(
                List.of(snapshot("ds", Dialect.GENERIC, List.of())),
                List.of(new MappedEntityFacts(null, null, List.of(), List.of())));
        for (DatabaseAdvisorRule rule : DatabaseAdvisorRuleRegistry.activeRules()) {
            DatabaseAdvisorRuleResultDto result = rule.evaluate(sparse);
            assertThat(result.status())
                    .as(rule.definition().id())
                    .isIn(PASS, VIOLATION, SKIPPED, DatabaseAdvisorRuleSupport.ERROR);
        }
    }
}
