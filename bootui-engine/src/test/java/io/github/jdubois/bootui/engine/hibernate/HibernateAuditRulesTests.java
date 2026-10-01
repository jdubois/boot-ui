package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Version;
import java.sql.Clob;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.junit.jupiter.api.Test;

/** Rules added or changed by the Hibernate advisor audit, each with positive, negative and edge cases. */
class HibernateAuditRulesTests {

    private static final int LONG32VARCHAR = 4001;

    private static HibernateContext declarations(Class<?>... types) {
        return new HibernateContext(
                List.of(types).stream().map(HibernateEntityModel::fromClass).toList(),
                List.of(),
                key -> null,
                List.of(),
                "7.4.5.Final");
    }

    private static HibernateContext observed(String dialect, Boolean enhanced, Class<?>... types) {
        TestEnvironment values = new TestEnvironment();
        if (dialect != null) values.withProperty("bootui.test.dialect", dialect);
        HibernateFactorySettings settings = HibernateObservationFixtures.settings(values);
        if (dialect == null) settings = withoutDialect(settings);
        return HibernateContext.observed(
                new HibernatePersistenceUnitObservation(
                        "orders",
                        "orders",
                        List.of(types).stream()
                                .map(HibernateEntityModel::fromClass)
                                .toList(),
                        List.of(),
                        "7.4.5.Final",
                        settings,
                        enhanced),
                HibernateObservationFixtures.application(new TestEnvironment()));
    }

    private static HibernateFactorySettings withoutDialect(HibernateFactorySettings s) {
        return new HibernateFactorySettings(
                s.jdbcBatchSize(),
                s.defaultBatchFetchSize(),
                s.orderInserts(),
                s.orderUpdates(),
                s.secondLevelCache(),
                s.queryCache(),
                s.paginationGuard(),
                s.statisticsEnabled(),
                s.regionFactory(),
                s.connectionProvider(),
                s.schemaAction(),
                s.lazyLoadOutsideTransaction(),
                s.inClausePadding(),
                s.slowQueryThreshold(),
                s.showSql(),
                s.formatSql(),
                s.sqlComments(),
                s.jdbcTimeZoneConfigured(),
                s.jdbcFetchSize(),
                s.oracle(),
                null);
    }

    // --- HIB-ENTITY-010 -----------------------------------------------------

    @Test
    void temporalVersionIsReportedWithItsLocation() {
        HibernateRuleResultDto result =
                new TemporalVersionRule().evaluate(declarations(InstantVersionEntity.class, DateVersionEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.INFO);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("modifiedAt is a Instant @Version"));
        assertThat(result.sampleLocations()).hasSize(2);
    }

    @Test
    void numericAndImmutableVersionsAreNotReported() {
        HibernateRuleResultDto result = new TemporalVersionRule()
                .evaluate(declarations(NumericVersionEntity.class, ImmutableInstantVersionEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-MAP-023 --------------------------------------------------------

    @Test
    void setOfEmbeddablesWithoutValueEqualityIsReported() {
        HibernateRuleResultDto result = new EmbeddableSetEqualityRule().evaluate(declarations(AddressBook.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.MEDIUM);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample)
                        .contains("#addresses is a Set of @Embeddable PlainAddress", "equals and hashCode"))
                .anySatisfy(sample -> assertThat(sample)
                        .contains("#halfAddresses is a Set of @Embeddable EqualsOnlyAddress", "override hashCode;"));
        assertThat(result.sampleLocations()).hasSize(2);
    }

    @Test
    void valueEqualityRecordsSortedSetsListsBasicValuesAndImmutableElementsAreNotReported() {
        HibernateRuleResultDto result = new EmbeddableSetEqualityRule().evaluate(declarations(SafeAddressBook.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void rawSetWithoutTargetClassIsUnknownNotClean() {
        HibernateContext context = declarations(RawAddressBook.class);

        HibernateRuleResultDto result = new EmbeddableSetEqualityRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(context.evidence().requiredUnknown()).isTrue();
    }

    @Test
    void rawSetWithTargetClassIsResolved() {
        HibernateRuleResultDto result =
                new EmbeddableSetEqualityRule().evaluate(declarations(TargetClassAddressBook.class));

        assertThat(result.violationCount()).isEqualTo(1);
    }

    // --- HIB-MAP-024 and its HIB-FETCH-005 hand-off ------------------------------

    @Test
    void postgresLobStringsAndBytesAreReportedButLocatorsAndNonLobTypeCodesAreNot() {
        HibernateRuleResultDto result = new PostgresLobRule().evaluate(observed("postgresql", true, LobDocument.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.LOW);
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("#body is @Lob on PostgreSQL"))
                .anySatisfy(sample -> assertThat(sample).contains("#payload is @Lob"))
                .anySatisfy(sample -> assertThat(sample).contains("#explicitClob is @Lob"))
                .noneMatch(sample -> sample.contains("#locator") || sample.contains("#text"));
        assertThat(result.sampleLocations()).hasSize(3);
    }

    @Test
    void postgresLobRuleNeedsAPostgresUnit() {
        assertThat(new PostgresLobRule()
                        .evaluate(observed("oracle", true, LobDocument.class))
                        .status())
                .isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(new PostgresLobRule()
                        .evaluate(declarations(LobDocument.class))
                        .status())
                .isEqualTo(HibernateRuleSupport.SKIPPED);
    }

    @Test
    void unknownDialectIsAGapOnlyWhenLobAttributesExist() {
        HibernateContext withLob = observed(null, true, LobDocument.class);
        assertThat(new PostgresLobRule().evaluate(withLob).status()).isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(withLob.evidence().requiredUnknown()).isTrue();

        HibernateContext withoutLob = observed(null, true, NumericVersionEntity.class);
        assertThat(new PostgresLobRule().evaluate(withoutLob).status()).isEqualTo(HibernateRuleSupport.PASS);
        assertThat(withoutLob.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void convertedLobIsUnresolvedRatherThanReportedOrCleared() {
        HibernateContext context = observed("postgresql", true, ConvertedLobDocument.class);

        HibernateRuleResultDto result = new PostgresLobRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(context.evidence().requiredUnknown()).isTrue();
    }

    @Test
    void lazyLobAdviceDefersToPostgresRuleAndSkipsLocators() {
        HibernateRuleResultDto postgres =
                new LobLazyFetchRule().evaluate(observed("postgresql", true, LobDocument.class));
        HibernateRuleResultDto other = new LobLazyFetchRule().evaluate(observed("oracle", true, LobDocument.class));

        // Only the text-typed attribute, which HIB-MAP-024 does not report, keeps the lazy-loading prompt.
        assertThat(postgres.sampleViolations()).singleElement().asString().contains("#text");
        assertThat(other.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(other.severity()).isEqualTo(HibernateRuleSupport.LOW);
        assertThat(other.sampleViolations()).hasSize(4).noneMatch(sample -> sample.contains("#locator"));
    }

    // --- HIB-CONFIG-013 -----------------------------------------------------

    @Test
    void jdbcTimeZoneReviewOnlyAppliesToCalendarBoundTemporalTypes() {
        assertThat(new JdbcTimeZoneRule()
                        .evaluate(declarations(UtcOnlyEntity.class))
                        .status())
                .isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(new JdbcTimeZoneRule()
                        .evaluate(declarations(WallClockEntity.class))
                        .status())
                .isEqualTo(HibernateRuleSupport.VIOLATION);
    }

    // --- HIB-MAP-006 --------------------------------------------------------

    @Test
    void oneToOneFindingsCarryMemberLocations() {
        HibernateRuleResultDto result = new OneToOneWithoutMapsIdRule().evaluate(declarations(OwningOneToOne.class));

        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.MEDIUM);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleLocations())
                .extracting(location -> location.memberName())
                .containsExactlyInAnyOrder("dependent", "plain");
    }

    // --- Fixtures -------------------------------------------------------------

    @Entity
    static class InstantVersionEntity {
        @Id
        Long id;

        @Version
        Instant modifiedAt;

        String name;
    }

    @Entity
    static class DateVersionEntity {
        @Id
        Long id;

        @Version
        Date modifiedAt;

        String name;
    }

    @Entity
    static class NumericVersionEntity {
        @Id
        Long id;

        @Version
        Long version;

        String name;
    }

    @Entity
    @Immutable
    static class ImmutableInstantVersionEntity {
        @Id
        Long id;

        @Version
        Instant modifiedAt;
    }

    @Embeddable
    static class PlainAddress {
        String street;
    }

    @Embeddable
    @SuppressWarnings("EqualsHashCode")
    static class EqualsOnlyAddress {
        String street;

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualsOnlyAddress address && Objects.equals(street, address.street);
        }
    }

    @Embeddable
    static class ValueAddress {
        String street;

        @Override
        public boolean equals(Object other) {
            return other instanceof ValueAddress address && Objects.equals(street, address.street);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(street);
        }
    }

    @Embeddable
    static class InheritedValueAddress extends ValueAddress {}

    @Embeddable
    record RecordAddress(String street) {}

    @Embeddable
    @Immutable
    static class ImmutableAddress {
        String street;
    }

    @Entity
    static class AddressBook {
        @Id
        Long id;

        @ElementCollection
        Set<PlainAddress> addresses;

        @ElementCollection
        Set<EqualsOnlyAddress> halfAddresses;
    }

    @Entity
    static class SafeAddressBook {
        @Id
        Long id;

        @ElementCollection
        Set<ValueAddress> values;

        @ElementCollection
        Set<InheritedValueAddress> inherited;

        @ElementCollection
        Set<RecordAddress> records;

        @ElementCollection
        Set<ImmutableAddress> immutable;

        @ElementCollection
        SortedSet<PlainAddress> sorted;

        @ElementCollection
        List<PlainAddress> list;

        @ElementCollection
        Set<String> tags;
    }

    @Entity
    @SuppressWarnings("rawtypes")
    static class RawAddressBook {
        @Id
        Long id;

        @ElementCollection
        Set addresses;
    }

    @Entity
    @SuppressWarnings("rawtypes")
    static class TargetClassAddressBook {
        @Id
        Long id;

        @ElementCollection(targetClass = PlainAddress.class)
        Set addresses;
    }

    @Entity
    static class LobDocument {
        @Id
        Long id;

        @Lob
        String body;

        @Lob
        byte[] payload;

        @Lob
        Clob locator;

        @Lob
        @JdbcTypeCode(LONG32VARCHAR)
        String text;

        @Lob
        @JdbcTypeCode(Types.CLOB)
        String explicitClob;
    }

    @Entity
    static class ConvertedLobDocument {
        @Id
        Long id;

        @Lob
        @Convert(converter = jakarta.persistence.AttributeConverter.class)
        String body;
    }

    @Entity
    static class UtcOnlyEntity {
        @Id
        Long id;

        Instant createdAt;

        LocalDate day;
    }

    @Entity
    static class WallClockEntity {
        @Id
        Long id;

        LocalDateTime startsAt;
    }

    @Entity
    static class OwningOneToOne {
        @Id
        Long id;

        @OneToOne(optional = false)
        NumericVersionEntity dependent;

        @OneToOne(cascade = CascadeType.PERSIST)
        InstantVersionEntity plain;
    }
}
