package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.column;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.advisor.AdvisorFindingIdentity;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolation;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseFindingIdentityTests {

    @Test
    void aTableFindingIsKeyedOnTheDatasourceAndTheSeparatedTableParts() {
        TableModel audit =
                table("audit", List.of(column("event", "text", Types.VARCHAR)), List.of(), List.of(), List.of());

        List<AdvisorViolation> findings = evaluate(new MissingPrimaryKeyRule(), "main", audit);

        assertThat(findings)
                .extracting(AdvisorViolation::identity)
                .containsExactly("v1:ds=main|catalog=app|schema=public|table=audit");
    }

    @Test
    void theIdentityIsStableAcrossScansAndDoesNotDependOnTheMessage() {
        TableModel audit =
                table("audit", List.of(column("event", "text", Types.VARCHAR)), List.of(), List.of(), List.of());

        assertThat(evaluate(new MissingPrimaryKeyRule(), "main", audit).get(0).identity())
                .isEqualTo(evaluate(new MissingPrimaryKeyRule(), "main", audit)
                        .get(0)
                        .identity());
    }

    @Test
    void aDottedNameCannotAliasAQualifiedOne() {
        TableModel dottedTable = TableModel.of("app", "x", "a.b", List.of(), List.of(), List.of(), List.of());
        TableModel dottedSchema = TableModel.of("app", "x.a", "b", List.of(), List.of(), List.of(), List.of());

        assertThat(DatabaseIdentities.table("main", "app", dottedTable.schema(), dottedTable.name()))
                .isNotEqualTo(DatabaseIdentities.table("main", "app", dottedSchema.schema(), dottedSchema.name()));
    }

    @Test
    void aDatasourceNameIsEncodedAndStaysTheScope() {
        TableModel audit = table("audit", List.of(), List.of(), List.of(), List.of());

        String identity =
                evaluate(new MissingPrimaryKeyRule(), "my db|1", audit).get(0).identity();

        assertThat(AdvisorFindingIdentity.scope(identity)).isEqualTo("ds=my%20db%7C1");
        assertThat(identity).matches(AdvisorFindingIdentity.VALID.pattern());
    }

    @Test
    void aPairFindingHasOneIdentityWhicheverIndexIsListedFirst() {
        List<String> columns = List.of("id");
        TableModel forward = table(
                "one",
                List.of(column("id", "int", Types.INTEGER)),
                List.of(),
                List.of(),
                List.of(btree("ix_a", columns), btree("ix_b", columns)));
        TableModel reverse = table(
                "one",
                List.of(column("id", "int", Types.INTEGER)),
                List.of(),
                List.of(),
                List.of(btree("ix_b", columns), btree("ix_a", columns)));

        List<AdvisorViolation> first = evaluate(new DuplicateIndexRule(), "main", forward);
        List<AdvisorViolation> second = evaluate(new DuplicateIndexRule(), "main", reverse);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).identity())
                .isEqualTo(second.get(0).identity())
                .isEqualTo("v1:ds=main|catalog=app|schema=public|table=one|index=ix_a|index=ix_b");
    }

    @Test
    void theSamePhysicalObjectInTwoDatasourcesHasTwoIdentities() {
        TableModel audit = table("audit", List.of(), List.of(), List.of(), List.of());

        assertThat(evaluate(new MissingPrimaryKeyRule(), "one", audit).get(0).identity())
                .isNotEqualTo(evaluate(new MissingPrimaryKeyRule(), "two", audit)
                        .get(0)
                        .identity());
    }

    @Test
    void anUnnamedForeignKeyIsKeyedOnItsColumnsNotOnAShiftingOrdinal() {
        ForeignKeyModel first = foreignKey("fk#0");
        ForeignKeyModel shifted = foreignKey("fk#1");
        ForeignKeyModel named = foreignKey("fk_orders_customer");

        assertThat(DatabaseIdentities.foreignKey(first)).isEqualTo(DatabaseIdentities.foreignKey(shifted));
        assertThat(DatabaseIdentities.foreignKey(first)).containsExactly("fkcolumn=customer_id", "references=customer");
        assertThat(DatabaseIdentities.foreignKey(named)).containsExactly("fk=fk_orders_customer");
    }

    @Test
    void namesThatContainSeparatorsStayDistinctSets() {
        assertThat(DatabaseIdentities.parts(DatabaseIdentities.each("unique", List.of("a,b", "c"))))
                .isNotEqualTo(DatabaseIdentities.parts(DatabaseIdentities.each("unique", List.of("a", "b,c"))));
    }

    private static ForeignKeyModel foreignKey(String name) {
        return new ForeignKeyModel(
                name, List.of("customer_id"), null, "public", "customer", List.of("id"), null, null, null);
    }

    @Test
    void onlyTheRuntimeSqlRuleReadsASlidingWindowAndOnlyCrossReferencesReadMappings() {
        assertThat(DatabaseAdvisorRuleRegistry.activeRules())
                .filteredOn(rule -> !rule.comparableAcrossScans())
                .extracting(rule -> rule.definition().id())
                .containsExactly("DB-RUNTIME-001");
        assertThat(DatabaseAdvisorRuleRegistry.activeRules())
                .filteredOn(DatabaseAdvisorRule::readsMappings)
                .extracting(rule -> rule.definition().id())
                .allMatch(id -> id.startsWith("DB-HIB-"));
    }

    @Test
    void aRuleWithNoTargetLeftIsToldApartFromAnyOtherSkip() {
        TableModel single = table(
                "one",
                List.of(column("id", "int", Types.INTEGER)),
                List.of(),
                List.of(),
                List.of(btree("only", List.of("id"))));
        DatabaseAdvisorContext context = new DatabaseAdvisorContext(
                List.of(schema("main", Dialect.GENERIC, List.of(single))),
                false,
                List.of(),
                List.of(),
                List.of(),
                new AdvisorViolationCollector(10));

        var result = new DuplicateIndexRule().evaluate(context);

        assertThat(DatabaseAdvisorRuleSupport.noTargets(result)).isTrue();
        assertThat(DatabaseAdvisorRuleSupport.noTargets(
                        DatabaseAdvisorRuleSupport.skipped(new DuplicateIndexRule().definition(), "Not applicable.")))
                .isFalse();
    }

    @Test
    void aLiveStatementShapeIsKeyedOnADigestNeverOnSqlText() {
        String identity = DatabaseIdentities.parts("shape=" + "a".repeat(32));

        assertThat(identity).isEqualTo("v1:shape=" + "a".repeat(32)).doesNotContain("select");
    }

    private static IndexModel btree(String name, List<String> columns) {
        return new IndexModel(
                name,
                columns.stream()
                        .map(column -> IndexKeyPart.column(column, true))
                        .toList(),
                false,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID,
                false,
                false,
                false,
                false,
                List.of(),
                true,
                true,
                null);
    }

    private static List<AdvisorViolation> evaluate(
            AbstractDatabaseAdvisorRule rule, String dataSource, TableModel table) {
        AdvisorViolationCollector collector = new AdvisorViolationCollector(100);
        DatabaseAdvisorContext context = new DatabaseAdvisorContext(
                List.of(schema(dataSource, Dialect.GENERIC, List.of(table))),
                false,
                List.of(),
                List.of(),
                List.of(),
                collector);
        rule.evaluate(context);
        return collector.retained(rule.definition().id());
    }
}
