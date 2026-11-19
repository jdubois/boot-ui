package io.github.jdubois.bootui.cli.ci;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class CiSarifTests {

    private static final CiPolicy POLICY = CiPolicy.parse("""
            {"schemaVersion":1,"advisors":{
              "architecture":{"required":true,"failOn":"high"},
              "security":{"required":false,"failOn":"high"},
              "spring":{"required":false,"failOn":"high"}
            }}
            """, "policy-sha");

    private static final JsonValue VIOLATION = JsonValue.parseStrict("""
            {"schemaVersion":2,"assessmentId":"a-1","status":"VIOLATION","frameworkName":"Spring Boot",
             "bootUiVersion":"2.4.1","instanceId":"i","runId":"r","policySha256":"policy-sha",
             "advisors":[
               {"name":"architecture","required":true,"status":"ASSESSED","reason":"-",
                "findings":[{"ruleId":"ARCH-001","severity":"HIGH","count":2},
                            {"ruleId":"ARCH-002","severity":"MEDIUM","count":1}]},
               {"name":"security","required":false,"status":"ASSESSED","reason":"-",
                "findings":[{"ruleId":"SEC-001","severity":"CRITICAL","count":1},
                            {"ruleId":"SEC-002","severity":"LOW","count":3},
                            {"ruleId":"SEC-003","severity":"INFO","count":1}]},
               {"name":"spring","required":false,"status":"SKIPPED","reason":"UNAVAILABLE","findings":[]}
             ],
             "errors":[]}
            """);

    @Test
    void declaresSarif210FromTheBootUiDriver() {
        JsonValue sarif = CiSarif.from(VIOLATION, POLICY);

        assertThat(sarif.get("version").asString(null)).isEqualTo("2.1.0");
        assertThat(sarif.get("$schema").asString(null)).isEqualTo("https://json.schemastore.org/sarif-2.1.0.json");
        JsonValue driver = run(sarif).get("tool").get("driver");
        assertThat(driver.get("name").asString(null)).isEqualTo("BootUI");
        assertThat(driver.get("version").asString(null)).isEqualTo("2.4.1");
        assertThat(driver.get("informationUri").asString(null)).isEqualTo("https://github.com/jdubois/boot-ui");
    }

    @Test
    void reportsOneResultPerRetainedFindingWithLevelFromSeverity() {
        List<JsonValue> results =
                run(CiSarif.from(VIOLATION, POLICY)).get("results").values();

        assertThat(results)
                .extracting(result -> result.get("ruleId").asString(null))
                .containsExactly("ARCH-001", "ARCH-002", "SEC-001", "SEC-002", "SEC-003");
        assertThat(results)
                .extracting(result -> result.get("level").asString(null))
                .containsExactly("error", "warning", "error", "note", "note");
        JsonValue first = results.get(0);
        assertThat(first.get("message").get("text").asString(null))
                .isEqualTo("ARCH-001: 2 occurrences reported by the BootUI architecture advisor at HIGH severity.");
        assertThat(first.get("properties").get("advisor").asString(null)).isEqualTo("architecture");
        assertThat(first.get("properties").get("severity").asString(null)).isEqualTo("HIGH");
        assertThat(first.get("properties").get("count").asLong(0)).isEqualTo(2);
        assertThat(first.get("properties").get("meetsThreshold").asBoolean(false))
                .isTrue();
        assertThat(results.get(1).get("properties").get("meetsThreshold").asBoolean(true))
                .isFalse();
        assertThat(results.get(4).get("message").get("text").asString(null)).contains("1 occurrence reported");
    }

    @Test
    void indexesEachRuleOnceAndTagsOnlySecurityRulesWithASecuritySeverity() {
        JsonValue run = run(CiSarif.from(VIOLATION, POLICY));
        List<JsonValue> rules = run.get("tool").get("driver").get("rules").values();

        assertThat(rules)
                .extracting(rule -> rule.get("id").asString(null))
                .containsExactly("ARCH-001", "ARCH-002", "SEC-001", "SEC-002", "SEC-003");
        assertThat(run.get("results").get(3).get("ruleIndex").asLong(-1)).isEqualTo(3);
        JsonValue architecture = rules.get(0);
        assertThat(architecture.get("defaultConfiguration").get("level").asString(null))
                .isEqualTo("error");
        assertThat(tags(architecture)).containsExactly("architecture");
        assertThat(architecture.get("properties").get("security-severity").isMissing())
                .isTrue();
        assertThat(tags(rules.get(2))).containsExactly("security");
        assertThat(rules.get(2).get("properties").get("security-severity").asString(null))
                .isEqualTo("9.5");
        assertThat(rules.get(3).get("properties").get("security-severity").asString(null))
                .isEqualTo("2.0");
        assertThat(rules.get(4).get("properties").get("security-severity").isMissing())
                .isTrue();
    }

    @Test
    void reportsSkippedAdvisorsAsNotificationsNotAsPassedChecks() {
        JsonValue invocation =
                run(CiSarif.from(VIOLATION, POLICY)).get("invocations").get(0);

        assertThat(invocation.get("executionSuccessful").asBoolean(false)).isTrue();
        JsonValue notification = invocation.get("toolExecutionNotifications").get(0);
        assertThat(notification.get("level").asString(null)).isEqualTo("warning");
        assertThat(notification.get("descriptor").get("id").asString(null)).isEqualTo("BOOTUI-ADVISOR-SKIPPED");
        assertThat(notification.get("message").get("text").asString(null))
                .isEqualTo("The optional spring advisor was not assessed (UNAVAILABLE); its zero finding count is "
                        + "not evidence that it passed.");
    }

    @Test
    void anUnassessedBundleIsAnUnsuccessfulRunWithItsErrorCodes() {
        JsonValue failed = JsonValue.parseStrict("""
                {"schemaVersion":1,"assessmentId":"a-2","status":"NOT_ASSESSED","errors":[{"code":"ATTACH_FAILED"}]}
                """);

        JsonValue run = run(CiSarif.from(failed, null));

        assertThat(run.get("results").size()).isZero();
        assertThat(run.get("tool").get("driver").get("rules").size()).isZero();
        JsonValue invocation = run.get("invocations").get(0);
        assertThat(invocation.get("executionSuccessful").asBoolean(true)).isFalse();
        JsonValue notification = invocation.get("toolExecutionNotifications").get(0);
        assertThat(notification.get("level").asString(null)).isEqualTo("error");
        assertThat(notification.get("descriptor").get("id").asString(null)).isEqualTo("ATTACH_FAILED");
        assertThat(run.get("properties").get("status").asString(null)).isEqualTo("NOT_ASSESSED");
        assertThat(run.get("tool").get("driver").get("version").isMissing()).isTrue();
    }

    @Test
    void isDeterministicAndCarriesOnlyBundleFields() {
        String first = JsonWriter.pretty(CiSarif.from(VIOLATION, POLICY));

        assertThat(JsonWriter.pretty(CiSarif.from(VIOLATION, POLICY))).isEqualTo(first);
        assertThat(run(CiSarif.from(VIOLATION, POLICY))
                        .get("properties")
                        .get("assessmentId")
                        .asString(null))
                .isEqualTo("a-1");
        assertThat(first).doesNotContain("startTimeUtc", "endTimeUtc", "physicalLocation");
    }

    @Test
    void aSinkViolationNamesItsCallSitesAsLogicalLocations() {
        JsonValue bundle = JsonValue.parseStrict("""
                {"schemaVersion":2,"assessmentId":"a-3","status":"VIOLATION","bootUiVersion":"2.4.1","policySha256":"s",
                 "runtimeContext":{"gate":[{"kind":"request-input-in-sink","result":"VIOLATION","reason":"-","affected":2,
                   "callSites":["com.example.web.SearchController#search","com.example.Repo#find"]}]},
                 "advisors":[],"errors":[]}
                """);

        JsonValue result = run(CiSarif.from(bundle, null)).get("results").get(0);

        assertThat(result.get("ruleId").asString(null)).isEqualTo("request-input-in-sink");
        assertThat(result.get("locations").values())
                .extracting(location -> location.get("logicalLocations")
                                .get(0)
                                .get("fullyQualifiedName")
                                .asString(null) + "/"
                        + location.get("logicalLocations").get(0).get("kind").asString(null))
                .containsExactly("com.example.web.SearchController.search/function", "com.example.Repo.find/function");
        assertThat(result.get("locations").get(0).get("physicalLocation").isMissing())
                .isTrue();
    }

    @Test
    void gateRulesCarryTheirCweAndOwaspClassification() {
        JsonValue bundle = JsonValue.parseStrict("""
                {"schemaVersion":2,"assessmentId":"a-4","status":"VIOLATION","bootUiVersion":"2.4.1","policySha256":"s",
                 "runtimeContext":{"gate":[
                   {"kind":"anonymous-success-on-restricted-route","result":"VIOLATION","reason":"-","affected":1,"callSites":[]},
                   {"kind":"anonymous-data-reach","result":"VIOLATION","reason":"-","affected":1,"callSites":[]},
                   {"kind":"request-input-in-sink","result":"VIOLATION","reason":"-","affected":1,"callSites":[]}]},
                 "advisors":[],"errors":[]}
                """);

        List<JsonValue> rules = run(CiSarif.from(bundle, null))
                .get("tool")
                .get("driver")
                .get("rules")
                .values();

        assertThat(rules)
                .extracting(rule ->
                        rule.get("id").asString(null) + " " + strings(rule, "cwe") + " " + strings(rule, "owasp"))
                .containsExactly(
                        "anonymous-success-on-restricted-route [CWE-862] [A01:2021-Broken Access Control]",
                        "anonymous-data-reach [CWE-306, CWE-862] [A01:2021-Broken Access Control]",
                        "request-input-in-sink [CWE-74] [A03:2021-Injection]");
        assertThat(tags(rules.get(2)))
                .containsExactly("security-sinks", "security", "external/cwe/cwe-74", "external/owasp/owasp-a03");
    }

    private static List<String> strings(JsonValue rule, String property) {
        return rule.get("properties").get(property).values().stream()
                .map(value -> value.asString(null))
                .toList();
    }

    private static JsonValue run(JsonValue sarif) {
        return sarif.get("runs").get(0);
    }

    private static List<String> tags(JsonValue rule) {
        return rule.get("properties").get("tags").values().stream()
                .map(tag -> tag.asString(null))
                .toList();
    }
}
