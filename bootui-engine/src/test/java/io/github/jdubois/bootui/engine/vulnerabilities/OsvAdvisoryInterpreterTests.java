package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.vulnerabilities.OsvAdvisoryInterpreter.Affected;
import io.github.jdubois.bootui.engine.vulnerabilities.OsvAdvisoryInterpreter.Event;
import io.github.jdubois.bootui.engine.vulnerabilities.OsvAdvisoryInterpreter.Range;
import io.github.jdubois.bootui.engine.vulnerabilities.OsvAdvisoryInterpreter.Result;
import io.github.jdubois.bootui.engine.vulnerabilities.OsvAdvisoryInterpreter.Severity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class OsvAdvisoryInterpreterTests {

    private static final String PACKAGE = "org.example:library";
    private static final Severity LOW = new Severity("CVSS_V3", "CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:U/C:L/I:L/A:N");
    private static final Severity CRITICAL = new Severity("CVSS_V3", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H");
    private static final Severity ZERO = new Severity("CVSS_V3", "CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N");

    @Test
    void selectsOnlyTheInstalledBranchNotTheFirstOrMostSeverePackageEntry() {
        Result result = interpret("1.5", entry("2", "2.4", CRITICAL), entry("1", "1.9", LOW));

        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.cvssScore()).isEqualTo(3.8d);
        assertThat(result.fixedVersions()).containsExactly("1.9");
        assertThat(result.unresolved()).isFalse();
    }

    @Test
    void takesTheMaximumSupportedScoreAcrossEveryApplicableEntryAndAssessment() {
        Affected assessments = new Affected("Maven", PACKAGE, List.of("1.5"), List.of(), List.of(ZERO, CRITICAL, LOW));

        Result result = interpret("1.5", entry("1", "2", LOW), assessments);

        assertThat(result.severity()).isEqualTo("CRITICAL");
        assertThat(result.cvssScore()).isEqualTo(9.8d);
        assertThat(result.fixedVersions()).containsExactly("2");
    }

    @Test
    void requiresExactEcosystemAndPackageButSupportsTheLiteralWildcard() {
        List<Affected> unrelated = List.of(
                named("npm", PACKAGE, CRITICAL),
                named("Maven:https://repo.example", PACKAGE, CRITICAL),
                named("Maven", "org.example:other", CRITICAL),
                named("Maven", "org.example:*", CRITICAL),
                named("maven", PACKAGE, CRITICAL),
                named("Maven", "ORG.EXAMPLE:library", CRITICAL));
        List<Affected> affected = new ArrayList<>(unrelated);
        affected.add(named("Maven", "*", LOW));

        Result result = interpret("1.5", affected.toArray(Affected[]::new));

        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.unresolved()).isFalse();
        assertThat(interpret("1.5", unrelated.toArray(Affected[]::new)).unresolved())
                .isTrue();
    }

    @Test
    void explicitVersionMembershipWorksWithoutRangesButNeverManufacturesAFix() {
        Affected explicit = new Affected("Maven", PACKAGE, List.of("1.5"), List.of(), List.of(LOW));

        assertThat(interpret("1.5", explicit).severity()).isEqualTo("LOW");
        assertThat(interpret("1.5", explicit).fixedVersions()).isEmpty();
        assertThat(interpret("1.6", explicit).severity()).isEqualTo("UNKNOWN");
        assertThat(interpret("1.6", explicit).unresolved()).isTrue();
    }

    @Test
    void versionsAndRangesAreAUnionIncludingExplicitVersionsOutsideTheRange() {
        Affected union = new Affected(
                "Maven",
                PACKAGE,
                List.of("3"),
                List.of(range(event("introduced", "1"), event("fixed", "2"))),
                List.of(LOW));

        assertThat(interpret("1.5", union).severity()).isEqualTo("LOW");
        assertThat(interpret("1.5", union).fixedVersions()).containsExactly("2");
        assertThat(interpret("3", union).severity()).isEqualTo("LOW");
        assertThat(interpret("3", union).fixedVersions()).isEmpty();
    }

    @Test
    void sortsUnsortedEventsAndAssociatesFixesWithReintroducedIntervals() {
        Affected reintroduced = ranges(
                range(event("fixed", "4"), event("introduced", "3"), event("fixed", "2"), event("introduced", "1")));

        assertThat(interpret("1.5", reintroduced).fixedVersions()).containsExactly("2");
        assertThat(interpret("3.5", reintroduced).fixedVersions()).containsExactly("4");
        assertThat(interpret("2.5", reintroduced).severity()).isEqualTo("UNKNOWN");
        assertThat(interpret("2.5", reintroduced).unresolved()).isTrue();
    }

    @Test
    void introducedZeroPrecedesEvenTheEarliestMavenPrerelease() {
        Result result = interpret("0-alpha", ranges(range(event("fixed", "1"), event("introduced", "0"))));

        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.fixedVersions()).containsExactly("1");
    }

    @Test
    void introducedIsInclusiveAndFixedIsExclusiveUsingMavenQualifierAliases() {
        Affected affected = entry("1.0-rc1", "1.0-final", LOW);

        assertThat(interpret("1.0-cr1", affected).severity()).isEqualTo("LOW");
        assertThat(interpret("1.0-SNAPSHOT", affected).fixedVersions()).containsExactly("1.0-final");
        assertThat(interpret("1.0-ga", affected).severity()).isEqualTo("UNKNOWN");
        assertThat(interpret("1.0-ga", affected).fixedVersions()).isEmpty();
        assertThat(interpret("1.0-ga", affected).unresolved()).isTrue();
    }

    @Test
    void lastAffectedIsInclusiveAndNeverAFix() {
        Affected affected = ranges(range(event("last_affected", "2"), event("introduced", "1")));

        assertThat(interpret("2", affected).severity()).isEqualTo("LOW");
        assertThat(interpret("2", affected).fixedVersions()).isEmpty();
        assertThat(interpret("2.0.1", affected).severity()).isEqualTo("UNKNOWN");
    }

    @Test
    void inclusiveSingletonsWorkInBothEventOrdersWithMavenEquivalentEndpoints() {
        for (String endpoint : List.of("1.0", "1.0-final", "1.0-ga")) {
            Event introduced = event("introduced", "1.0");
            Event lastAffected = event("last_affected", endpoint);
            for (Range range : List.of(range(introduced, lastAffected), range(lastAffected, introduced))) {
                Affected affected = ranges(range);
                Result singleton = interpret("1.0", affected);
                assertThat(singleton.severity()).as("%s", range).isEqualTo("LOW");
                assertThat(singleton.unresolved()).as("%s", range).isFalse();
                assertThat(singleton.fixedVersions()).isEmpty();
                assertThat(interpret("0.9", affected).cvssScore()).isNull();
                assertThat(interpret("1.0.1", affected).cvssScore()).isNull();
            }
        }
    }

    @Test
    void limitsAreExclusiveScopeBoundsNotFixesOrTimelineClosures() {
        Affected affected = ranges(range(event("limit", "2"), event("introduced", "0")));

        assertThat(interpret("1.5", affected).severity()).isEqualTo("LOW");
        assertThat(interpret("1.5", affected).fixedVersions()).isEmpty();
        assertThat(interpret("2", affected).severity()).isEqualTo("UNKNOWN");
    }

    @Test
    void multipleLimitsExpandScopeAndInfinityRestoresUnboundedScope() {
        Affected limited = ranges(range(event("limit", "2"), event("introduced", "0"), event("limit", "4")));
        Affected infinite = ranges(range(event("limit", "2"), event("limit", "*"), event("introduced", "0")));

        assertThat(interpret("3", limited).severity()).isEqualTo("LOW");
        assertThat(interpret("4", limited).severity()).isEqualTo("UNKNOWN");
        assertThat(interpret("9999", infinite).severity()).isEqualTo("LOW");
        assertThat(interpret("9999", infinite).fixedVersions()).isEmpty();
    }

    @Test
    void limitsAreIndependentOfFixedAndReintroducedStatusEvents() {
        Affected affected = ranges(range(
                event("limit", "4"),
                event("fixed", "2"),
                event("introduced", "0"),
                event("introduced", "3"),
                event("limit", "5")));

        assertThat(interpret("1.5", affected).fixedVersions()).containsExactly("2");
        assertThat(interpret("2.5", affected).severity()).isEqualTo("UNKNOWN");
        assertThat(interpret("4.5", affected).severity()).isEqualTo("LOW");
        assertThat(interpret("4.5", affected).fixedVersions()).isEmpty();
    }

    @Test
    void unsupportedDomainsRemainUnresolvedInsteadOfBorrowingPackageSeverityOrFixes() {
        for (String type : List.of("SEMVER", "GIT", "future-domain")) {
            Affected affected = ranges(new Range(type, List.of(event("introduced", "0"), event("fixed", "2"))));
            Result result = interpret("1.5", affected);

            assertThat(result.severity()).as(type).isEqualTo("UNKNOWN");
            assertThat(result.cvssScore()).as(type).isNull();
            assertThat(result.fixedVersions()).as(type).isEmpty();
            assertThat(result.unresolved()).as(type).isTrue();
        }
    }

    @Test
    void explicitMembershipCanProvePackageSeverityDespiteAnUnresolvedRange() {
        Affected affected = new Affected(
                "Maven",
                PACKAGE,
                List.of("1.5"),
                List.of(new Range("GIT", List.of(event("introduced", "0"), event("fixed", "abcdef")))),
                List.of(LOW));

        Result result = interpret("1.5", affected);

        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.fixedVersions()).isEmpty();
        assertThat(result.unresolved()).isTrue();
    }

    @Test
    void malformedEventsMissingIntroductionsAndMixedClosuresRemainUnknown() {
        List<Range> malformed = Arrays.asList(
                null,
                new Range(null, List.of(event("introduced", "0"))),
                range(),
                range(event("fixed", "2")),
                range(event("introduced", "0"), event("limit", "")),
                range(event("introduced", "0"), event(null, null)),
                range(event("introduced", "0"), event("unexpected", "2")),
                range(event("introduced", "0"), event("fixed", "2"), event("last_affected", "1.9")),
                new Range("ECOSYSTEM", Arrays.asList(event("introduced", "0"), null)));

        for (Range range : malformed) {
            Result result =
                    interpret("1.5", new Affected("Maven", PACKAGE, List.of(), Arrays.asList(range), List.of(LOW)));

            assertThat(result.severity()).as("%s", range).isEqualTo("UNKNOWN");
            assertThat(result.fixedVersions()).as("%s", range).isEmpty();
            assertThat(result.unresolved()).as("%s", range).isTrue();
        }
    }

    @Test
    void contradictoryMavenEquivalentStatusBoundariesDoNotDependOnInputOrder() {
        Affected first = ranges(range(event("introduced", "1"), event("fixed", "1.0")));
        Affected reversed = ranges(range(event("fixed", "1.0"), event("introduced", "1")));

        assertThat(interpret("1", first)).isEqualTo(interpret("1", reversed));
        assertThat(interpret("1", first).unresolved()).isTrue();
        assertThat(interpret("1", first).fixedVersions()).isEmpty();
    }

    @Test
    void emptyMissingAndMalformedEvidenceNeverBecomesAnUnaffectedVerdict() {
        for (List<Affected> affected : List.of(
                List.<Affected>of(),
                Arrays.<Affected>asList((Affected) null),
                List.of(new Affected("Maven", PACKAGE, null, null, null)))) {
            Result result = OsvAdvisoryInterpreter.interpret(PACKAGE, "1.5", affected, List.of(CRITICAL), null);

            assertThat(result.severity()).isEqualTo("CRITICAL");
            assertThat(result.fixedVersions()).isEmpty();
            assertThat(result.unresolved()).isTrue();
        }
        assertThat(OsvAdvisoryInterpreter.interpret(PACKAGE, null, null, null, null)
                        .unresolved())
                .isTrue();
        assertThat(OsvAdvisoryInterpreter.interpret(null, "1.5", List.of(named("Maven", "*", LOW)), null, null)
                        .unresolved())
                .isTrue();
    }

    @Test
    void usesMaximumGlobalAssessmentOnlyWhenNoApplicablePackageSeverityWasSupplied() {
        Affected affected = new Affected("Maven", PACKAGE, List.of("1.5"), List.of(), List.of());
        Result result = OsvAdvisoryInterpreter.interpret(
                PACKAGE, "1.5", List.of(affected), List.of(LOW, CRITICAL, ZERO), "LOW");

        assertThat(result.cvssScore()).isEqualTo(9.8d);
        assertThat(result.unresolved()).isFalse();
        assertThat(OsvAdvisoryInterpreter.interpret(
                                PACKAGE, "1.5", List.of(entry("1", "2", LOW)), List.of(CRITICAL), null)
                        .cvssScore())
                .isEqualTo(3.8d);
    }

    @Test
    void suppliedInvalidOrUnsupportedPackageSeverityDoesNotBorrowConflictingGlobalVectors() {
        List<Severity> invalid = Arrays.asList(
                null,
                new Severity(null, null),
                new Severity("CVSS_V3", "9.8"),
                new Severity("CVSS_V2", "AV:N/AC:L/Au:N/C:C/I:C/A:C"),
                new Severity("CVSS_V4", "CVSS:4.0/AV:N"));
        for (Severity assessment : invalid) {
            Affected affected = new Affected("Maven", PACKAGE, List.of("1.5"), List.of(), Arrays.asList(assessment));
            Result result =
                    OsvAdvisoryInterpreter.interpret(PACKAGE, "1.5", List.of(affected), List.of(CRITICAL), "MODERATE");

            assertThat(result.severity()).isEqualTo("MEDIUM");
            assertThat(result.cvssScore()).isNull();
            assertThat(OsvAdvisoryInterpreter.interpret(PACKAGE, "1.5", List.of(affected), List.of(CRITICAL), null)
                            .severity())
                    .isEqualTo("UNKNOWN");
        }
    }

    @Test
    void knownZeroRemainsNoneAndDoesNotFallBackToADatabaseLabel() {
        Result result =
                OsvAdvisoryInterpreter.interpret(PACKAGE, "1.5", List.of(entry("1", "2", ZERO)), List.of(), "CRITICAL");

        assertThat(result.cvssScore()).isZero();
        assertThat(result.severity()).isEqualTo("NONE");
    }

    @Test
    void doesNotBorrowAFixFromAnUnrelatedBranchWhenTheInstalledBranchIsOpenEnded() {
        Result result = interpret("1.5", ranges(range(event("introduced", "1"))), entry("2", "2.4", CRITICAL));

        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.fixedVersions()).isEmpty();
        assertThat(result.unresolved()).isFalse();
    }

    @Test
    void rejectsTargetsExplicitlyAffectedByTheSameOrAnotherEntry() {
        Affected explicit = new Affected("Maven", PACKAGE, List.of("2"), List.of(), List.of());
        Affected union = new Affected(
                "Maven", PACKAGE, List.of("2"), entry("1", "2", LOW).ranges(), List.of(LOW));

        assertThat(interpret("1.5", entry("1", "2", LOW), explicit).fixedVersions())
                .isEmpty();
        assertThat(interpret("1.5", union).fixedVersions()).isEmpty();
    }

    @Test
    void rejectsOverlappingTargetsButRetainsTheFixThatClosesEveryAffectedInterval() {
        Result result = interpret("1.5", entry("1", "2", LOW), entry("1.4", "3", LOW));

        assertThat(result.fixedVersions()).containsExactly("3");
    }

    @Test
    void rejectsAFixReintroducedByAnotherEntryEvenIfThatEntryDoesNotMatchInstalledVersion() {
        Result result = interpret("1.5", entry("1", "2", LOW), entry("2", "3", CRITICAL));

        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.fixedVersions()).isEmpty();
    }

    @Test
    void unresolvedTargetEvidenceCannotVerifyAnUpgrade() {
        Result result = interpret(
                "1.5",
                entry("1", "2", LOW),
                ranges(new Range("SEMVER", List.of(event("introduced", "3"), event("fixed", "4")))));

        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.fixedVersions()).isEmpty();
        assertThat(result.unresolved()).isTrue();
    }

    @Test
    void unrelatedOldFixesCannotConsumeTheCandidateDisplayLimit() {
        List<Affected> affected = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            affected.add(entry(i + ".0", i + ".1", CRITICAL));
        }
        affected.add(entry("20", "21", LOW));

        Result result = interpret("20.5", affected.toArray(Affected[]::new));

        assertThat(result.fixedVersions()).containsExactly("21");
        assertThat(result.severity()).isEqualTo("LOW");
    }

    @Test
    void blankInstalledVersionPreservesUnknownAndCannotOfferAFix() {
        Result result = interpret(" ", entry("0", "2", LOW));

        assertThat(result.cvssScore()).isNull();
        assertThat(result.fixedVersions()).isEmpty();
        assertThat(result.unresolved()).isTrue();
    }

    @Test
    void malformedExplicitVersionsDoNotHideAProvenMatchButBlockUnverifiedTargets() {
        Affected affected = new Affected(
                "Maven",
                PACKAGE,
                Arrays.asList("1.5", null),
                entry("1", "2", LOW).ranges(),
                List.of(LOW));

        Result result = interpret("1.5", affected);

        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.fixedVersions()).isEmpty();
        assertThat(result.unresolved()).isTrue();
    }

    @Test
    void neutralRecordsSnapshotMutableAdapterCollections() {
        List<Event> events = new ArrayList<>(List.of(event("introduced", "0"), event("fixed", "2")));
        Range range = new Range("ECOSYSTEM", events);
        List<Range> ranges = new ArrayList<>(List.of(range));
        Affected affected = new Affected("Maven", PACKAGE, List.of(), ranges, List.of(LOW));
        events.clear();
        ranges.clear();

        assertThat(interpret("1.5", affected).fixedVersions()).containsExactly("2");
    }

    private static Result interpret(String version, Affected... affected) {
        return OsvAdvisoryInterpreter.interpret(PACKAGE, version, Arrays.asList(affected), List.of(), null);
    }

    private static Affected named(String ecosystem, String name, Severity severity) {
        return new Affected(ecosystem, name, List.of("1.5"), List.of(), List.of(severity));
    }

    private static Affected entry(String introduced, String fixed, Severity severity) {
        return new Affected(
                "Maven",
                PACKAGE,
                List.of(),
                List.of(range(event("introduced", introduced), event("fixed", fixed))),
                List.of(severity));
    }

    private static Affected ranges(Range... ranges) {
        return new Affected("Maven", PACKAGE, List.of(), Arrays.asList(ranges), List.of(LOW));
    }

    private static Range range(Event... events) {
        return new Range("ECOSYSTEM", Arrays.asList(events));
    }

    private static Event event(String type, String version) {
        return new Event(type, version);
    }
}
