package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.column;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.foreignKey;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.index;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.notNullColumn;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.prefixIndex;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateAttributeModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateEntityModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedSecondaryTableFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedUniqueConstraintFacts;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hibernate ↔ physical schema cross-reference rules (DB-HIB-001..007). */
class DatabaseAdvisorHibernateRulesTests {

    private static final String PASS = DatabaseAdvisorRuleSupport.PASS;
    private static final String VIOLATION = DatabaseAdvisorRuleSupport.VIOLATION;
    private static final String SKIPPED = DatabaseAdvisorRuleSupport.SKIPPED;

    private static DatabaseAdvisorContext hibernateContext(
            List<SchemaSnapshot> schemas, List<MappedEntityFacts> entities) {
        return new DatabaseAdvisorContext(schemas, true, entities);
    }

    private static DatabaseAdvisorContext hibernateContext(SchemaSnapshot schema, MappedEntityFacts entity) {
        return hibernateContext(List.of(schema), List.of(entity));
    }

    private static MappedEntityFacts entity(
            String name,
            String tableName,
            List<MappedForeignKeyFacts> foreignKeys,
            List<MappedColumnFacts> columns,
            List<MappedUniqueConstraintFacts> uniqueConstraints) {
        return new MappedEntityFacts(name, tableName, null, null, foreignKeys, columns, uniqueConstraints);
    }

    // --- DB-HIB-002: mapped table missing ---

    @Test
    void hibernateMissingTableRuleReportsAnAbsentMappedTable() {
        MappedEntityFacts entity = entity("com.example.Ghost", "ghosts", List.of(), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingTableRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of()), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("ghosts");
    }

    @Test
    void hibernateMissingTableRuleSkipsAmbiguousMatchesAcrossDatasources() {
        TableModel orders = table("orders", List.of(), List.of("id"), List.of(), List.of());
        MappedEntityFacts entity = entity("com.example.Order", "orders", List.of(), List.of(), List.of());
        DatabaseAdvisorContext context = hibernateContext(
                List.of(
                        schema("primary", Dialect.GENERIC, List.of(orders)),
                        schema("reporting", Dialect.GENERIC, List.of(orders))),
                List.of(entity));
        assertThat(new HibernateMissingTableRule().evaluate(context).status()).isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isNotEmpty();
    }

    @Test
    void hibernateMissingTableRuleSkipsWhenTheTableListWasTruncated() {
        SchemaSnapshot truncated = new SchemaSnapshot(
                "ds",
                Dialect.GENERIC,
                "H2",
                DatabaseVersion.UNKNOWN,
                "UPPER",
                List.of(),
                VendorFindings.EMPTY,
                List.of(),
                true,
                null);
        MappedEntityFacts entity = entity("com.example.Order", "orders", List.of(), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result =
                new HibernateMissingTableRule().evaluate(hibernateContext(truncated, entity));
        assertThat(result.status()).isEqualTo(SKIPPED);
        assertThat(result.sampleViolations().get(0)).contains("truncated");
    }

    @Test
    void hibernateMissingTableRuleHonorsTheDeclaredSchema() {
        TableModel orders = TableModel.of("app", "public", "orders", List.of(), List.of("id"), List.of(), List.of());
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Order", "orders", "reporting", null, List.of(), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingTableRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders)), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("reporting.orders");
    }

    // --- DB-HIB-003: type/nullability mismatch ---

    @Test
    void hibernateColumnMismatchRuleOnlyComparesExplicitNullability() {
        TableModel users = table(
                "users", List.of(notNullColumn("email", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of());
        MappedEntityFacts undeclared = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#email", "email", null, "String")),
                List.of());
        assertThat(new HibernateColumnMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), undeclared))
                        .status())
                .isEqualTo(SKIPPED);

        MappedEntityFacts declaredNullable = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#email", "email", true, "String")),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateColumnMismatchRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), declaredNullable));
        assertThat(result.status()).isEqualTo(SKIPPED);

        TableModel nullable =
                table("users", List.of(column("email", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of());
        MappedEntityFacts notNullable = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#email", "email", false, "String")),
                List.of());
        DatabaseAdvisorRuleResultDto mismatch = new HibernateColumnMismatchRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(nullable)), notNullable));
        assertThat(mismatch.status()).isEqualTo(VIOLATION);
        assertThat(mismatch.sampleViolations().get(0)).contains("nullable=false", "allows NULL");
    }

    @Test
    void hibernateColumnMismatchRuleSkipsConverterAndEnumMappings() {
        TableModel users =
                table("users", List.of(column("status", "int4", Types.INTEGER)), List.of(), List.of(), List.of());
        MappedEntityFacts entity = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts(
                        "com.example.User#status", "status", null, "Status", null, false, true, false, null)),
                List.of());
        assertThat(new HibernateColumnMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), entity))
                        .status())
                .isEqualTo(SKIPPED);
    }

    // --- DB-HIB-004: mapped length longer than the physical column ---

    @Test
    void hibernateColumnLengthRuleOnlyComparesExplicitlyDeclaredLengths() {
        TableModel users = table(
                "users", List.of(column("nickname", "varchar", Types.VARCHAR, 50)), List.of(), List.of(), List.of());
        MappedEntityFacts undeclared = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#nickname", "nickname", null, "String")),
                List.of());
        assertThat(new HibernateColumnLengthMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), undeclared))
                        .status())
                .isEqualTo(SKIPPED);

        MappedEntityFacts declared = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#nickname", "nickname", null, "String", 120)),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateColumnLengthMismatchRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), declared));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("length=120").contains("varchar(50)");
    }

    @Test
    void hibernateColumnLengthRuleSkipsUnboundedTextColumnsAndLobs() {
        TableModel documents = table(
                "documents",
                List.of(column("body", "text", Types.VARCHAR, Integer.MAX_VALUE)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts entity = entity(
                "com.example.Document",
                "documents",
                List.of(),
                List.of(new MappedColumnFacts(
                        "com.example.Document#body", "body", null, "String", 4000, true, true, false, null)),
                List.of());
        assertThat(new HibernateColumnLengthMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(documents)), entity))
                        .status())
                .isEqualTo(SKIPPED);
    }

    // --- DB-HIB-005: unique constraint coverage ---

    @Test
    void hibernateUniqueIndexRuleAcceptsStrongerPrefixUniquenessAsCoverage() {
        TableModel users = table(
                "users",
                List.of(column("email", "varchar", Types.VARCHAR, 255)),
                List.of(),
                List.of(),
                List.of(prefixIndex("uq_email_prefix", "email", 20, true)));
        MappedEntityFacts entity = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("com.example.User#email", List.of("email"))));
        DatabaseAdvisorRuleResultDto result = new HibernateMissingUniqueIndexRule()
                .evaluate(hibernateContext(schema("ds", Dialect.MYSQL, List.of(users)), entity));
        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void hibernateUniqueIndexRuleIgnoresColumnOrderForCompositeUniqueness() {
        TableModel memberships = table(
                "memberships",
                List.of(column("user_id", "int8", Types.BIGINT), column("group_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(new IndexModel(
                        "uq_group_user",
                        List.of(IndexKeyPart.column("group_id", true), IndexKeyPart.column("user_id", true)),
                        true,
                        "btree",
                        null,
                        IndexModel.Visibility.VISIBLE,
                        IndexModel.Validity.VALID)));
        MappedEntityFacts entity = entity(
                "com.example.Membership",
                "memberships",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts(
                        "com.example.Membership @Table unique constraint", List.of("user_id", "group_id"))));
        assertThat(new HibernateMissingUniqueIndexRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(memberships)), entity))
                        .status())
                .isEqualTo(PASS);
    }

    // --- DB-HIB-006: mapped column missing physically ---

    @Test
    void hibernateMissingColumnRuleReportsMappedColumnsAndJoinColumnsThatDoNotExist() {
        TableModel users =
                table("users", List.of(column("id", "int8", Types.BIGINT)), List.of("id"), List.of(), List.of());
        MappedEntityFacts entity = entity(
                "com.example.User",
                "users",
                List.of(new MappedForeignKeyFacts("com.example.User#team", List.of("team_id"))),
                List.of(new MappedColumnFacts("com.example.User#nickname", "nickname", null, "String")),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingColumnRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations().get(0)).contains("users.nickname");
        assertThat(result.sampleViolations().get(1)).contains("users.team_id");
    }

    @Test
    void hibernateMissingColumnRuleSkipsTablesWithIncompleteColumnMetadata() {
        TableModel truncated = new TableModel(
                "app",
                "public",
                "users",
                "TABLE",
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                new TableMetadata(true, true, true, true, true, List.of("only the first 300 columns were read")));
        MappedEntityFacts entity = entity(
                "com.example.User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("com.example.User#nickname", "nickname", null, "String")),
                List.of());
        assertThat(new HibernateMissingColumnRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(truncated)), entity))
                        .status())
                .isEqualTo(SKIPPED);
    }

    // --- DB-HIB-007: mapped association without a physical foreign key ---

    @Test
    void hibernateMissingForeignKeyConstraintRuleReportsAnUnenforcedAssociation() {
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(index("ix_customer", List.of("customer_id"))));
        MappedEntityFacts entity =
                entity("com.example.Order", "orders", List.of(customerAssociation()), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyConstraintRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders, customers())), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("no matching foreign key");
    }

    @Test
    void hibernateMissingForeignKeyConstraintRulePassesWhenTheConstraintExists() {
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(foreignKey("fk_orders_customer", List.of("customer_id"), "customers", List.of("id"))
                        .withEnforcement(true, true, "SIMPLE")),
                List.of());
        MappedEntityFacts entity =
                entity("com.example.Order", "orders", List.of(customerAssociation()), List.of(), List.of());
        assertThat(new HibernateMissingForeignKeyConstraintRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders, customers())), entity))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void hibernateMissingForeignKeyConstraintRuleSkipsAssociationsDeclaringNoConstraint() {
        TableModel orders =
                table("orders", List.of(column("customer_id", "int8", Types.BIGINT)), List.of(), List.of(), List.of());
        MappedForeignKeyFacts foreignKey = new MappedForeignKeyFacts(
                "com.example.Order#customer",
                List.of("customer_id"),
                Arrays.asList((String) null),
                null,
                false,
                null,
                null,
                null);
        MappedEntityFacts entity = new MappedEntityFacts("com.example.Order", "orders", List.of(foreignKey), List.of());
        assertThat(new HibernateMissingForeignKeyConstraintRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders)), entity))
                        .status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void hibernateMissingForeignKeyConstraintRuleToleratesReorderedColumnsWithMatchingPairing() {
        TableModel orderLines = table(
                "order_lines",
                List.of(column("tenant_id", "int8", Types.BIGINT), column("order_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(foreignKey(
                                "fk_order_lines_order",
                                List.of("order_id", "tenant_id"),
                                "orders",
                                List.of("id", "tenant_id"))
                        .withEnforcement(true, true, "SIMPLE")),
                List.of());
        MappedForeignKeyFacts foreignKey = new MappedForeignKeyFacts(
                "com.example.OrderLine#order",
                List.of("tenant_id", "order_id"),
                Arrays.asList("tenant_id", "id"),
                null,
                true,
                "orders",
                null,
                null);
        MappedEntityFacts entity =
                new MappedEntityFacts("com.example.OrderLine", "order_lines", List.of(foreignKey), List.of());
        TableModel target = table(
                "orders",
                List.of(column("id", "int8", Types.BIGINT), column("tenant_id", "int8", Types.BIGINT)),
                List.of("tenant_id", "id"),
                List.of(),
                List.of());
        assertThat(new HibernateMissingForeignKeyConstraintRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orderLines, target)), entity))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void hibernateMissingForeignKeyConstraintRuleRejectsAConstraintReferencingTheWrongTable() {
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(foreignKey(
                        "fk_orders_something_else", List.of("customer_id"), "archived_customers", List.of("id"))),
                List.of());
        MappedForeignKeyFacts foreignKey = new MappedForeignKeyFacts(
                "com.example.Order#customer",
                List.of("customer_id"),
                List.of("id"),
                null,
                true,
                "customers",
                null,
                null);
        MappedEntityFacts entity = new MappedEntityFacts("com.example.Order", "orders", List.of(foreignKey), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyConstraintRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders, customers())), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("customer_id");
    }

    // --- @SecondaryTable support ---

    @Test
    void hibernateMissingColumnRuleChecksSecondaryTableColumnsAgainstTheSecondaryTable() {
        TableModel users =
                table("users", List.of(column("id", "int8", Types.BIGINT)), List.of("id"), List.of(), List.of());
        TableModel profiles = table(
                "user_profiles", List.of(column("bio", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of());
        MappedSecondaryTableFacts secondaryTable = new MappedSecondaryTableFacts("user_profiles", null, null);
        MappedColumnFacts bio = new MappedColumnFacts(
                "com.example.User#bio", "bio", null, "String", null, false, false, false, "user_profiles");
        MappedColumnFacts missing = new MappedColumnFacts(
                "com.example.User#nickname", "nickname", null, "String", null, false, false, false, "user_profiles");
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.User",
                "users",
                null,
                null,
                List.of(),
                List.of(bio, missing),
                List.of(),
                List.of(secondaryTable));
        DatabaseAdvisorRuleResultDto result = new HibernateMissingColumnRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users, profiles)), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0)).contains("user_profiles.nickname");
    }

    @Test
    void hibernateMissingTableRuleReportsAnAbsentSecondaryTable() {
        TableModel users = table("users", List.of(), List.of("id"), List.of(), List.of());
        MappedSecondaryTableFacts secondaryTable = new MappedSecondaryTableFacts("user_profiles", null, null);
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.User", "users", null, null, List.of(), List.of(), List.of(), List.of(secondaryTable));
        DatabaseAdvisorRuleResultDto result = new HibernateMissingTableRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), entity));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("user_profiles");
    }

    private static MappedForeignKeyFacts customerAssociation() {
        return new MappedForeignKeyFacts(
                "com.example.Order#customer",
                List.of("customer_id"),
                List.of("id"),
                null,
                true,
                "customers",
                "public",
                "app");
    }

    private static TableModel customers() {
        return table("customers", List.of(column("id", "int8", Types.BIGINT)), List.of("id"), List.of(), List.of());
    }

    @Test
    void failedDatasourceAndIncompleteInventoryCannotProveAnAbsentDeclaration() {
        MappedEntityFacts ghost = entity("Ghost", "ghosts", List.of(), List.of(), List.of());
        DatabaseAdvisorContext failed = hibernateContext(
                List.of(schema("readable", Dialect.GENERIC, List.of()), SchemaSnapshot.failed("unreadable", "offline")),
                List.of(ghost));
        assertThat(new HibernateMissingTableRule().evaluate(failed).status()).isEqualTo(SKIPPED);
        assertThat(failed.evaluationDiagnostics()).isNotEmpty();

        SchemaSnapshot incomplete = new SchemaSnapshot(
                "ds",
                Dialect.GENERIC,
                "H2",
                DatabaseVersion.UNKNOWN,
                "LOWER",
                List.of(),
                VendorFindings.EMPTY,
                List.of(),
                false,
                null,
                false);
        assertThat(new HibernateMissingTableRule()
                        .evaluate(hibernateContext(incomplete, ghost))
                        .status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void aViewCanSatisfyADeclaredRelationName() {
        TableModel view = new TableModel(
                "app",
                "public",
                "users",
                "VIEW",
                List.of(column("email", "varchar", Types.VARCHAR)),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                TableMetadata.COMPLETE);
        MappedEntityFacts mapped = entity("User", "users", List.of(), List.of(), List.of());
        assertThat(new HibernateMissingTableRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(view)), mapped))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void viewsResolveNamesButAreNotMissingPhysicalConstraintTargets() {
        MappedEntityFacts mapped = entity(
                "Order",
                "orders",
                List.of(customerAssociation()),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("Order customer uniqueness", List.of("customer_id"))));
        for (String type : List.of("VIEW", "MATERIALIZED VIEW")) {
            TableModel view = new TableModel(
                    "app",
                    "public",
                    "orders",
                    type,
                    List.of(column("customer_id", "int8", Types.BIGINT)),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    false,
                    false,
                    TableMetadata.COMPLETE);
            for (AbstractDatabaseAdvisorRule rule :
                    List.of(new HibernateMissingUniqueIndexRule(), new HibernateMissingForeignKeyConstraintRule())) {
                DatabaseAdvisorContext context =
                        hibernateContext(schema("ds", Dialect.GENERIC, List.of(view, customers())), mapped);
                assertThat(rule.evaluate(context).status()).isEqualTo(SKIPPED);
                assertThat(context.evaluationDiagnostics()).isEmpty();
            }
            DatabaseAdvisorContext context =
                    hibernateContext(schema("ds", Dialect.GENERIC, List.of(view, customers())), mapped);
            assertThat(new HibernateMissingTableRule().evaluate(context).status())
                    .isEqualTo(PASS);
            assertThat(new HibernateMissingColumnRule().evaluate(context).status())
                    .isEqualTo(PASS);
        }
    }

    @Test
    void baseTableAssociationReferencingAViewIsOutsideThePhysicalForeignKeyCheck() {
        TableModel orders =
                table("orders", List.of(column("customer_id", "int8", Types.BIGINT)), List.of(), List.of(), List.of());
        TableModel view = new TableModel(
                "app",
                "public",
                "customers",
                "VIEW",
                List.of(column("id", "int8", Types.BIGINT)),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                TableMetadata.COMPLETE);
        MappedEntityFacts mapped = entity("Order", "orders", List.of(customerAssociation()), List.of(), List.of());
        DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders, view)), mapped);
        assertThat(new HibernateMissingForeignKeyConstraintRule()
                        .evaluate(context)
                        .status())
                .isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isEmpty();
    }

    @Test
    void aSecondaryViewDoesNotProduceAMissingUniqueConstraintFinding() {
        TableModel users =
                table("users", List.of(column("id", "int8", Types.BIGINT)), List.of("id"), List.of(), List.of());
        TableModel view = new TableModel(
                "app",
                "public",
                "profiles",
                "VIEW",
                List.of(column("email", "varchar", Types.VARCHAR)),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                TableMetadata.COMPLETE);
        MappedEntityFacts mapped = new MappedEntityFacts(
                "User",
                "users",
                null,
                null,
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User email uniqueness", List.of("email"), "profiles")),
                List.of(new MappedSecondaryTableFacts("profiles", null, null)));
        DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.GENERIC, List.of(users, view)), mapped);
        assertThat(new HibernateMissingUniqueIndexRule().evaluate(context).status())
                .isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isEmpty();
    }

    @Test
    void columnDeclarationsRespectQuotedCaseAndUnquotedFolding() {
        TableModel users = table(
                "USERS",
                List.of(column("EMAIL", "varchar", Types.VARCHAR), column("Email", "varchar", Types.VARCHAR)),
                List.of(),
                List.of(),
                List.of());
        SchemaSnapshot schema = new SchemaSnapshot(
                "ds",
                Dialect.GENERIC,
                "H2",
                DatabaseVersion.UNKNOWN,
                "UPPER",
                List.of(users),
                VendorFindings.EMPTY,
                List.of(),
                false,
                null);
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(
                        new MappedColumnFacts("User#folded", "email", null, "String"),
                        new MappedColumnFacts("User#quoted", "\"Email\"", null, "String"),
                        new MappedColumnFacts("User#missing", "\"email\"", null, "String")),
                List.of());
        DatabaseAdvisorRuleResultDto result =
                new HibernateMissingColumnRule().evaluate(hibernateContext(schema, mapped));
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0))
                .contains("User#missing")
                .doesNotContain("User#folded", "User#quoted");
    }

    @Test
    void sharedDeclarationMatchingHandlesInsensitiveNamesBeforeExactUniqueKeyComparison() {
        IndexModel unique = new IndexModel(
                "uq_email",
                List.of(IndexKeyPart.column("eMail", true)),
                true,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
        TableModel users = table(
                "users", List.of(column("eMail", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of(unique));
        SchemaSnapshot schema = new SchemaSnapshot(
                "ds",
                Dialect.GENERIC,
                "database",
                DatabaseVersion.UNKNOWN,
                "INSENSITIVE",
                List.of(users),
                VendorFindings.EMPTY,
                List.of(),
                false,
                null);
        MappedEntityFacts mapped = entity(
                "User",
                "USERS",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User email uniqueness", List.of("EMAIL"))));
        assertThat(new HibernateMissingUniqueIndexRule()
                        .evaluate(hibernateContext(schema, mapped))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void doubledIdentifierQuotesAreUnescapedByTheSharedDeclarationLookup() {
        TableModel users = table(
                "users",
                List.of(column("a\"b", "varchar", Types.VARCHAR), column("a`b", "varchar", Types.VARCHAR)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(
                        new MappedColumnFacts("User#doubleQuote", "\"a\"\"b\"", null, "String"),
                        new MappedColumnFacts("User#backtick", "`a``b`", null, "String")),
                List.of());
        assertThat(new HibernateMissingColumnRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), mapped))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void unknownFoldingCanUseAnExactColumnMatchButCannotProveAnAbsentColumn() {
        TableModel users =
                table("users", List.of(column("email", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of());
        SchemaSnapshot schema = new SchemaSnapshot(
                "ds",
                Dialect.GENERIC,
                "database",
                DatabaseVersion.UNKNOWN,
                null,
                List.of(users),
                VendorFindings.EMPTY,
                List.of(),
                false,
                null);
        MappedEntityFacts exact = entity(
                "User",
                "\"users\"",
                List.of(),
                List.of(new MappedColumnFacts("User#email", "email", null, "String")),
                List.of());
        DatabaseAdvisorContext exactContext = hibernateContext(schema, exact);
        assertThat(new HibernateMissingColumnRule().evaluate(exactContext).status())
                .isEqualTo(PASS);
        assertThat(exactContext.evaluationDiagnostics()).isEmpty();
        MappedEntityFacts uncertain = entity(
                "User",
                "\"users\"",
                List.of(),
                List.of(new MappedColumnFacts("User#email", "EMAIL", null, "String")),
                List.of());
        DatabaseAdvisorContext uncertainContext = hibernateContext(schema, uncertain);
        assertThat(new HibernateMissingColumnRule().evaluate(uncertainContext).status())
                .isEqualTo(SKIPPED);
        assertThat(uncertainContext.evaluationDiagnostics()).hasSize(1);
    }

    @Test
    void aSingleMatchingNameAcrossMultipleSourcesDoesNotProvePersistenceUnitAssociation() {
        TableModel users =
                table("users", List.of(column("email", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of());
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("User#missing", "missing", null, "String")),
                List.of());
        DatabaseAdvisorContext context = hibernateContext(
                List.of(schema("first", Dialect.GENERIC, List.of(users)), schema("second", Dialect.GENERIC, List.of())),
                List.of(mapped));
        assertThat(new HibernateMissingColumnRule().evaluate(context).status()).isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isNotEmpty();
    }

    @Test
    void rawJavaTypeFamiliesNeverProveJdbcMappingMismatches() {
        TableModel users = table(
                "users",
                List.of(
                        column("flag", "int4", Types.INTEGER),
                        column("uuid", "bytea", Types.BINARY),
                        column("text", "int4", Types.INTEGER)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(
                        new MappedColumnFacts("User#flag", "flag", null, "Boolean"),
                        new MappedColumnFacts("User#uuid", "uuid", null, "UUID"),
                        new MappedColumnFacts("User#text", "text", null, "String")),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateColumnMismatchRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), mapped));
        assertThat(result.status()).isEqualTo(SKIPPED);
        assertThat(result.violationCount()).isZero();
    }

    @Test
    void unknownPhysicalNullabilityIsNotNonNull() {
        TableModel users = table(
                "users",
                List.of(new ColumnModel(
                        "email", "varchar", Types.VARCHAR, ColumnModel.Nullability.UNKNOWN, 100, null, false)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("User#email", "email", false, "String")),
                List.of());
        DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), mapped);
        assertThat(new HibernateColumnMismatchRule().evaluate(context).status()).isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isNotEmpty();
    }

    @Test
    void realAnnotationDefaultsAndNondefaultsReachNullabilityRuleConservatively() {
        MappedEntityFacts mapped = annotationFacts(DeclaredColumns.class);
        TableModel table = table(
                "declarations",
                List.of(
                        notNullColumn("omitted", "varchar", Types.VARCHAR),
                        notNullColumn("explicit_true", "varchar", Types.VARCHAR),
                        column("required", "varchar", Types.VARCHAR)),
                List.of(),
                List.of(),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateColumnMismatchRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(table)), mapped));
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0)).contains("required").doesNotContain("omitted", "explicit_true");
    }

    @Test
    void unrelatedKeyMetadataFailureDoesNotEraseObservedColumnFacts() {
        TableModel users = new TableModel(
                "app",
                "public",
                "users",
                "TABLE",
                List.of(column("email", "varchar", Types.VARCHAR)),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                new TableMetadata(true, false, false, false, false, List.of("keys unreadable")));
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("User#email", "email", false, "String")),
                List.of());
        assertThat(new HibernateColumnMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), mapped))
                        .status())
                .isEqualTo(VIOLATION);
        assertThat(new HibernateMissingColumnRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), mapped))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void boundedLargeLengthsAreComparedWithoutAnArbitraryMillionCharacterCutoff() {
        TableModel documents = table(
                "documents",
                List.of(column("body", "varchar", Types.VARCHAR, 1_500_000)),
                List.of(),
                List.of(),
                List.of());
        MappedEntityFacts mapped = entity(
                "Document",
                "documents",
                List.of(),
                List.of(new MappedColumnFacts("Document#body", "body", null, "String", 2_000_000)),
                List.of());
        assertThat(new HibernateColumnLengthMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(documents)), mapped))
                        .status())
                .isEqualTo(VIOLATION);
    }

    @Test
    void lengthsSkipKnownConvertersDefaultValuesAndUnboundedTypes() {
        TableModel documents = table(
                "documents", List.of(column("body", "varchar", Types.VARCHAR, 20)), List.of(), List.of(), List.of());
        for (MappedColumnFacts column : List.of(
                new MappedColumnFacts("Document#body", "body", null, "String", 255),
                new MappedColumnFacts("Document#body", "body", null, "String", 0),
                new MappedColumnFacts("Document#body", "body", null, "String", 400, false, true, false, null))) {
            MappedEntityFacts mapped = entity("Document", "documents", List.of(), List.of(column), List.of());
            assertThat(new HibernateColumnLengthMismatchRule()
                            .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(documents)), mapped))
                            .status())
                    .isEqualTo(SKIPPED);
        }

        TableModel text =
                table("documents", List.of(column("body", "text", Types.VARCHAR, 20)), List.of(), List.of(), List.of());
        MappedEntityFacts mapped = entity(
                "Document",
                "documents",
                List.of(),
                List.of(new MappedColumnFacts("Document#body", "body", null, "String", 400)),
                List.of());
        assertThat(new HibernateColumnLengthMismatchRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(text)), mapped))
                        .status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void mixedLengthEvidencePreservesKnownResultsAndReportsUnknownTargets() {
        MappedEntityFacts mapped = entity(
                "Document",
                "documents",
                List.of(),
                List.of(
                        new MappedColumnFacts("Document#known", "known", null, "String", 400),
                        new MappedColumnFacts("Document#unknown", "unknown", null, "String", 400)),
                List.of());
        for (int knownSize : List.of(20, 500)) {
            TableModel documents = table(
                    "documents",
                    List.of(
                            column("known", "varchar", Types.VARCHAR, knownSize),
                            column("unknown", "varchar", Types.VARCHAR)),
                    List.of(),
                    List.of(),
                    List.of());
            DatabaseAdvisorContext context =
                    hibernateContext(schema("ds", Dialect.GENERIC, List.of(documents)), mapped);
            DatabaseAdvisorRuleResultDto result = new HibernateColumnLengthMismatchRule().evaluate(context);
            assertThat(result.status()).isEqualTo(knownSize < 400 ? VIOLATION : PASS);
            assertThat(result.violationCount()).isEqualTo(knownSize < 400 ? 1 : 0);
            assertThat(context.evaluationDiagnostics()).hasSize(1);
        }
    }

    @Test
    void noNondefaultLengthDeclarationIsInapplicableNotAnUnknownEvidenceWarning() {
        MappedEntityFacts mapped = entity(
                "Document",
                "documents",
                List.of(),
                List.of(new MappedColumnFacts("Document#body", "body", null, "String", 255)),
                List.of());
        TableModel documents = table(
                "documents", List.of(column("body", "varchar", Types.VARCHAR, 20)), List.of(), List.of(), List.of());
        DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.GENERIC, List.of(documents)), mapped);
        assertThat(new HibernateColumnLengthMismatchRule().evaluate(context).status())
                .isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isEmpty();
    }

    @Test
    void implicitTableNamesAreInformationalSkipsEvenWithMultipleDatasources() {
        MappedEntityFacts implicit = entity(
                "Implicit",
                null,
                List.of(),
                List.of(new MappedColumnFacts("Implicit#value", "value", false, "String", 400)),
                List.of());
        List<AbstractDatabaseAdvisorRule> rules = List.of(
                new HibernateMissingTableRule(),
                new HibernateColumnMismatchRule(),
                new HibernateColumnLengthMismatchRule(),
                new HibernateMissingColumnRule(),
                new HibernateMissingUniqueIndexRule(),
                new HibernateMissingForeignKeyConstraintRule());
        for (AbstractDatabaseAdvisorRule rule : rules) {
            DatabaseAdvisorContext context = hibernateContext(
                    List.of(schema("first", Dialect.GENERIC, List.of()), schema("second", Dialect.GENERIC, List.of())),
                    List.of(implicit));
            assertThat(rule.evaluate(context).status()).isEqualTo(SKIPPED);
            assertThat(context.evaluationDiagnostics()).isEmpty();
        }
    }

    @Test
    void implicitEntitiesDoNotInvalidateEvaluatedExplicitDeclarations() {
        MappedEntityFacts implicit = entity(
                "Implicit",
                null,
                List.of(),
                List.of(new MappedColumnFacts("Implicit#value", "value", false, "String")),
                List.of());
        MappedEntityFacts explicit = entity(
                "Explicit",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("Explicit#email", "email", false, "String")),
                List.of());
        TableModel users = table(
                "users", List.of(notNullColumn("email", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of());
        for (AbstractDatabaseAdvisorRule rule :
                List.of(new HibernateMissingTableRule(), new HibernateColumnMismatchRule())) {
            DatabaseAdvisorContext context = hibernateContext(
                    List.of(schema("ds", Dialect.GENERIC, List.of(users))), List.of(implicit, explicit));
            assertThat(rule.evaluate(context).status()).isEqualTo(PASS);
            assertThat(context.evaluationDiagnostics()).isEmpty();
        }
    }

    @Test
    void defaultOnlyDeclarationsDoNotWarnAboutUnneededSourceResolution() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(new MappedColumnFacts("User#email", "email", null, "String", 255)),
                List.of());
        for (AbstractDatabaseAdvisorRule rule :
                List.of(new HibernateColumnMismatchRule(), new HibernateColumnLengthMismatchRule())) {
            DatabaseAdvisorContext context = hibernateContext(
                    List.of(schema("first", Dialect.GENERIC, List.of()), schema("second", Dialect.GENERIC, List.of())),
                    List.of(mapped));
            assertThat(rule.evaluate(context).status()).isEqualTo(SKIPPED);
            assertThat(context.evaluationDiagnostics()).isEmpty();
        }
    }

    @Test
    void uniquenessAcceptsStrongerPrimaryKeysAndInvisibleUniqueIndexes() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User uniqueness", List.of("email", "tenant"))));
        List<ColumnModel> columns =
                List.of(column("email", "varchar", Types.VARCHAR), column("tenant", "int4", Types.INTEGER));
        TableModel primary = new TableModel(
                "app",
                "public",
                "users",
                "TABLE",
                columns,
                "pk_users",
                List.of("email"),
                List.of(),
                List.of(),
                false,
                false,
                false,
                TableMetadata.COMPLETE.withPrimaryKeyEnforced(true));
        assertThat(new HibernateMissingUniqueIndexRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(primary)), mapped))
                        .status())
                .isEqualTo(PASS);
        IndexModel invisible = new IndexModel(
                "uq_email",
                List.of(IndexKeyPart.column("email", true)),
                true,
                "btree",
                null,
                IndexModel.Visibility.INVISIBLE,
                IndexModel.Validity.VALID);
        TableModel indexed = table("users", columns, List.of(), List.of(), List.of(invisible));
        assertThat(new HibernateMissingUniqueIndexRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.MYSQL, List.of(indexed)), mapped))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void oracleNullableOrUnknownSubsetDoesNotProveCompositeUniqueness() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User uniqueness", List.of("a", "b"))));
        IndexModel subset = new IndexModel(
                "uq_a",
                List.of(IndexKeyPart.column("a", true)),
                true,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
        for (ColumnModel.Nullability nullability :
                List.of(ColumnModel.Nullability.NULLABLE, ColumnModel.Nullability.UNKNOWN)) {
            TableModel users = table(
                    "users",
                    List.of(
                            new ColumnModel("a", "number", Types.NUMERIC, nullability, 10, 0, false),
                            column("b", "number", Types.NUMERIC)),
                    List.of(),
                    List.of(),
                    List.of(subset));
            DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.ORACLE, List.of(users)), mapped);
            DatabaseAdvisorRuleResultDto result = new HibernateMissingUniqueIndexRule().evaluate(context);
            assertThat(result.status()).isEqualTo(SKIPPED);
            assertThat(result.violationCount()).isZero();
            assertThat(context.evaluationDiagnostics()).hasSize(1);
        }
    }

    @Test
    void primaryKeyDeclarationAloneDoesNotEstablishEnforcedUniqueness() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User uniqueness", List.of("a", "b"))));
        for (Boolean enforced : Arrays.asList(null, false, true)) {
            TableModel users = new TableModel(
                    "app",
                    "public",
                    "users",
                    "TABLE",
                    List.of(notNullColumn("a", "int4", Types.INTEGER), column("b", "int4", Types.INTEGER)),
                    "pk_users",
                    List.of("a"),
                    List.of(),
                    List.of(),
                    false,
                    false,
                    false,
                    TableMetadata.COMPLETE.withPrimaryKeyEnforced(enforced));
            DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), mapped);
            assertThat(new HibernateMissingUniqueIndexRule().evaluate(context).status())
                    .isEqualTo(enforced == null ? SKIPPED : enforced ? PASS : VIOLATION);
            assertThat(context.evaluationDiagnostics()).hasSize(enforced == null ? 1 : 0);
        }
    }

    @Test
    void oracleKnownNotNullSubsetProvesCompositeUniqueness() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User uniqueness", List.of("a", "b"))));
        IndexModel subset = new IndexModel(
                "uq_a",
                List.of(IndexKeyPart.column("a", true)),
                true,
                "btree",
                null,
                IndexModel.Visibility.INVISIBLE,
                IndexModel.Validity.VALID);
        TableModel users = table(
                "users",
                List.of(notNullColumn("a", "number", Types.NUMERIC), column("b", "number", Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(subset));
        DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.ORACLE, List.of(users)), mapped);
        assertThat(new HibernateMissingUniqueIndexRule().evaluate(context).status())
                .isEqualTo(PASS);
        assertThat(context.evaluationDiagnostics()).isEmpty();
    }

    @Test
    void oracleSameColumnSetStillCoversNullableCompositeDeclarations() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User uniqueness", List.of("a", "b"))));
        IndexModel sameColumns = new IndexModel(
                "uq_b_a",
                List.of(IndexKeyPart.column("b", true), IndexKeyPart.column("a", true)),
                true,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
        TableModel users = table(
                "users",
                List.of(column("a", "number", Types.NUMERIC), column("b", "number", Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(sameColumns));
        DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.ORACLE, List.of(users)), mapped);
        assertThat(new HibernateMissingUniqueIndexRule().evaluate(context).status())
                .isEqualTo(PASS);
        assertThat(context.evaluationDiagnostics()).isEmpty();
    }

    @Test
    void postgresAndMysqlOrdinaryNullSemanticsPermitNullableSubsetCoverage() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User uniqueness", List.of("a", "b"))));
        IndexModel subset = new IndexModel(
                "uq_a",
                List.of(IndexKeyPart.column("a", true)),
                true,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
        TableModel users = table(
                "users",
                List.of(column("a", "int4", Types.INTEGER), column("b", "int4", Types.INTEGER)),
                List.of(),
                List.of(),
                List.of(subset));
        for (Dialect dialect : List.of(Dialect.POSTGRESQL, Dialect.MYSQL, Dialect.MARIADB)) {
            DatabaseAdvisorContext context = hibernateContext(schema("ds", dialect, List.of(users)), mapped);
            assertThat(new HibernateMissingUniqueIndexRule().evaluate(context).status())
                    .isEqualTo(PASS);
            assertThat(context.evaluationDiagnostics()).isEmpty();
        }
    }

    @Test
    void absenceOfAnObservedUniqueKeyIsADeclarationReview() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User#email", List.of("email"))));
        TableModel users =
                table("users", List.of(column("email", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingUniqueIndexRule()
                .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), mapped));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("no enforcing key", "declaration-name");
    }

    @Test
    void partialInvalidAndUnknownUniqueIndexesCannotProveAnAbsentGuarantee() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User#email", List.of("email"))));
        for (IndexModel index : List.of(
                new IndexModel(
                        "uq",
                        List.of(IndexKeyPart.column("email", true)),
                        true,
                        "btree",
                        "email IS NOT NULL",
                        IndexModel.Visibility.VISIBLE,
                        IndexModel.Validity.VALID),
                new IndexModel(
                        "uq",
                        List.of(IndexKeyPart.column("email", true)),
                        true,
                        "btree",
                        null,
                        IndexModel.Visibility.VISIBLE,
                        IndexModel.Validity.INVALID),
                IndexModel.of("uq", List.of("email"), true))) {
            TableModel users = table(
                    "users", List.of(column("email", "varchar", Types.VARCHAR)), List.of(), List.of(), List.of(index));
            DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.GENERIC, List.of(users)), mapped);
            assertThat(new HibernateMissingUniqueIndexRule().evaluate(context).status())
                    .isEqualTo(SKIPPED);
            assertThat(context.evaluationDiagnostics()).isNotEmpty();
        }
    }

    @Test
    void oracleNonuniqueBackingIndexDoesNotProveMissingUniqueness() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User#email", List.of("email"))));
        TableModel users = table(
                "users",
                List.of(column("email", "varchar", Types.VARCHAR)),
                List.of(),
                List.of(),
                List.of(index("constraint_backing", List.of("email"))));
        DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.ORACLE, List.of(users)), mapped);
        assertThat(new HibernateMissingUniqueIndexRule().evaluate(context).status())
                .isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isNotEmpty();
    }

    @Test
    void includedPayloadDoesNotExtendTheUniqueKey() {
        MappedEntityFacts mapped = entity(
                "User",
                "users",
                List.of(),
                List.of(),
                List.of(new MappedUniqueConstraintFacts("User#email", List.of("email"))));
        IndexModel included = new IndexModel(
                "uq_email",
                List.of(IndexKeyPart.column("email", true)),
                true,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID,
                false,
                false,
                false,
                false,
                List.of("tenant"),
                true,
                true,
                null);
        TableModel users = table(
                "users",
                List.of(column("email", "varchar", Types.VARCHAR), column("tenant", "int4", Types.INTEGER)),
                List.of(),
                List.of(),
                List.of(included));
        assertThat(new HibernateMissingUniqueIndexRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.POSTGRESQL, List.of(users)), mapped))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void unresolvedAssociationTargetOrImplicitReferencedColumnsCannotProveMissingConstraints() {
        TableModel orders =
                table("orders", List.of(column("customer_id", "int8", Types.BIGINT)), List.of(), List.of(), List.of());
        for (MappedForeignKeyFacts association : List.of(
                new MappedForeignKeyFacts("Order#customer", List.of("customer_id")),
                new MappedForeignKeyFacts(
                        "Order#customer",
                        List.of("customer_id"),
                        Arrays.asList((String) null),
                        null,
                        true,
                        "customers",
                        "public",
                        "app"))) {
            MappedEntityFacts mapped = entity("Order", "orders", List.of(association), List.of(), List.of());
            DatabaseAdvisorContext context = hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders)), mapped);
            assertThat(new HibernateMissingForeignKeyConstraintRule()
                            .evaluate(context)
                            .status())
                    .isEqualTo(SKIPPED);
            assertThat(context.evaluationDiagnostics()).isNotEmpty();
        }
    }

    @Test
    void foreignKeyColumnPairingMustMatchNotJustTheColumnSets() {
        TableModel target = table(
                "customers",
                List.of(column("id", "int8", Types.BIGINT), column("tenant", "int8", Types.BIGINT)),
                List.of("tenant", "id"),
                List.of(),
                List.of());
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int8", Types.BIGINT), column("tenant_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(foreignKey(
                        "wrong_pairs", List.of("customer_id", "tenant_id"), "customers", List.of("tenant", "id"))),
                List.of());
        MappedForeignKeyFacts foreignKey = new MappedForeignKeyFacts(
                "Order#customer",
                List.of("customer_id", "tenant_id"),
                List.of("id", "tenant"),
                null,
                true,
                "customers",
                "public",
                "app");
        MappedEntityFacts mapped = entity("Order", "orders", List.of(foreignKey), List.of(), List.of());
        assertThat(new HibernateMissingForeignKeyConstraintRule()
                        .evaluate(hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders, target)), mapped))
                        .status())
                .isEqualTo(VIOLATION);
    }

    @Test
    void anAmbiguousReferencedRelationIsUnknownEvenWhenTheSourceResolves() {
        TableModel orders =
                table("orders", List.of(column("customer_id", "int8", Types.BIGINT)), List.of(), List.of(), List.of());
        TableModel other = TableModel.of(
                "app",
                "crm",
                "customers",
                List.of(column("id", "int8", Types.BIGINT)),
                List.of("id"),
                List.of(),
                List.of());
        MappedForeignKeyFacts foreignKey = new MappedForeignKeyFacts(
                "Order#customer", List.of("customer_id"), List.of("id"), null, true, "customers", null, null);
        MappedEntityFacts mapped = entity("Order", "orders", List.of(foreignKey), List.of(), List.of());
        DatabaseAdvisorContext context =
                hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders, customers(), other)), mapped);
        assertThat(new HibernateMissingForeignKeyConstraintRule()
                        .evaluate(context)
                        .status())
                .isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isNotEmpty();
    }

    @Test
    void knownOracleDisabledForeignKeyIsAStateReviewNotAnAbsentConstraint() {
        TableModel orders =
                table("orders", List.of(column("customer_id", "int8", Types.BIGINT)), List.of(), List.of(), List.of());
        OracleConstraintDetail disabled = new OracleConstraintDetail(
                "public", "orders", "fk_customer", "R", "DISABLED", "VALIDATED", false, null);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_CONSTRAINTS, List.of(disabled), false))
                .build();
        MappedEntityFacts mapped = entity("Order", "orders", List.of(customerAssociation()), List.of(), List.of());
        DatabaseAdvisorContext context =
                hibernateContext(schema("ds", Dialect.ORACLE, List.of(orders, customers()), findings), mapped);
        assertThat(new HibernateMissingForeignKeyConstraintRule()
                        .evaluate(context)
                        .status())
                .isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isNotEmpty();
    }

    @Test
    void matchingForeignKeyDefinitionsNeedKnownEnforcementRatherThanAnAbsenceFinding() {
        MappedEntityFacts mapped = entity("Order", "orders", List.of(customerAssociation()), List.of(), List.of());
        for (Boolean enforced : Arrays.asList(null, false, true)) {
            ForeignKeyModel physical = foreignKey("fk_customer", List.of("customer_id"), "customers", List.of("id"))
                    .withEnforcement(enforced, true, "SIMPLE");
            TableModel orders = table(
                    "orders",
                    List.of(column("customer_id", "int8", Types.BIGINT)),
                    List.of(),
                    List.of(physical),
                    List.of());
            DatabaseAdvisorContext context =
                    hibernateContext(schema("ds", Dialect.GENERIC, List.of(orders, customers())), mapped);
            DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyConstraintRule().evaluate(context);
            assertThat(result.status()).isEqualTo(Boolean.TRUE.equals(enforced) ? PASS : SKIPPED);
            assertThat(result.violationCount()).isZero();
            assertThat(context.evaluationDiagnostics()).hasSize(Boolean.TRUE.equals(enforced) ? 0 : 1);
        }
    }

    @Test
    void enforcedNotValidForeignKeyIsObservedWithoutClaimingHistoricalValidation() {
        MappedEntityFacts mapped = entity("Order", "orders", List.of(customerAssociation()), List.of(), List.of());
        ForeignKeyModel physical = foreignKey("fk_customer", List.of("customer_id"), "customers", List.of("id"))
                .withEnforcement(true, false, "SIMPLE");
        TableModel orders = table(
                "orders",
                List.of(column("customer_id", "int8", Types.BIGINT)),
                List.of(),
                List.of(physical),
                List.of());
        DatabaseAdvisorRuleResultDto result = new HibernateMissingForeignKeyConstraintRule()
                .evaluate(hibernateContext(schema("ds", Dialect.POSTGRESQL, List.of(orders, customers())), mapped));
        assertThat(result.status()).isEqualTo(PASS);
        assertThat(result.description()).contains("historical row validation is not inferred");
    }

    private static MappedEntityFacts annotationFacts(Class<?> type) {
        List<HibernateAttributeModel> attributes = Arrays.stream(type.getDeclaredFields())
                .map(field -> new HibernateAttributeModel(
                        type.getName(),
                        field.getName(),
                        field.getType(),
                        field.getGenericType(),
                        "BASIC",
                        false,
                        true,
                        List.of(field.getDeclaredAnnotations())))
                .toList();
        return HibernateSchemaBridge.toMappedEntities(
                        List.of(new HibernateEntityModel(type.getName(), type, attributes)))
                .get(0);
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "declarations")
    static class DeclaredColumns {
        @jakarta.persistence.Id
        Long id;

        @jakarta.persistence.Column(name = "omitted")
        String omitted;

        @jakarta.persistence.Column(name = "explicit_true", nullable = true)
        String explicitTrue;

        @jakarta.persistence.Column(name = "required", nullable = false)
        String required;
    }
}
