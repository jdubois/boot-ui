package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HibernateRulesTests {

    private static HibernateContext context(TestEnvironment environment, Class<?>... entityTypes) {
        return new HibernateContext(
                List.of(entityTypes).stream()
                        .map(HibernateEntityModel::fromClass)
                        .toList(),
                List.of(),
                environment.lookup(),
                environment.activeProfiles(),
                "7.3.9.Final");
    }

    private static HibernateContext context(
            TestEnvironment environment, List<HibernateRepositoryModel> repositories, Class<?>... entityTypes) {
        return new HibernateContext(
                List.of(entityTypes).stream()
                        .map(HibernateEntityModel::fromClass)
                        .toList(),
                repositories,
                environment.lookup(),
                environment.activeProfiles(),
                "7.3.9.Final");
    }

    private static HibernateRepositoryMethodModel queryMethod(
            String name, String query, List<Class<?>> parameterTypes) {
        return new HibernateRepositoryMethodModel(
                "com.example.Repo",
                name,
                Object.class,
                List.class,
                query,
                false,
                null,
                false,
                false,
                false,
                false,
                parameterTypes);
    }

    // --- HIB-ID-006 ---------------------------------------------------------

    @Test
    void identityBatchingRuleFlagsIdentityWhenBatchingConfigured() {
        TestEnvironment environment =
                new TestEnvironment().withProperty("spring.jpa.properties.hibernate.jdbc.batch_size", "25");

        HibernateRuleResultDto result =
                new IdentityDisablesBatchingRule().evaluate(context(environment, IdentityEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.HIGH);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("GenerationType.IDENTITY"));
    }

    @Test
    void identityBatchingRuleSkipsWhenBatchingNotConfigured() {
        HibernateRuleResultDto result =
                new IdentityDisablesBatchingRule().evaluate(context(new TestEnvironment(), IdentityEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
    }

    @Test
    void identityBatchingRulePassesForSequenceIdentifiers() {
        TestEnvironment environment = new TestEnvironment().withProperty("hibernate.jdbc.batch_size", "25");

        HibernateRuleResultDto result =
                new IdentityDisablesBatchingRule().evaluate(context(environment, SequenceEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-MAP-020 --------------------------------------------------------

    @Test
    void unidirectionalOneToManyJoinColumnRuleFlagsWritableJoinColumn() {
        HibernateRuleResultDto result = new UnidirectionalOneToManyJoinColumnRule()
                .evaluate(context(new TestEnvironment(), JoinColumnOneToManyEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.MEDIUM);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("extra UPDATE"));
    }

    @Test
    void unidirectionalOneToManyJoinColumnRuleSkipsReadOnlyJoinColumn() {
        HibernateRuleResultDto result = new UnidirectionalOneToManyJoinColumnRule()
                .evaluate(context(new TestEnvironment(), ReadOnlyJoinColumnOneToManyEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void unidirectionalOneToManyJoinColumnRulePassesForMappedBy() {
        HibernateRuleResultDto result = new UnidirectionalOneToManyJoinColumnRule()
                .evaluate(context(new TestEnvironment(), MappedByOneToManyEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-QUERY-007 ------------------------------------------------------

    @Test
    void multipleCollectionJoinFetchRuleFlagsMultipleBagsAsHigh() {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.Repo",
                MultiCollectionRoot.class,
                List.of(queryMethod(
                        "findAll",
                        "select r from MultiCollectionRoot r join fetch r.firstBag join fetch r.secondBag",
                        List.of())));

        HibernateRuleResultDto result = new MultipleCollectionJoinFetchRule()
                .evaluate(context(new TestEnvironment(), List.of(repository), MultiCollectionRoot.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.HIGH);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("MultipleBagFetchException"));
    }

    @Test
    void multipleCollectionJoinFetchRuleFlagsMixedCollectionsAsMedium() {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.Repo",
                MultiCollectionRoot.class,
                List.of(queryMethod(
                        "findAll",
                        "select r from MultiCollectionRoot r join fetch r.firstBag join fetch r.tagSet",
                        List.of())));

        HibernateRuleResultDto result = new MultipleCollectionJoinFetchRule()
                .evaluate(context(new TestEnvironment(), List.of(repository), MultiCollectionRoot.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.MEDIUM);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("Cartesian product"));
    }

    @Test
    void multipleCollectionJoinFetchRulePassesForSingleCollectionFetch() {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.Repo",
                MultiCollectionRoot.class,
                List.of(queryMethod(
                        "findAll", "select r from MultiCollectionRoot r join fetch r.firstBag", List.of())));

        HibernateRuleResultDto result = new MultipleCollectionJoinFetchRule()
                .evaluate(context(new TestEnvironment(), List.of(repository), MultiCollectionRoot.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-CONFIG-002 -----------------------------------------------------

    @Test
    void riskyDdlAutoRuleUsesCriticalForCreateInProduction() {
        TestEnvironment environment =
                new TestEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "create-drop");
        environment.setActiveProfiles("prod");

        HibernateRuleResultDto result = new RiskyDdlAutoRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.CRITICAL);
    }

    @Test
    void riskyDdlAutoRuleUsesHighForUpdateInProduction() {
        TestEnvironment environment = new TestEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
        environment.setActiveProfiles("production");

        HibernateRuleResultDto result = new RiskyDdlAutoRule().evaluate(context(environment));

        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.HIGH);
    }

    @Test
    void riskyDdlAutoRuleUsesInfoForDevProfile() {
        TestEnvironment environment = new TestEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
        environment.setActiveProfiles("dev");

        HibernateRuleResultDto result = new RiskyDdlAutoRule().evaluate(context(environment));

        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.INFO);
    }

    @Test
    void riskyDdlAutoRuleUsesMediumWithoutProfile() {
        TestEnvironment environment = new TestEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");

        HibernateRuleResultDto result = new RiskyDdlAutoRule().evaluate(context(environment));

        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.MEDIUM);
    }

    @Test
    void riskyDdlAutoRuleLetsProductionWinOverTestProfile() {
        TestEnvironment environment = new TestEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "create");
        environment.setActiveProfiles("prod", "test");

        HibernateRuleResultDto result = new RiskyDdlAutoRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.CRITICAL);
    }

    @Test
    void riskyDdlAutoRulePassesForTestProfile() {
        TestEnvironment environment = new TestEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "create");
        environment.setActiveProfiles("test");

        HibernateRuleResultDto result = new RiskyDdlAutoRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-CONFIG-008 -----------------------------------------------------

    @Test
    void providerDisablesAutocommitRuleFlagsWhenHikariDisablesAutoCommit() {
        TestEnvironment environment =
                new TestEnvironment().withProperty("spring.datasource.hikari.auto-commit", "false");

        HibernateRuleResultDto result = new ProviderDisablesAutocommitRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
    }

    @Test
    void providerDisablesAutocommitRulePassesWhenPropertyEnabled() {
        TestEnvironment environment = new TestEnvironment()
                .withProperty("spring.datasource.hikari.auto-commit", "false")
                .withProperty("hibernate.connection.provider_disables_autocommit", "true");

        HibernateRuleResultDto result = new ProviderDisablesAutocommitRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void providerDisablesAutocommitRuleSkipsWhenSignalUnknown() {
        HibernateRuleResultDto result = new ProviderDisablesAutocommitRule().evaluate(context(new TestEnvironment()));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
    }

    // --- HIB-CONFIG-009 -----------------------------------------------------

    @Test
    void inClausePaddingRuleMatchesNamedParameterInPredicate() {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.Repo",
                Object.class,
                List.of(queryMethod("findByIds", "select o from Order o where o.id in :ids", List.of(List.class))));

        HibernateRuleResultDto result =
                new InClausePaddingRule().evaluate(context(new TestEnvironment(), List.of(repository)));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
    }

    @Test
    void inClausePaddingRuleDoesNotMatchJoinKeyword() {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.Repo",
                Object.class,
                List.of(queryMethod(
                        "findWithJoin",
                        "select o from Order o join fetch o.lines where o.code = :code",
                        List.of(List.class))));

        HibernateRuleResultDto result =
                new InClausePaddingRule().evaluate(context(new TestEnvironment(), List.of(repository)));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void inClausePaddingRuleIgnoresInsideQuotedLiteral() {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.Repo",
                Object.class,
                List.of(queryMethod(
                        "findByLabel",
                        "select o from Order o where o.label = 'shipped in (transit)' and o.id = :id",
                        List.of(List.class))));

        HibernateRuleResultDto result =
                new InClausePaddingRule().evaluate(context(new TestEnvironment(), List.of(repository)));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-CONFIG-015 -----------------------------------------------------

    @Test
    void deferDatasourceInitializationRuleSkipsWhenDdlAutoUnset() {
        TestEnvironment environment =
                new TestEnvironment().withProperty("spring.jpa.defer-datasource-initialization", "true");

        HibernateRuleResultDto result = new DeferDatasourceInitializationRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
    }

    @Test
    void deferDatasourceInitializationRuleViolatesForValidate() {
        TestEnvironment environment = new TestEnvironment()
                .withProperty("spring.jpa.defer-datasource-initialization", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate");

        HibernateRuleResultDto result = new DeferDatasourceInitializationRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
    }

    @Test
    void deferDatasourceInitializationRulePassesForCreate() {
        TestEnvironment environment = new TestEnvironment()
                .withProperty("spring.jpa.defer-datasource-initialization", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "create");

        HibernateRuleResultDto result = new DeferDatasourceInitializationRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-MAP-006 --------------------------------------------------------

    @Test
    void oneToOneWithoutMapsIdRuleUsesMediumForDependentAssociation() {
        HibernateRuleResultDto result =
                new OneToOneWithoutMapsIdRule().evaluate(context(new TestEnvironment(), DependentOneToOneEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.MEDIUM);
    }

    @Test
    void oneToOneWithoutMapsIdRuleUsesLowForPlainAssociation() {
        HibernateRuleResultDto result =
                new OneToOneWithoutMapsIdRule().evaluate(context(new TestEnvironment(), PlainOneToOneEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.LOW);
    }

    // --- HIB-MAP-019 --------------------------------------------------------

    @Test
    void missingForeignKeyIndexRulePassesWhenInferredColumnLeadsIndex() {
        HibernateRuleResultDto result =
                new MissingForeignKeyIndexRule().evaluate(context(new TestEnvironment(), IndexedOwnerEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void missingForeignKeyIndexRuleFlagsWhenColumnIsNotLeadingIndexColumn() {
        HibernateRuleResultDto result =
                new MissingForeignKeyIndexRule().evaluate(context(new TestEnvironment(), NonLeadingIndexEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("customer_id"));
    }

    // --- Fixtures -----------------------------------------------------------

    @Entity
    static class IdentityEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
    }

    @Entity
    static class SequenceEntity {
        @Id
        @SequenceGenerator(name = "seq", allocationSize = 50)
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq")
        Long id;
    }

    @Entity
    static class JoinColumnOneToManyEntity {
        @Id
        Long id;

        @OneToMany
        @JoinColumn(name = "owner_id")
        List<Child> children;
    }

    @Entity
    static class ReadOnlyJoinColumnOneToManyEntity {
        @Id
        Long id;

        @OneToMany
        @JoinColumn(name = "owner_id", insertable = false, updatable = false)
        List<Child> children;
    }

    @Entity
    static class MappedByOneToManyEntity {
        @Id
        Long id;

        @OneToMany(mappedBy = "owner")
        List<Child> children;
    }

    @Entity
    static class MultiCollectionRoot {
        @Id
        Long id;

        @OneToMany(mappedBy = "root")
        List<Child> firstBag;

        @OneToMany(mappedBy = "root")
        List<Child> secondBag;

        @ManyToMany
        Set<Child> tagSet;
    }

    @Entity
    static class DependentOneToOneEntity {
        @Id
        Long id;

        @OneToOne(optional = false, cascade = CascadeType.ALL)
        Child child;
    }

    @Entity
    static class PlainOneToOneEntity {
        @Id
        Long id;

        @OneToOne
        Child child;
    }

    @Entity
    @Table(indexes = @jakarta.persistence.Index(name = "idx_customer", columnList = "customer_id"))
    static class IndexedOwnerEntity {
        @Id
        Long id;

        @ManyToOne
        Child customer;
    }

    @Entity
    @Table(indexes = @jakarta.persistence.Index(name = "idx_composite", columnList = "tenant_id, customer_id"))
    static class NonLeadingIndexEntity {
        @Id
        Long id;

        @ManyToOne
        Child customer;
    }

    static class Child {}
}
