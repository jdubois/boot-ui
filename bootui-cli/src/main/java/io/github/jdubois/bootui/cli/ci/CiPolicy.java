package io.github.jdubois.bootui.cli.ci;

import io.github.jdubois.bootui.client.JsonParseException;
import io.github.jdubois.bootui.client.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict, bounded policy input for the live-target CI assessment. */
public record CiPolicy(Map<Advisor, Selection> advisors, boolean includeInsights, String sha256) {

    public static final int MAX_POLICY_BYTES = 64 * 1024;
    private static final Set<String> REQUIRED_ROOT_KEYS = Set.of("schemaVersion", "advisors");
    private static final Set<String> ALLOWED_ROOT_KEYS = Set.of("schemaVersion", "advisors", "runtimeContext");
    private static final int MAX_POLICY_BYTES_PLUS_ONE = MAX_POLICY_BYTES + 1;

    public CiPolicy {
        advisors = Map.copyOf(advisors);
    }

    public static CiPolicy read(Path path) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_POLICY_BYTES_PLUS_ONE);
        }
        if (bytes.length == 0 || bytes.length > MAX_POLICY_BYTES) {
            throw new IllegalArgumentException("Policy must be between 1 and " + MAX_POLICY_BYTES + " bytes");
        }
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        return parse(text, sha256(bytes));
    }

    static CiPolicy parse(String text, String sha256) {
        JsonValue root;
        try {
            root = JsonValue.parseStrict(text);
        } catch (JsonParseException invalid) {
            throw new IllegalArgumentException("Policy is not strict JSON: " + invalid.getMessage(), invalid);
        }
        requireObject(root, "Policy");
        rejectUnknownOnly(root, ALLOWED_ROOT_KEYS, "policy");
        requireKeys(root, REQUIRED_ROOT_KEYS, "policy");
        JsonValue schemaVersion = root.get("schemaVersion");
        if (!schemaVersion.isNumber() || !"1".equals(schemaVersion.asDisplayText())) {
            throw new IllegalArgumentException("Policy schemaVersion must be 1");
        }
        JsonValue runtimeContext = root.get("runtimeContext");
        boolean includeInsights = false;
        if (!runtimeContext.isMissing()) {
            requireObject(runtimeContext, "runtimeContext");
            rejectUnknown(runtimeContext, Set.of("includeInsights"), "runtimeContext");
            JsonValue include = runtimeContext.get("includeInsights");
            if (!include.isBoolean()) {
                throw new IllegalArgumentException("runtimeContext.includeInsights must be a boolean");
            }
            includeInsights = include.asBoolean(false);
        }
        JsonValue entries = root.get("advisors");
        requireObject(entries, "advisors");
        if (entries.size() == 0) {
            throw new IllegalArgumentException("Policy must select at least one advisor");
        }
        Map<Advisor, Selection> selected = new LinkedHashMap<>();
        for (String name : entries.names()) {
            Advisor advisor = Advisor.forPolicyName(name);
            JsonValue config = entries.get(name);
            requireObject(config, "advisors." + name);
            rejectUnknown(config, Set.of("required", "failOn"), "advisors." + name);
            JsonValue required = config.get("required");
            if (!required.isBoolean()) {
                throw new IllegalArgumentException("advisors." + name + ".required must be a boolean");
            }
            String failOn = config.get("failOn").asString(null);
            if (failOn == null) {
                throw new IllegalArgumentException("advisors." + name + ".failOn must be a severity string");
            }
            selected.put(advisor, new Selection(required.asBoolean(false), Severity.parse(failOn)));
        }
        if (selected.values().stream().noneMatch(Selection::required)) {
            throw new IllegalArgumentException("Policy must include at least one required advisor");
        }
        return new CiPolicy(selected, includeInsights, sha256);
    }

    public List<Advisor> orderedAdvisors() {
        return advisors.keySet().stream().sorted().toList();
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireObject(JsonValue value, String label) {
        if (!value.isObject()) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
    }

    private static void rejectUnknown(JsonValue value, Set<String> allowed, String label) {
        rejectUnknownOnly(value, allowed, label);
        for (String required : allowed) {
            if (value.get(required).isMissing()) {
                throw new IllegalArgumentException("Missing " + label + " key: " + required);
            }
        }
    }

    private static void rejectUnknownOnly(JsonValue value, Set<String> allowed, String label) {
        List<String> unknown = new ArrayList<>(value.names());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown " + label + " key: " + unknown.get(0));
        }
    }

    private static void requireKeys(JsonValue value, Set<String> required, String label) {
        for (String key : required) {
            if (value.get(key).isMissing()) {
                throw new IllegalArgumentException("Missing " + label + " key: " + key);
            }
        }
    }

    public record Selection(boolean required, Severity failOn) {}

    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW,
        INFO;

        static Severity parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Unknown severity in policy: " + value);
            }
        }

        public boolean meets(Severity threshold) {
            return ordinal() <= threshold.ordinal();
        }
    }

    /** Existing advisor tool names and supported report projections. */
    public enum Advisor {
        ARCHITECTURE(
                "architecture",
                "architecture_scan",
                "get_architecture_report",
                "get_architecture_rule_violations",
                "classesAnalyzed",
                "results"),
        HIBERNATE(
                "hibernate",
                "hibernate_scan",
                "get_hibernate_report",
                "get_hibernate_rule_violations",
                "entitiesAnalyzed",
                "results"),
        SPRING(
                "spring",
                "spring_scan",
                "get_spring_report",
                "get_spring_rule_violations",
                "componentsAnalyzed",
                "results"),
        REST_API(
                "rest-api",
                "rest_api_scan",
                "get_rest_api_report",
                "get_rest_api_rule_violations",
                "handlersAnalyzed",
                "results"),
        SECURITY(
                "security",
                "security_scan",
                "get_security_report",
                "get_security_rule_violations",
                "filterChainsAnalyzed",
                "results"),
        DATABASE(
                "database-advisor",
                "database_advisor_scan",
                "get_database_advisor_report",
                "get_database_advisor_rule_violations",
                "tablesAnalyzed",
                "results");

        private final String policyName;
        private final String scanTool;
        private final String reportTool;
        private final String detailsTool;
        private final String subjectField;
        private final String resultsField;

        Advisor(
                String policyName,
                String scanTool,
                String reportTool,
                String detailsTool,
                String subjectField,
                String resultsField) {
            this.policyName = policyName;
            this.scanTool = scanTool;
            this.reportTool = reportTool;
            this.detailsTool = detailsTool;
            this.subjectField = subjectField;
            this.resultsField = resultsField;
        }

        public String policyName() {
            return policyName;
        }

        public String scanTool() {
            return scanTool;
        }

        public String reportTool() {
            return reportTool;
        }

        public String detailsTool() {
            return detailsTool;
        }

        public String subjectField() {
            return subjectField;
        }

        public String resultsField() {
            return resultsField;
        }

        static Advisor forPolicyName(String name) {
            for (Advisor advisor : values()) {
                if (advisor.policyName.equals(name)) {
                    return advisor;
                }
            }
            throw new IllegalArgumentException("Unsupported advisor in policy: " + name);
        }
    }
}
