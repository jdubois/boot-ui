package io.github.jdubois.bootui.engine.quarkusapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.core.dto.SpringSeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.spi.QuarkusAppEvidenceProblem;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata.SharedField;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot.Setting;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class QuarkusAppScannerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    private static final Set<String> CONFIG_RULES = Set.of(
            "QA-CFG-002",
            "QA-CFG-003",
            "QA-CFG-004",
            "QA-PROD-002",
            "QA-PROD-003",
            "QA-WEB-001",
            "QA-WEB-002",
            "QA-WEB-003",
            "QA-WEB-004");
    private static final Set<String> RETIRED_RULES =
            Set.of("QA-CFG-001", "QA-RX-001", "QA-SCH-001", "QA-PROD-001", "QA-PROF-001", "QA-DB-001");

    private static final class Snap {
        QuarkusAppMetadata metadata = new QuarkusAppMetadata(
                true, 4, 2, 0, 0, 8, true, true, false, List.of(), List.of(), List.of(), List.of());
        int runtimeJdk = 21;
        List<String> profiles = List.of("prod");
        List<Setting> settings = new ArrayList<>();
        Set<String> evaluated = new HashSet<>(CONFIG_RULES);
        List<QuarkusAppEvidenceProblem> problems = new ArrayList<>();

        Snap setting(String rule, String value) {
            settings.add(new Setting(rule, "observed target", value, "production declaration"));
            return this;
        }

        Snap fields(SharedField... fields) {
            metadata = new QuarkusAppMetadata(
                    true, 4, 2, 0, 0, 8, false, false, false, List.of(fields), List.of(), List.of(), List.of());
            return this;
        }

        Snap methods(String... methods) {
            metadata = new QuarkusAppMetadata(
                    true, 4, 2, 0, 0, 8, false, false, false, List.of(), List.of(methods), List.of(), List.of());
            return this;
        }

        Snap metadataProblem(String rule) {
            metadata = new QuarkusAppMetadata(
                    metadata.available(),
                    metadata.beanCount(),
                    metadata.endpointCount(),
                    metadata.configPropertyCount(),
                    metadata.configMappingCount(),
                    metadata.scheduledDeclarationCount(),
                    metadata.hibernateOrmSupported(),
                    metadata.jdbcDatasourceSupported(),
                    metadata.restClientSupported(),
                    metadata.sharedFields(),
                    metadata.synchronizedVirtualThreadMethods(),
                    metadata.restClients(),
                    List.of(new QuarkusAppEvidenceProblem(rule, "password=must-not-be-rendered")));
            return this;
        }

        QuarkusAppSnapshot build() {
            return new QuarkusAppSnapshot(metadata, profiles, runtimeJdk, settings, evaluated, problems);
        }
    }

    private static SpringRuleResultDto find(SpringReport report, String id) {
        return report.results().stream()
                .filter(result -> result.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private static SpringReport scan(Snap snap) {
        return QuarkusAppScanner.usingSnapshot(snap::build, CLOCK).scan();
    }

    @Test
    void inspectedMetadataAndInapplicableInventoriesDoNotCompleteChecks() {
        Snap snap = new Snap();
        snap.runtimeJdk = 24;
        SpringReport report = scan(snap);
        assertThat(report.rulesEvaluated()).isEqualTo(13);
        assertThat(report.inspected()).isNotEmpty();
        assertThat(report.evidence().usable()).isFalse();
        assertThat(report.evidence().coverageComplete()).isTrue();
    }

    @Test
    void genuineInfoFindingSurvivesItsIncompleteEvaluationWithoutACompletedCheck() {
        Snap snap = new Snap().setting("QA-WEB-001", "default-disabled");
        snap.metadataProblem("QA-WEB-001");
        QuarkusAppScanner scanner = QuarkusAppScanner.usingSnapshot(snap::build, CLOCK);
        QuarkusAppChecks.Evaluation evaluation = QuarkusAppChecks.evaluate(snap.build());
        SpringReport report = scanner.scan();
        assertThat(evaluation.usable()).isTrue();
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("QA-WEB-001");
            assertThat(result.severity()).isEqualTo("INFO");
        });
        assertThat(scanner.applyDismissals(report, Set.of("QA-WEB-001")).evidence())
                .isEqualTo(report.evidence());
    }

    @Test
    void completedCleanSettingSurvivesMissingMetadataWithoutInventingPasses() {
        Snap snap = new Snap().setting("QA-WEB-001", "enabled");
        snap.metadata = QuarkusAppMetadata.unavailable();
        snap.evaluated = Set.of("QA-WEB-001");
        SpringReport report = scan(snap);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).isEmpty();
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations()).isNotEmpty();
    }

    @Test
    void failedConfigurationDoesNotCountAndDismissalsKeepEvidence() {
        Snap snap = new Snap().setting("QA-WEB-001", "invalid").setting("QA-WEB-002", "zero");
        QuarkusAppScanner scanner = QuarkusAppScanner.usingSnapshot(snap::build, CLOCK);
        SpringReport report = scanner.scan();
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(scanner.applyDismissals(report, Set.of("QA-WEB-002")).evidence())
                .isEqualTo(report.evidence());
        assertThat(QuarkusAppScanner.usingSnapshot(() -> null, CLOCK)
                        .scan()
                        .evidence()
                        .usable())
                .isFalse();
    }

    @Test
    void completeNineteenRuleAuditKeepsThirteenIdentifiersAndRetiresSixWithoutReuse() {
        assertThat(QuarkusAppChecks.ruleIds())
                .containsExactly(
                        "QA-CDI-001",
                        "QA-CDI-002",
                        "QA-CDI-003",
                        "QA-CFG-002",
                        "QA-CFG-003",
                        "QA-CFG-004",
                        "QA-PROD-002",
                        "QA-PROD-003",
                        "QA-WEB-001",
                        "QA-WEB-002",
                        "QA-WEB-003",
                        "QA-WEB-004",
                        "QA-PERF-002");
        assertThat(QuarkusAppChecks.ruleCount()).isEqualTo(13);
        Set<String> audited = new HashSet<>(QuarkusAppChecks.ruleIds());
        audited.addAll(RETIRED_RULES);
        assertThat(audited).hasSize(19);
        assertThat(QuarkusAppChecks.ruleIds()).doesNotContainAnyElementsOf(RETIRED_RULES);
    }

    @Test
    void knownAbsenceAndKnownNonApplicabilityAreCleanAndCountsAreReal() {
        Snap snap = new Snap();
        snap.metadata = new QuarkusAppMetadata(
                true, 4, 2, 0, 0, 8, false, false, false, List.of(), List.of(), List.of(), List.of());
        SpringReport report = scan(snap);
        assertThat(report.results()).isEmpty();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.rulesEvaluated()).isEqualTo(13);
        assertThat(report.scan().rulesEvaluated()).isEqualTo(13);
        assertThat(report.componentsAnalyzed()).isEqualTo(4);
        assertThat(report.scan().componentsAnalyzed()).isEqualTo(4);
        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(1000L);
        assertThat(report.violationsFound()).isZero();
        assertThat(report.inspected())
                .contains("0 @ConfigProperty sites", "8 scheduled declarations (not an active job count)");
    }

    @Test
    void retiredConfigurationObservationsCannotProduceFindingsOrErrors() {
        Snap snap = new Snap();
        for (String id : RETIRED_RULES) {
            snap.setting(id, "true");
            snap.problems.add(new QuarkusAppEvidenceProblem(id, "not relevant"));
        }
        assertThat(scan(snap).results()).isEmpty();
        assertThat(scan(snap).analysisErrors()).isEmpty();
        assertThat(scan(snap).rulesEvaluated()).isEqualTo(13);
    }

    @ParameterizedTest
    @CsvSource({"APPLICATION,QA-CDI-001,LOW", "SINGLETON,QA-CDI-003,LOW"})
    void publicStateIsOnlyAPotentialStateReview(String scope, String id, String severity) {
        SpringRuleResultDto result =
                find(scan(new Snap().fields(new SharedField("Service", "state", scope, false))), id);
        assertThat(result.severity()).isEqualTo(severity);
        assertThat(result.description()).contains("potentially mutable public state", "not evidence");
        assertThat(result.violationCount()).isOne();
        assertThat(result.sampleViolations()).containsExactly("Service.state");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLICATION", "SINGLETON"})
    void resourceStateIsReviewedOnceWithoutGeneralCdiDoubleCounting(String scope) {
        SpringReport report = scan(new Snap()
                .fields(
                        new SharedField("Resource", "state", scope, true),
                        new SharedField("Resource", "state", scope, false),
                        new SharedField("Resource", "state", scope, true)));
        assertThat(report.results()).hasSize(1);
        SpringRuleResultDto result = find(report, "QA-CDI-002");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.violationCount()).isOne();
        assertThat(result.description()).contains("no race or actual mutation");
    }

    @Test
    void absentPublicFieldEvidenceDoesNotInventPrivateFieldRaces() {
        assertThat(scan(new Snap()).results()).noneMatch(result -> result.id().startsWith("QA-CDI-"));
    }

    @Test
    void unsupportedScopeIsUnknownRatherThanASharedStateFinding() {
        SpringReport report = scan(new Snap().fields(new SharedField("Resource", "state", "REQUEST", true)));
        assertThat(report.results()).isEmpty();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.analysisErrors())
                .extracting(SpringRuleResultDto::id)
                .containsExactly("QA-CDI-001", "QA-CDI-002", "QA-CDI-003");
    }

    @ParameterizedTest
    @CsvSource({
        "QA-CFG-002,true,MEDIUM",
        "QA-CFG-003,verbose,MEDIUM",
        "QA-CFG-004,legacy,LOW",
        "QA-PROD-002,create,HIGH",
        "QA-PROD-002,update,HIGH",
        "QA-PROD-002,drop,CRITICAL",
        "QA-PROD-002,drop-and-create,CRITICAL",
        "QA-PROD-003,in-memory,MEDIUM",
        "QA-WEB-001,disabled,INFO",
        "QA-WEB-001,default-disabled,INFO",
        "QA-WEB-002,zero,MEDIUM",
        "QA-WEB-003,connect-zero,MEDIUM",
        "QA-WEB-003,read-zero,MEDIUM",
        "QA-WEB-004,absent,INFO"
    })
    void normalizedSettingsHaveExplicitTriggersAndSeverities(String id, String value, String severity) {
        SpringReport report = scan(new Snap().setting(id, value));
        SpringRuleResultDto result = find(report, id);
        assertThat(result).isNotNull();
        assertThat(result.severity()).isEqualTo(severity);
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.sampleViolations())
                .containsExactly("observed target: " + value + " (production declaration)");
        assertThat(report.scan().status()).isEqualTo("SCANNED");
    }

    @ParameterizedTest
    @CsvSource({
        "QA-CFG-002,false",
        "QA-CFG-003,normal",
        "QA-PROD-002,none",
        "QA-PROD-002,validate",
        "QA-PROD-003,persistent-or-unclassified",
        "QA-WEB-001,enabled",
        "QA-WEB-002,positive",
        "QA-WEB-002,absent",
        "QA-WEB-004,zero",
        "QA-WEB-004,positive"
    })
    void normalizedNonTriggersAreSuccessfullyEvaluated(String id, String value) {
        SpringReport report = scan(new Snap().setting(id, value));
        assertThat(report.results()).isEmpty();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.rulesEvaluated()).isEqualTo(13);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QA-CFG-002",
                "QA-CFG-003",
                "QA-CFG-004",
                "QA-PROD-002",
                "QA-PROD-003",
                "QA-WEB-001",
                "QA-WEB-002",
                "QA-WEB-003",
                "QA-WEB-004"
            })
    void unknownOrNullClassificationIsAnErrorEvenWhenMarkedEvaluated(String id) {
        for (String value : new String[] {"password=unclassified", null}) {
            SpringReport report = scan(new Snap().setting(id, value));
            assertThat(report.results()).isEmpty();
            assertThat(report.analysisErrors())
                    .extracting(SpringRuleResultDto::id)
                    .containsExactly(id);
            assertThat(report.rulesEvaluated()).isEqualTo(12);
            assertThat(report.scan().status()).isEqualTo("PARTIAL");
            assertThat(report.toString()).doesNotContain("password=unclassified");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "derby", "hsqldb", "jdbc:h2:mem:password=secret"})
    void rawDatabaseKindOrUrlIsNotAnInMemoryClassification(String value) {
        SpringReport report = scan(new Snap().setting("QA-PROD-003", value));
        assertThat(report.results()).isEmpty();
        assertThat(report.analysisErrors()).extracting(SpringRuleResultDto::id).containsExactly("QA-PROD-003");
        assertThat(report.toString()).doesNotContain(value);
    }

    @Test
    void createOnlyAndMixedSchemaActionsKeepTheirMeaning() {
        SpringRuleResultDto create = find(scan(new Snap().setting("QA-PROD-002", "create")), "QA-PROD-002");
        assertThat(create.severity()).isEqualTo("HIGH");
        assertThat(create.description()).contains("create is create-only, not a drop operation");
        SpringRuleResultDto mixed =
                find(scan(new Snap().setting("QA-PROD-002", "create").setting("QA-PROD-002", "drop")), "QA-PROD-002");
        assertThat(mixed.severity()).isEqualTo("CRITICAL");
        assertThat(mixed.violationCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "create-drop", " drop ", "invalid"})
    void nonCanonicalSchemaActionsAreUnknownAndNeverNormalizedByEngine(String value) {
        SpringReport report = scan(new Snap().setting("QA-PROD-002", value));
        assertThat(report.results()).isEmpty();
        assertThat(report.analysisErrors()).extracting(SpringRuleResultDto::id).containsExactly("QA-PROD-002");
        assertThat(report.rulesEvaluated()).isEqualTo(12);
    }

    @Test
    void compressionWordingSeparatesDefaultFromExplicitAndRemainsConditional() {
        SpringRuleResultDto explicit = find(scan(new Snap().setting("QA-WEB-001", "disabled")), "QA-WEB-001");
        SpringRuleResultDto defaulted = find(scan(new Snap().setting("QA-WEB-001", "default-disabled")), "QA-WEB-001");
        assertThat(explicit.sampleViolations()).isNotEqualTo(defaulted.sampleViolations());
        assertThat(explicit.description()).contains("Upstream compression", "media types", "workload");
        assertThat(explicit.recommendation()).contains("only if");
    }

    @ParameterizedTest
    @CsvSource({"zero,QA-WEB-002", "absent,QA-WEB-004"})
    void shutdownChecksAreMutuallyExclusiveAndRecommendPositiveDuration(String value, String id) {
        SpringReport report = scan(new Snap().setting("QA-WEB-002", value).setting("QA-WEB-004", value));
        assertThat(report.results()).extracting(SpringRuleResultDto::id).containsExactly(id);
        assertThat(find(report, id).recommendation()).contains("quarkus.shutdown.timeout=10s");
        if ("zero".equals(value)) {
            assertThat(find(report, id).recommendation()).contains("Removing the override does not enable draining");
        }
    }

    @Test
    void finiteClientTimeoutsHaveNoFindingEvenWhenOverFiveMinutes() {
        // The provider emits no finding observations for finite timers, independently of their length.
        SpringReport report = scan(new Snap());
        assertThat(find(report, "QA-WEB-003")).isNull();
        assertThat(report.rulesEvaluated()).isEqualTo(13);
        SpringRuleResultDto zero = find(scan(new Snap().setting("QA-WEB-003", "read-zero")), "QA-WEB-003");
        assertThat(zero.description()).contains("That timer is disabled", "other application deadlines");
        assertThat(zero.recommendation()).contains("Long finite timers are not inherently invalid");
    }

    @ParameterizedTest
    @ValueSource(ints = {21, 22, 23})
    void actualVirtualThreadMethodsAreLowConditionalRiskOnRunningJdk21To23(int jdk) {
        Snap snap = new Snap().methods("Resource#get()", "Resource#get()");
        snap.runtimeJdk = jdk;
        SpringRuleResultDto result = find(scan(snap), "QA-PERF-002");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.violationCount()).isOne();
        assertThat(result.description()).contains("running JDK 21–23", "have not been observed");
    }

    @ParameterizedTest
    @ValueSource(ints = {17, 20, 24, 25})
    void runtimeJdkNotAugmentationJdkDeterminesSynchronizedPinningApplicability(int jdk) {
        Snap snap = new Snap().methods("Resource#compiledOn21()");
        snap.runtimeJdk = jdk;
        SpringReport report = scan(snap);
        assertThat(find(report, "QA-PERF-002")).isNull();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.rulesEvaluated()).isEqualTo(13);
    }

    @Test
    void unknownRuntimeJdkDoesNotInventACleanVerdictOrPinningFinding() {
        Snap snap = new Snap().methods("Resource#get()");
        snap.runtimeJdk = 0;
        SpringReport report = scan(snap);
        assertThat(report.results()).isEmpty();
        assertThat(report.analysisErrors()).extracting(SpringRuleResultDto::id).containsExactly("QA-PERF-002");
        assertThat(report.rulesEvaluated()).isEqualTo(12);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
    }

    @Test
    void missingApplicableMetadataPreservesConfigurationFindingsAndMarksFourChecksUnknown() {
        Snap snap = new Snap().setting("QA-CFG-002", "true");
        snap.metadata = QuarkusAppMetadata.unavailable();
        SpringReport report = scan(snap);
        assertThat(find(report, "QA-CFG-002")).isNotNull();
        assertThat(report.rulesEvaluated()).isEqualTo(9);
        assertThat(report.componentsAnalyzed()).isZero();
        assertThat(report.analysisErrors())
                .extracting(SpringRuleResultDto::id)
                .containsExactly("QA-CDI-001", "QA-CDI-002", "QA-CDI-003", "QA-PERF-002");
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.inspected()).noneMatch(text -> text.contains("0 registered REST endpoints"));
    }

    @Test
    void explicitJdkNonApplicabilityDoesNotRequireMissingMethodMetadata() {
        Snap snap = new Snap();
        snap.metadata = QuarkusAppMetadata.unavailable();
        snap.runtimeJdk = 24;
        SpringReport report = scan(snap);
        assertThat(report.rulesEvaluated()).isEqualTo(10);
        assertThat(report.analysisErrors()).extracting(SpringRuleResultDto::id).doesNotContain("QA-PERF-002");
    }

    @ParameterizedTest
    @ValueSource(ints = {17, 20, 24, 25})
    void runtimeJdkNonApplicabilityOverridesMethodDiscoveryProblems(int jdk) {
        Snap snap = new Snap().methods("Resource#get()").metadataProblem("QA-PERF-002");
        snap.runtimeJdk = jdk;
        snap.problems.add(new QuarkusAppEvidenceProblem(
                "QA-PERF-002", "Application declaration collection reached its safety limit."));
        SpringReport report = scan(snap);
        assertThat(find(report, "QA-PERF-002")).isNull();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.rulesEvaluated()).isEqualTo(13);
        assertThat(report.scan().status()).isEqualTo("SCANNED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"QA-CDI-001", "QA-CDI-002", "QA-CDI-003", "QA-PERF-002"})
    void partialMetadataProblemsPreserveConcreteFindingsAndExcludeIncompleteEvaluation(String id) {
        Snap snap = id.equals("QA-PERF-002")
                ? new Snap().methods("Resource#get()")
                : new Snap()
                        .fields(new SharedField(
                                "Bean",
                                "state",
                                id.equals("QA-CDI-003") ? "SINGLETON" : "APPLICATION",
                                id.equals("QA-CDI-002")));
        snap.metadataProblem(id);
        SpringReport report = scan(snap);
        assertThat(find(report, id)).isNotNull();
        assertThat(report.analysisErrors()).extracting(SpringRuleResultDto::id).containsExactly(id);
        assertThat(report.rulesEvaluated()).isEqualTo(12);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.toString()).doesNotContain("password", "must-not-be-rendered");
    }

    @Test
    void unsupportedClientMetadataIsNotCountedAsKnownNonApplicability() {
        Snap snap = new Snap().metadataProblem("QA-WEB-003");
        assertThat(snap.metadata.restClientSupported()).isFalse();
        SpringReport report = scan(snap);
        assertThat(report.analysisErrors()).extracting(SpringRuleResultDto::id).containsExactly("QA-WEB-003");
        assertThat(report.rulesEvaluated()).isEqualTo(12);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
    }

    @Test
    void unevaluatedConfigurationWithoutProblemIsNeverSilentlyClean() {
        Snap snap = new Snap();
        snap.evaluated.clear();
        SpringReport report = scan(snap);
        assertThat(report.rulesEvaluated()).isEqualTo(4);
        assertThat(report.analysisErrors())
                .extracting(SpringRuleResultDto::id)
                .containsExactlyInAnyOrderElementsOf(CONFIG_RULES);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
    }

    @Test
    void configurationErrorsCanCoexistWithConcreteFindingsAndAreSanitized() {
        Snap snap = new Snap().setting("QA-PROD-002", "drop");
        snap.problems.add(new QuarkusAppEvidenceProblem("QA-PROD-002", "jdbc:secret-url password=secret"));
        SpringReport report = scan(snap);
        assertThat(find(report, "QA-PROD-002").severity()).isEqualTo("CRITICAL");
        assertThat(report.analysisErrors()).singleElement().satisfies(error -> {
            assertThat(error.id()).isEqualTo("QA-PROD-002");
            assertThat(error.status()).isEqualTo("ERROR");
            assertThat(error.violationCount()).isZero();
            assertThat(error.sampleViolations()).isEmpty();
        });
        assertThat(report.rulesEvaluated()).isEqualTo(12);
        assertThat(report.toString()).doesNotContain("jdbc:secret-url", "password=secret");
        assertThat(report.severityCounts()).contains(new SpringSeverityCountDto("CRITICAL", 1));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Application declaration collection reached its safety limit.|declaration collection reached its safety limit",
                "Application declaration metadata could not be resolved.|declaration metadata could not be resolved",
                "Application declaration resource is invalid or unreadable.|declaration resource is invalid or unreadable",
                "Configuration name discovery reached its inspection limit.|Configuration discovery reached an inspection limit",
                "A configuration name could not be inspected within the name limit.|Configuration discovery reached an inspection limit",
                "Production source discovery reached its inspection limit.|Configuration discovery reached an inspection limit",
                "REST client discovery reached its inspection limit.|REST client discovery reached its inspection limit",
                "ORM and JDBC capability evidence is unavailable.|ORM or JDBC capability evidence is unavailable",
                "REST client registration evidence is unavailable.|REST client registration evidence is unavailable",
                "Required configuration evidence could not be read or converted.|configuration evidence could not be completely read or converted",
                "A schema action could not be classified safely.|configuration value could not be classified safely",
                "The logging level could not be classified safely.|configuration value could not be classified safely",
                "The shutdown duration is invalid.|configuration value could not be classified safely",
                "A managed REST client timer could not be classified safely.|configuration value could not be classified safely"
            })
    void recognizedProblemMessagesBecomeFixedActionableExplanations(String message, String expected) {
        Snap snap = new Snap();
        snap.problems.add(new QuarkusAppEvidenceProblem("QA-WEB-003", message));
        SpringReport report = scan(snap);
        assertThat(report.analysisErrors()).singleElement().satisfies(error -> {
            assertThat(error.description()).contains(expected);
            assertThat(error.status()).isEqualTo("ERROR");
            assertThat(error.violationCount()).isZero();
        });
    }

    @Test
    void metadataLimitReasonSurvivesInTheRuleError() {
        Snap snap = new Snap();
        snap.metadata = new QuarkusAppMetadata(
                true,
                4,
                2,
                0,
                0,
                0,
                false,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(new QuarkusAppEvidenceProblem(
                        "QA-CDI-001", "Application declaration collection reached its safety limit.")));
        assertThat(scan(snap).analysisErrors()).singleElement().satisfies(error -> {
            assertThat(error.id()).isEqualTo("QA-CDI-001");
            assertThat(error.description()).contains("safety limit", "metadata coverage is incomplete");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"QA-CFG-002", "QA-CFG-003", "QA-PROD-002", "QA-PROD-003"})
    void productionCoverageErrorsExplicitlyExplainLoadedDeclarationLimitations(String id) {
        Snap snap = new Snap();
        snap.problems.add(new QuarkusAppEvidenceProblem(
                id,
                "Only loaded production declarations were inspected; effective production configuration, "
                        + "external overrides and unloaded profile-aware files are unavailable."));
        SpringReport report = scan(snap);
        assertThat(report.analysisErrors()).singleElement().satisfies(error -> {
            assertThat(error.description())
                    .contains(
                            "Only loaded production declarations were inspected",
                            "Loaded declarations cannot reconstruct a future production deployment's effective",
                            "external overrides",
                            "unloaded profile-aware files");
            assertThat(error.recommendation()).contains("Review loaded production declarations separately");
        });
        snap.problems.clear();
        snap.evaluated.remove(id);
        assertThat(scan(snap).analysisErrors())
                .singleElement()
                .satisfies(error -> assertThat(error.description())
                        .contains("Loaded declarations cannot reconstruct a future production"));
    }

    @Test
    void exactMessageClassificationNeverRendersAnArbitrarySuffixOrUnknownProblem() {
        for (String message : new String[] {
            "Application declaration collection reached its safety limit. secret-suffix",
            "Only loaded production declarations were inspected; secret-suffix",
            "arbitrary secret-suffix",
            null
        }) {
            Snap snap = new Snap();
            snap.problems.add(new QuarkusAppEvidenceProblem("QA-WEB-003", message));
            SpringReport report = scan(snap);
            assertThat(report.analysisErrors())
                    .singleElement()
                    .satisfies(error -> assertThat(error.description())
                            .isEqualTo("Required evidence could not be completely inspected for this rule."));
            assertThat(report.toString()).doesNotContain("secret-suffix");
        }
    }

    @Test
    void multipleKnownReasonsAreDeduplicatedAndOrderedWithoutLosingFindings() {
        Snap snap = new Snap().setting("QA-PROD-002", "drop");
        String production = "Only loaded production declarations were inspected; effective production configuration, "
                + "external overrides and unloaded profile-aware files are unavailable.";
        String limit = "Production source discovery reached its inspection limit.";
        snap.problems.add(new QuarkusAppEvidenceProblem("QA-PROD-002", limit));
        snap.problems.add(new QuarkusAppEvidenceProblem("QA-PROD-002", production));
        snap.problems.add(new QuarkusAppEvidenceProblem("QA-PROD-002", limit));
        SpringReport report = scan(snap);
        Collections.reverse(snap.problems);
        assertThat(scan(snap)).isEqualTo(report);
        assertThat(find(report, "QA-PROD-002")).isNotNull();
        assertThat(report.analysisErrors())
                .singleElement()
                .satisfies(error -> assertThat(error.description())
                        .contains(
                                "Only loaded production declarations were inspected",
                                "Configuration discovery reached an inspection limit"));
        assertThat(report.rulesEvaluated()).isEqualTo(12);
    }

    @Test
    void zeroSuccessfulRulesWithConcreteEvidenceIsPartialNotError() {
        Snap snap = new Snap().setting("QA-CFG-002", "true");
        snap.metadata = QuarkusAppMetadata.unavailable();
        snap.evaluated.clear();
        SpringReport report = scan(snap);
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.analysisErrors()).hasSize(13);
        assertThat(report.results()).hasSize(1);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
    }

    @Test
    void noInspectableEvidenceIsErrorAndHasNoScoredOccurrences() {
        Snap snap = new Snap();
        snap.metadata = null;
        snap.evaluated.clear();
        snap.profiles = List.of("password=secret");
        SpringReport report = scan(snap);
        assertThat(report.scan().status()).isEqualTo("ERROR");
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.analysisErrors()).hasSize(13);
        assertThat(report.results()).isEmpty();
        assertThat(report.severityCounts()).allMatch(count -> count.count() == 0);
        assertThat(report.toString()).doesNotContain("password=secret");
    }

    @Test
    void nullAndThrowingSuppliersReturnFixedErrorsAndReleaseAdmission() {
        assertUnavailable(QuarkusAppScanner.usingSnapshot(() -> null, CLOCK).scan());
        assertUnavailable(QuarkusAppScanner.usingSnapshot(null, CLOCK).scan());
        AtomicInteger calls = new AtomicInteger();
        QuarkusAppScanner scanner = QuarkusAppScanner.usingSnapshot(
                () -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new IllegalStateException("password=secret");
                    }
                    return new Snap().build();
                },
                CLOCK);
        assertUnavailable(scanner.scan());
        assertThat(scanner.scan().scan().status()).isEqualTo("SCANNED");
        assertUnavailable(QuarkusAppScanner.usingSnapshot(
                        () -> {
                            throw new NoClassDefFoundError("password=secret");
                        },
                        CLOCK)
                .scan());
    }

    private static void assertUnavailable(SpringReport report) {
        assertThat(report.scan().status()).isEqualTo("ERROR");
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.componentsAnalyzed()).isZero();
        assertThat(report.analysisErrors()).hasSize(13).allSatisfy(error -> {
            assertThat(error.status()).isEqualTo("ERROR");
            assertThat(error.violationCount()).isZero();
        });
        assertThat(report.results()).isEmpty();
        assertThat(report.toString()).doesNotContain("password=secret");
    }

    @Test
    void samplesAreBoundedDeterministicAndOccurrenceCountsRemainComplete() {
        List<SharedField> fields = IntStream.range(0, 35)
                .mapToObj(index -> new SharedField("Service", "field%02d".formatted(index), "APPLICATION", false))
                .toList();
        List<SharedField> reversed = new ArrayList<>(fields);
        Collections.reverse(reversed);
        SpringReport first = scan(new Snap().fields(fields.toArray(SharedField[]::new)));
        SpringReport second = scan(new Snap().fields(reversed.toArray(SharedField[]::new)));
        SpringRuleResultDto result = find(first, "QA-CDI-001");
        assertThat(result).isEqualTo(find(second, "QA-CDI-001"));
        assertThat(result.sampleViolations()).hasSize(20).isSorted();
        assertThat(result.violationCount()).isEqualTo(35);
        assertThat(first.severityCounts()).contains(new SpringSeverityCountDto("LOW", 35));
        assertThat(first.violationsFound()).isOne();
        assertThat(first.scan().violationsFound()).isOne();
    }

    @Test
    void configurationSamplesKeepFullCountsAndDeterministicOrdering() {
        Snap snap = new Snap();
        IntStream.range(0, 30)
                .forEach(index -> snap.settings.add(
                        new Setting("QA-CFG-002", "unit%02d".formatted(index), "true", "production declaration")));
        SpringRuleResultDto result = find(scan(snap), "QA-CFG-002");
        Collections.reverse(snap.settings);
        assertThat(find(scan(snap), "QA-CFG-002")).isEqualTo(result);
        assertThat(result.violationCount()).isEqualTo(30);
        assertThat(result.sampleViolations()).hasSize(20).isSorted();
    }

    @Test
    void orderingIsSeverityThenOccurrenceCountThenStableIdentifier() {
        Snap snap = new Snap()
                .setting("QA-PROD-002", "drop")
                .setting("QA-CFG-003", "verbose")
                .setting("QA-CFG-002", "true")
                .setting("QA-WEB-003", "connect-zero")
                .setting("QA-WEB-003", "read-zero")
                .setting("QA-CFG-004", "legacy")
                .setting("QA-WEB-001", "disabled");
        assertThat(scan(snap).results())
                .extracting(SpringRuleResultDto::id)
                .containsExactly("QA-PROD-002", "QA-WEB-003", "QA-CFG-002", "QA-CFG-003", "QA-CFG-004", "QA-WEB-001");
    }

    @Test
    void dismissalsChangeOnlyConcreteFindingCountsAndNeverClearIncompleteCoverage() {
        Snap snap = new Snap()
                .fields(
                        new SharedField("Service", "first", "APPLICATION", false),
                        new SharedField("Service", "second", "APPLICATION", false))
                .metadataProblem("QA-CDI-001");
        QuarkusAppScanner scanner = QuarkusAppScanner.usingSnapshot(snap::build, CLOCK);
        SpringReport scanned = scanner.scan();
        SpringReport dismissed = scanner.applyDismissals(scanned, Set.of("QA-CDI-001"));
        assertThat(dismissed.scan().status()).isEqualTo("PARTIAL");
        assertThat(dismissed.rulesEvaluated()).isEqualTo(12);
        assertThat(dismissed.analysisErrors()).isEqualTo(scanned.analysisErrors());
        assertThat(dismissed.violationsFound()).isZero();
        assertThat(dismissed.scan().violationsFound()).isZero();
        assertThat(dismissed.results()).singleElement().satisfies(result -> {
            assertThat(result.dismissed()).isTrue();
            assertThat(result.violationCount()).isEqualTo(2);
        });
        assertThat(dismissed.severityCounts()).allMatch(count -> count.count() == 0);
        SpringReport restored = scanner.applyDismissals(scanned, Set.of("unrelated"));
        assertThat(restored.violationsFound()).isOne();
        assertThat(restored.severityCounts()).contains(new SpringSeverityCountDto("LOW", 2));
        assertThat(scanner.applyDismissals(scanned, RETIRED_RULES)).isEqualTo(scanned);
        assertThat(scanner.applyDismissals(scanned, null)).isSameAs(scanned);
        assertThat(scanner.applyDismissals(scanned, Set.of())).isSameAs(scanned);
        assertThat(scanner.applyDismissals(null, Set.of("QA-CDI-001"))).isNull();
    }

    @Test
    void initialReportHasZeroEvaluationsAndDoesNotReadEvidence() {
        SpringReport report = QuarkusAppScanner.usingSnapshot(
                        () -> {
                            throw new AssertionError("Initial report must not inspect evidence");
                        },
                        CLOCK)
                .initialReport();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.scan().rulesEvaluated()).isZero();
        assertThat(report.violationsFound()).isZero();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.scan().scannedAt()).isNull();
    }

    @Test
    void overlappingScansFailFastWithoutReadingEvidenceTwice() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        QuarkusAppScanner scanner = QuarkusAppScanner.usingSnapshot(
                () -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test did not release scan");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return new Snap().build();
                },
                CLOCK);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(scanner::scan);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(scanner::scan).isInstanceOf(ActionBusyException.class);
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).scan().status()).isEqualTo("SCANNED");
            assertThat(scanner.scan().scan().status()).isEqualTo("SCANNED");
            assertThat(calls).hasValue(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
