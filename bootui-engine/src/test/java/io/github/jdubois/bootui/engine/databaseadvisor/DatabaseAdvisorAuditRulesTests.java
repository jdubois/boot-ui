package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.column;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.context;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.index;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.invalidIndex;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.invisibleIndex;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.table;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.vendorSchema;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rules added or changed by the 2026-10 Database advisor audit: DB-PG-002/005, DB-SCHEMA-010 and DB-HIB-009. */
class DatabaseAdvisorAuditRulesTests {

    // --- DB-PG-005 -----------------------------------------------------------------------------------

    @Test
    void unloggedTablesAndPartitionsAreLowDurabilityReviews() {
        DatabaseAdvisorRuleResultDto result = new PostgresUnloggedTableRule()
                .evaluate(context(vendorSchema(
                        "primary",
                        Dialect.POSTGRESQL,
                        VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_UNLOGGED_TABLES,
                                List.of(
                                        new PostgresUnloggedTable("public", "cache", false),
                                        new PostgresUnloggedTable("public", "events_2026", true)),
                                false))));
        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(DatabaseAdvisorRuleSupport.LOW);
        assertThat(result.sampleViolations())
                .containsExactly(
                        "primary: table public.cache is UNLOGGED: its rows are truncated after a crash and are absent "
                                + "from physical standbys and WAL-based point-in-time recovery.",
                        "primary: partition public.events_2026 is UNLOGGED: its rows are truncated after a crash and "
                                + "are absent from physical standbys and WAL-based point-in-time recovery.");
    }

    @Test
    void unloggedTableRuleNeedsItsCatalogAndIsNotApplicableElsewhere() {
        DatabaseAdvisorContext clean = context(vendorSchema(
                "primary",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_UNLOGGED_TABLES, List.of(), false)));
        assertThat(new PostgresUnloggedTableRule().evaluate(clean).status()).isEqualTo(DatabaseAdvisorRuleSupport.PASS);

        DatabaseAdvisorContext denied = context(vendorSchema(
                "primary",
                Dialect.POSTGRESQL,
                VendorAugmentation.failed(VendorFindingKinds.POSTGRES_UNLOGGED_TABLES, "permission denied")));
        DatabaseAdvisorRuleResultDto skipped = new PostgresUnloggedTableRule().evaluate(denied);
        assertThat(skipped.status()).isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(denied.evaluationDiagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("permission denied"));

        DatabaseAdvisorContext truncated = context(vendorSchema(
                "primary",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_UNLOGGED_TABLES, List.of(), true)));
        assertThat(new PostgresUnloggedTableRule().evaluate(truncated).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);

        DatabaseAdvisorContext mysql = context(schema("primary", Dialect.MYSQL, List.of()));
        assertThat(new PostgresUnloggedTableRule()
                        .evaluate(mysql)
                        .sampleViolations()
                        .get(0))
                .startsWith("Not applicable:");
        assertThat(mysql.evaluationDiagnostics()).isEmpty();
    }

    // --- DB-PG-002 never-read sequences ---------------------------------------------------------------

    @Test
    void aNeverReadSequenceIsAssessedRatherThanUnknown() {
        PostgresSequenceUsage fresh = new PostgresSequenceUsage(
                "public",
                "orders_id_seq",
                null,
                java.math.BigInteger.valueOf(Long.MAX_VALUE),
                java.math.BigInteger.valueOf(Long.MAX_VALUE),
                false,
                "public",
                "orders",
                "id",
                "int8",
                1L,
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                java.math.BigInteger.valueOf(Long.MIN_VALUE),
                java.math.BigInteger.ONE,
                true);
        DatabaseAdvisorContext context = context(vendorSchema(
                "primary",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_SEQUENCES, List.of(fresh), false)));
        assertThat(new PostgresSequenceExhaustionRule().evaluate(context).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.PASS);
        assertThat(context.evaluationDiagnostics()).isEmpty();

        PostgresSequenceUsage hidden = new PostgresSequenceUsage(
                "public",
                "orders_id_seq",
                null,
                java.math.BigInteger.valueOf(Long.MAX_VALUE),
                java.math.BigInteger.valueOf(Long.MAX_VALUE),
                false,
                "public",
                "orders",
                "id",
                "int8",
                1L,
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                java.math.BigInteger.valueOf(Long.MIN_VALUE),
                java.math.BigInteger.ONE);
        DatabaseAdvisorContext hiddenContext = context(vendorSchema(
                "primary",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_SEQUENCES, List.of(hidden), false)));
        assertThat(new PostgresSequenceExhaustionRule().evaluate(hiddenContext).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(hiddenContext.evaluationDiagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("unknown counter"));
    }

    // --- DB-SCHEMA-010 -------------------------------------------------------------------------------

    @Test
    void invisibleAndIgnoredIndexesAreReportedWithTheirVendorState() {
        TableModel orders = table(
                "orders",
                List.of(column("status", "varchar", Types.VARCHAR)),
                List.of(),
                List.of(),
                List.of(invisibleIndex("ix_status", List.of("status")), visible("ix_created", "created")));
        DatabaseAdvisorRuleResultDto mysql =
                new InvisibleIndexRule().evaluate(context(schema("ds", Dialect.MYSQL, List.of(orders))));
        assertThat(mysql.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(mysql.severity()).isEqualTo(DatabaseAdvisorRuleSupport.LOW);
        assertThat(mysql.violationCount()).isEqualTo(1);
        assertThat(mysql.sampleViolations().get(0))
                .contains("index ix_status [status] is INVISIBLE")
                .contains("still maintained on every write");

        DatabaseAdvisorRuleResultDto mariadb =
                new InvisibleIndexRule().evaluate(context(schema("ds", Dialect.MARIADB, List.of(orders))));
        assertThat(mariadb.sampleViolations().get(0)).contains("is IGNORED");

        DatabaseAdvisorRuleResultDto oracle = new InvisibleIndexRule().evaluate(context(oracle19(List.of(orders))));
        assertThat(oracle.sampleViolations().get(0)).contains("is INVISIBLE");
    }

    @Test
    void visibleIndexesPassAndExcludedIndexesAreNotFindings() {
        TableModel visibleOnly = table("t", List.of(), List.of(), List.of(), List.of(visible("ix_a", "a")));
        assertThat(new InvisibleIndexRule()
                        .evaluate(context(schema("ds", Dialect.MYSQL, List.of(visibleOnly))))
                        .status())
                .isEqualTo(DatabaseAdvisorRuleSupport.PASS);

        IndexModel automatic = invisibleIndex("SYS_AI_8ab1", List.of("a"));
        IndexModel backing = invisibleIndex("uk_a", List.of("a")).withBackingConstraint("UK_A");
        IndexModel unusable = new IndexModel(
                "ix_unusable",
                List.of(IndexKeyPart.column("a", true)),
                false,
                "normal",
                null,
                IndexModel.Visibility.INVISIBLE,
                IndexModel.Validity.INVALID);
        TableModel excluded = table(
                "t", List.of(), List.of(), List.of(), List.of(automatic, backing, unusable, visible("ix_b", "b")));
        DatabaseAdvisorContext context = context(oracle19(List.of(excluded)));
        assertThat(new InvisibleIndexRule().evaluate(context).status()).isEqualTo(DatabaseAdvisorRuleSupport.PASS);
        assertThat(context.evaluationDiagnostics()).isEmpty();
        assertThat(invalidIndex("x", List.of("a")).visibility()).isEqualTo(IndexModel.Visibility.VISIBLE);
    }

    @Test
    void unknownVisibilityAndUnsupportedVersionsAreNeverFindings() {
        TableModel generic = table("t", List.of(), List.of(), List.of(), List.of(index("ix_a", List.of("a"))));
        DatabaseAdvisorContext unknown = context(schema("ds", Dialect.MYSQL, List.of(generic)));
        assertThat(new InvisibleIndexRule().evaluate(unknown).status()).isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(unknown.evaluationDiagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("visibility"));

        DatabaseAdvisorContext oldMariaDb = context(withVersion(
                schema("ds", Dialect.MARIADB, List.of(generic)), DatabaseVersion.of(10, 5, "10.5.27-MariaDB")));
        assertThat(new InvisibleIndexRule().evaluate(oldMariaDb).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(oldMariaDb.evaluationDiagnostics()).isEmpty();

        DatabaseAdvisorContext postgres = context(schema("ds", Dialect.POSTGRESQL, List.of(generic)));
        assertThat(new InvisibleIndexRule().evaluate(postgres).status()).isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(postgres.evaluationDiagnostics()).isEmpty();
    }

    // --- DB-HIB-009 ----------------------------------------------------------------------------------

    @Test
    void identityKeyWithoutReportedGenerationIsReported() {
        DatabaseAdvisorRuleResultDto result =
                evaluateIdentity(Dialect.POSTGRESQL, idColumn(Boolean.FALSE, null, Boolean.FALSE), true);
        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(DatabaseAdvisorRuleSupport.MEDIUM);
        assertThat(result.sampleViolations().get(0))
                .contains("com.example.Order#id declares @GeneratedValue(strategy = IDENTITY)")
                .contains("public.orders.id (int8)")
                .contains("unless a trigger assigns the key");
        assertThat(evaluateIdentity(Dialect.MYSQL, idColumn(Boolean.FALSE, null, null), true)
                        .status())
                .isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
    }

    @Test
    void reportedIdentityDefaultsAndGeneratedColumnsPass() {
        for (ColumnModel generated : List.of(
                idColumn(Boolean.TRUE, null, Boolean.FALSE),
                idColumn(Boolean.FALSE, "nextval('orders_id_seq'::regclass)", Boolean.FALSE),
                idColumn(Boolean.FALSE, "gen_order_id()", Boolean.FALSE),
                idColumn(Boolean.FALSE, null, Boolean.TRUE))) {
            assertThat(evaluateIdentity(Dialect.POSTGRESQL, generated, true).status())
                    .as(generated.toString())
                    .isEqualTo(DatabaseAdvisorRuleSupport.PASS);
        }
        assertThat(evaluateIdentity(Dialect.MARIADB, idColumn(Boolean.FALSE, "NULL", Boolean.FALSE), true)
                        .status())
                .as("an explicit NULL default generates nothing")
                .isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
    }

    @Test
    void unknownGenerationMetadataOtherDialectsAndNonIdentityStrategiesAreNotFindings() {
        ColumnModel unreported = idColumn(null, null, null);
        DatabaseAdvisorContext unknown = identityContext(Dialect.POSTGRESQL, unreported, true);
        assertThat(new HibernateIdentityGenerationMismatchRule()
                        .evaluate(unknown)
                        .status())
                .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(unknown.evaluationDiagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("IS_AUTOINCREMENT"));

        for (Dialect unverified : List.of(Dialect.ORACLE, Dialect.GENERIC)) {
            DatabaseAdvisorContext context =
                    identityContext(unverified, idColumn(Boolean.FALSE, null, Boolean.FALSE), true);
            assertThat(new HibernateIdentityGenerationMismatchRule()
                            .evaluate(context)
                            .status())
                    .as(unverified.name())
                    .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
            assertThat(context.evaluationDiagnostics()).as(unverified.name()).isEmpty();
        }
        assertThat(evaluateIdentity(Dialect.POSTGRESQL, idColumn(Boolean.FALSE, null, Boolean.FALSE), false)
                        .status())
                .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
    }

    @Test
    void legacySevenArgumentColumnsNeverClaimAnExplicitNo() {
        ColumnModel legacy =
                new ColumnModel("id", "int8", Types.BIGINT, ColumnModel.Nullability.NOT_NULL, 19, 0, false);
        assertThat(legacy.autoIncrementReported()).isNull();
        assertThat(legacy.knownWithoutDatabaseGeneration()).isFalse();
        assertThat(new ColumnModel("id", "int8", Types.BIGINT, ColumnModel.Nullability.NOT_NULL, 19, 0, true)
                        .autoIncrementReported())
                .isTrue();
    }

    private static DatabaseAdvisorRuleResultDto evaluateIdentity(
            Dialect dialect, ColumnModel physical, boolean identityGenerated) {
        return new HibernateIdentityGenerationMismatchRule()
                .evaluate(identityContext(dialect, physical, identityGenerated));
    }

    private static DatabaseAdvisorContext identityContext(
            Dialect dialect, ColumnModel physical, boolean identityGenerated) {
        MappedColumnFacts id = new MappedColumnFacts(
                "com.example.Order#id",
                "id",
                null,
                "Long",
                null,
                false,
                false,
                true,
                null,
                null,
                null,
                identityGenerated);
        MappedEntityFacts entity =
                new MappedEntityFacts("com.example.Order", "orders", null, null, List.of(), List.of(id), List.of());
        TableModel orders = table("orders", List.of(physical), List.of("id"), List.of(), List.of());
        return new DatabaseAdvisorContext(List.of(schema("ds", dialect, List.of(orders))), true, List.of(entity));
    }

    private static ColumnModel idColumn(Boolean autoIncrement, String defaultValue, Boolean generated) {
        return new ColumnModel(
                "id",
                "int8",
                Types.BIGINT,
                ColumnModel.Nullability.NOT_NULL,
                19,
                0,
                Boolean.TRUE.equals(autoIncrement),
                autoIncrement,
                defaultValue,
                generated);
    }

    private static IndexModel visible(String name, String column) {
        return new IndexModel(
                name,
                List.of(IndexKeyPart.column(column, true)),
                false,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
    }

    private static SchemaSnapshot oracle19(List<TableModel> tables) {
        return withVersion(schema("ds", Dialect.ORACLE, tables), DatabaseVersion.of(19, 0, "19.0"));
    }

    private static SchemaSnapshot withVersion(SchemaSnapshot schema, DatabaseVersion version) {
        return new SchemaSnapshot(
                schema.dataSourceName(),
                schema.dialect(),
                schema.databaseProductName(),
                version,
                schema.identifierCase(),
                schema.tables(),
                schema.vendorFindings(),
                schema.diagnostics(),
                schema.truncated(),
                schema.error());
    }
}
