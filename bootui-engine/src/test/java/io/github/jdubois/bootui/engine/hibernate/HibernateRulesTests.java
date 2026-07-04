package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.NaturalId;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;

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

    // --- HIB-MAP-010 --------------------------------------------------------

    @Test
    void elementCollectionListOrderRulePassesWithOrderColumn() {
        HibernateRuleResultDto result = new ElementCollectionListOrderRule()
                .evaluate(context(new TestEnvironment(), OrderColumnElementCollectionEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void elementCollectionListOrderRuleFlagsPlainListWithNoOrderingAnnotation() {
        HibernateRuleResultDto result = new ElementCollectionListOrderRule()
                .evaluate(context(new TestEnvironment(), UnorderedElementCollectionEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("without @OrderColumn"));
    }

    @Test
    void elementCollectionListOrderRuleFlagsOrderByAloneBecauseItIsNotPersisted() {
        // @OrderBy only adds a query-time SQL ORDER BY and is never persisted, so Hibernate still cannot
        // compute a minimal diff on mutation - it is not a valid alternative to @OrderColumn.
        HibernateRuleResultDto result = new ElementCollectionListOrderRule()
                .evaluate(context(new TestEnvironment(), OrderByOnlyElementCollectionEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample)
                        .contains("without @OrderColumn")
                        .contains("@OrderBy only affects read-time ordering"));
    }

    // --- HIB-ENTITY-005 ------------------------------------------------------
    //
    // isPanacheFieldAccessRewriteActive() is a raw Class.forName classpath probe, so its "Panache present"
    // branch can only be exercised with a real io.quarkus.hibernate.*.panache.PanacheEntityBase class on the
    // JVM classpath. Adding one as a test fixture would make it "present" for every test in this module (the
    // JVM has no per-test classpath isolation), which would silently turn this rule's evaluateRule() into an
    // unconditional pass() everywhere else this module scans a public field - including the unrelated
    // HibernateScannerTests fixtures that intentionally assert a HIB-ENTITY-005 violation. So, matching the
    // untested classpath-presence precedent in AiFrameworkDetector, only the real-world default (Panache
    // absent) is exercised here; the "present" branch is reviewed by inspection, not by an automated fixture.

    @Test
    void publicPersistentFieldRuleFlagsPublicFieldWhenPanacheIsAbsent() {
        HibernateRuleResultDto result =
                new PublicPersistentFieldRule().evaluate(context(new TestEnvironment(), PublicFieldEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("is exposed as a public field"));
    }

    @Test
    void publicPersistentFieldRuleIgnoresTransientPublicFields() {
        HibernateRuleResultDto result = new PublicPersistentFieldRule()
                .evaluate(context(new TestEnvironment(), PublicTransientAndPersistentFieldEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("persistentName"));
        assertThat(result.sampleViolations())
                .noneSatisfy(sample -> assertThat(sample).contains("cachedFlag"));
    }

    @Test
    void publicPersistentFieldRuleIgnoresPropertyAccessGetters() throws NoSuchMethodException {
        // HibernateEntityModel.fromClass() (used by every other fixture via the context(...) helper) walks
        // fields and methods separately, so it cannot reproduce the real bug: the actual scan path,
        // JpaMetamodelReader.toAttributeModel(), resolves one Member per JPA attribute via
        // attribute.getJavaMember() and always passes the plain attribute name ("name", never "name()"), even
        // when that Member is a public getter Method for a property-access (getter-mapped) entity. Building
        // the attribute with HibernateAttributeModel.fromMember(...) directly - exactly as JpaMetamodelReader
        // does - reproduces that path here.
        Method getter = PropertyAccessEntity.class.getDeclaredMethod("getName");
        HibernateAttributeModel propertyAttribute = HibernateAttributeModel.fromMember("name", getter, "BASIC");
        HibernateEntityModel entity = new HibernateEntityModel(
                PropertyAccessEntity.class.getName(), PropertyAccessEntity.class, List.of(propertyAttribute));
        HibernateContext context = new HibernateContext(
                List.of(entity), List.of(), new TestEnvironment().lookup(), List.of(), "7.3.9.Final");

        HibernateRuleResultDto result = new PublicPersistentFieldRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void isPanacheFieldAccessRewriteActiveIsFalseWithoutPanacheOnClasspath() {
        assertThat(HibernateRuleModelSupport.isPanacheFieldAccessRewriteActive())
                .isFalse();
    }

    // --- HIB-ID-004 -----------------------------------------------------------

    @Test
    void generatedValueWithoutStrategyRuleFlagsMissingStrategyOnPlainEntity() {
        HibernateRuleResultDto result = new GeneratedValueWithoutStrategyRule()
                .evaluate(context(new TestEnvironment(), AutoGeneratedValueEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("@GeneratedValue without an explicit strategy"));
    }

    @Test
    void generatedValueWithoutStrategyRuleSkipsFrameworkDeclaredPanacheIdentifierButFlagsAppOwnedOnes()
            throws NoSuchFieldException {
        // Built by hand (entityName overridden to the Panache FQN) instead of via a fixture class that
        // extends a real io.quarkus.hibernate.orm.panache.PanacheEntity, so this test does not depend on any
        // class being added to the shared module classpath (see the HIB-ENTITY-005 section above for why that
        // would be unsafe here). The rule reads attribute.entityName(), which HibernateAttributeModel.from(...)
        // always sets to the *declaring* class - exactly what distinguishes a framework-inherited id from one
        // the application declares itself, even on a class that otherwise extends a Panache base class.
        HibernateAttributeModel template =
                HibernateAttributeModel.from(AutoGeneratedValueEntity.class.getDeclaredField("id"));
        HibernateAttributeModel panacheFrameworkIdentifier =
                withEntityName(template, "io.quarkus.hibernate.orm.panache.PanacheEntity");
        HibernateAttributeModel appOwnedIdentifier = withEntityName(template, "com.example.PanacheProductWithOwnId");

        HibernateEntityModel panacheEntity =
                new HibernateEntityModel("com.example.PanacheProduct", null, List.of(panacheFrameworkIdentifier));
        HibernateEntityModel appOwnedIdEntity =
                new HibernateEntityModel("com.example.PanacheProductWithOwnId", null, List.of(appOwnedIdentifier));
        HibernateContext context = new HibernateContext(
                List.of(panacheEntity, appOwnedIdEntity),
                List.of(),
                new TestEnvironment().lookup(),
                List.of(),
                "7.3.9.Final");

        HibernateRuleResultDto result = new GeneratedValueWithoutStrategyRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).hasSize(1);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("PanacheProductWithOwnId#id"));
    }

    @Test
    void generatedValueWithoutStrategyRuleSkipsUuidIdentifiersSoOnlyHibId005OwnsThem() {
        // A UUID-typed @Id @GeneratedValue with no explicit strategy resolves to Hibernate's UuidGenerator, not
        // a sequence, so HIB-ID-004's "AUTO always resolves to a sequence" rationale does not apply to it; that
        // finding belongs exclusively to HIB-ID-005, which is asserted separately below.
        HibernateContext context = context(new TestEnvironment(), UuidIdentifierEntity.class);

        HibernateRuleResultDto result = new GeneratedValueWithoutStrategyRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-ID-005 -----------------------------------------------------------

    @Test
    void uuidIdentifierGeneratorRuleFlagsUuidIdWithoutUuidGeneratorAndOnlyThatRuleFires() {
        HibernateContext context = context(new TestEnvironment(), UuidIdentifierEntity.class);

        HibernateRuleResultDto uuidResult = new UuidIdentifierGeneratorRule().evaluate(context);
        HibernateRuleResultDto generatedValueResult = new GeneratedValueWithoutStrategyRule().evaluate(context);

        assertThat(uuidResult.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(uuidResult.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("is a UUID id without @UuidGenerator"));
        assertThat(generatedValueResult.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void uuidIdentifierGeneratorRuleRecommendsVersion7OnHibernate7AndAbove() {
        HibernateEntityModel entity = HibernateEntityModel.fromClass(UuidIdentifierEntity.class);
        HibernateContext context = new HibernateContext(
                List.of(entity), List.of(), new TestEnvironment().lookup(), List.of(), "7.0.0.Final");

        HibernateRuleResultDto result = new UuidIdentifierGeneratorRule().evaluate(context);

        assertThat(result.sampleViolations())
                .anySatisfy(sample ->
                        assertThat(sample).contains("style = VERSION_7").doesNotContain("style = TIME"));
    }

    @Test
    void uuidIdentifierGeneratorRuleRecommendsTimeStyleBeforeHibernate7() {
        HibernateEntityModel entity = HibernateEntityModel.fromClass(UuidIdentifierEntity.class);
        HibernateContext context = new HibernateContext(
                List.of(entity), List.of(), new TestEnvironment().lookup(), List.of(), "6.6.5.Final");

        HibernateRuleResultDto result = new UuidIdentifierGeneratorRule().evaluate(context);

        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample)
                        .contains("style = TIME")
                        .contains("no more index-friendly than random")
                        .doesNotContain("VERSION_7")
                        .doesNotContain("VERSION_6"));
    }

    @Test
    void isFrameworkDeclaredPanacheIdentifierMatchesBothOrmAndReactiveBaseClasses() {
        HibernateAttributeModel ormIdentifier = new HibernateAttributeModel(
                "io.quarkus.hibernate.orm.panache.PanacheEntity",
                "id",
                Long.class,
                Long.class,
                null,
                true,
                true,
                List.of());
        HibernateAttributeModel reactiveIdentifier = new HibernateAttributeModel(
                "io.quarkus.hibernate.reactive.panache.PanacheEntity",
                "id",
                Long.class,
                Long.class,
                null,
                true,
                true,
                List.of());
        HibernateAttributeModel applicationOwnedIdentifier = new HibernateAttributeModel(
                "com.example.Order", "id", Long.class, Long.class, null, false, true, List.of());

        assertThat(HibernateRuleModelSupport.isFrameworkDeclaredPanacheIdentifier(ormIdentifier))
                .isTrue();
        assertThat(HibernateRuleModelSupport.isFrameworkDeclaredPanacheIdentifier(reactiveIdentifier))
                .isTrue();
        assertThat(HibernateRuleModelSupport.isFrameworkDeclaredPanacheIdentifier(applicationOwnedIdentifier))
                .isFalse();
    }

    // --- HIB-ENTITY-006 --------------------------------------------------------

    @Test
    void primitiveIdentifierOrVersionRuleFlagsPrimitiveIdentifier() {
        HibernateRuleResultDto result = new PrimitiveIdentifierOrVersionRule()
                .evaluate(context(new TestEnvironment(), PrimitiveIdEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.HIGH);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("primitive long"));
    }

    @Test
    void primitiveIdentifierOrVersionRuleFlagsPrimitiveVersion() {
        HibernateRuleResultDto result = new PrimitiveIdentifierOrVersionRule()
                .evaluate(context(new TestEnvironment(), PrimitiveVersionEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("primitive int"));
    }

    @Test
    void primitiveIdentifierOrVersionRulePassesForWrapperTypes() {
        HibernateRuleResultDto result =
                new PrimitiveIdentifierOrVersionRule().evaluate(context(new TestEnvironment(), SequenceEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-ENTITY-007 --------------------------------------------------------

    @Test
    void assignedIdPersistableRuleFlagsAssignedIdWithoutPersistable() {
        // Spring Data Commons' real Persistable interface is on this module's test classpath, so
        // isSpringDataPersistableAvailable() is always true here; the "Spring Data absent" skip branch is not
        // reachable from this module (matching the AiFrameworkDetector classpath-presence precedent).
        HibernateRuleResultDto result =
                new AssignedIdPersistableRule().evaluate(context(new TestEnvironment(), AssignedIdEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("does not implement Persistable"));
    }

    @Test
    void assignedIdPersistableRulePassesWhenEntityImplementsPersistable() {
        HibernateRuleResultDto result = new AssignedIdPersistableRule()
                .evaluate(context(new TestEnvironment(), PersistableAssignedIdEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void assignedIdPersistableRulePassesForGeneratedIdentifiers() {
        HibernateRuleResultDto result =
                new AssignedIdPersistableRule().evaluate(context(new TestEnvironment(), IdentityEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void isSpringDataPersistableAvailableIsTrueWhenSpringDataCommonsIsOnTheClasspath() {
        assertThat(HibernateRuleModelSupport.isSpringDataPersistableAvailable()).isTrue();
    }

    // --- HIB-ID-007 -------------------------------------------------------------

    @Test
    void compositeIdentifierContractRulePassesForWellFormedEmbeddedId() {
        HibernateRuleResultDto result = new CompositeIdentifierContractRule()
                .evaluate(context(new TestEnvironment(), WellFormedEmbeddedIdEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void compositeIdentifierContractRuleFlagsEmbeddedIdViolatingTheFullContract() {
        HibernateRuleResultDto result = new CompositeIdentifierContractRule()
                .evaluate(context(new TestEnvironment(), BrokenEmbeddedIdEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.HIGH);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample)
                        .contains("not Serializable")
                        .contains("no public no-arg ctor")
                        .contains("no equals() override")
                        .contains("no hashCode() override"));
    }

    @Test
    void compositeIdentifierContractRulePassesForWellFormedIdClass() {
        HibernateRuleResultDto result = new CompositeIdentifierContractRule()
                .evaluate(context(new TestEnvironment(), WellFormedIdClassEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void compositeIdentifierContractRuleFlagsIdClassMissingOnlySerializable() {
        HibernateRuleResultDto result = new CompositeIdentifierContractRule()
                .evaluate(context(new TestEnvironment(), BrokenIdClassEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("not Serializable"));
        assertThat(result.sampleViolations())
                .noneSatisfy(sample -> assertThat(sample).contains("override"));
    }

    // --- HIB-CONFIG-018 ----------------------------------------------------------

    @Test
    void bindParameterLoggingInProductionRuleFlagsTraceBinderLoggingInProduction() {
        TestEnvironment environment =
                new TestEnvironment().withProperty("logging.level.org.hibernate.orm.jdbc.bind", "TRACE");
        environment.setActiveProfiles("prod");

        HibernateRuleResultDto result = new BindParameterLoggingInProductionRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.HIGH);
    }

    @Test
    void bindParameterLoggingInProductionRuleFlagsLegacyBasicBinderTraceLoggingInProduction() {
        TestEnvironment environment = new TestEnvironment()
                .withProperty("logging.level.org.hibernate.type.descriptor.sql.BasicBinder", "trace");
        environment.setActiveProfiles("production");

        HibernateRuleResultDto result = new BindParameterLoggingInProductionRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
    }

    @Test
    void bindParameterLoggingInProductionRulePassesWhenNotInProduction() {
        TestEnvironment environment =
                new TestEnvironment().withProperty("logging.level.org.hibernate.orm.jdbc.bind", "TRACE");

        HibernateRuleResultDto result = new BindParameterLoggingInProductionRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void bindParameterLoggingInProductionRulePassesWhenLoggingNotEnabledInProduction() {
        TestEnvironment environment = new TestEnvironment();
        environment.setActiveProfiles("prod");

        HibernateRuleResultDto result = new BindParameterLoggingInProductionRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void bindParameterLoggingInProductionRulePassesForDebugLevelInProduction() {
        TestEnvironment environment =
                new TestEnvironment().withProperty("logging.level.org.hibernate.orm.jdbc.bind", "DEBUG");
        environment.setActiveProfiles("prod");

        HibernateRuleResultDto result = new BindParameterLoggingInProductionRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- HIB-ENTITY-009 ----------------------------------------------------------

    @Test
    void naturalIdCandidateRuleFlagsUniqueColumnWithoutNaturalId() {
        HibernateRuleResultDto result = new NaturalIdCandidateRule()
                .evaluate(context(new TestEnvironment(), UniqueColumnWithoutNaturalIdEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.INFO);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("email").contains("unique column"));
    }

    @Test
    void naturalIdCandidateRulePassesWhenNaturalIdIsDeclared() {
        HibernateRuleResultDto result =
                new NaturalIdCandidateRule().evaluate(context(new TestEnvironment(), NaturalIdEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void naturalIdCandidateRuleFlagsTableLevelUniqueConstraintWithoutNaturalId() {
        HibernateRuleResultDto result = new NaturalIdCandidateRule()
                .evaluate(context(new TestEnvironment(), TableUniqueConstraintEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("isbn"));
    }

    @Test
    void naturalIdCandidateRulePassesWhenNoUniqueColumnDeclared() {
        HibernateRuleResultDto result =
                new NaturalIdCandidateRule().evaluate(context(new TestEnvironment(), SequenceEntity.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.PASS);
    }

    // --- Fixtures -----------------------------------------------------------

    /** Copies {@code template} onto a different declaring class name, without needing a real class of that name. */
    private static HibernateAttributeModel withEntityName(HibernateAttributeModel template, String entityName) {
        return new HibernateAttributeModel(
                entityName,
                template.name(),
                template.rawType(),
                template.genericType(),
                template.persistentAttributeType(),
                template.publicMember(),
                template.fieldMember(),
                template.annotations());
    }

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

    @Entity
    static class OrderColumnElementCollectionEntity {
        @Id
        Long id;

        @ElementCollection
        @OrderColumn(name = "tag_order")
        List<String> tags;
    }

    @Entity
    static class UnorderedElementCollectionEntity {
        @Id
        Long id;

        @ElementCollection
        List<String> tags;
    }

    @Entity
    static class OrderByOnlyElementCollectionEntity {
        @Id
        Long id;

        @ElementCollection
        @OrderBy("name")
        List<String> tags;
    }

    @Entity
    static class AutoGeneratedValueEntity {
        @Id
        @GeneratedValue
        Long id;
    }

    @Entity
    static class UuidIdentifierEntity {
        @Id
        @GeneratedValue
        UUID id;
    }

    @Entity
    static class PrimitiveIdEntity {
        @Id
        long id;
    }

    @Entity
    static class PrimitiveVersionEntity {
        @Id
        Long id;

        @Version
        int version;
    }

    @Entity
    static class AssignedIdEntity {
        @Id
        Long id;
    }

    @Entity
    static class PersistableAssignedIdEntity implements Persistable<Long> {
        @Id
        Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public boolean isNew() {
            return id == null;
        }
    }

    /**
     * Plain (non-Panache) entity with a real public field, used to prove PublicPersistentFieldRule still flags a
     * public field in the default, Panache-absent case.
     */
    @Entity
    static class PublicFieldEntity {
        @Id
        Long id;

        public String name;
    }

    /**
     * Entity with one genuinely persistent public field and one {@code @Transient} public field, used to prove
     * PublicPersistentFieldRule only flags the persistent one - a {@code @Transient} field is never written to the
     * database, so it carries none of the lazy-loading/dirty-tracking risk the rule targets.
     */
    @Entity
    static class PublicTransientAndPersistentFieldEntity {
        @Id
        Long id;

        public String persistentName;

        @Transient
        public boolean cachedFlag;
    }

    /**
     * Entity using property (getter) access - "name" is backed by a public getter Method, not a field. This is
     * fully supported, instrumented JPA/Hibernate behavior and must not be treated as an exposed public field.
     */
    @Entity
    static class PropertyAccessEntity {
        private Long id;
        private String name;

        @Id
        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    static class Child {}

    /** Well-formed composite identifier class: implements Serializable, public no-arg ctor, equals + hashCode. */
    static class WellFormedEmbeddedIdKey implements Serializable {
        private Long orderId;
        private Long lineNumber;

        public WellFormedEmbeddedIdKey() {}

        WellFormedEmbeddedIdKey(Long orderId, Long lineNumber) {
            this.orderId = orderId;
            this.lineNumber = lineNumber;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WellFormedEmbeddedIdKey key
                    && java.util.Objects.equals(orderId, key.orderId)
                    && java.util.Objects.equals(lineNumber, key.lineNumber);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(orderId, lineNumber);
        }
    }

    @Entity
    static class WellFormedEmbeddedIdEntity {
        @EmbeddedId
        WellFormedEmbeddedIdKey id;
    }

    /** Composite identifier class violating every part of the JPA contract HIB-ID-007 checks. */
    static class BrokenEmbeddedIdKey {
        private final Long orderId;

        BrokenEmbeddedIdKey(Long orderId) {
            this.orderId = orderId;
        }
    }

    @Entity
    static class BrokenEmbeddedIdEntity {
        @EmbeddedId
        BrokenEmbeddedIdKey id;
    }

    /** Well-formed @IdClass identifier class. */
    static class WellFormedIdClassKey implements Serializable {
        private Long orderId;
        private Long lineNumber;

        public WellFormedIdClassKey() {}

        @Override
        public boolean equals(Object other) {
            return other instanceof WellFormedIdClassKey;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    @Entity
    @IdClass(WellFormedIdClassKey.class)
    static class WellFormedIdClassEntity {
        @Id
        Long orderId;

        @Id
        Long lineNumber;
    }

    /** @IdClass identifier class missing only Serializable - has a public no-arg ctor and overrides equals/hashCode. */
    static class BrokenIdClassKey {

        public BrokenIdClassKey() {}

        @Override
        public boolean equals(Object other) {
            return other instanceof BrokenIdClassKey;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    @Entity
    @IdClass(BrokenIdClassKey.class)
    static class BrokenIdClassEntity {
        @Id
        Long orderId;

        @Id
        Long lineNumber;
    }

    @Entity
    static class UniqueColumnWithoutNaturalIdEntity {
        @Id
        Long id;

        @Column(unique = true)
        String email;
    }

    @Entity
    static class NaturalIdEntity {
        @Id
        Long id;

        @NaturalId
        @Column(unique = true)
        String email;
    }

    @Entity
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "isbn"))
    static class TableUniqueConstraintEntity {
        @Id
        Long id;

        String isbn;
    }
}
