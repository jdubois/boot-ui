package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorChangesDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HibernateScanChangesTests {

    private final AtomicReference<List<String>> methods = new AtomicReference<>(List.of("a", "b", "c"));
    private final AtomicReference<List<HibernateObservationDiagnostic>> diagnostics = new AtomicReference<>(List.of());

    @Test
    void rescanningAfterFixingOneFindingReportsItResolvedAndTheRestUnchanged() {
        HibernateScanner scanner = scanner(new BulkUpdateVersionRule());
        HibernateReport first = scanner.scan();
        assertThat(first.violationDetails().changes()).isNull();

        methods.set(List.of("a", "c"));
        HibernateReport second = scanner.scan();

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.previousScanId()).isEqualTo(first.violationDetails().scanId());
        assertThat(changes.complete()).isTrue();
        assertThat(changes.resolvedCount()).isEqualTo(1);
        assertThat(changes.unchangedCount()).isEqualTo(2);
        assertThat(changes.newCount()).isZero();
        assertThat(changes.resolved()).singleElement().satisfies(resolved -> {
            assertThat(resolved.ruleId()).isEqualTo("HIB-QUERY-008");
            assertThat(resolved.text()).contains("OrderRepository#b");
        });
    }

    @Test
    void anImmediateRescanIsCompleteAndEverythingIsUnchanged() {
        HibernateScanner scanner = scanner(new BulkUpdateVersionRule());
        scanner.scan();
        HibernateReport second = scanner.scan();

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.complete()).isTrue();
        assertThat(changes.unchangedCount()).isEqualTo(3);
        var page = scanner.ruleViolations(
                "HIB-QUERY-008", second.violationDetails().scanId(), 0, 10);
        assertThat(page.identities()).doesNotContainNull().hasSize(3);
        assertThat(page.changes()).containsOnly("UNCHANGED");
    }

    @Test
    void aRuleThatFailsInTheNextScanNeverMakesItsFindingsLookResolved() {
        FailingOnDemandRule rule = new FailingOnDemandRule();
        HibernateScanner scanner = scanner(rule, new AlwaysPassingRule());
        scanner.scan();

        rule.fail = true;
        HibernateReport second = scanner.scan();

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.resolvedCount()).isZero();
        assertThat(changes.unverifiedCount()).isEqualTo(1);
        assertThat(changes.complete()).isFalse();
    }

    @Test
    void aUnitThatCouldNotBeObservedBlocksResolutionForThatUnit() {
        HibernateScanner scanner = scanner(new BulkUpdateVersionRule());
        scanner.scan();

        methods.set(List.of());
        diagnostics.set(List.of(new HibernateObservationDiagnostic(
                "orders", HibernateObservationDiagnostic.Reason.METAMODEL_UNAVAILABLE)));
        HibernateReport second = scanner.scan();

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.resolvedCount()).isZero();
        assertThat(changes.unverifiedCount()).isEqualTo(3);
        assertThat(changes.complete()).isFalse();
    }

    @Test
    void anUnobservedRepositoryOrFactoryBlocksEveryClaimOfThatScan() {
        HibernateScanner scanner = scanner(new BulkUpdateVersionRule(), new AlwaysPassingRule());
        scanner.scan();

        methods.set(List.of("a", "c"));
        diagnostics.set(List.of(new HibernateObservationDiagnostic(
                "application", HibernateObservationDiagnostic.Reason.REPOSITORY_METADATA_UNAVAILABLE)));
        AdvisorChangesDto changes = scanner.scan().violationDetails().changes();

        assertThat(changes.resolvedCount()).isZero();
        assertThat(changes.newCount()).isZero();
        assertThat(changes.complete()).isFalse();
        assertThat(changes.notes())
                .anyMatch(note -> note.contains("could not be observed"))
                .anyMatch(note -> note.contains("not compared"));
    }

    @Test
    void aScanWithNothingToInspectDoesNotReplaceTheBaseline() {
        HibernateScanner scanner = new HibernateScanner(
                () -> new HibernateAdvisorObservation(
                        methods.get().isEmpty() ? List.of() : List.of(unit()),
                        HibernateApplicationFacts.unknown(List.of()),
                        List.of()),
                Clock.systemUTC(),
                List.of(new BulkUpdateVersionRule()));
        HibernateReport first = scanner.scan();
        List<String> before = methods.getAndSet(List.of());
        HibernateReport outage = scanner.scan();
        assertThat(outage.violationDetails().changes().complete()).isFalse();
        assertThat(outage.violationDetails().changes().resolvedCount()).isZero();

        methods.set(before);
        HibernateReport recovered = scanner.scan();
        assertThat(recovered.violationDetails().changes().previousScanId())
                .isEqualTo(first.violationDetails().scanId());
        assertThat(recovered.violationDetails().changes().unchangedCount()).isEqualTo(3);
    }

    @Test
    void dischargedBulkObligationAllowsResolutionButAnApplicableQueryGapKeepsItUnverified() {
        for (boolean retainGap : List.of(false, true)) {
            AtomicReference<String> query =
                    new AtomicReference<>("select o from VersionedOrder o join fetch o.lines join fetch o.tags");
            HibernateScanner scanner = new HibernateScanner(
                    () -> new HibernateAdvisorObservation(
                            List.of(fetchUnit(query.get())), HibernateApplicationFacts.unknown(List.of()), List.of()),
                    Clock.systemUTC(),
                    List.of(new MultipleCollectionJoinFetchRule(), new BulkUpdateVersionRule()));
            HibernateReport first = scanner.scan();
            assertThat(first.evidence().coverageComplete()).isTrue();
            assertThat(first.results())
                    .extracting(HibernateRuleResultDto::id)
                    .containsExactlyInAnyOrder("HIB-QUERY-007", "HIB-QUERY-008");
            assertThat(first.violationDetails().truncated()).isFalse();
            assertThat(scanner.ruleViolations(
                                    "HIB-QUERY-007", first.violationDetails().scanId(), 0, 10)
                            .identities())
                    .singleElement()
                    .isNotNull();

            query.set(
                    retainGap
                            ? "select o from VersionedOrder o join o.lines l join fetch l.tags"
                            : "select o from VersionedOrder o join fetch o.lines");
            HibernateReport second = scanner.scan();
            assertThat(second.evidence().coverageComplete()).isEqualTo(!retainGap);
            assertThat(second.evidence().usable()).isTrue();
            assertThat(second.violationDetails().truncated()).isFalse();
            AdvisorChangesDto changes = second.violationDetails().changes();
            assertThat(changes.previousScanId())
                    .isEqualTo(first.violationDetails().scanId());
            assertThat(changes.complete()).isEqualTo(!retainGap);
            assertThat(changes.resolvedCount()).isEqualTo(retainGap ? 0 : 1);
            assertThat(changes.unverifiedCount()).isEqualTo(retainGap ? 1 : 0);
            assertThat(changes.unchangedCount()).isEqualTo(1);
            assertThat(scanner.ruleViolations(
                                    "HIB-QUERY-008", second.violationDetails().scanId(), 0, 10)
                            .changes())
                    .containsExactly("UNCHANGED");
            if (!retainGap) {
                assertThat(changes.resolved())
                        .singleElement()
                        .satisfies(resolved -> assertThat(resolved.ruleId()).isEqualTo("HIB-QUERY-007"));
            }
        }
    }

    @Test
    void advisorLimitOnlyComparisonExceptionDoesNotMakeAssessmentComplete() {
        for (boolean additionalGap : List.of(false, true)) {
            AtomicReference<Boolean> finding = new AtomicReference<>(true);
            HibernateRule limited =
                    new AbstractHibernateRule(new HibernateRuleDefinition(
                            "HIB-TEST-LIMIT",
                            "Limit comparison",
                            HibernateCategory.CONFIGURATION,
                            "LOW",
                            "Test",
                            "Test",
                            null)) {
                        @Override
                        HibernateRuleResultDto evaluateRule(HibernateContext context) {
                            context.evidence().markApplicable(true);
                            context.missingEvidence(HibernateEvidenceGap.POOL_GUARANTEES_BY_DESIGN);
                            if (additionalGap) context.missingEvidence(HibernateEvidenceGap.QUERY_SHAPE);
                            var details = new io.github.jdubois.bootui.engine.advisor.AdvisorFindings();
                            details.add("retained finding", null, HibernateIdentities.setting(context, "retained"));
                            if (finding.get())
                                details.add("known finding", null, HibernateIdentities.setting(context, "known"));
                            return violation(context, details);
                        }
                    };
            HibernateScanner scanner = scanner(limited, new BulkUpdateVersionRule());
            HibernateReport first = scanner.scan();
            assertThat(first.evidence().coverageComplete()).isFalse();
            finding.set(false);
            HibernateReport second = scanner.scan();
            assertThat(second.scan().status()).isEqualTo("PARTIAL");
            assertThat(second.evidence().coverageComplete()).isFalse();
            assertThat(second.diagnostics())
                    .singleElement()
                    .satisfies(
                            diagnostic -> assertThat(diagnostic.level()).isEqualTo(additionalGap ? "WARNING" : "INFO"));
            AdvisorChangesDto changes = second.violationDetails().changes();
            assertThat(changes.resolvedCount()).isEqualTo(additionalGap ? 0 : 1);
            assertThat(changes.unverifiedCount()).isEqualTo(additionalGap ? 1 : 0);
            assertThat(changes.unchangedCount()).isEqualTo(4);
            assertThat(changes.complete()).isEqualTo(!additionalGap);
        }
    }

    private HibernatePersistenceUnitObservation fetchUnit(String query) {
        List<HibernateRepositoryMethodModel> queries = List.of(
                new HibernateRepositoryMethodModel(
                        "OrderRepository",
                        "fetch",
                        VersionedOrder.class,
                        List.class,
                        query,
                        false,
                        null,
                        false,
                        false,
                        false,
                        false,
                        List.of(),
                        new HibernateQueryEvidence(
                                true, true, false, false, false, VersionedOrder.class, false, null, null, List.of())),
                new HibernateRepositoryMethodModel(
                        "OrderRepository",
                        "bulk",
                        VersionedOrder.class,
                        int.class,
                        "update VersionedOrder o set o.amount = 1",
                        false,
                        null,
                        false,
                        true,
                        false,
                        false,
                        List.of(),
                        new HibernateQueryEvidence(
                                true, true, false, false, false, int.class, false, null, null, List.of())));
        return new HibernatePersistenceUnitObservation(
                "orders",
                "orders",
                List.of(HibernateEntityModel.fromClass(VersionedOrder.class)),
                List.of(new HibernateRepositoryModel("OrderRepository", VersionedOrder.class, queries)),
                "7.4.5.Final",
                HibernateFactorySettings.unknown(),
                false);
    }

    private HibernateScanner scanner(HibernateRule... rules) {
        return new HibernateScanner(
                () -> new HibernateAdvisorObservation(
                        List.of(unit()), HibernateApplicationFacts.unknown(List.of()), diagnostics.get()),
                Clock.systemUTC(),
                List.of(rules));
    }

    private HibernatePersistenceUnitObservation unit() {
        List<HibernateRepositoryMethodModel> repositoryMethods = new ArrayList<>();
        for (String name : methods.get()) {
            repositoryMethods.add(new HibernateRepositoryMethodModel(
                    "OrderRepository",
                    name,
                    VersionedOrder.class,
                    int.class,
                    "update VersionedOrder e set e.amount = 1",
                    false,
                    null,
                    false,
                    true,
                    false,
                    false,
                    List.of(),
                    new HibernateQueryEvidence(
                            true, true, false, false, false, int.class, false, null, null, List.of())));
        }
        return new HibernatePersistenceUnitObservation(
                "orders",
                "orders",
                List.of(HibernateEntityModel.fromClass(VersionedOrder.class)),
                List.of(new HibernateRepositoryModel("OrderRepository", VersionedOrder.class, repositoryMethods)),
                "7.4.5.Final",
                HibernateFactorySettings.unknown(),
                false);
    }

    @Entity
    static class VersionedOrder {
        @Id
        Long id;

        @Version
        Long version;

        int amount;

        @OneToMany
        List<VersionedOrder> lines;

        @OneToMany
        List<VersionedOrder> tags;
    }

    private static final class AlwaysPassingRule extends AbstractHibernateRule {
        AlwaysPassingRule() {
            super(new HibernateRuleDefinition(
                    "HIB-TEST-PASS", "Always passes", HibernateCategory.QUERY, "LOW", "Test.", "Nothing.", ""));
        }

        @Override
        HibernateRuleResultDto evaluateRule(HibernateContext context) {
            context.evidence().markApplicable(true);
            return pass();
        }
    }

    /** Reports the same keyed finding until told to fail. */
    private static final class FailingOnDemandRule extends AbstractHibernateRule {
        boolean fail;

        FailingOnDemandRule() {
            super(new HibernateRuleDefinition(
                    "HIB-TEST-FAIL", "Fails on demand", HibernateCategory.QUERY, "LOW", "Test.", "Nothing.", ""));
        }

        @Override
        HibernateRuleResultDto evaluateRule(HibernateContext context) {
            context.evidence().markApplicable(true);
            if (fail) throw new IllegalStateException("boom");
            var findings = new io.github.jdubois.bootui.engine.advisor.AdvisorFindings();
            findings.add("finding", null, HibernateIdentities.setting(context, "x"));
            return violation(context, findings);
        }
    }
}
