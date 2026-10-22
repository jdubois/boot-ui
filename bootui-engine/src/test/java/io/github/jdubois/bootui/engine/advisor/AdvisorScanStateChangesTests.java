package io.github.jdubois.bootui.engine.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorChangesDto;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class AdvisorScanStateChangesTests {

    private static final String SCOPE = AdvisorFindingIdentity.encode("unit=main");

    @Test
    void theFirstScanHasNoChangesAndAnUntrackedStateNeverHasAny() {
        AdvisorScanState<Report> tracked = tracked();
        assertThat(scan(tracked, scan -> scan.finding("R1", "a")).violationDetails().changes())
                .isNull();

        AdvisorScanState<Report> untracked = new AdvisorScanState<>(Report::with);
        scan(untracked, scan -> scan.finding("R1", "a"));
        AdvisorChangesDto none = scan(untracked, scan -> scan.finding("R1", "a"))
                .violationDetails()
                .changes();
        assertThat(none).isNull();
    }

    @Test
    void anIdenticalRescanReportsEverythingUnchangedAndCompleteAndMarksEachFinding() {
        AdvisorScanState<Report> state = tracked();
        scan(state, scan -> scan.finding("R1", "a").finding("R1", "b").finding("R2", "c"));
        Report second = scan(state, scan -> scan.finding("R1", "a").finding("R1", "b").finding("R2", "c"));

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.complete()).isTrue();
        assertThat(changes.notes()).isEmpty();
        assertThat(changes.unchangedCount()).isEqualTo(3);
        assertThat(changes.newCount()).isZero();
        assertThat(changes.resolvedCount()).isZero();
        assertThat(changes.unkeyedCount()).isZero();
        assertThat(changes.rules()).isEmpty();
        AdvisorRuleViolationsDto page = state.ruleViolations("R1", second.violationDetails().scanId(), 0, 10);
        assertThat(page.changes()).containsExactly("UNCHANGED", "UNCHANGED");
        assertThat(page.identities()).containsExactly(id("a"), id("b"));
    }

    @Test
    void oneFixedFindingIsResolvedAndANewOneIsMarkedNew() {
        AdvisorScanState<Report> state = tracked();
        Report first = scan(state, scan -> scan.finding("R1", "a").finding("R1", "b"));
        Report second = scan(state, scan -> scan.finding("R1", "b").finding("R1", "c"));

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.previousScanId()).isEqualTo(first.violationDetails().scanId());
        assertThat(changes.complete()).isTrue();
        assertThat(changes.newCount()).isEqualTo(1);
        assertThat(changes.resolvedCount()).isEqualTo(1);
        assertThat(changes.unchangedCount()).isEqualTo(1);
        assertThat(changes.resolved()).singleElement().satisfies(resolved -> {
            assertThat(resolved.ruleId()).isEqualTo("R1");
            assertThat(resolved.text()).isEqualTo("text a");
        });
        assertThat(changes.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("R1");
            assertThat(rule.newCount()).isEqualTo(1);
            assertThat(rule.resolvedCount()).isEqualTo(1);
        });
        AdvisorRuleViolationsDto page = state.ruleViolations("R1", second.violationDetails().scanId(), 0, 10);
        assertThat(page.violations()).containsExactly("text b", "text c");
        assertThat(page.changes()).containsExactly("UNCHANGED", "NEW");
    }

    @Test
    void aRuleThatFixedItsLastFindingIsResolvedAndAFirstFindingIsNew() {
        AdvisorScanState<Report> state = tracked();
        scan(state, scan -> scan.finding("R1", "a"));
        AdvisorChangesDto none = scan(state, scan -> scan.concluded("R1")).violationDetails().changes();
        assertThat(none.resolvedCount()).isEqualTo(1);
        assertThat(none.complete()).isTrue();

        AdvisorChangesDto back = scan(state, scan -> scan.finding("R1", "a")).violationDetails().changes();
        assertThat(back.newCount()).isEqualTo(1);
        assertThat(back.complete()).isTrue();
    }

    @Test
    void aRuleThatDidNotConcludeNeverMakesAFindingLookResolved() {
        AdvisorScanState<Report> state = tracked();
        scan(state, scan -> scan.finding("R1", "a").finding("R2", "b"));
        Report second = scan(state, scan -> scan.concluded("R2"));

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.resolvedCount()).isEqualTo(1);
        assertThat(changes.resolved()).extracting(resolved -> resolved.ruleId()).containsExactly("R2");
        assertThat(changes.unverifiedCount()).isEqualTo(1);
        assertThat(changes.complete()).isFalse();
        assertThat(changes.notes()).anyMatch(note -> note.contains("not compared"));
        assertThat(changes.rules()).anySatisfy(rule -> {
            assertThat(rule.ruleId()).isEqualTo("R1");
            assertThat(rule.unverifiedCount()).isEqualTo(1);
        });
    }

    @Test
    void aFindingIsNotNewWhenThePreviousScanDidNotConcludeItsRule() {
        AdvisorScanState<Report> state = tracked();
        scan(state, scan -> scan.concluded("R2"));
        Report second = scan(state, scan -> scan.finding("R1", "a").concluded("R2"));

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.newCount()).isZero();
        assertThat(changes.unverifiedCount()).isEqualTo(1);
        assertThat(changes.complete()).isFalse();
        assertThat(state.ruleViolations("R1", second.violationDetails().scanId(), 0, 10)
                        .changes())
                .isEmpty();
    }

    @Test
    void anUnverifiedScopeBlocksOnlyItsOwnFindings() {
        AdvisorScanState<Report> state = tracked();
        scan(state, scan -> scan.finding("R1", "a").finding("R1", "b", "text b", "unit=other"));
        Report second = scan(state, scan -> scan.unverifiedScope(AdvisorFindingIdentity.encode("unit=other")));

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.resolved()).extracting(resolved -> resolved.text()).containsExactly("text a");
        assertThat(changes.unverifiedCount()).isEqualTo(1);
        assertThat(changes.complete()).isFalse();
    }

    @Test
    void truncatedRetentionNeverClaimsResolutionOrNewnessForTheTruncatedRule() {
        AdvisorScanState<Report> state = tracked();
        state.setRetentionLimit(() -> 2);
        scan(state, scan -> scan.finding("R1", "a").finding("R1", "b").finding("R1", "c"));
        Report second = scan(state, scan -> scan.finding("R1", "b").finding("R1", "c").finding("R1", "d"));

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(second.violationDetails().truncated()).isTrue();
        assertThat(changes.complete()).isFalse();
        assertThat(changes.resolvedCount()).isZero();
        assertThat(changes.newCount()).isZero();
        assertThat(changes.unchangedCount()).isEqualTo(1);
        assertThat(changes.unverifiedCount()).isEqualTo(2);
        assertThat(changes.notes()).anyMatch(note -> note.contains("retained 2 of 3"));
    }

    @Test
    void unkeyedFindingsAreCountedButNeverComparedAndMakeTheComparisonIncomplete() {
        AdvisorScanState<Report> state = tracked();
        scan(state, scan -> scan.unkeyed("R1", "a").finding("R1", "k"));
        Report second = scan(state, scan -> scan.unkeyed("R1", "b").finding("R1", "k"));

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.unkeyedCount()).isEqualTo(1);
        assertThat(changes.unchangedCount()).isEqualTo(1);
        assertThat(changes.newCount()).isZero();
        assertThat(changes.resolvedCount()).isZero();
        assertThat(changes.complete()).isFalse();
        AdvisorRuleViolationsDto page = state.ruleViolations("R1", second.violationDetails().scanId(), 0, 10);
        assertThat(page.identities()).containsExactly(null, id("k"));
        assertThat(page.changes()).containsExactly(null, "UNCHANGED");
    }

    @Test
    void duplicateIdentitiesAreMatchedAsAMultiset() {
        AdvisorScanState<Report> state = tracked();
        scan(state, scan -> scan.finding("R1", "a", "text 1").finding("R1", "a", "text 2"));
        Report second = scan(state, scan -> scan.finding("R1", "a", "text 3"));

        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.unchangedCount()).isEqualTo(1);
        assertThat(changes.resolvedCount()).isEqualTo(1);
        assertThat(changes.newCount()).isZero();
    }

    @Test
    void aScanThatConcludedNothingIsNotComparedAndDoesNotReplaceTheBaseline() {
        AdvisorScanState<Report> state = tracked();
        Report first = scan(state, scan -> scan.finding("R1", "a"));
        Report outage = scan(state, scan -> scan.scanUnverified("No datasource was found, so no rule could run."));

        AdvisorChangesDto notCompared = outage.violationDetails().changes();
        assertThat(notCompared.previousScanId()).isEqualTo(first.violationDetails().scanId());
        assertThat(notCompared.complete()).isFalse();
        assertThat(notCompared.resolvedCount()).isZero();
        assertThat(notCompared.notes()).anyMatch(note -> note.contains("No datasource"));

        Report recovered = scan(state, scan -> scan.finding("R1", "a"));
        AdvisorChangesDto changes = recovered.violationDetails().changes();
        assertThat(changes.previousScanId()).isEqualTo(first.violationDetails().scanId());
        assertThat(changes.unchangedCount()).isEqualTo(1);
        assertThat(changes.newCount()).isZero();
    }

    @Test
    void anOutageBeforeAnyBaselineLeavesTheFirstRealScanWithoutChanges() {
        AdvisorScanState<Report> state = tracked();
        assertThat(scan(state, scan -> scan.scanUnverified("x")).violationDetails().changes())
                .isNull();
        assertThat(scan(state, scan -> scan.finding("R1", "a")).violationDetails().changes())
                .isNull();
        assertThat(scan(state, scan -> scan.finding("R1", "a")).violationDetails().changes())
                .isNotNull();
    }

    @Test
    void theComparisonIgnoresStaleScanIds() {
        AdvisorScanState<Report> state = tracked();
        Report first = scan(state, scan -> scan.finding("R1", "a"));
        scan(state, scan -> scan.finding("R1", "a"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> state.ruleViolations("R1", first.violationDetails().scanId(), 0, 10))
                .isInstanceOf(AdvisorViolationException.class);
    }

    @Test
    void changesAreAlignedOnEveryPage() {
        AdvisorScanState<Report> state = tracked();
        scan(state, scan -> {
            for (int i = 0; i < 6; i++) scan.finding("R1", "k" + i);
        });
        Report second = scan(state, scan -> {
            for (int i = 3; i < 9; i++) scan.finding("R1", "k" + i);
        });
        String scanId = second.violationDetails().scanId();
        List<String> markers = new ArrayList<>();
        for (int offset = 0; offset < 6; offset += 4) {
            AdvisorRuleViolationsDto page = state.ruleViolations("R1", scanId, offset, 4);
            assertThat(page.changes()).hasSameSizeAs(page.violations());
            assertThat(page.identities()).hasSameSizeAs(page.violations());
            markers.addAll(page.changes());
        }
        assertThat(markers).containsExactly("UNCHANGED", "UNCHANGED", "UNCHANGED", "NEW", "NEW", "NEW");
    }

    private static String id(String key) {
        return AdvisorFindingIdentity.of("v1", "unit=main", "key=" + key);
    }

    private static AdvisorScanState<Report> tracked() {
        return new AdvisorScanState<>(Report::with).trackingChanges();
    }

    private static Report scan(AdvisorScanState<Report> state, Consumer<Scan> body) {
        AdvisorViolationCollector collector = state.collector();
        body.accept(new Scan(collector));
        return state.publish(new Report("report", null), collector);
    }

    /** A scan script: a finding also concludes its rule, as a scanner would. */
    private static final class Scan {
        private final AdvisorViolationCollector collector;

        private Scan(AdvisorViolationCollector collector) {
            this.collector = collector;
        }

        Scan finding(String rule, String key) {
            return finding(rule, key, "text " + key);
        }

        Scan finding(String rule, String key, String text) {
            return finding(rule, key, text, "unit=main");
        }

        Scan finding(String rule, String key, String text, String scope) {
            collector.markConcluded(rule, AdvisorFindingIdentity.encode(scope));
            collector.record(
                    rule,
                    1,
                    List.of(AdvisorViolation.of(text, AdvisorFindingIdentity.of("v1", scope, "key=" + key))),
                    UnaryOperator.identity(),
                    0);
            return this;
        }

        Scan concluded(String rule) {
            collector.markConcluded(rule, AdvisorViolationCollector.ALL_SCOPES);
            return this;
        }

        Scan unkeyed(String rule, String text) {
            collector.markConcluded(rule, AdvisorViolationCollector.ALL_SCOPES);
            collector.record(rule, 1, List.of(AdvisorViolation.of(text)), UnaryOperator.identity(), 0);
            return this;
        }

        Scan unverifiedScope(String scope) {
            collector.markConcluded("R1", AdvisorViolationCollector.ALL_SCOPES);
            collector.markScopeUnverified(scope, "A scope could not be read.");
            return this;
        }

        Scan scanUnverified(String reason) {
            collector.markScanUnverified(reason);
            return this;
        }
    }

    private record Report(String label, AdvisorViolationDetailsDto violationDetails) {
        Report with(AdvisorViolationDetailsDto metadata) {
            return new Report(label, metadata);
        }
    }
}
