package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorChangesDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
