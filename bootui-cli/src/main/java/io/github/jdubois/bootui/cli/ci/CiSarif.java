package io.github.jdubois.bootui.cli.ci;

import io.github.jdubois.bootui.client.JsonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SARIF 2.1.0 projection of a CI assessment bundle, for code-scanning and SARIF viewers.
 *
 * <p>It reads only the sanitized bundle, so it carries the same bounded facts as {@code summary.md} and never a raw
 * finding text, value, or route. The bundle retains no source frames, so results have no physical location.</p>
 */
final class CiSarif {

    static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String SECURITY_ADVISOR = CiPolicy.Advisor.SECURITY.policyName();

    private CiSarif() {}

    static JsonValue from(JsonValue bundle, CiPolicy policy) {
        Map<String, Integer> ruleIndexes = new LinkedHashMap<>();
        List<JsonValue> rules = new ArrayList<>();
        List<JsonValue> results = new ArrayList<>();
        List<JsonValue> notifications = new ArrayList<>();
        for (JsonValue advisor : bundle.get("advisors").values()) {
            String name = advisor.get("name").asString("");
            if ("SKIPPED".equals(advisor.get("status").asString(null))) {
                notifications.add(notification(
                        "warning",
                        "BOOTUI-ADVISOR-SKIPPED",
                        "The optional " + name + " advisor was not assessed ("
                                + advisor.get("reason").asString("-")
                                + "); its zero finding count is not evidence that it passed."));
            }
            CiPolicy.Severity threshold = threshold(policy, name);
            for (JsonValue finding : advisor.get("findings").values()) {
                String ruleId = finding.get("ruleId").asString("");
                CiPolicy.Severity severity =
                        CiPolicy.Severity.valueOf(finding.get("severity").asString(null));
                Integer index = ruleIndexes.get(ruleId);
                if (index == null) {
                    index = rules.size();
                    ruleIndexes.put(ruleId, index);
                    rules.add(rule(ruleId, name, severity));
                }
                results.add(result(
                        ruleId, index, name, severity, finding.get("count").asLong(0), threshold));
            }
        }
        for (JsonValue gate : bundle.get("runtimeContext").get("gate").values()) {
            String kind = gate.get("kind").asString("");
            String result = gate.get("result").asString("");
            if (CiRuntimeGate.VIOLATION.equals(result)) {
                results.add(gateResult(kind, rules.size(), gate.get("affected").asLong(0)));
                rules.add(gateRule(kind));
            } else if (CiRuntimeGate.NOT_ASSESSED.equals(result)) {
                notifications.add(notification(
                        "warning",
                        "BOOTUI-RUNTIME-GATE-NOT-ASSESSED",
                        "The runtime gate on " + kind + " was not assessed ("
                                + gate.get("reason").asString("-") + "); it did not pass."));
            }
        }
        for (JsonValue error : bundle.get("errors").values()) {
            String code = error.get("code").asString("");
            notifications.add(notification("error", code, "Assessment error: " + code + "."));
        }
        String status = bundle.get("status").asString("NOT_ASSESSED");

        Map<String, JsonValue> driver = new LinkedHashMap<>();
        driver.put("name", JsonValue.of("BootUI"));
        String version = bundle.get("bootUiVersion").asString("");
        if (!version.isEmpty()) {
            driver.put("version", JsonValue.of(version));
        }
        driver.put("informationUri", JsonValue.of("https://github.com/jdubois/boot-ui"));
        driver.put("rules", JsonValue.array(rules));

        Map<String, JsonValue> invocation = new LinkedHashMap<>();
        invocation.put("executionSuccessful", JsonValue.of(!"NOT_ASSESSED".equals(status)));
        invocation.put("toolExecutionNotifications", JsonValue.array(notifications));

        Map<String, JsonValue> properties = new LinkedHashMap<>();
        properties.put("assessmentId", JsonValue.of(bundle.get("assessmentId").asString("")));
        properties.put("status", JsonValue.of(status));
        properties.put("policySha256", JsonValue.of(bundle.get("policySha256").asString("")));

        Map<String, JsonValue> run = new LinkedHashMap<>();
        run.put("tool", object("driver", JsonValue.object(driver)));
        run.put("invocations", JsonValue.array(List.of(JsonValue.object(invocation))));
        run.put("results", JsonValue.array(results));
        run.put("properties", JsonValue.object(properties));

        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("$schema", JsonValue.of(SCHEMA));
        root.put("version", JsonValue.of("2.1.0"));
        root.put("runs", JsonValue.array(List.of(JsonValue.object(run))));
        return JsonValue.object(root);
    }

    private static CiPolicy.Severity threshold(CiPolicy policy, String advisor) {
        if (policy == null) {
            return null;
        }
        for (Map.Entry<CiPolicy.Advisor, CiPolicy.Selection> entry :
                policy.advisors().entrySet()) {
            if (entry.getKey().policyName().equals(advisor)) {
                return entry.getValue().failOn();
            }
        }
        return null;
    }

    private static JsonValue rule(String ruleId, String advisor, CiPolicy.Severity severity) {
        Map<String, JsonValue> properties = new LinkedHashMap<>();
        properties.put("tags", JsonValue.array(List.of(JsonValue.of(advisor))));
        String securitySeverity = SECURITY_ADVISOR.equals(advisor) ? securitySeverity(severity) : null;
        if (securitySeverity != null) {
            properties.put("security-severity", JsonValue.of(securitySeverity));
        }
        Map<String, JsonValue> rule = new LinkedHashMap<>();
        rule.put("id", JsonValue.of(ruleId));
        rule.put("name", JsonValue.of(ruleId));
        rule.put("shortDescription", object("text", JsonValue.of("BootUI " + advisor + " rule " + ruleId)));
        rule.put("defaultConfiguration", object("level", JsonValue.of(level(severity))));
        rule.put("properties", JsonValue.object(properties));
        return JsonValue.object(rule);
    }

    private static JsonValue result(
            String ruleId,
            int ruleIndex,
            String advisor,
            CiPolicy.Severity severity,
            long count,
            CiPolicy.Severity threshold) {
        Map<String, JsonValue> properties = new LinkedHashMap<>();
        properties.put("advisor", JsonValue.of(advisor));
        properties.put("severity", JsonValue.of(severity.name()));
        properties.put("count", JsonValue.of(count));
        if (threshold != null) {
            properties.put("meetsThreshold", JsonValue.of(severity.meets(threshold)));
        }
        Map<String, JsonValue> result = new LinkedHashMap<>();
        result.put("ruleId", JsonValue.of(ruleId));
        result.put("ruleIndex", JsonValue.of(ruleIndex));
        result.put("level", JsonValue.of(level(severity)));
        result.put(
                "message",
                object(
                        "text",
                        JsonValue.of(ruleId + ": " + count + (count == 1 ? " occurrence" : " occurrences")
                                + " reported by the BootUI " + advisor + " advisor at " + severity.name()
                                + " severity.")));
        result.put("properties", JsonValue.object(properties));
        return JsonValue.object(result);
    }

    private static JsonValue gateRule(String kind) {
        Map<String, JsonValue> properties = new LinkedHashMap<>();
        properties.put("tags", JsonValue.array(List.of(JsonValue.of("runtime-insights"), JsonValue.of("security"))));
        properties.put("security-severity", JsonValue.of(securitySeverity(CiPolicy.Severity.HIGH)));
        Map<String, JsonValue> rule = new LinkedHashMap<>();
        rule.put("id", JsonValue.of(kind));
        rule.put("name", JsonValue.of(kind));
        rule.put("shortDescription", object("text", JsonValue.of("BootUI Runtime Insights observation " + kind)));
        rule.put("defaultConfiguration", object("level", JsonValue.of("error")));
        rule.put("properties", JsonValue.object(properties));
        return JsonValue.object(rule);
    }

    private static JsonValue gateResult(String kind, int ruleIndex, long affected) {
        Map<String, JsonValue> properties = new LinkedHashMap<>();
        properties.put("source", JsonValue.of("runtime-insights"));
        properties.put("affected", JsonValue.of(affected));
        properties.put("meetsThreshold", JsonValue.of(true));
        Map<String, JsonValue> result = new LinkedHashMap<>();
        result.put("ruleId", JsonValue.of(kind));
        result.put("ruleIndex", JsonValue.of(ruleIndex));
        result.put("level", JsonValue.of("error"));
        result.put(
                "message",
                object(
                        "text",
                        JsonValue.of(kind + ": Runtime Insights observed this in the assessed run (affected: "
                                + affected + "); the policy's runtime gate fails on it.")));
        result.put("properties", JsonValue.object(properties));
        return JsonValue.object(result);
    }

    private static JsonValue notification(String level, String id, String text) {
        Map<String, JsonValue> notification = new LinkedHashMap<>();
        notification.put("level", JsonValue.of(level));
        notification.put("descriptor", object("id", JsonValue.of(id)));
        notification.put("message", object("text", JsonValue.of(text)));
        return JsonValue.object(notification);
    }

    private static String level(CiPolicy.Severity severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW, INFO -> "note";
        };
    }

    /** GitHub code scanning's security-severity bands: critical over 9.0, high 7.0–8.9, medium 4.0–6.9, low under 4. */
    private static String securitySeverity(CiPolicy.Severity severity) {
        return switch (severity) {
            case CRITICAL -> "9.5";
            case HIGH -> "8.0";
            case MEDIUM -> "5.5";
            case LOW -> "2.0";
            case INFO -> null;
        };
    }

    private static JsonValue object(String name, JsonValue value) {
        return JsonValue.object(Map.of(name, value));
    }
}
