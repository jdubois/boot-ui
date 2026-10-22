package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.column;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.foreignKey;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.notNullColumn;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateAttributeModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateEntityModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.sql.Types;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Regression corpus for tricky mappings seen from the Database advisor, one test per scenario and rule, named
 * {@code scenarioNN_<what>_<rule>}. The Hibernate cross-reference rules compare explicit declarations with observed
 * schema metadata; these scenarios pin that mappings the advisor cannot read stay unknown rather than becoming a
 * finding or a scan error. Scenario numbers match {@code HibernateMappingScenarioTests}.
 */
class DatabaseAdvisorScenarioTests {

    private static final String VIOLATION = DatabaseAdvisorRuleSupport.VIOLATION;
    private static final String PASS = DatabaseAdvisorRuleSupport.PASS;
    private static final String ERROR = DatabaseAdvisorRuleSupport.ERROR;

    private static List<DatabaseAdvisorRule> hibernateRules() {
        List<DatabaseAdvisorRule> rules = DatabaseAdvisorRuleRegistry.activeRules().stream()
                .filter(rule -> rule.definition().id().startsWith("DB-HIB-"))
                .toList();
        assertThat(rules).isNotEmpty();
        return rules;
    }

    private static DatabaseAdvisorRule rule(String id) {
        return hibernateRules().stream()
                .filter(rule -> rule.definition().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static DatabaseAdvisorContext context(SchemaSnapshot schema, MappedEntityFacts... entities) {
        return new DatabaseAdvisorContext(List.of(schema), true, List.of(entities));
    }

    private static void assertNoFindingsOrErrors(SchemaSnapshot schema, MappedEntityFacts... entities) {
        for (DatabaseAdvisorRule rule : hibernateRules()) {
            DatabaseAdvisorRuleResultDto result = rule.evaluate(context(schema, entities));
            assertThat(result.status()).as(rule.definition().id()).isNotIn(VIOLATION, ERROR);
        }
    }

    private static MappedEntityFacts facts(Class<?> type) {
        List<HibernateAttributeModel> attributes = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                attributes.add(new HibernateAttributeModel(
                        current.getName(),
                        field.getName(),
                        field.getType(),
                        field.getGenericType(),
                        field.isAnnotationPresent(OneToOne.class) ? "ONE_TO_ONE" : "BASIC",
                        false,
                        true,
                        List.of(field.getDeclaredAnnotations())));
            }
        }
        return HibernateSchemaBridge.toMappedEntities(
                        List.of(new HibernateEntityModel(type.getName(), type, attributes)))
                .get(0);
    }

    // --- S6: Envers -------------------------------------------------------------------------------------------------

    /**
     * Envers creates {@code *_AUD} tables and the revision table, none of which the application maps. The
     * cross-reference rules start from the application's declarations, so an unmapped physical table has nothing to
     * be compared with; the generic schema rules are unaffected. Envers' own {@code DefaultRevisionEntity} is left
     * out of entity discovery (see {@code HibernateMappingScenarioTests}), so the revision table stays unmapped too.
     */
    @Test
    void scenario06_unmappedAuditTablesProduceNoHibernateCrossReferenceFinding_DB_HIB_all() {
        SchemaSnapshot schema = schema(
                "ds",
                Dialect.GENERIC,
                List.of(
                        table(
                                "audited_orders",
                                List.of(
                                        notNullColumn("id", "bigint", Types.BIGINT),
                                        notNullColumn("name", "varchar", Types.VARCHAR)),
                                List.of("id"),
                                List.of(),
                                List.of()),
                        table(
                                "audited_orders_aud",
                                List.of(
                                        notNullColumn("id", "bigint", Types.BIGINT),
                                        notNullColumn("rev", "integer", Types.INTEGER),
                                        column("revtype", "smallint", Types.SMALLINT),
                                        column("name", "varchar", Types.VARCHAR)),
                                List.of("id", "rev"),
                                List.of(foreignKey("fk_aud_rev", List.of("rev"), "revinfo", List.of("rev"))),
                                List.of()),
                        table(
                                "revinfo",
                                List.of(
                                        notNullColumn("rev", "integer", Types.INTEGER),
                                        column("revtstmp", "bigint", Types.BIGINT)),
                                List.of("rev"),
                                List.of(),
                                List.of())));

        assertNoFindingsOrErrors(schema, facts(AuditedOrder.class));
        assertThat(rule("DB-HIB-003")
                        .evaluate(context(schema, facts(AuditedOrder.class)))
                        .status())
                .isEqualTo(PASS);
    }

    // --- S10: narrow physical types behind wider Java types ------------------------------------------------------

    @Test
    void scenario10_tinyintAndSmallintColumnsBehindIntEnumAndMonth_DB_HIB_all() {
        SchemaSnapshot schema = schema(
                "ds",
                Dialect.GENERIC,
                List.of(table(
                        "readings",
                        List.of(
                                notNullColumn("id", "bigint", Types.BIGINT),
                                notNullColumn("status", "tinyint", Types.TINYINT),
                                column("level", "smallint", Types.SMALLINT, 5),
                                notNullColumn("reading_month", "smallint", Types.SMALLINT)),
                        List.of("id"),
                        List.of(),
                        List.of())));
        MappedEntityFacts reading = facts(Reading.class);

        assertNoFindingsOrErrors(schema, reading);
        // The declarations are compared (nullability), but a Java type is never judged against a JDBC type.
        assertThat(rule("DB-HIB-003").evaluate(context(schema, reading)).status())
                .isEqualTo(PASS);
        assertThat(reading.columns())
                .filteredOn(column -> column.columnName().equals("level"))
                .singleElement()
                .satisfies(level -> assertThat(level.ambiguousType()).isTrue());
    }

    // --- S11: mappings the bridge cannot fully resolve ----------------------------------------------------------

    @Test
    void scenario11_genericEmbeddedIdIdentityOneToOneAndAnnotationFreeEntity_DB_HIB_all() {
        List<MappedEntityFacts> mapped = List.of(
                facts(GenericEmbeddedIdEntity.class),
                facts(IdentityOwner.class),
                facts(IdentityTarget.class),
                facts(HbmXmlOnlyPojo.class));
        SchemaSnapshot schema = schema(
                "ds",
                Dialect.GENERIC,
                List.of(
                        table(
                                "generic_keyed",
                                List.of(column("name", "varchar", Types.VARCHAR)),
                                List.of(),
                                List.of(),
                                List.of()),
                        table(
                                "identity_owner",
                                List.of(notNullColumn("id", "bigint", Types.BIGINT)),
                                List.of("id"),
                                List.of(),
                                List.of()),
                        table(
                                "identity_target",
                                List.of(
                                        notNullColumn("id", "bigint", Types.BIGINT),
                                        column("owner_id", "bigint", Types.BIGINT)),
                                List.of("id"),
                                List.of(foreignKey("fk_owner", List.of("owner_id"), "identity_owner", List.of("id"))),
                                List.of())));

        assertNoFindingsOrErrors(schema, mapped.toArray(MappedEntityFacts[]::new));
    }

    // --- Fixtures ---------------------------------------------------------------------------------------------------

    @Entity
    @Table(name = "audited_orders")
    static class AuditedOrder {
        @Id
        @Column(name = "id", nullable = false)
        Long id;

        @Column(name = "name", nullable = false)
        String name;
    }

    enum Level {
        LOW,
        HIGH
    }

    @Entity
    @Table(name = "readings")
    static class Reading {
        @Id
        @Column(name = "id", nullable = false)
        Long id;

        @Column(name = "status", nullable = false)
        int status;

        @Enumerated(EnumType.ORDINAL)
        @Column(name = "level", length = 50)
        Level level;

        @Column(name = "reading_month", nullable = false)
        Month month;
    }

    @MappedSuperclass
    abstract static class GenericKeyBase<T extends Serializable> implements Serializable {
        T first;
        Long second;

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericKeyBase<?> key
                    && Objects.equals(first, key.first)
                    && Objects.equals(second, key.second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
    }

    @Embeddable
    static class StringKey extends GenericKeyBase<String> {
        public StringKey() {}
    }

    @Entity
    @Table(name = "generic_keyed")
    static class GenericEmbeddedIdEntity {
        @EmbeddedId
        StringKey id;

        @Column(name = "name")
        String name;
    }

    @Entity
    @Table(name = "identity_owner")
    static class IdentityOwner {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        Long id;

        @OneToOne(mappedBy = "owner")
        IdentityTarget target;
    }

    @Entity
    @Table(name = "identity_target")
    static class IdentityTarget {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        Long id;

        @OneToOne
        @JoinColumn(name = "owner_id")
        IdentityOwner owner;
    }

    /** What an entity mapped only in {@code .hbm.xml} looks like to reflection: a plain, unannotated class. */
    static class HbmXmlOnlyPojo {
        Long id;
        String name;
    }
}
