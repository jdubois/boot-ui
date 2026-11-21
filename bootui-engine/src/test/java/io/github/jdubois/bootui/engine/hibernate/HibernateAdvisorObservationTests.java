package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DismissedFindingDto;
import io.github.jdubois.bootui.core.dto.HibernateDiagnosticDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;

class HibernateAdvisorObservationTests {
    private static final Clock CLOCK = Clock.systemUTC();
    private static final String[] RETIRED_IDS = {
        "HIB-FETCH-004",
        "HIB-MAP-012",
        "HIB-MAP-017",
        "HIB-MAP-019",
        "HIB-MAP-021",
        "HIB-ENTITY-002",
        "HIB-ENTITY-003",
        "HIB-ENTITY-004",
        "HIB-QUERY-006",
        "HIB-CONFIG-001",
        "HIB-CONFIG-007",
        "HIB-CONFIG-011"
    };
    private static final HibernateApplicationFacts APP =
            new HibernateApplicationFacts(List.of("prod"), false, false, false, false, false);

    @Test
    void filteredFetchRetainsMediumFindingsWithoutCompletingUnknownQueryCoverage() {
        HibernateRepositoryMethodModel known =
                method("select o from Order o join fetch o.items i where i.id = :childId", null, Order.class, false);
        HibernateRepositoryMethodModel unknown = method(
                "select o from Order o join fetch o.items i where i.id = :childId or 1 = 1", null, Order.class, false);
        HibernateScanner scanner = scanner(
                List.of(filteredFetchUnit("known", List.of(known)), filteredFetchUnit("unknown", List.of(unknown))),
                List.of(),
                List.of(new FilteredCollectionJoinFetchRule()));

        HibernateReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("HIB-QUERY-010");
            assertThat(result.severity()).isEqualTo("MEDIUM");
            assertThat(result.violationCount()).isEqualTo(1);
            assertThat(result.coverageNote()).contains("[unknown]");
            assertThat(result.sampleViolations())
                    .singleElement()
                    .satisfies(text -> assertThat(text)
                            .contains("may initialize an incomplete managed collection")
                            .doesNotContain("childId", "select ", "delet", "corrupt"));
        });
        var page = scanner.ruleViolations(
                "HIB-QUERY-010", report.violationDetails().scanId(), 0, 10);
        assertThat(page.identities()).containsExactly("v1:unit=known|repository=Repo|method=query%28%29");
        assertThat(page.violations()).hasSize(1);
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("HIB-QUERY-010");
            assertThat(diagnostic.unit()).isEqualTo("unknown");
            assertThat(diagnostic.message()).doesNotContain("childId", "select ", "1 = 1");
        });
        HibernateReport dismissed = scanner.applyDismissals(report, Set.of("HIB-QUERY-010"));
        assertThat(dismissed.results())
                .singleElement()
                .satisfies(result -> assertThat(result.dismissed()).isTrue());
        assertThat(dismissed.violationsFound()).isZero();
        assertThat(dismissed.evidence().coverageComplete()).isFalse();
    }

    @Test
    void filteredFetchRequiresVerifiedStaticQueryAndEntityReturnEvidence() {
        for (HibernateQueryEvidence evidence : List.of(
                HibernateQueryEvidence.unknown(),
                new HibernateQueryEvidence(true, false, false, false, false, Order.class, false, null, null, List.of()),
                new HibernateQueryEvidence(true, true, false, true, false, Order.class, false, null, null, List.of()),
                new HibernateQueryEvidence(true, true, false, false, true, Order.class, false, null, null, List.of()),
                new HibernateQueryEvidence(true, true, false, false, false, null, false, null, null, List.of()))) {
            HibernateRepositoryMethodModel method = new HibernateRepositoryMethodModel(
                    "Repo",
                    "filtered",
                    Order.class,
                    List.class,
                    "select o from Order o join fetch o.items i where i.id = :id",
                    false,
                    null,
                    false,
                    false,
                    false,
                    false,
                    List.of(),
                    evidence);
            HibernateContext context = context("7.4.13.Final", List.of(method));

            assertThat(new FilteredCollectionJoinFetchRule().evaluate(context).status())
                    .as(evidence.toString())
                    .isEqualTo("SKIPPED");
            assertThat(context.evidence().requiredUnknown()).isTrue();
        }
        for (String query : new String[] {null, "", "select o from Order o join fetch o.items i where i.id = 'bad"}) {
            HibernateContext context = context("7.4.13.Final", List.of(method(query, null, Order.class, false)));
            assertThat(new FilteredCollectionJoinFetchRule().evaluate(context).status())
                    .isEqualTo("SKIPPED");
            assertThat(context.evidence().requiredUnknown()).isTrue();
        }
        HibernateContext projection = context(
                "7.4.13.Final",
                List.of(method(
                        "select o from Order o join fetch o.items i where i.id = :id", null, Projection.class, false)));
        assertThat(new FilteredCollectionJoinFetchRule().evaluate(projection).violationCount())
                .isZero();
        assertThat(projection.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void filteredFetchDoesNotCreateCoverageGapsForProvenIrrelevantOrAbsentRepositoryMetadata() {
        List<HibernateRepositoryMethodModel> irrelevant = List.of(
                new HibernateRepositoryMethodModel(
                        "Repo",
                        "findByName",
                        Order.class,
                        List.class,
                        null,
                        false,
                        null,
                        false,
                        false,
                        false,
                        false,
                        List.of(),
                        new HibernateQueryEvidence(
                                true, false, true, false, false, Order.class, false, null, null, List.of())),
                new HibernateRepositoryMethodModel(
                        "Repo",
                        "nativeRead",
                        Order.class,
                        List.class,
                        "select * from orders",
                        true,
                        null,
                        false,
                        false,
                        false,
                        false,
                        List.of()),
                method("select o from Order o where o.name = :#{#p.name}", null, Order.class, false),
                method(
                        "select o from Order o /* #{#fragment} ${fragment} -- join fetch o.items */",
                        null,
                        Order.class,
                        false),
                method("select o from Order o join fetch o.items i where i.id = :id", null, Projection.class, false),
                method("select o.id from Order o join fetch o.items i where i.id = :id", null, Long.class, false));
        for (List<HibernateRepositoryModel> repositories : List.of(
                List.<HibernateRepositoryModel>of(),
                List.of(new HibernateRepositoryModel("Repo", Order.class, irrelevant)))) {
            HibernateReport report = scanner(
                            List.of(unit("orders", settings(0), repositories)),
                            List.of(),
                            List.of(new FilteredCollectionJoinFetchRule()))
                    .scan();
            assertThat(report.scan().status()).isEqualTo("SCANNED");
            assertThat(report.results()).isEmpty();
            assertThat(report.diagnostics()).isEmpty();
            assertThat(report.evidence().coverageComplete()).isTrue();
        }
    }

    private static HibernatePersistenceUnitObservation filteredFetchUnit(
            String label, List<HibernateRepositoryMethodModel> methods) {
        return new HibernatePersistenceUnitObservation(
                label,
                label,
                List.of(HibernateEntityModel.fromClass(Order.class), HibernateEntityModel.fromClass(Other.class)),
                List.of(new HibernateRepositoryModel("Repo", Order.class, methods, true)),
                "7.4.13.Final",
                settings(0),
                false);
    }

    @Test
    void jdbcValueLoggingKeepsIndependentFindingsAndCoverageForKnownAndUnknownLoggerStates() {
        List<HibernatePersistenceUnitObservation> units =
                List.of(unit("orders", settings(25), List.of()), unit("archive", settings(25), List.of()));
        for (String profile : List.of("prod", "dev")) {
            for (Boolean bind : new Boolean[] {false, true, null}) {
                for (Boolean extract : new Boolean[] {false, true, null}) {
                    HibernateApplicationFacts app =
                            new HibernateApplicationFacts(List.of(profile), false, false, bind, extract, false);
                    HibernateScanner scanner = new HibernateScanner(
                            () -> new HibernateAdvisorObservation(units, app, List.of()),
                            CLOCK,
                            List.of(new BindParameterLoggingInProductionRule()));
                    HibernateReport report = scanner.scan();
                    boolean production = "prod".equals(profile);
                    boolean incomplete = production && (bind == null || extract == null);
                    List<String> expectedIdentities = new ArrayList<>();
                    List<String> expectedText = new ArrayList<>();
                    if (production && Boolean.TRUE.equals(bind)) {
                        expectedIdentities.add("v1:unit=application|setting=hibernate.type.descriptor.sql.BasicBinder");
                        expectedText.add(
                                "[application] Bind-parameter logging is enabled at TRACE while a production profile is active.");
                    }
                    if (production && Boolean.TRUE.equals(extract)) {
                        expectedIdentities.add("v1:unit=application|setting=org.hibernate.orm.jdbc.extract");
                        expectedText.add(
                                "[application] Result-value logging is enabled at TRACE while a production profile is active.");
                    }
                    assertThat(report.scan().status()).isEqualTo(incomplete ? "PARTIAL" : "SCANNED");
                    assertThat(report.rulesEvaluated()).isEqualTo(1);
                    assertThat(report.evidence().coverageComplete()).isEqualTo(!incomplete);
                    assertThat(report.evidence().usable())
                            .as("profile=%s, bind=%s, extract=%s", profile, bind, extract)
                            .isEqualTo(!expectedIdentities.isEmpty() || production && !incomplete);
                    assertThat(report.diagnostics()).hasSize(incomplete ? 1 : 0);
                    assertThat(report.violationDetails().total()).isEqualTo(expectedIdentities.size());
                    assertThat(report.severityCounts())
                            .filteredOn(count -> "HIGH".equals(count.severity()))
                            .singleElement()
                            .satisfies(count -> assertThat(count.count()).isEqualTo(expectedIdentities.size()));
                    if (expectedIdentities.isEmpty()) {
                        assertThat(report.results()).isEmpty();
                    } else {
                        assertThat(report.results()).singleElement().satisfies(result -> {
                            assertThat(result.id()).isEqualTo("HIB-CONFIG-018");
                            assertThat(result.violationCount()).isEqualTo(expectedIdentities.size());
                            assertThat(result.sampleViolations()).containsExactlyElementsOf(expectedText);
                            assertThat(result.coverageNote() != null).isEqualTo(incomplete);
                        });
                        var page = scanner.ruleViolations(
                                "HIB-CONFIG-018", report.violationDetails().scanId(), 0, 100);
                        assertThat(page.identities()).containsExactlyElementsOf(expectedIdentities);
                        assertThat(page.violations()).containsExactlyElementsOf(expectedText);
                    }
                }
            }
        }
    }

    @Test
    void oldApplicationFactsConstructorDoesNotClaimResultValueLoggingIsOff() {
        HibernateApplicationFacts legacy = new HibernateApplicationFacts(List.of("prod"), false, false, true, false);
        HibernateReport report = new HibernateScanner(
                        () -> new HibernateAdvisorObservation(
                                List.of(unit("orders", settings(25), List.of())), legacy, List.of()),
                        CLOCK,
                        List.of(new BindParameterLoggingInProductionRule()))
                .scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.violationCount()).isEqualTo(1);
            assertThat(result.sampleViolations())
                    .containsExactly(
                            "[application] Bind-parameter logging is enabled at TRACE while a production profile is active.");
            assertThat(result.coverageNote()).contains("org.hibernate.orm.jdbc.extract");
        });
    }

    @Test
    void existingBindFindingDismissalNeverSuppressesResultValueLogging() {
        AtomicReference<HibernateApplicationFacts> app =
                new AtomicReference<>(new HibernateApplicationFacts(List.of("prod"), false, false, true, false, false));
        HibernateScanner scanner = new HibernateScanner(
                () -> new HibernateAdvisorObservation(
                        List.of(unit("orders", settings(25), List.of())), app.get(), List.of()),
                CLOCK,
                List.of(new BindParameterLoggingInProductionRule()));
        HibernateReport before = scanner.scan();
        String bindIdentity = "v1:unit=application|setting=hibernate.type.descriptor.sql.BasicBinder";
        assertThat(scanner.ruleViolations(
                                "HIB-CONFIG-018", before.violationDetails().scanId(), 0, 100)
                        .identities())
                .containsExactly(bindIdentity);
        List<DismissedFindingDto> dismissals = List.of(new DismissedFindingDto("HIB-CONFIG-018", bindIdentity, null));

        app.set(new HibernateApplicationFacts(List.of("prod"), false, false, true, true, false));
        HibernateReport raw = scanner.scan();
        assertThat(raw.violationDetails().changes().newCount()).isEqualTo(1);
        assertThat(raw.violationDetails().changes().unchangedCount()).isEqualTo(1);
        assertThat(raw.violationDetails().changes().complete()).isTrue();
        HibernateReport dismissed = scanner.dismissedReport(Set.of(), dismissals);
        assertThat(dismissed.results()).singleElement().satisfies(result -> {
            assertThat(result.violationCount()).isEqualTo(1);
            assertThat(result.suppressedCount()).isEqualTo(1);
            assertThat(result.sampleViolations())
                    .containsExactly(
                            "[application] Result-value logging is enabled at TRACE while a production profile is active.");
        });
        assertThat(dismissed.severityCounts())
                .filteredOn(count -> "HIGH".equals(count.severity()))
                .singleElement()
                .satisfies(count -> assertThat(count.count()).isEqualTo(1));
        assertThat(scanner.ruleViolations(
                                "HIB-CONFIG-018", raw.violationDetails().scanId(), 0, 100, dismissals)
                        .suppressed())
                .containsExactly(true, false);
        assertThat(scanner.dismissedReport(Set.of("HIB-CONFIG-018"), List.of()).results())
                .singleElement()
                .satisfies(result -> assertThat(result.dismissed()).isTrue());
    }

    @Test
    void selectedCatalogTitlesAndSeveritiesStayScoped() {
        Map<String, List<String>> expected = Map.of(
                "HIB-ID-002", List.of("Review table-based identifier allocation", "MEDIUM"),
                "HIB-ID-004", List.of("Review provider-selected identifier strategies", "INFO"),
                "HIB-MAP-007", List.of("Review TABLE_PER_CLASS polymorphic queries", "INFO"),
                "HIB-QUERY-002", List.of("Review streaming query resource lifetime", "INFO"),
                "HIB-ENTITY-006", List.of("Review primitive-version newness semantics", "INFO"),
                "HIB-MAP-018", List.of("Review lazy inverse @OneToOne without enhancement", "MEDIUM"),
                "HIB-FETCH-005", List.of("Enhanced @Lob attributes should be loaded lazily", "LOW"),
                "HIB-ENTITY-010", List.of("Prefer a numeric @Version over a timestamp", "INFO"),
                "HIB-MAP-023", List.of("Set element collections of embeddables need equals and hashCode", "MEDIUM"),
                "HIB-MAP-024", List.of("@Lob on PostgreSQL stores values as large objects", "LOW"));
        expected.forEach((id, values) -> {
            HibernateRuleDefinition definition = HibernateRuleRegistry.activeRules().stream()
                    .map(HibernateRule::definition)
                    .filter(rule -> id.equals(rule.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(List.of(definition.name(), definition.severity())).isEqualTo(values);
        });
    }

    @Test
    void primitiveVersionAdviceRequiresStandardJpaNewnessAndDoesNotTargetIds() {
        List<Class<?>> types =
                List.of(PrimitiveVersion.class, NullableVersion.class, OnlyPrimitiveId.class, SubPersistable.class);
        for (Boolean standard : new Boolean[] {true, false, null}) {
            List<HibernateRepositoryModel> repositories = standard == null
                    ? List.of()
                    : types.stream()
                            .map(type -> new HibernateRepositoryModel("Repo", type, List.of(), standard))
                            .toList();
            HibernatePersistenceUnitObservation unit = new HibernatePersistenceUnitObservation(
                    "orders",
                    "orders",
                    types.stream().map(HibernateEntityModel::fromClass).toList(),
                    repositories,
                    "7.4.5.Final",
                    settings(0),
                    false);
            HibernateReport report = scanner(List.of(unit), List.of(), List.of(new PrimitiveIdentifierOrVersionRule()))
                    .scan();
            if (Boolean.TRUE.equals(standard)) {
                assertThat(report.results()).singleElement().satisfies(result -> {
                    assertThat(result.id()).isEqualTo("HIB-ENTITY-006");
                    assertThat(result.severity()).isEqualTo("INFO");
                    assertThat(result.violationCount()).isEqualTo(1);
                    assertThat(result.sampleViolations())
                            .singleElement()
                            .asString()
                            .contains("PrimitiveVersion#version");
                });
            } else {
                assertThat(report.results()).isEmpty();
            }
            assertThat(report.scan().status()).isEqualTo(Boolean.FALSE.equals(standard) ? "PARTIAL" : "SCANNED");
        }
    }

    @Test
    void unitSettingsNeverBleedAcrossFactoriesAndIdentityIsScoredOnlyOncePerUnit() {
        HibernatePersistenceUnitObservation small = unit("small", settings(1), List.of());
        HibernatePersistenceUnitObservation batched = unit("batched", settings(25), List.of());
        List<HibernateRule> rules = List.of(
                new IdentityIdentifierRule(),
                new IdentityDisablesBatchingRule(),
                new JdbcBatchSizeRule(),
                new OrderedBatchingRule());
        for (List<HibernatePersistenceUnitObservation> units :
                List.of(List.of(small, batched), List.of(batched, small))) {
            HibernateReport report = scanner(units, List.of(), rules).scan();
            assertThat(report.entitiesAnalyzed()).isEqualTo(2);
            assertThat(report.rulesEvaluated()).isEqualTo(4);
            assertThat(report.scan().status()).isEqualTo("SCANNED");
            assertThat(result(report, "HIB-ID-001").sampleViolations()).allMatch(value -> value.startsWith("[small]"));
            assertThat(result(report, "HIB-ID-006").sampleViolations())
                    .allMatch(value -> value.startsWith("[batched]"));
            assertThat(result(report, "HIB-CONFIG-004").sampleViolations())
                    .allMatch(value -> value.startsWith("[small]"));
            assertThat(result(report, "HIB-CONFIG-005").violationCount()).isEqualTo(2);
        }
    }

    @Test
    void batchSizesZeroOneTwoTwentyFiveAndUnknownHaveDistinctEvidence() {
        for (Integer batch : new Integer[] {0, 1, 2, 25, null}) {
            HibernateReport report = scanner(
                            List.of(unit(
                                    "orders",
                                    batch == null ? HibernateFactorySettings.unknown() : settings(batch),
                                    List.of())),
                            List.of(),
                            List.of(
                                    new IdentityIdentifierRule(),
                                    new IdentityDisablesBatchingRule(),
                                    new JdbcBatchSizeRule()))
                    .scan();
            if (batch == null) {
                assertThat(report.scan().status()).isEqualTo("PARTIAL");
                assertThat(report.results())
                        .extracting(HibernateRuleResultDto::id)
                        .containsExactly("HIB-ID-001");
            } else if (batch <= 1) {
                assertThat(report.results())
                        .extracting(HibernateRuleResultDto::id)
                        .containsExactlyInAnyOrder("HIB-ID-001", "HIB-CONFIG-004");
            } else {
                assertThat(report.results())
                        .extracting(HibernateRuleResultDto::id)
                        .containsExactly("HIB-ID-006");
            }
        }
    }

    @Test
    void missingUpdateOrderingDoesNotDiscardKnownInsertOrderingViolation() {
        HibernateFactorySettings settings = new HibernateFactorySettings(
                25, null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        HibernateReport report = scanner(
                        List.of(unit("orders", settings, List.of())), List.of(), List.of(new OrderedBatchingRule()))
                .scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("HIB-CONFIG-005");
            assertThat(result.violationCount()).isEqualTo(1);
            assertThat(result.sampleViolations()).singleElement().asString().contains("insert-ordering");
        });
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
    }

    @Test
    void aggregationKeepsRealCountsAndTenSamplesAndDismissalKey() {
        List<HibernatePersistenceUnitObservation> units = new ArrayList<>();
        for (int i = 0; i < 14; i++) units.add(unit("unit-" + i, settings(25), List.of()));
        HibernateScanner scanner = scanner(units, List.of(), List.of(new IdentityDisablesBatchingRule()));
        HibernateReport report = scanner.scan();
        assertThat(report.results()).hasSize(1);
        assertThat(result(report, "HIB-ID-006").violationCount()).isEqualTo(14);
        assertThat(result(report, "HIB-ID-006").sampleViolations()).hasSize(10);
        assertThat(report.rulesEvaluated()).isEqualTo(1);
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isTrue();
        HibernateReport dismissed = scanner.applyDismissals(report, Set.of("HIB-ID-006"));
        assertThat(dismissed.violationsFound()).isZero();
        assertThat(dismissed.evidence()).isEqualTo(report.evidence());
        assertThat(dismissed.results())
                .singleElement()
                .satisfies(value -> assertThat(value.dismissed()).isTrue());
    }

    @Test
    void ruleRuntimeAndLinkageFailuresMakeReadableFindingsPartialWithoutLeakingMessages() {
        for (boolean linkage : List.of(false, true)) {
            HibernateRule throwing = testRule("HIB-TEST-ERROR", context -> {
                if (linkage) throw new NoClassDefFoundError("secret-jdbc-password");
                throw new IllegalStateException("secret-jdbc-password");
            });
            HibernateReport report = scanner(
                            List.of(unit("orders", settings(25), List.of())),
                            List.of(new HibernateObservationDiagnostic(
                                    "failed-unit", HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE)),
                            List.of(new IdentityDisablesBatchingRule(), throwing))
                    .scan();
            assertThat(report.scan().status()).isEqualTo("PARTIAL");
            assertThat(report.scan().message()).contains("failed 1").doesNotContain("secret-jdbc-password");
            assertThat(report.diagnostics())
                    .containsExactly(
                            new HibernateDiagnosticDto(
                                    "discovery",
                                    "failed-unit",
                                    "WARNING",
                                    "EntityManagerFactory unavailable; its mappings were not inspected."),
                            new HibernateDiagnosticDto(
                                    "HIB-TEST-ERROR",
                                    "orders",
                                    "ERROR",
                                    "Rule evaluation failed; no conclusion was reached for this unit."));
            assertThat(report.diagnostics())
                    .extracting(HibernateDiagnosticDto::message)
                    .noneMatch(value -> value.contains("secret-jdbc-password"));
            assertThat(report.results()).extracting(HibernateRuleResultDto::id).containsExactly("HIB-ID-006");
            assertThat(report.evidence().usable()).isTrue();
            assertThat(report.evidence().coverageComplete()).isFalse();
        }
    }

    @Test
    void requiredUnknownDiffersFromIntentionalInapplicability() {
        HibernatePersistenceUnitObservation unit = unit("orders", HibernateFactorySettings.unknown(), List.of());
        HibernateReport missing = scanner(List.of(unit), List.of(), List.of(new JdbcBatchSizeRule()))
                .scan();
        assertThat(missing.scan().status()).isEqualTo("PARTIAL");
        assertThat(missing.results()).isEmpty();
        assertThat(missing.scan().message()).contains("required evidence unavailable 1", "See diagnostics for 1 entry");
        assertThat(missing.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("HIB-CONFIG-004");
            assertThat(diagnostic.unit()).isEqualTo("orders");
            assertThat(diagnostic.level()).isEqualTo("WARNING");
            assertThat(diagnostic.message())
                    .isEqualTo("No conclusion reached. Required evidence unavailable: 1 effective"
                            + " persistence-unit setting(s) unavailable.");
        });
        assertThat(missing.evidence().usable()).isFalse();
        assertThat(missing.evidence().coverageComplete()).isFalse();
        HibernateReport panache = scanner(
                        List.of(unit),
                        List.of(),
                        List.of(
                                new EagerToOneFetchJoinRule(),
                                new CollectionJoinFetchPageableRule(),
                                new InClausePaddingRule()))
                .scan();
        assertThat(panache.scan().status()).isEqualTo("SCANNED");
        assertThat(panache.scan().message())
                .contains("skipped 3", "required evidence unavailable 0")
                .doesNotContain("diagnostics");
        assertThat(panache.diagnostics()).isEmpty();
        assertThat(panache.evidence().usable()).isFalse();
        assertThat(panache.evidence().coverageComplete()).isTrue();
    }

    @Test
    void irrelevantBulkQueriesAreNeutralAndDoNotEraseAnEarlierApplicableFetchEvaluation() {
        HibernateRepositoryMethodModel bulk = fetchMethod("bulk", "update Order o set o.name = :name");
        HibernateRepositoryMethodModel select = fetchMethod("find", "select o from Order o join fetch o.items");
        for (List<HibernateRepositoryMethodModel> methods :
                List.of(List.of(bulk), List.of(bulk, select), List.of(select, bulk))) {
            HibernatePersistenceUnitObservation unit =
                    unit("orders", settings(25), List.of(new HibernateRepositoryModel("Repo", Order.class, methods)));
            HibernateContext context = HibernateContext.observed(unit, APP);
            assertThat(new MultipleCollectionJoinFetchRule().evaluate(context).status())
                    .isEqualTo("PASS");
            assertThat(context.evidence().evaluated()).isTrue();
            assertThat(context.evidence().requiredUnknown()).isFalse();
            assertThat(context.evidence().applicable()).isEqualTo(methods.size() > 1);
            assertThat(context.evidence().usable()).isEqualTo(methods.size() > 1);

            HibernateReport report = scanner(List.of(unit), List.of(), List.of(new MultipleCollectionJoinFetchRule()))
                    .scan();
            assertThat(report.scan().status()).isEqualTo("SCANNED");
            assertThat(report.rulesEvaluated()).isEqualTo(1);
            assertThat(report.results()).isEmpty();
            assertThat(report.diagnostics()).isEmpty();
            assertThat(report.evidence().coverageComplete()).isTrue();
            assertThat(report.evidence().usable()).isEqualTo(methods.size() > 1);
        }
    }

    @Test
    void uncertainBulkAndApplicableUnsupportedQueriesStillProduceRuleScopedGaps() {
        for (String query : List.of(
                "update Order o set",
                "update Order o set O.name = :name",
                "delete from Order o where O.id = :id",
                "update Order o set o.id = 1--1",
                "delete from Order o -- trailing text",
                "delete from Order o /* unclosed",
                "update Order o set o.name = 'unclosed",
                "delete from Order o " + " ".repeat(16_384),
                "delete from #{#entityName} o",
                "select o from Order o join o.items i join fetch i.children",
                "select o from Order o join Other p on p.id = o.id join fetch o.items join fetch o.tags",
                "select o from Order o, Order p join fetch o.items join fetch o.tags")) {
            HibernateContext context = context("7.4.5.Final", List.of(fetchMethod("unknown", query)));
            assertThat(new MultipleCollectionJoinFetchRule().evaluate(context).status())
                    .as(query)
                    .isEqualTo("SKIPPED");
            assertThat(context.evidence().requiredUnknown()).as(query).isTrue();
            assertThat(context.evidence().usable()).as(query).isFalse();
        }
        for (HibernateQueryEvidence evidence : List.of(
                HibernateQueryEvidence.unknown(),
                new HibernateQueryEvidence(true, true, false, true, false, int.class, false, null, null, List.of()),
                new HibernateQueryEvidence(true, true, false, false, true, int.class, false, null, null, List.of()))) {
            HibernateRepositoryMethodModel method = new HibernateRepositoryMethodModel(
                    "Repo",
                    "uncertain",
                    Order.class,
                    int.class,
                    "delete from Order o",
                    false,
                    null,
                    false,
                    true,
                    false,
                    false,
                    List.of(),
                    evidence);
            HibernateContext context = context("7.4.5.Final", List.of(method));
            assertThat(new MultipleCollectionJoinFetchRule().evaluate(context).status())
                    .isEqualTo("SKIPPED");
            assertThat(context.evidence().gaps()).containsOnlyKeys(HibernateEvidenceGap.QUERY_PROVENANCE);
        }
    }

    @Test
    void realParallelFetchFindingSurvivesAnUnrelatedGapInTheSameOrAnotherUnit() {
        HibernateRepositoryMethodModel finding =
                fetchMethod("findParallel", "select o from Order o join fetch o.items join fetch o.tags");
        HibernateRepositoryMethodModel bulk = fetchMethod("bulk", "delete from Order o where o.id = :id");
        HibernateRepositoryMethodModel unsupported =
                fetchMethod("findNested", "select o from Order o join o.items i join fetch i.children");
        for (boolean sameUnit : List.of(false, true)) {
            for (boolean reverse : List.of(false, true)) {
                List<HibernateRepositoryMethodModel> known = reverse ? List.of(bulk, finding) : List.of(finding, bulk);
                List<HibernatePersistenceUnitObservation> units = sameUnit
                        ? List.of(unit(
                                "known",
                                settings(25),
                                List.of(new HibernateRepositoryModel(
                                        "Repo",
                                        Order.class,
                                        reverse
                                                ? List.of(unsupported, bulk, finding)
                                                : List.of(finding, bulk, unsupported)))))
                        : List.of(
                                unit(
                                        "known",
                                        settings(25),
                                        List.of(new HibernateRepositoryModel("Repo", Order.class, known))),
                                unit(
                                        "unknown",
                                        settings(25),
                                        List.of(new HibernateRepositoryModel(
                                                "Repo", Order.class, List.of(unsupported)))));
                if (reverse && !sameUnit) units = List.of(units.get(1), units.get(0));
                HibernateScanner scanner = scanner(units, List.of(), List.of(new MultipleCollectionJoinFetchRule()));
                HibernateReport report = scanner.scan();
                assertThat(report.scan().status()).isEqualTo("PARTIAL");
                assertThat(report.rulesEvaluated()).isEqualTo(1);
                assertThat(report.evidence().usable()).isTrue();
                assertThat(report.evidence().coverageComplete()).isFalse();
                assertThat(result(report, "HIB-QUERY-007").violationCount()).isEqualTo(1);
                assertThat(result(report, "HIB-QUERY-007").severity()).isEqualTo("MEDIUM");
                assertThat(result(report, "HIB-QUERY-007").coverageNote()).contains(sameUnit ? "[known]" : "[unknown]");
                assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
                    assertThat(diagnostic.source()).isEqualTo("HIB-QUERY-007");
                    assertThat(diagnostic.message()).contains("Repo#findNested").doesNotContain("Repo#bulk");
                });
                var details = scanner.ruleViolations(
                        "HIB-QUERY-007", report.violationDetails().scanId(), 0, 10);
                assertThat(details.identities()).singleElement().asString().contains("unit=known", "findParallel");
                assertThat(details.violationCount()).isEqualTo(1);
                HibernateReport dismissed = scanner.applyDismissals(report, Set.of("HIB-QUERY-007"));
                assertThat(dismissed.violationsFound()).isZero();
                assertThat(dismissed.evidence()).isEqualTo(report.evidence());
                assertThat(dismissed.violationDetails()).isEqualTo(report.violationDetails());
            }
        }
    }

    private static HibernateRepositoryMethodModel fetchMethod(String name, String query) {
        return new HibernateRepositoryMethodModel(
                "Repo",
                name,
                Order.class,
                List.class,
                query,
                false,
                null,
                false,
                false,
                false,
                false,
                List.of(),
                new HibernateQueryEvidence(true, true, false, false, false, Order.class, false, null, null, List.of()));
    }

    @Test
    void usableUnitOrRetainedFindingSurvivesUnknownRequiredObservations() {
        HibernateReport partial = scanner(
                        List.of(
                                unit("known", settings(25), List.of()),
                                unit("unknown", HibernateFactorySettings.unknown(), List.of())),
                        List.of(),
                        List.of(new JdbcBatchSizeRule()))
                .scan();
        assertThat(partial.rulesEvaluated()).isEqualTo(1);
        assertThat(partial.evidence().usable()).isTrue();
        assertThat(partial.evidence().coverageComplete()).isFalse();

        HibernateRule uncertain = testRule("HIB-TEST-UNKNOWN", context -> {
            context.missingEvidence();
            return HibernateRuleSupport.violation(
                    new HibernateRuleDefinition(
                            "HIB-TEST-UNKNOWN", "Test", HibernateCategory.CONFIGURATION, "LOW", "Test", "Review", null),
                    List.of("A reliable finding alongside unavailable evidence"));
        });
        HibernateReport report = scanner(List.of(unit("unit", settings(25), List.of())), List.of(), List.of(uncertain))
                .scan();
        assertThat(report.results()).hasSize(1);
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations()).singleElement().asString().contains("see the report diagnostics");
        assertThat(result(report, "HIB-TEST-UNKNOWN").coverageNote())
                .isEqualTo("Incomplete in [unit]: 1 required observation(s) unavailable.");
        assertThat(report.diagnostics())
                .containsExactly(new HibernateDiagnosticDto(
                        "HIB-TEST-UNKNOWN",
                        "unit",
                        "WARNING",
                        "Partly evaluated; findings come from the evaluated part only. Required evidence"
                                + " unavailable: 1 required observation(s) unavailable."));
    }

    @Test
    void everyIncompleteEvaluationIsListedInDiagnosticsWithoutTruncation() {
        List<HibernateRule> rules = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String id = "HIB-TEST-" + i;
            rules.add(testRule(id, context -> {
                context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE);
                context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE);
                context.missingEvidence(HibernateEvidenceGap.QUERY_PROVENANCE);
                return HibernateRuleSupport.pass(definition(id, "INFO"));
            }));
        }
        HibernateReport report = scanner(List.of(unit("default", settings(25), List.of())), List.of(), rules)
                .scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.scan().message())
                .contains("required evidence unavailable 12", "See diagnostics for 12 entries")
                .doesNotContain("more", "Incomplete:");
        assertThat(report.diagnostics()).hasSize(12);
        assertThat(report.diagnostics())
                .extracting(HibernateDiagnosticDto::source)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 12)
                        .mapToObj(i -> "HIB-TEST-" + i)
                        .toList());
        assertThat(report.diagnostics().get(0).message())
                .startsWith("No conclusion reached. Required evidence unavailable: 1 repository query method(s)"
                        + " whose query provenance is unverified")
                .contains("; 2 repository query method(s) whose JPQL statement is outside the readable shape");
    }

    @Test
    void failedEvaluationKeepsTheEvidenceGapsItRecordedBeforeFailing() {
        HibernateRule failing = testRule("HIB-TEST-FAIL", context -> {
            context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE);
            throw new IllegalStateException("secret");
        });
        HibernateReport report = scanner(List.of(unit("default", settings(25), List.of())), List.of(), List.of(failing))
                .scan();

        assertThat(report.scan().message()).contains("failed 1", "required evidence unavailable 0");
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo("ERROR");
            assertThat(diagnostic.message())
                    .startsWith("Rule evaluation failed;")
                    .contains("Required evidence also unavailable: 1 repository query method(s) whose JPQL")
                    .doesNotContain("secret");
        });
    }

    @Test
    void failedUnitIsNamedInTheCoverageNoteOfAViolatingRule() {
        HibernateRule rule = testRule("HIB-TEST-SPLIT", context -> {
            if ("broken".equals(context.unitLabel())) throw new IllegalStateException("secret");
            return HibernateRuleSupport.violation(definition("HIB-TEST-SPLIT", "LOW"), List.of("Finding"));
        });
        HibernateReport report = scanner(
                        List.of(unit("ok", settings(25), List.of()), unit("broken", settings(25), List.of())),
                        List.of(),
                        List.of(rule))
                .scan();

        assertThat(result(report, "HIB-TEST-SPLIT").coverageNote())
                .isEqualTo("Incomplete in [broken]: rule evaluation failed.");
        assertThat(report.scan().message()).contains("failed 1", "required evidence unavailable 0");
    }

    @Test
    void partialCleanPassAdvisorLimitsAndSubjectExamplesAreDescribed() {
        HibernateRule partialPass = testRule("HIB-TEST-PASS", context -> {
            context.evidence().markApplicable(true);
            context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE, "OrderRepository#findA");
            context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE, "OrderRepository#findA");
            context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE, "OrderRepository#findB");
            context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE, "OrderRepository#findC");
            context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE, "OrderRepository#findD");
            return HibernateRuleSupport.pass(definition("HIB-TEST-PASS", "LOW"));
        });
        HibernateRule byDesign = testRule("HIB-TEST-DESIGN", context -> {
            context.missingEvidence(HibernateEvidenceGap.POOL_GUARANTEES_BY_DESIGN);
            return HibernateRuleSupport.skipped(definition("HIB-TEST-DESIGN", "LOW"), "Not observed.");
        });
        HibernateReport report = scanner(
                        List.of(unit("default", settings(25), List.of())), List.of(), List.of(partialPass, byDesign))
                .scan();

        assertThat(report.diagnostics()).hasSize(2);
        HibernateDiagnosticDto pass = report.diagnostics().get(0);
        assertThat(pass.level()).isEqualTo("WARNING");
        assertThat(pass.message())
                .startsWith("Partly evaluated; the evaluated part produced no findings. Required evidence"
                        + " unavailable: 4 repository query method(s) whose JPQL")
                .endsWith("(e.g. OrderRepository#findA, OrderRepository#findB, OrderRepository#findC, ...).");
        HibernateDiagnosticDto design = report.diagnostics().get(1);
        assertThat(design.level()).isEqualTo("INFO");
        assertThat(design.message()).startsWith("No conclusion reached.").contains("by design");
    }

    @Test
    void diagnosticsAndCoverageNotesStayBoundedAcrossManyUnits() {
        List<HibernatePersistenceUnitObservation> units = new ArrayList<>();
        for (int i = 0; i < 30; i++) units.add(unit("unit-" + i, settings(25), List.of()));
        List<HibernateRule> rules = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = "HIB-TEST-" + i;
            rules.add(testRule(id, context -> {
                context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE);
                return HibernateRuleSupport.violation(definition(id, "LOW"), List.of("Finding"));
            }));
        }
        HibernateReport report = scanner(units, List.of(), rules).scan();

        assertThat(report.scan().message()).contains("Diagnostics show 199 of 300 entries");
        assertThat(report.diagnostics()).hasSize(HibernateScanner.MAX_DIAGNOSTICS);
        HibernateDiagnosticDto omitted = report.diagnostics().get(HibernateScanner.MAX_DIAGNOSTICS - 1);
        assertThat(omitted.source()).isEqualTo(HibernateScanner.OMITTED_SOURCE);
        assertThat(omitted.message()).startsWith("101 further diagnostics omitted;");
        assertThat(report.diagnostics().subList(0, HibernateScanner.MAX_DIAGNOSTICS - 1))
                .extracting(HibernateDiagnosticDto::source)
                .containsAll(java.util.stream.IntStream.range(0, 10)
                        .mapToObj(i -> "HIB-TEST-" + i)
                        .toList());
        assertThat(result(report, "HIB-TEST-0").coverageNote())
                .contains("[unit-9]")
                .doesNotContain("[unit-10]")
                .endsWith("; and 20 more units.");
    }

    @Test
    void capKeepsLateRulesAndEveryFailure() {
        List<HibernatePersistenceUnitObservation> units = new ArrayList<>();
        for (int i = 0; i < 250; i++) units.add(unit("unit-" + i, settings(25), List.of()));
        HibernateRule noisy = testRule("HIB-TEST-NOISY", context -> {
            context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE);
            return HibernateRuleSupport.pass(definition("HIB-TEST-NOISY", "LOW"));
        });
        HibernateRule failing = testRule("HIB-TEST-LATE", context -> {
            if ("unit-249".equals(context.unitLabel())) throw new IllegalStateException("secret");
            return HibernateRuleSupport.pass(definition("HIB-TEST-LATE", "LOW"));
        });
        HibernateReport report =
                scanner(units, List.of(), List.of(noisy, failing)).scan();

        assertThat(report.diagnostics())
                .filteredOn(diagnostic -> "HIB-TEST-LATE".equals(diagnostic.source()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("ERROR");
                    assertThat(diagnostic.unit()).isEqualTo("unit-249");
                });
        assertThat(report.diagnostics().get(report.diagnostics().size() - 1).message())
                .startsWith("52 further diagnostics omitted;");
    }

    @Test
    void boundedKeepsEveryRuleAndDiscoveryReasonWhenFailuresAloneExceedTheCap() {
        List<HibernateDiagnosticDto> diagnostics = new ArrayList<>();
        diagnostics.add(new HibernateDiagnosticDto("discovery", "a", "WARNING", "Factory unavailable."));
        diagnostics.add(new HibernateDiagnosticDto("discovery", "b", "WARNING", "Factory unavailable."));
        diagnostics.add(new HibernateDiagnosticDto("discovery", "c", "WARNING", "Metamodel unavailable."));
        for (int i = 0; i < 300; i++)
            diagnostics.add(
                    new HibernateDiagnosticDto("HIB-TEST-FAIL", "unit-" + i, "ERROR", "Rule evaluation failed."));
        diagnostics.add(new HibernateDiagnosticDto("HIB-TEST-LAST", "unit-0", "WARNING", "No conclusion reached."));

        List<HibernateDiagnosticDto> bounded = HibernateScanner.bounded(diagnostics);

        assertThat(bounded).hasSize(HibernateScanner.MAX_DIAGNOSTICS);
        assertThat(bounded)
                .extracting(HibernateDiagnosticDto::message)
                .contains("Factory unavailable.", "Metamodel unavailable.", "No conclusion reached.");
        assertThat(bounded).filteredOn(d -> "ERROR".equals(d.level())).hasSize(HibernateScanner.MAX_DIAGNOSTICS - 4);
        assertThat(bounded.get(bounded.size() - 1).message()).startsWith("105 further diagnostics omitted;");
        assertThat(HibernateScanner.bounded(diagnostics.subList(0, HibernateScanner.MAX_DIAGNOSTICS)))
                .hasSize(HibernateScanner.MAX_DIAGNOSTICS)
                .noneMatch(d -> HibernateScanner.OMITTED_SOURCE.equals(d.source()));
    }

    @Test
    void gapCountsDistinctIdentitiesWhileShowingSanitizedExamples() {
        HibernateEvaluationEvidence evidence = new HibernateEvaluationEvidence();
        evidence.markRequiredUnknown(HibernateEvidenceGap.QUERY_SHAPE, "Repo#find", "Repo#find(int)");
        evidence.markRequiredUnknown(HibernateEvidenceGap.QUERY_SHAPE, "Repo#find", "Repo#find(long)");
        evidence.markRequiredUnknown(HibernateEvidenceGap.QUERY_SHAPE, "Repo#find", "Repo#find(long)");
        evidence.markRequiredUnknown(HibernateEvidenceGap.QUERY_SHAPE);

        assertThat(evidence.gaps()).containsEntry(HibernateEvidenceGap.QUERY_SHAPE, 3);
        assertThat(evidence.subjects(HibernateEvidenceGap.QUERY_SHAPE)).containsExactly("Repo#find");
        assertThat(evidence.hasMoreSubjects(HibernateEvidenceGap.QUERY_SHAPE)).isFalse();
    }

    @Test
    void unitsSharingADisplayLabelAreCountedSeparately() {
        HibernateRule rule = testRule("HIB-TEST-DUP", context -> {
            context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE);
            return HibernateRuleSupport.pass(definition("HIB-TEST-DUP", "LOW"));
        });
        List<HibernatePersistenceUnitObservation> units = List.of(
                new HibernatePersistenceUnitObservation(
                        "first",
                        "unit-2",
                        List.of(HibernateEntityModel.fromClass(Order.class)),
                        List.of(),
                        "7.2.19.Final",
                        settings(25),
                        false),
                new HibernatePersistenceUnitObservation(
                        "second",
                        "unit-2",
                        List.of(HibernateEntityModel.fromClass(Order.class)),
                        List.of(),
                        "7.2.19.Final",
                        settings(25),
                        false));

        HibernateReport report = scanner(units, List.of(), List.of(rule)).scan();

        assertThat(report.scan().message()).contains("required evidence unavailable 2");
        assertThat(report.diagnostics()).hasSize(2);
    }

    @Test
    void violatingRuleKeepsAPerUnitCoverageNoteAcrossUnits() {
        HibernateRule mixed = testRule("HIB-TEST-MIXED", context -> {
            if ("partial".equals(context.unitLabel())) context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE);
            if ("clean".equals(context.unitLabel()))
                return HibernateRuleSupport.pass(definition("HIB-TEST-MIXED", "LOW"));
            return HibernateRuleSupport.violation(
                    definition("HIB-TEST-MIXED", "LOW"), List.of("Finding in " + context.unitLabel()));
        });
        HibernateReport report = scanner(
                        List.of(
                                unit("full", settings(25), List.of()),
                                unit("partial", settings(25), List.of()),
                                unit("clean", settings(25), List.of())),
                        List.of(),
                        List.of(mixed))
                .scan();

        HibernateRuleResultDto result = result(report, "HIB-TEST-MIXED");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.coverageNote())
                .isEqualTo("Incomplete in [partial]: 1 repository query method(s) whose JPQL statement is outside"
                        + " the readable shape (subquery, set operation, multiple roots, or an unrecognized statement"
                        + " head).");
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("HIB-TEST-MIXED");
            assertThat(diagnostic.unit()).isEqualTo("partial");
            assertThat(diagnostic.message()).startsWith("Partly evaluated;");
        });
        HibernateReport dismissed = scanner(List.of(), List.of(), List.of()).applyDismissals(report, Set.of("x"));
        assertThat(dismissed.diagnostics()).isEqualTo(report.diagnostics());
        assertThat(report.withViolationDetails(null).diagnostics()).isEqualTo(report.diagnostics());
    }

    @Test
    void emptyUnitAndFailedRulesDoNotCountAsCompleted() {
        HibernatePersistenceUnitObservation empty = new HibernatePersistenceUnitObservation(
                "empty", "empty", List.of(), List.of(), "7.2.19.Final", settings(25), false);
        HibernateReport mixed = scanner(
                        List.of(unit("known", settings(25), List.of()), empty),
                        List.of(),
                        List.of(new JdbcBatchSizeRule()))
                .scan();
        assertThat(mixed.evidence().usable()).isTrue();
        HibernateReport noTargets = scanner(List.of(empty), List.of(), List.of(new JdbcBatchSizeRule()))
                .scan();
        assertThat(noTargets.evidence().usable()).isFalse();
        HibernateReport failed = scanner(
                        List.of(unit("known", settings(25), List.of())),
                        List.of(),
                        List.of(testRule("HIB-TEST-ERROR", context -> {
                            throw new IllegalStateException();
                        })))
                .scan();
        assertThat(failed.evidence().usable()).isFalse();
        assertThat(failed.evidence().coverageComplete()).isFalse();
    }

    @Test
    void actualAttributeSelectionAndCompletionAreOwnedByTheEvaluator() {
        HibernateContext scalar = HibernateContext.observed(
                new HibernatePersistenceUnitObservation(
                        "scalar",
                        "scalar",
                        List.of(HibernateEntityModel.fromClass(OnlyPrimitiveId.class)),
                        List.of(),
                        "7.2.19.Final",
                        settings(25),
                        false),
                APP);
        assertThat(new EagerFetchRule().evaluate(scalar).status()).isEqualTo("PASS");
        assertThat(scalar.evidence().applicable()).isFalse();
        assertThat(scalar.evidence().usable()).isFalse();
        assertThat(new IdentityDisablesBatchingRule().evaluate(scalar).status()).isEqualTo("PASS");
        assertThat(scalar.evidence().usable()).isFalse();

        HibernateContext version = HibernateContext.observed(
                new HibernatePersistenceUnitObservation(
                        "version",
                        "version",
                        List.of(HibernateEntityModel.fromClass(PrimitiveVersion.class)),
                        List.of(new HibernateRepositoryModel("Repo", PrimitiveVersion.class, List.of(), true)),
                        "7.2.19.Final",
                        settings(25),
                        false),
                APP);
        var result = new PrimitiveIdentifierOrVersionRule().evaluate(version);
        assertThat(result.severity()).isEqualTo("INFO");
        assertThat(version.evidence().usable()).isTrue();
        assertThat(version.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void exceptionAfterSelectingRealTargetsDoesNotCompleteEvaluation() {
        HibernateRule rule = new AbstractHibernateRule(new EagerFetchRule().definition()) {
            @Override
            HibernateRuleResultDto evaluateRule(HibernateContext context) {
                context.targets(context.entities());
                throw new IllegalStateException("failed after selection");
            }
        };
        HibernateContext context = HibernateContext.observed(unit("orders", settings(25), List.of()), APP);
        assertThat(rule.evaluate(context).status()).isEqualTo("ERROR");
        assertThat(context.evidence().applicable()).isTrue();
        assertThat(context.evidence().usable()).isFalse();
    }

    @Test
    void initialReadIsLazyAndTotalSourceFailureIsIncompleteRatherThanDisabled() {
        AtomicInteger calls = new AtomicInteger();
        HibernateScanner scanner = HibernateScanner.observing(
                () -> {
                    calls.incrementAndGet();
                    throw new IllegalArgumentException("credential");
                },
                CLOCK);
        assertThat(scanner.initialReport().scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(calls).hasValue(0);
        HibernateReport report = scanner.scan();
        assertThat(calls).hasValue(1);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.scan().message()).doesNotContain("credential");
    }

    @Test
    void compatibilitySourceDoesNotPromoteGlobalPropertiesToEffectiveFactorySettings() {
        HibernateScanner scanner = HibernateScanner.using(
                () -> new EntityDiscovery(List.of(HibernateEntityModel.fromClass(Order.class)), List.of(), List.of()),
                key -> "hibernate.jdbc.batch_size".equals(key) ? "25" : null,
                List::of,
                CLOCK);
        HibernateReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).extracting(HibernateRuleResultDto::id).doesNotContain("HIB-ID-006");
    }

    @Test
    void retiredIdsAreNotRegisteredOrReported() {
        assertThat(HibernateRuleRegistry.activeRules()).hasSize(78);
        assertThat(HibernateRuleRegistry.RETIRED_RULE_IDS).containsExactlyInAnyOrder(RETIRED_IDS);
        assertThat(HibernateRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .doesNotContain(RETIRED_IDS);
        HibernateReport report = HibernateScanner.observing(
                        () -> new HibernateAdvisorObservation(
                                List.of(unit("orders", settings(0), List.of())), APP, List.of()),
                        CLOCK)
                .scan();
        assertThat(report.results()).extracting(HibernateRuleResultDto::id).doesNotContain(RETIRED_IDS);
    }

    @Test
    void customEntityNameResolvesButJavaSimpleNameIsNotAnAlternativeRoot() {
        for (String root : List.of("Purchase", "AliasedOrder")) {
            HibernateRepositoryMethodModel query =
                    method("select o from " + root + " o join fetch o.items", null, AliasedOrder.class, false);
            HibernatePersistenceUnitObservation unit = new HibernatePersistenceUnitObservation(
                    "orders",
                    "orders",
                    List.of(
                            HibernateEntityModel.fromClass(AliasedOrder.class),
                            HibernateEntityModel.fromClass(Other.class)),
                    List.of(new HibernateRepositoryModel("Repo", AliasedOrder.class, List.of(query), true)),
                    "7.2.19.Final",
                    settings(0),
                    false);
            HibernateReport report = scanner(List.of(unit), List.of(), List.of(new CollectionJoinFetchPageableRule()))
                    .scan();
            if ("Purchase".equals(root)) {
                assertThat(report.scan().status()).isEqualTo("SCANNED");
                assertThat(report.results()).singleElement().satisfies(result -> {
                    assertThat(result.id()).isEqualTo("HIB-FETCH-003");
                    assertThat(result.violationCount()).isEqualTo(1);
                });
            } else {
                assertThat(report.scan().status()).isEqualTo("PARTIAL");
                assertThat(report.results()).isEmpty();
            }
        }
    }

    @Test
    void paginationUsesObservedRootAndDeclaredHintRatherThanRepositoryDomainOrVersionGuess() {
        for (Boolean hint : new Boolean[] {true, false, null}) {
            HibernateRepositoryMethodModel method =
                    method("select o from Order o join fetch o.items", hint, Order.class, false);
            HibernateContext context = context("7.4.5.Final", List.of(method));
            HibernateRuleResultDto fetch = new CollectionJoinFetchPageableRule().evaluate(context);
            if (Boolean.TRUE.equals(hint)) {
                assertThat(fetch.violationCount()).isEqualTo(1);
            } else {
                assertThat(fetch.violationCount()).isZero();
                assertThat(context.evidence().requiredUnknown()).isTrue();
            }
            // The disabled guard is one INFO configuration prompt whatever the query hints are.
            HibernateRuleResultDto guard = new FailOnPaginationOverCollectionFetchRule().evaluate(context);
            assertThat(guard.violationCount()).isEqualTo(1);
            assertThat(guard.severity()).isEqualTo(HibernateRuleSupport.INFO);
            assertThat(context.evidence().requiredUnknown()).isFalse();
        }
        HibernateContext legacy = context(
                "7.2.19.Final", List.of(method("select o from Order o join fetch o.items", null, Order.class, false)));
        assertThat(new FailOnPaginationOverCollectionFetchRule()
                        .evaluate(legacy)
                        .violationCount())
                .isEqualTo(1);
    }

    @Test
    void queryEligibilityRejectsProjectionSubqueryAndNestedAliasesAndIgnoresCommentsAndLiterals() {
        for (String query : List.of(
                "select o from Order o where o.id in (select x.id from Other x) join fetch o.items",
                "select o from Order o join o.items i join fetch i.tags",
                "select o from Order o, Other x join fetch o.items")) {
            HibernateContext context = context("7.2.19.Final", List.of(method(query, null, Order.class, false)));
            assertThat(new CollectionJoinFetchPageableRule().evaluate(context).violationCount())
                    .isZero();
            assertThat(context.evidence().requiredUnknown()).isTrue();
        }
        for (String query : List.of(
                "select o from Order o join fetch o.items i",
                "select o from Order o join fetch o.items as i where o.name in ('a', 'b') order by o.name, o.id")) {
            HibernateContext context = context("7.2.19.Final", List.of(method(query, null, Order.class, false)));
            assertThat(new CollectionJoinFetchPageableRule().evaluate(context).violationCount())
                    .isEqualTo(1);
            assertThat(context.evidence().requiredUnknown()).isFalse();
        }
        HibernateContext harmless = context(
                "7.2.19.Final",
                List.of(method(
                        "select o from Order o /* join fetch o.items */ where o.name = 'join fetch o.items'",
                        null,
                        Order.class,
                        false)));
        assertThat(new CollectionJoinFetchPageableRule().evaluate(harmless).violationCount())
                .isZero();
        HibernateContext projection = context(
                "7.2.19.Final",
                List.of(method("select o from Order o join fetch o.items", null, Projection.class, false)));
        assertThat(new CollectionJoinFetchPageableRule().evaluate(projection).violationCount())
                .isZero();
    }

    @Test
    void verifiedFetchFreeEntityJoinsDoNotMakeCollectionFetchCoverageUnknown() {
        for (String query : List.of(
                "select new app.PostDto(o.id, u.id) from Order o join Other u on o.id = u.id",
                "select o.id, u.id from Order o join Other u on o.id = u.id",
                "select o from Order o join Other u on o.id = u.id",
                "select new app.PostDto(o.id, u.id) from Order o join Other u on o.id = u.id"
                        + " where lower(o.name) like :query",
                "select new app.PostDto(o.id, u.id) from Order o join Other u on o.id = u.id"
                        + " /* join fetch o.items */ where o.name = 'join fetch o.tags'")) {
            HibernateRepositoryMethodModel method = method(query, null, Projection.class, false);
            HibernateContext context = context("7.2.19.Final", List.of(method));
            for (HibernateRule rule :
                    List.of(new CollectionJoinFetchPageableRule(), new MultipleCollectionJoinFetchRule())) {
                assertThat(rule.evaluate(context).status()).as(query).isEqualTo("PASS");
                assertThat(context.evidence().requiredUnknown()).as(query).isFalse();
            }
            HibernateReport report = scanner(
                            List.of(unit(
                                    "orders",
                                    settings(25),
                                    List.of(new HibernateRepositoryModel("Repo", Order.class, List.of(method), true)))),
                            List.of(),
                            List.of(
                                    new CollectionJoinFetchPageableRule(),
                                    new MultipleCollectionJoinFetchRule(),
                                    new HibernateBuiltinPoolRule()))
                    .scan();
            assertThat(report.scan().status()).as(query).isEqualTo("SCANNED");
            assertThat(report.evidence().coverageComplete()).as(query).isTrue();
            assertThat(report.evidence().usable()).as(query).isTrue();
            assertThat(report.diagnostics()).as(query).isEmpty();
        }
    }

    @Test
    void adjacentMinusOperatorsDoNotHideLaterCollectionFetches() {
        String query = "select o from Order o join o.owner u on u.id = 1--1 " + "join fetch o.items join fetch o.tags";
        HibernateContext context = context("7.2.19.Final", List.of(method(query, null, Order.class, false)));

        for (HibernateRule rule :
                List.of(new CollectionJoinFetchPageableRule(), new MultipleCollectionJoinFetchRule())) {
            assertThat(rule.evaluate(context).status()).as(query).isEqualTo("VIOLATION");
        }
        assertThat(context.evidence().requiredUnknown()).as(query).isFalse();
    }

    @Test
    void commentLikeAdjacentMinusTextCannotInventCollectionFetches() {
        String query = "select o from Order o -- join fetch o.items";
        HibernateContext context = context("7.2.19.Final", List.of(method(query, null, Order.class, false)));

        for (HibernateRule rule :
                List.of(new CollectionJoinFetchPageableRule(), new MultipleCollectionJoinFetchRule())) {
            assertThat(rule.evaluate(context).violationCount()).isZero();
        }
        assertThat(context.evidence().requiredUnknown()).as(query).isFalse();
    }

    @Test
    void fetchFreeExclusionPreservesUnknownAndGenuineFetchEvaluations() {
        for (String query : List.of(
                "select new app.PostDto(o.id) from Order o join Other u on o.id = u.id join\nFeTcH o.items",
                "select o from Order o join fetch treat(o.items as SpecialItem)",
                "select o from Order o /* unclosed",
                "select o from Order o where o.name = 'unclosed",
                "select o from Order o where o.id in (select u.id from Other u)",
                "select o from Order o, Other u",
                "select o from UnknownEntity o join Other u on o.id = u.id",
                "select o from Order o where o.id = #{#id}",
                "select o from Order o where (o.id = :id",
                "select o from Order o;" + " ".repeat(16_384))) {
            HibernateContext context = context("7.2.19.Final", List.of(method(query, null, Projection.class, false)));
            for (HibernateRule rule :
                    List.of(new CollectionJoinFetchPageableRule(), new MultipleCollectionJoinFetchRule())) {
                rule.evaluate(context);
                assertThat(context.evidence().requiredUnknown()).as(query).isTrue();
            }
        }
        List<HibernateRepositoryMethodModel> methods = List.of(
                method(
                        "select new app.Dto(o.id) from Order o join Other u on o.id = u.id",
                        null,
                        Projection.class,
                        false),
                method("select o from Order o join fetch o.items join fetch o.tags", null, Order.class, false),
                method("select o from Order o join o.items i join fetch i.tags", null, Order.class, false));
        HibernateReport report = scanner(
                        List.of(unit(
                                "orders",
                                settings(25),
                                List.of(new HibernateRepositoryModel("Repo", Order.class, methods, true)))),
                        List.of(),
                        List.of(new CollectionJoinFetchPageableRule(), new MultipleCollectionJoinFetchRule()))
                .scan();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).extracting(HibernateRuleResultDto::id).contains("HIB-FETCH-003", "HIB-QUERY-007");
        assertThat(report.diagnostics())
                .extracting(HibernateDiagnosticDto::source)
                .contains("HIB-FETCH-003", "HIB-QUERY-007");
    }

    @Test
    void fetchFreeExclusionDoesNotBypassQueryProvenance() {
        String query = "select new app.Dto(o.id) from Order o join Other u on o.id = u.id";
        for (HibernateQueryEvidence evidence : List.of(
                HibernateQueryEvidence.unknown(),
                new HibernateQueryEvidence(
                        true, true, false, true, false, Projection.class, false, null, null, List.of()),
                new HibernateQueryEvidence(
                        true, true, false, false, true, Projection.class, false, null, null, List.of()))) {
            HibernateRepositoryMethodModel method = new HibernateRepositoryMethodModel(
                    "Repo",
                    "query",
                    Order.class,
                    List.class,
                    query,
                    false,
                    null,
                    true,
                    false,
                    false,
                    false,
                    List.of(),
                    evidence);
            HibernateContext context = context("7.2.19.Final", List.of(method));
            for (HibernateRule rule :
                    List.of(new CollectionJoinFetchPageableRule(), new MultipleCollectionJoinFetchRule())) {
                rule.evaluate(context);
                assertThat(context.evidence().gaps()).containsKey(HibernateEvidenceGap.QUERY_PROVENANCE);
            }
        }
    }

    @Test
    void fetchFreeExclusionLeavesOtherQueryRulesConservative() {
        String query = "select new app.Dto(o.id) from Order o join Other u on o.id = u.id where o.id in :ids";
        HibernateRepositoryMethodModel method = new HibernateRepositoryMethodModel(
                "Repo",
                "query",
                Order.class,
                List.class,
                query,
                false,
                null,
                false,
                false,
                false,
                false,
                List.of(List.class),
                new HibernateQueryEvidence(
                        true, true, false, false, false, Projection.class, false, null, null, List.of(":ids")));
        HibernateContext context = context("7.2.19.Final", List.of(method));
        new InClausePaddingRule().evaluate(context);
        assertThat(context.evidence().gaps()).containsKey(HibernateEvidenceGap.QUERY_JOIN_PATH);

        context = context(
                "7.2.19.Final",
                List.of(method("select o from Order o join Other u on o.id = u.id", null, Order.class, false)));
        new EagerToOneFetchJoinRule().evaluate(context);
        assertThat(context.evidence().gaps()).containsKey(HibernateEvidenceGap.QUERY_JOIN_PATH);
    }

    @Test
    void resolvedRootCanDifferFromRepositoryDomainAndGraphIsNotAssumedToFixFetches() {
        HibernateRepositoryMethodModel method = method("select o from Order o", null, Order.class, true);
        HibernateContext context = context("7.2.19.Final", List.of(method));
        assertThat(HibernateQueryShape.entityRoot(context, method).javaType()).isEqualTo(Order.class);
        assertThat(new EagerToOneFetchJoinRule().evaluate(context).violationCount())
                .isZero();
        assertThat(context.evidence().requiredUnknown()).isTrue();
    }

    @Test
    void compositeReadOnlyJoinColumnsAreExemptOnlyWhenEveryColumnIsReadOnly() {
        HibernateContext context = declarations(ReadOnlyJoins.class, PartlyWritableJoins.class);
        HibernateRuleResultDto result = new UnidirectionalOneToManyJoinColumnRule().evaluate(context);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).allMatch(value -> value.contains("PartlyWritableJoins"));
    }

    @Test
    void immutableVersionDoesNotProveWritableAndRoleCacheDoesNotCoverTargetState() {
        HibernateContext context = declarations(ImmutableVersioned.class, CachedOwner.class, Other.class);
        assertThat(new ReadOnlyCacheOnWritableEntityRule().evaluate(context).violationCount())
                .isZero();
        HibernateRuleResultDto coverage = new CacheAssociationCoverageRule().evaluate(context);
        assertThat(coverage.sampleViolations()).anyMatch(value -> value.contains("CachedOwner#targets"));
    }

    @Test
    void persistableSubinterfaceAndNullableVersionExemptAssignedIdentifierAdvice() {
        List<HibernateRepositoryModel> repositories = List.of(
                new HibernateRepositoryModel("Repo", SubPersistable.class, List.of(), true),
                new HibernateRepositoryModel("Repo", NullableVersion.class, List.of(), true),
                new HibernateRepositoryModel("Repo", PrimitiveVersion.class, List.of(), true));
        HibernateContext context = new HibernateContext(
                List.of(SubPersistable.class, NullableVersion.class, PrimitiveVersion.class).stream()
                        .map(HibernateEntityModel::fromClass)
                        .toList(),
                repositories,
                key -> null,
                List.of());
        HibernateRuleResultDto result = new AssignedIdPersistableRule().evaluate(context);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).allMatch(value -> value.contains("PrimitiveVersion"));
    }

    private static HibernateRuleDefinition definition(String id, String severity) {
        return new HibernateRuleDefinition(
                id, "Test", HibernateCategory.CONFIGURATION, severity, "Test", "Review", null);
    }

    private static HibernateRule testRule(
            String id, java.util.function.Function<HibernateContext, HibernateRuleResultDto> body) {
        return new AbstractHibernateRule(new HibernateRuleDefinition(
                id, "Test", HibernateCategory.CONFIGURATION, "INFO", "Test", "Test", null)) {
            @Override
            HibernateRuleResultDto evaluateRule(HibernateContext context) {
                return body.apply(context);
            }
        };
    }

    @Test
    void emittedKotlinNullableAndPrimitiveVersionsAndNoArgVersusOpenAreDistinct() throws Exception {
        Class<?> nullable = io.github.jdubois.bootui.engine.hibernate.kotlinfixtures.KotlinNullableVersionEntity.class;
        Class<?> primitive =
                io.github.jdubois.bootui.engine.hibernate.kotlinfixtures.KotlinPrimitiveVersionEntity.class;
        assertThat(nullable.getDeclaredConstructor()).isNotNull();
        assertThat(java.lang.reflect.Modifier.isFinal(nullable.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isFinal(primitive.getModifiers())).isFalse();
        List<HibernateRepositoryModel> repositories = List.of(
                new HibernateRepositoryModel("Nullable", nullable, List.of(), true),
                new HibernateRepositoryModel("Primitive", primitive, List.of(), true));
        HibernateContext context = new HibernateContext(
                List.of(nullable, primitive).stream()
                        .map(HibernateEntityModel::fromClass)
                        .toList(),
                repositories,
                key -> null,
                List.of());
        HibernateRuleResultDto result = new AssignedIdPersistableRule().evaluate(context);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).allMatch(value -> value.contains("KotlinPrimitiveVersionEntity"));
        HibernateReport scan = HibernateScanner.observing(
                        () -> new HibernateAdvisorObservation(
                                List.of(new HibernatePersistenceUnitObservation(
                                        "kotlin",
                                        "kotlin",
                                        List.of(
                                                HibernateEntityModel.fromClass(
                                                        io.github.jdubois.bootui.engine.hibernate.kotlinfixtures
                                                                .KotlinBodyAssociationEntity.class),
                                                HibernateEntityModel.fromClass(
                                                        io.github.jdubois.bootui.engine.hibernate.kotlinfixtures
                                                                .KotlinConstructorAssociationEntity.class)),
                                        List.of(),
                                        "7.4.5.Final",
                                        settings(0),
                                        false)),
                                APP,
                                List.of()),
                        CLOCK)
                .scan();
        assertThat(scan.results())
                .extracting(HibernateRuleResultDto::id)
                .doesNotContain("HIB-ENTITY-003", "HIB-ENTITY-004");
    }

    private static HibernateScanner scanner(
            List<HibernatePersistenceUnitObservation> units,
            List<HibernateObservationDiagnostic> diagnostics,
            List<HibernateRule> rules) {
        return new HibernateScanner(() -> new HibernateAdvisorObservation(units, APP, diagnostics), CLOCK, rules);
    }

    private static HibernateRuleResultDto result(HibernateReport report, String id) {
        return report.results().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static HibernateFactorySettings settings(int batch) {
        return HibernateObservationFixtures.settings(
                new TestEnvironment().withProperty("hibernate.jdbc.batch_size", Integer.toString(batch)));
    }

    private static HibernatePersistenceUnitObservation unit(
            String label, HibernateFactorySettings settings, List<HibernateRepositoryModel> repositories) {
        return new HibernatePersistenceUnitObservation(
                label,
                label,
                List.of(HibernateEntityModel.fromClass(Order.class)),
                repositories,
                "7.2.19.Final",
                settings,
                false);
    }

    private static HibernateContext context(String version, List<HibernateRepositoryMethodModel> methods) {
        return HibernateContext.observed(
                new HibernatePersistenceUnitObservation(
                        "orders",
                        "orders",
                        List.of(
                                HibernateEntityModel.fromClass(Order.class),
                                HibernateEntityModel.fromClass(Other.class)),
                        List.of(new HibernateRepositoryModel("Repo", Other.class, methods, true)),
                        version,
                        settings(0),
                        false),
                APP);
    }

    private static HibernateRepositoryMethodModel method(String query, Boolean hint, Class<?> element, boolean graph) {
        return new HibernateRepositoryMethodModel(
                "Repo",
                "query",
                Other.class,
                List.class,
                query,
                false,
                null,
                true,
                false,
                false,
                false,
                List.of(),
                new HibernateQueryEvidence(true, true, false, false, false, element, graph, "FETCH", hint, List.of()));
    }

    private static HibernateContext declarations(Class<?>... types) {
        return new HibernateContext(
                List.of(types).stream().map(HibernateEntityModel::fromClass).toList(),
                List.of(),
                key -> null,
                List.of());
    }

    @Entity(name = "Purchase")
    static class AliasedOrder {
        @Id
        Long id;

        @OneToMany
        List<Other> items;
    }

    @Entity
    static class Order {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @ManyToOne
        Other owner;

        @OneToMany
        List<Other> items;

        @OneToMany
        List<Other> tags;

        String name;

        @Override
        public boolean equals(Object other) {
            return other instanceof Order order && id != null && id.equals(order.id);
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public String toString() {
            return "Order:" + id;
        }
    }

    @Entity
    static class Other {
        @Id
        Long id;
    }

    interface Projection {}

    @Entity
    static class ReadOnlyJoins {
        @Id
        Long id;

        @OneToMany
        @JoinColumns({
            @JoinColumn(name = "a", insertable = false, updatable = false),
            @JoinColumn(name = "b", insertable = false, updatable = false)
        })
        List<Other> targets;
    }

    @Entity
    static class PartlyWritableJoins {
        @Id
        Long id;

        @OneToMany
        @JoinColumns({@JoinColumn(name = "a", insertable = false, updatable = false), @JoinColumn(name = "b")})
        List<Other> targets;
    }

    @Entity
    @Immutable
    @Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
    static class ImmutableVersioned {
        @Id
        Long id;

        @Version
        Long version;
    }

    @Entity
    @Cacheable
    static class CachedOwner {
        @Id
        Long id;

        @OneToMany
        @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
        List<Other> targets;
    }

    interface Newness<T> extends Persistable<T> {}

    @Entity
    static class OnlyPrimitiveId {
        @Id
        long id;
    }

    @Entity
    static class SubPersistable implements Newness<Long> {
        @Id
        Long id;

        @Version
        long version;

        public Long getId() {
            return id;
        }

        public boolean isNew() {
            return true;
        }
    }

    @Entity
    static class NullableVersion {
        @Id
        Long id;

        @Version
        Long version;
    }

    @Entity
    static class PrimitiveVersion {
        @Id
        Long id;

        @Version
        long version;
    }
}
