package io.github.jdubois.bootui.cli.ci;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.cli.BootUiCli;
import io.github.jdubois.bootui.client.JsonValue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CiBundleEvaluatorTests {

    @TempDir
    Path temp;

    private Path policy;
    private String policyHash;
    private int bundleCount;

    @BeforeEach
    void writePolicy() throws Exception {
        policy = temp.resolve("policy.json");
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}}}
                """);
        policyHash = CiPolicy.read(policy).sha256();
    }

    @Test
    void passesACompleteMatchingBundleWithoutAServiceUrl() throws Exception {
        assertThat(check(bundle("PASS", List.of(), policyHash), "test-assessment"))
                .isEqualTo(0);
    }

    @Test
    void ciCheckEvaluatesTheSavedBundleWithoutCallingAService() throws Exception {
        Path input = writeBundle(bundle("PASS", List.of(), policyHash));
        StringWriter output = new StringWriter();
        int exitCode = runCheck(input, output);

        assertThat(exitCode).isZero();
        assertThat(output.toString()).contains("offline assessment passed");
    }

    @Test
    void ciCheckPropagatesViolationAndInvalidInputResults() throws Exception {
        JsonValue finding = object("ruleId", "ARCH-001", "severity", "HIGH", "count", 1);
        Path violation = writeBundle(bundle("VIOLATION", List.of(finding), policyHash));
        StringWriter violationOutput = new StringWriter();
        assertThat(runCheck(violation, violationOutput)).isEqualTo(3);
        assertThat(violationOutput.toString()).contains("policy-threshold violation");

        StringWriter invalidOutput = new StringWriter();
        assertThat(runCheck(temp.resolve("missing.json"), invalidOutput)).isEqualTo(1);
        assertThat(invalidOutput.toString()).contains("could not be read or verified");
    }

    @Test
    void recomputesThresholdViolationsFromFindings() throws Exception {
        JsonValue finding = object("ruleId", "ARCH-001", "severity", "HIGH", "count", 2);
        assertThat(check(bundle("VIOLATION", List.of(finding), policyHash), "test-assessment"))
                .isEqualTo(3);
    }

    @Test
    void rejectsForgedStatusPolicyAssessmentAndIncompleteEvidence() throws Exception {
        JsonValue finding = object("ruleId", "ARCH-001", "severity", "HIGH", "count", 1);
        assertThat(check(bundle("PASS", List.of(finding), policyHash), "test-assessment"))
                .isEqualTo(1);
        assertThat(check(bundle(JsonValue.NULL, List.of(), policyHash), "test-assessment"))
                .isEqualTo(1);
        assertThat(check(bundle("PASS", List.of(), "0".repeat(64)), "test-assessment"))
                .isEqualTo(1);
        assertThat(check(bundle("PASS", List.of(), policyHash), "different-assessment"))
                .isEqualTo(1);

        JsonValue incomplete = advisor("ASSESSED", null, List.of(), false, true, 4);
        assertThat(check(bundle("PASS", List.of(), policyHash, incomplete), "test-assessment"))
                .isEqualTo(1);
    }

    @Test
    void acceptsLegacyBundlesOnlyWhenRuntimeInsightsWereNotRequested() throws Exception {
        JsonValue legacy = bundle("PASS", List.of(), policyHash);
        assertThat(check(legacy, "test-assessment")).isZero();

        enableRuntimeInsightsPolicy();
        JsonValue optedInBundle = bundle("PASS", List.of(), policyHash);
        assertThat(check(optedInBundle, "test-assessment")).isEqualTo(1);
        assertThat(check(withRuntimeContext(optedInBundle, runtimeContext("NOT_REQUESTED")), "test-assessment"))
                .isEqualTo(1);

        assertThat(check(withRuntimeContext(optedInBundle, runtimeContext("CAPTURED")), "test-assessment"))
                .isZero();
        disableRuntimeInsightsPolicy();
        JsonValue optedOutBundle = bundle("PASS", List.of(), policyHash);
        assertThat(check(withRuntimeContext(optedOutBundle, runtimeContext("CAPTURED")), "test-assessment"))
                .isEqualTo(1);
        assertThat(check(withRuntimeContext(optedOutBundle, runtimeContext("NOT_REQUESTED")), "test-assessment"))
                .isZero();
    }

    @Test
    void validatesRuntimeContextCoverageAndKeepsItReportOnly() throws Exception {
        enableRuntimeInsightsPolicy();
        JsonValue first = runtimeObservation("repeated-selects", "OBSERVED", 0, 5);
        JsonValue duplicateKind = runtimeObservation("repeated-selects", "OBSERVED", 2, 4);
        JsonValue captured =
                runtimeContext("CAPTURED", false, false, 3, 1, 4, 1, 1, 1, 1, 2, List.of(first, duplicateKind));
        JsonValue valid = withRuntimeContext(bundle("PASS", List.of(), policyHash), captured);
        assertThat(check(valid, "test-assessment")).isZero();

        JsonValue reportOnlyError = withRuntimeContext(bundle("PASS", List.of(), policyHash), runtimeContext("ERROR"));
        assertThat(check(reportOnlyError, "test-assessment")).isZero();

        JsonValue inconsistentCoverage = runtimeContext("CAPTURED", false, false, 3, 1, 5, 1, 1, 1, 1, 2, List.of());
        assertThat(check(
                        withRuntimeContext(bundle("PASS", List.of(), policyHash), inconsistentCoverage),
                        "test-assessment"))
                .isEqualTo(1);

        JsonValue eventsWithoutSources = runtimeContext("CAPTURED", false, false, 3, 0, 1, 1, 0, 0, 0, 2, List.of());
        assertThat(check(
                        withRuntimeContext(bundle("PASS", List.of(), policyHash), eventsWithoutSources),
                        "test-assessment"))
                .isEqualTo(1);

        JsonValue overflowingCoverage =
                runtimeContext("CAPTURED", false, false, 3, 1, 0, Long.MAX_VALUE, 1, 0, 0, 0, List.of());
        assertThat(check(
                        withRuntimeContext(bundle("PASS", List.of(), policyHash), overflowingCoverage),
                        "test-assessment"))
                .isEqualTo(1);
    }

    @Test
    void rejectsMalformedRuntimeContextAndTooManyObservations() throws Exception {
        enableRuntimeInsightsPolicy();
        JsonValue base = bundle("PASS", List.of(), policyHash);
        JsonValue nullContextStatus = withMember(runtimeContext("CAPTURED"), "status", JsonValue.NULL);
        assertThat(check(withRuntimeContext(base, nullContextStatus), "test-assessment"))
                .isEqualTo(1);

        JsonValue invalidKind = runtimeObservation("Private route", "OBSERVED", 1, 1);
        assertThat(check(
                        withRuntimeContext(
                                base,
                                runtimeContext("CAPTURED", false, false, 1, 0, 0, 0, 0, 0, 0, 0, List.of(invalidKind))),
                        "test-assessment"))
                .isEqualTo(1);

        JsonValue nullObservationStatus =
                withMember(runtimeObservation("repeated-selects", "OBSERVED", 1, 1), "status", JsonValue.NULL);
        assertThat(check(
                        withRuntimeContext(
                                base,
                                runtimeContext(
                                        "CAPTURED",
                                        false,
                                        false,
                                        1,
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        List.of(nullObservationStatus))),
                        "test-assessment"))
                .isEqualTo(1);

        List<JsonValue> tooMany = java.util.stream.IntStream.range(0, CiAssessmentRunner.MAX_RUNTIME_OBSERVATIONS + 1)
                .mapToObj(index -> runtimeObservation("repeated-selects", "OBSERVED", index, index))
                .toList();
        assertThat(check(
                        withRuntimeContext(
                                base, runtimeContext("CAPTURED", false, false, 1, 0, 0, 0, 0, 0, 0, 0, tooMany)),
                        "test-assessment"))
                .isEqualTo(1);
    }

    @Test
    void rejectsRequiredSkipsAndUnknownFindingSeverities() throws Exception {
        JsonValue skipped = advisor("SKIPPED", "UNAVAILABLE", List.of(), false, false, 0);
        assertThat(check(bundle("PASS", List.of(), policyHash, skipped), "test-assessment"))
                .isEqualTo(1);

        JsonValue unknown = object("ruleId", "ARCH-001", "severity", "UNKNOWN", "count", 1);
        assertThat(check(bundle("VIOLATION", List.of(unknown), policyHash), "test-assessment"))
                .isEqualTo(1);
    }

    @Test
    void rejectsFindingsThatCannotFitWithinEvaluatedRuleCounts() throws Exception {
        JsonValue first = object("ruleId", "ARCH-001", "severity", "HIGH", "count", 1);
        JsonValue second = object("ruleId", "ARCH-002", "severity", "LOW", "count", 1);
        JsonValue impossible = advisor("ASSESSED", null, List.of(first, second), true, true, 1);
        assertThat(check(bundle("VIOLATION", List.of(first, second), policyHash, impossible), "test-assessment"))
                .isEqualTo(1);

        JsonValue nonDatabaseSkip = advisor("architecture", "ASSESSED", null, List.of(), true, true, 4, 1, 0);
        assertThat(check(bundle("PASS", List.of(), policyHash, nonDatabaseSkip), "test-assessment"))
                .isEqualTo(1);
    }

    @Test
    void rejectsFindingCountsThatDisagreeWithTheFindingOccurrences() throws Exception {
        JsonValue finding = object("ruleId", "ARCH-001", "severity", "HIGH", "count", 2);
        JsonValue inconsistent = advisor("ASSESSED", null, List.of(finding), true, true, 4, 1);
        assertThat(check(bundle("VIOLATION", List.of(finding), policyHash, inconsistent), "test-assessment"))
                .isEqualTo(1);
    }

    @Test
    void rejectsFindingOccurrenceSumsThatOverflow() throws Exception {
        JsonValue first = object("ruleId", "ARCH-001", "severity", "HIGH", "count", Long.MAX_VALUE);
        JsonValue second = object("ruleId", "ARCH-002", "severity", "HIGH", "count", Long.MAX_VALUE);
        JsonValue third = object("ruleId", "ARCH-003", "severity", "HIGH", "count", 3);
        List<JsonValue> findings = List.of(first, second, third);
        JsonValue inconsistent = advisor("ASSESSED", null, findings, true, true, 4, 1);
        assertThat(check(bundle("VIOLATION", findings, policyHash, inconsistent), "test-assessment"))
                .isEqualTo(1);
    }

    @Test
    void capsTotalFindingOccurrencesAcrossAdvisors() throws Exception {
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{
                  "architecture":{"required":true,"failOn":"high"},
                  "rest-api":{"required":true,"failOn":"high"}
                }}
                """);
        policyHash = CiPolicy.read(policy).sha256();
        JsonValue architectureFinding = object("ruleId", "ARCH-001", "severity", "LOW", "count", 10_000);
        JsonValue restFinding = object("ruleId", "REST-001", "severity", "LOW", "count", 10_000);
        JsonValue architecture =
                advisor("architecture", "ASSESSED", null, List.of(architectureFinding), true, true, 4, 0, 10_000);
        JsonValue rest = advisor("rest-api", "ASSESSED", null, List.of(restFinding), true, true, 4, 0, 10_000);
        assertThat(check(bundle("PASS", policyHash, List.of(architecture, rest)), "test-assessment"))
                .isZero();

        JsonValue overLimitFinding = object("ruleId", "REST-001", "severity", "LOW", "count", 10_001);
        JsonValue overLimit =
                advisor("rest-api", "ASSESSED", null, List.of(overLimitFinding), true, true, 4, 0, 10_001);
        assertThat(check(bundle("PASS", policyHash, List.of(architecture, overLimit)), "test-assessment"))
                .isEqualTo(1);
    }

    @Test
    void acceptsDatabaseOccurrenceCountsAboveTheNumberOfActiveRules() throws Exception {
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"database-advisor":{"required":true,"failOn":"high"}}}
                """);
        policyHash = CiPolicy.read(policy).sha256();
        JsonValue finding = object("ruleId", "DB-001", "severity", "LOW", "count", 3);
        JsonValue database = advisor("database-advisor", "ASSESSED", null, List.of(finding), true, true, 6, 5, 3);

        assertThat(check(bundle("PASS", List.of(finding), policyHash, database), "test-assessment"))
                .isZero();

        JsonValue impossibleSkipCount = advisor("database-advisor", "ASSESSED", null, List.of(), true, true, 6, 6, 0);
        assertThat(check(bundle("PASS", List.of(), policyHash, impossibleSkipCount), "test-assessment"))
                .isEqualTo(1);

        JsonValue secondFinding = object("ruleId", "DB-002", "severity", "LOW", "count", 1);
        JsonValue tooManyRules =
                advisor("database-advisor", "ASSESSED", null, List.of(finding, secondFinding), true, true, 6, 5, 4);
        assertThat(check(bundle("PASS", List.of(finding, secondFinding), policyHash, tooManyRules), "test-assessment"))
                .isEqualTo(1);
    }

    @Test
    void acceptsOnlyDeclaredOptionalCapabilitySkips() throws Exception {
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{
                  "architecture":{"required":true,"failOn":"high"},
                  "hibernate":{"required":false,"failOn":"high"}
                }}
                """);
        policyHash = CiPolicy.read(policy).sha256();
        JsonValue architecture = advisor("ASSESSED", null, List.of(), true, true, 4);
        JsonValue hibernate = object(
                "name",
                "hibernate",
                "required",
                false,
                "status",
                "SKIPPED",
                "reason",
                "UNAVAILABLE",
                "findingCount",
                0,
                "rulesSkipped",
                0,
                "rulesEvaluated",
                0,
                "subjectsAnalyzed",
                0,
                "usable",
                false,
                "coverageComplete",
                false,
                "limitations",
                JsonValue.array(List.of()),
                "findings",
                JsonValue.array(List.of()));
        JsonValue bundle = object(
                "schemaVersion",
                1,
                "assessmentId",
                "test-assessment",
                "status",
                "PASS",
                "bootUiVersion",
                "2.2.0",
                "instanceId",
                "a1b2c3d4",
                "runId",
                "b1b2c3d4",
                "policySha256",
                policyHash,
                "advisors",
                JsonValue.array(List.of(architecture, hibernate)),
                "errors",
                JsonValue.array(List.of()));

        assertThat(check(bundle, "test-assessment")).isZero();
    }

    @Test
    void acceptsAnExplicitNotAssessedErrorAsAnError() throws Exception {
        JsonValue failed = object(
                "schemaVersion", 1,
                "assessmentId", "test-assessment",
                "status", "NOT_ASSESSED",
                "bootUiVersion", "",
                "instanceId", "",
                "runId", "",
                "policySha256", "",
                "advisors", JsonValue.array(List.of()),
                "errors", JsonValue.array(List.of(object("code", "TARGET_REQUEST_FAILED"))));
        assertThat(check(failed, "test-assessment")).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateKeysMalformedAndOversizedBundles() throws Exception {
        JsonValue passingBundle = bundle("PASS", List.of(), policyHash);
        assertThat(check(passingBundle, "test-assessment")).isZero();
        String validJson = passingBundle.toJson();

        Path duplicate = temp.resolve("duplicate.json");
        String rootDuplicate =
                validJson.replaceFirst("\"schemaVersion\"\\s*:\\s*1", "\"schemaVersion\":1,\"schemaVersion\":1");
        assertThat(rootDuplicate).isNotEqualTo(validJson);
        Files.writeString(duplicate, rootDuplicate);
        assertThat(CiBundleEvaluator.evaluate(duplicate, "test-assessment", policy)
                        .exitCode())
                .isEqualTo(1);

        Path nestedDuplicate = temp.resolve("nested-duplicate.json");
        String nestedDuplicateJson = validJson.replaceFirst(
                "\"status\"\\s*:\\s*\"ASSESSED\"", "\"status\":\"ASSESSED\",\"status\":\"ASSESSED\"");
        assertThat(nestedDuplicateJson).isNotEqualTo(validJson);
        Files.writeString(nestedDuplicate, nestedDuplicateJson);
        assertThat(CiBundleEvaluator.evaluate(nestedDuplicate, "test-assessment", policy)
                        .exitCode())
                .isEqualTo(1);

        Path malformed = temp.resolve("malformed.json");
        Files.writeString(malformed, "{\"schemaVersion\":1");
        assertThat(CiBundleEvaluator.evaluate(malformed, "test-assessment", policy)
                        .exitCode())
                .isEqualTo(1);

        Path oversized = temp.resolve("oversized.json");
        Files.writeString(oversized, validJson + " ".repeat(2 * 1024 * 1024 + 1));
        assertThat(CiBundleEvaluator.evaluate(oversized, "test-assessment", policy)
                        .exitCode())
                .isEqualTo(1);
    }

    private int check(JsonValue bundle, String assessmentId) throws Exception {
        Path input = writeBundle(bundle);
        return CiBundleEvaluator.evaluate(input, assessmentId, policy).exitCode();
    }

    private int runCheck(Path input, StringWriter output) {
        return BootUiCli.run(
                new String[] {
                    "ci",
                    "check",
                    "--input",
                    input.toString(),
                    "--assessment-id",
                    "test-assessment",
                    "--policy",
                    policy.toString()
                },
                Map.of(),
                false,
                new PrintWriter(output, true),
                new PrintWriter(output, true));
    }

    private Path writeBundle(JsonValue bundle) throws Exception {
        Path input = temp.resolve("bundle-" + bundleCount++ + ".json");
        Files.writeString(input, bundle.toJson());
        return input;
    }

    private JsonValue bundle(String status, List<JsonValue> findings, String hash) {
        return bundle(status, findings, hash, advisor("ASSESSED", null, findings, true, true, 4));
    }

    private JsonValue bundle(JsonValue status, List<JsonValue> findings, String hash) {
        return object(
                "schemaVersion",
                1,
                "assessmentId",
                "test-assessment",
                "status",
                status,
                "bootUiVersion",
                "2.2.0",
                "instanceId",
                "a1b2c3d4",
                "runId",
                "b1b2c3d4",
                "policySha256",
                hash,
                "advisors",
                JsonValue.array(List.of(advisor("ASSESSED", null, findings, true, true, 4))),
                "errors",
                JsonValue.array(List.of()));
    }

    private JsonValue bundle(String status, List<JsonValue> findings, String hash, JsonValue advisor) {
        return bundle(status, hash, List.of(advisor));
    }

    private JsonValue bundle(String status, String hash, List<JsonValue> advisors) {
        return object(
                "schemaVersion",
                1,
                "assessmentId",
                "test-assessment",
                "status",
                status,
                "bootUiVersion",
                "2.2.0",
                "instanceId",
                "a1b2c3d4",
                "runId",
                "b1b2c3d4",
                "policySha256",
                hash,
                "advisors",
                JsonValue.array(advisors),
                "errors",
                JsonValue.array(List.of()));
    }

    private JsonValue withRuntimeContext(JsonValue bundle, JsonValue context) {
        return withMember(bundle, "runtimeContext", context);
    }

    private JsonValue withMember(JsonValue object, String member, JsonValue replacement) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        for (String name : object.names()) {
            values.put(name, object.get(name));
        }
        values.put(member, replacement);
        return JsonValue.object(values);
    }

    private JsonValue runtimeContext(String status) {
        return runtimeContext(status, false, false, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    private JsonValue runtimeContext(
            String status,
            boolean lowFootprintActive,
            boolean capturePaused,
            long httpExchanges,
            long sources,
            long retainedEvents,
            long byRequestId,
            long byExecutionId,
            long byTraceId,
            long unlinked,
            long droppedEvents,
            List<JsonValue> observations) {
        boolean hasData = Set.of("CAPTURED", "PARTIAL").contains(status);
        return object(
                "status",
                status,
                "workloadAttribution",
                "UNVERIFIED",
                "lowFootprintActive",
                lowFootprintActive,
                "capturePaused",
                capturePaused,
                "httpExchangesRead",
                httpExchanges,
                "coverage",
                object(
                        "sources",
                        sources,
                        "retainedEvents",
                        retainedEvents,
                        "byRequestId",
                        byRequestId,
                        "byExecutionId",
                        byExecutionId,
                        "byTraceId",
                        byTraceId,
                        "unlinked",
                        unlinked,
                        "droppedEvents",
                        droppedEvents),
                "observations",
                JsonValue.array(observations),
                "omittedObservations",
                hasData ? 2 : 0,
                "checksNotRunCount",
                hasData ? 1 : 0,
                "notExercisedRouteCount",
                hasData ? 3 : 0,
                "limitationsCount",
                hasData ? 1 : 0);
    }

    private JsonValue runtimeObservation(String kind, String status, long eligible, long affected) {
        return object("kind", kind, "status", status, "eligible", eligible, "affected", affected);
    }

    private void enableRuntimeInsightsPolicy() throws Exception {
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}},
                 "runtimeContext":{"includeInsights":true}}
                """);
        policyHash = CiPolicy.read(policy).sha256();
    }

    private void disableRuntimeInsightsPolicy() throws Exception {
        Files.writeString(policy, """
                {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}},
                 "runtimeContext":{"includeInsights":false}}
                """);
        policyHash = CiPolicy.read(policy).sha256();
    }

    private JsonValue advisor(
            String status, String reason, List<JsonValue> findings, boolean usable, boolean complete, long rules) {
        long findingCount = findings.stream()
                .mapToLong(finding -> finding.get("count").asLong(0))
                .sum();
        return advisor(status, reason, findings, usable, complete, rules, findingCount);
    }

    private JsonValue advisor(
            String status,
            String reason,
            List<JsonValue> findings,
            boolean usable,
            boolean complete,
            long rules,
            long findingCount) {
        return advisor("architecture", status, reason, findings, usable, complete, rules, 0, findingCount);
    }

    private JsonValue advisor(
            String name,
            String status,
            String reason,
            List<JsonValue> findings,
            boolean usable,
            boolean complete,
            long rules,
            long skipped,
            long findingCount) {
        return object(
                "name",
                name,
                "required",
                true,
                "status",
                status,
                "reason",
                reason == null ? JsonValue.NULL : JsonValue.of(reason),
                "findingCount",
                findingCount,
                "rulesSkipped",
                skipped,
                "rulesEvaluated",
                rules,
                "subjectsAnalyzed",
                3,
                "usable",
                usable,
                "coverageComplete",
                complete,
                "limitations",
                JsonValue.array(List.of()),
                "findings",
                JsonValue.array(findings));
    }

    private JsonValue object(Object... entries) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], value(entries[i + 1]));
        }
        return JsonValue.object(values);
    }

    private JsonValue value(Object value) {
        if (value instanceof JsonValue json) {
            return json;
        }
        if (value instanceof String text) {
            return JsonValue.of(text);
        }
        if (value instanceof Boolean bool) {
            return JsonValue.of(bool);
        }
        if (value instanceof Number number) {
            return JsonValue.of(number.longValue());
        }
        throw new IllegalArgumentException("Unsupported test value");
    }
}
