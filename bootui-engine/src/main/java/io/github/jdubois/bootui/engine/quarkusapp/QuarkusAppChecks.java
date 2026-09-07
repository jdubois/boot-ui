package io.github.jdubois.bootui.engine.quarkusapp;

import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.spi.QuarkusAppEvidenceProblem;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot.Setting;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Fixed, evidence-based Quarkus application checks. Retired identifiers are never reused. */
final class QuarkusAppChecks {

    private static final String GUIDE = "https://quarkus.io/guides/";
    private static final int MAX_SAMPLES = 20;
    private static final Set<String> PRODUCTION_RULES =
            Set.of("QA-CFG-002", "QA-CFG-003", "QA-PROD-002", "QA-PROD-003");
    private static final List<Check> CHECKS = List.of(
            new Check(
                    "QA-CDI-001",
                    "Public state on an application-scoped bean",
                    "CDI",
                    "LOW",
                    "Resolved application-scoped beans expose potentially mutable public state. This is a review"
                            + " prompt, not evidence of concurrent mutation or a data race.",
                    "Review ownership and access to public state. Encapsulate it or use request scope when appropriate;"
                            + " a final reference alone does not establish deep immutability.",
                    "cdi-reference",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-CDI-002",
                    "Public state on a shared REST resource",
                    "CDI",
                    "MEDIUM",
                    "Application REST resources with a resolved shared scope expose potentially mutable public state."
                            + " Concurrent access is possible; no race or actual mutation has been established.",
                    "Review state ownership and concurrent access. Prefer request-local state where appropriate;"
                            + " interceptor locks do not protect direct public-field access.",
                    "cdi-reference",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-CDI-003",
                    "Public state on a singleton bean",
                    "CDI",
                    "LOW",
                    "Resolved singleton beans expose potentially mutable public state. This is a review prompt,"
                            + " not evidence of concurrent mutation or a data race.",
                    "Review ownership and access to public state. Encapsulate it or use request scope when appropriate;"
                            + " a final reference alone does not establish deep immutability.",
                    "cdi-reference",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-CFG-002",
                    "SQL logging in observed production configuration",
                    "Configuration",
                    "MEDIUM",
                    "Observed production configuration enables SQL logging. SQL literals can contain application data;"
                            + " this does not establish that bind-parameter logging is enabled or prove a deployed setting.",
                    "Review whether SQL logging is appropriate for production and its log handling requirements.",
                    "hibernate-orm",
                    Set.of("true", "false"),
                    Set.of("true")),
            new Check(
                    "QA-CFG-003",
                    "Verbose root logging in observed production configuration",
                    "Configuration",
                    "MEDIUM",
                    "Observed production configuration selects verbose root logging. This increases verbosity;"
                            + " it does not prove a leak, measured overhead, or a future deployment's effective setting.",
                    "Review the root logging level for the intended production workload and log handling policy.",
                    "logging",
                    Set.of("verbose", "normal"),
                    Set.of("verbose")),
            new Check(
                    "QA-CFG-004",
                    "Legacy schema-generation property",
                    "Configuration",
                    "LOW",
                    "An observed nonblank declaration uses the deprecated Hibernate database.generation property.",
                    "Migrate to schema-management.strategy and remove the legacy declaration after review;"
                            + " the explicit legacy property takes precedence on this Quarkus baseline.",
                    "hibernate-orm",
                    Set.of("legacy"),
                    Set.of("legacy")),
            new Check(
                    "QA-PROD-002",
                    "Automatic schema changes in observed production configuration",
                    "Production",
                    "HIGH",
                    "Observed production configuration requests automatic schema changes. create is create-only, not"
                            + " a drop operation; update alters the schema. drop and drop-and-create can destroy data."
                            + " These declarations do not prove a future deployment's effective configuration.",
                    "Review schema creation and alteration before production use; prefer reviewed migrations where"
                            + " appropriate. Use none or validate when automatic changes are not intended.",
                    "hibernate-orm",
                    Set.of("create", "update", "drop", "drop-and-create", "none", "validate"),
                    Set.of("create", "update", "drop", "drop-and-create")),
            new Check(
                    "QA-PROD-003",
                    "In-memory storage in observed production configuration",
                    "Production",
                    "MEDIUM",
                    "An allowlisted in-memory JDBC URL form was observed in production configuration."
                            + " Transient storage may be intentional; database kind alone does not prove volatile storage,"
                            + " and network-accessible in-memory databases may be shared.",
                    "Confirm that transient storage matches the intended durability requirements.",
                    "datasource",
                    Set.of("in-memory", "persistent-or-unclassified"),
                    Set.of("in-memory")),
            new Check(
                    "QA-WEB-001",
                    "Application-server compression is disabled",
                    "Web",
                    "INFO",
                    "Application-server response compression is disabled. Upstream compression, response media types,"
                            + " and workload have not been inspected.",
                    "Consider enabling application-server compression only if upstream handling and suitable response"
                            + " media types justify it; avoid unnecessary duplicate compression.",
                    "http-reference",
                    Set.of("default-disabled", "disabled", "enabled"),
                    Set.of("default-disabled", "disabled")),
            new Check(
                    "QA-WEB-002",
                    "HTTP request-draining timeout is zero",
                    "Web",
                    "MEDIUM",
                    "The configured zero shutdown timeout disables waiting for in-flight HTTP requests."
                            + " This is not a statement about completion of every background operation.",
                    "Set a positive supported duration, for example quarkus.shutdown.timeout=10s, if HTTP request"
                            + " draining is needed. Removing the override does not enable draining.",
                    "http-reference",
                    Set.of("zero", "positive", "absent"),
                    Set.of("zero")),
            new Check(
                    "QA-WEB-003",
                    "Registered REST client timer is disabled",
                    "Web",
                    "MEDIUM",
                    "An effective connect or read timer is explicitly zero for an observed registered REST client."
                            + " That timer is disabled; other application deadlines and custom transports are not assessed.",
                    "Use a positive finite timer appropriate for the remote service, or restore the standard"
                            + " Quarkus defaults (15s connect / 30s read). Long finite timers are not inherently invalid.",
                    "rest-client",
                    Set.of("connect-zero", "read-zero"),
                    Set.of("connect-zero", "read-zero")),
            new Check(
                    "QA-WEB-004",
                    "HTTP request draining is not configured",
                    "Web",
                    "INFO",
                    "The shutdown timeout is known to be absent. By default, Quarkus does not wait for in-flight"
                            + " HTTP requests; this does not describe completion of all background operations.",
                    "If request draining is needed, configure a positive duration such as quarkus.shutdown.timeout=10s.",
                    "http-reference",
                    Set.of("zero", "positive", "absent"),
                    Set.of("absent")),
            new Check(
                    "QA-PERF-002",
                    "Synchronized virtual-thread entry method on JDK 21–23",
                    "Performance",
                    "LOW",
                    "Registered REST methods dispatched on virtual threads are synchronized on the running JDK 21–23."
                            + " Blocking while holding the monitor can pin a carrier thread; blocking and pinning"
                            + " have not been observed. JDK 24+ removes this synchronized-related pinning.",
                    "Review whether these entry methods block while synchronized. Consider targeted locking changes"
                            + " only when justified, or use JDK 24+; do not replace all synchronization indiscriminately.",
                    "virtual-threads",
                    Set.of(),
                    Set.of()));

    private QuarkusAppChecks() {}

    static int ruleCount() {
        return CHECKS.size();
    }

    static List<String> ruleIds() {
        return CHECKS.stream().map(Check::id).toList();
    }

    record Evaluation(
            List<SpringRuleResultDto> findings,
            List<SpringRuleResultDto> errors,
            int rulesEvaluated,
            boolean evidenceInspected) {}

    static Evaluation evaluate(QuarkusAppSnapshot snapshot) {
        List<SpringRuleResultDto> findings = new ArrayList<>();
        List<SpringRuleResultDto> errors = new ArrayList<>();
        Map<String, Set<Reason>> failures = new HashMap<>();
        QuarkusAppMetadata metadata = snapshot == null ? null : snapshot.metadata();
        if (snapshot != null) {
            snapshot.problems().forEach(problem -> recordProblem(failures, problem));
        }
        if (metadata != null) {
            metadata.problems().forEach(problem -> recordProblem(failures, problem));
        }
        Set<String> resourceFields = new HashSet<>();
        if (metadata != null) {
            metadata.sharedFields().stream()
                    .filter(QuarkusAppMetadata.SharedField::resource)
                    .filter(field -> "APPLICATION".equals(field.scope()) || "SINGLETON".equals(field.scope()))
                    .map(field -> field.className() + "." + field.fieldName())
                    .forEach(resourceFields::add);
        }
        int evaluated = 0;
        boolean inspected = metadata != null && metadata.available();
        for (Check check : CHECKS) {
            List<String> samples = new ArrayList<>();
            String severity = check.severity();
            if (check.configuration()) {
                if ((snapshot == null || !snapshot.evaluatedConfigurationRules().contains(check.id()))
                        && !failures.containsKey(check.id())) {
                    fail(failures, check.id(), Reason.CONFIGURATION_UNAVAILABLE);
                }
                if (snapshot != null) {
                    for (Setting setting :
                            snapshot.settings().stream().distinct().toList()) {
                        if (!check.id().equals(setting.ruleId())) {
                            continue;
                        }
                        if (setting.value() == null || !check.accepted().contains(setting.value())) {
                            fail(failures, check.id(), Reason.INVALID_CONFIGURATION);
                            continue;
                        }
                        inspected = true;
                        if (check.triggers().contains(setting.value())) {
                            samples.add(setting.target() + ": " + setting.value() + " (" + setting.provenance() + ")");
                            if (check.id().equals("QA-PROD-002")
                                    && Set.of("drop", "drop-and-create").contains(setting.value())) {
                                severity = "CRITICAL";
                            }
                        }
                    }
                }
            } else if (check.id().equals("QA-PERF-002")
                    && snapshot != null
                    && snapshot.runtimeJdkMajorVersion() > 0
                    && (snapshot.runtimeJdkMajorVersion() < 21 || snapshot.runtimeJdkMajorVersion() >= 24)) {
                // The running JDK establishes non-applicability, independently of the augmentation JDK.
                failures.remove(check.id());
                inspected = true;
            } else {
                if (metadata == null || !metadata.available()) {
                    fail(failures, check.id(), Reason.METADATA_UNAVAILABLE);
                }
                if (metadata != null) {
                    if (check.id().equals("QA-PERF-002")) {
                        if (snapshot.runtimeJdkMajorVersion() <= 0) {
                            fail(failures, check.id(), Reason.RUNTIME_JDK_UNAVAILABLE);
                        } else {
                            samples.addAll(metadata.synchronizedVirtualThreadMethods());
                        }
                    } else {
                        for (QuarkusAppMetadata.SharedField field : metadata.sharedFields()) {
                            if (!"APPLICATION".equals(field.scope()) && !"SINGLETON".equals(field.scope())) {
                                fail(failures, check.id(), Reason.UNRESOLVED_DECLARATION);
                                continue;
                            }
                            String fieldRule = field.resource()
                                    ? "QA-CDI-002"
                                    : "APPLICATION".equals(field.scope()) ? "QA-CDI-001" : "QA-CDI-003";
                            String identity = field.className() + "." + field.fieldName();
                            if (check.id().equals(fieldRule)
                                    && (field.resource() || !resourceFields.contains(identity))) {
                                samples.add(identity);
                            }
                        }
                    }
                }
            }
            List<String> unique = samples.stream().distinct().sorted().toList();
            if (!unique.isEmpty()) {
                inspected = true;
                findings.add(check.result(
                        severity,
                        "VIOLATION",
                        unique.size(),
                        unique.stream().limit(MAX_SAMPLES).toList()));
            }
            if (failures.containsKey(check.id())) {
                errors.add(error(check, failures.get(check.id())));
            } else {
                evaluated++;
                inspected = true;
            }
        }
        return new Evaluation(List.copyOf(findings), List.copyOf(errors), evaluated, inspected);
    }

    private static void recordProblem(Map<String, Set<Reason>> failures, QuarkusAppEvidenceProblem problem) {
        fail(failures, problem.ruleId(), Reason.classify(problem.message()));
    }

    private static void fail(Map<String, Set<Reason>> failures, String ruleId, Reason reason) {
        failures.computeIfAbsent(ruleId, ignored -> EnumSet.noneOf(Reason.class))
                .add(reason);
    }

    private static SpringRuleResultDto error(Check check, Set<Reason> reasons) {
        String description = reasons.stream().map(reason -> reason.description).collect(Collectors.joining(" "));
        String recommendation =
                "Restore the required application metadata or configuration evidence and run the checks again.";
        if (PRODUCTION_RULES.contains(check.id())) {
            description += " Loaded declarations cannot reconstruct a future production deployment's effective"
                    + " configuration, external overrides, or unloaded profile-aware files.";
            recommendation = "Review loaded production declarations separately from the intended deployment's"
                    + " configuration. " + recommendation;
        }
        return new SpringRuleResultDto(
                check.id(),
                check.name(),
                check.category(),
                check.severity(),
                description,
                "ERROR",
                0,
                List.of(),
                recommendation,
                GUIDE + check.guide());
    }

    private enum Reason {
        PRODUCTION_UNOBSERVED(
                "Only loaded production declarations were inspected; effective production coverage is unavailable."),
        DECLARATION_LIMIT(
                "Application declaration collection reached its safety limit; metadata coverage is incomplete."),
        CONFIGURATION_LIMIT(
                "Configuration discovery reached an inspection limit; configuration coverage is incomplete."),
        CLIENT_LIMIT("REST client discovery reached its inspection limit; client coverage is incomplete."),
        UNRESOLVED_DECLARATION("Application declaration metadata could not be resolved."),
        INVALID_RESOURCE("The application declaration resource is invalid or unreadable."),
        METADATA_UNAVAILABLE("Required application declaration metadata is unavailable."),
        CAPABILITY_UNAVAILABLE("Required ORM or JDBC capability evidence is unavailable."),
        CLIENT_UNAVAILABLE("REST client registration evidence is unavailable."),
        CONFIGURATION_UNAVAILABLE("Required configuration evidence could not be completely read or converted."),
        INVALID_CONFIGURATION("A required configuration value could not be classified safely."),
        RUNTIME_JDK_UNAVAILABLE("The running JDK version could not be determined."),
        UNKNOWN("Required evidence could not be completely inspected for this rule.");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        static Reason classify(String message) {
            if (message == null) {
                return UNKNOWN;
            }
            // Match adapter-owned literals only. Never copy arbitrary evidence or exception text into reports.
            return switch (message) {
                case "Only loaded production declarations were inspected; effective production configuration, "
                        + "external overrides and unloaded profile-aware files are unavailable." ->
                    PRODUCTION_UNOBSERVED;
                case "Application declaration collection reached its safety limit." -> DECLARATION_LIMIT;
                case "Configuration name discovery reached its inspection limit.",
                        "A configuration name could not be inspected within the name limit.",
                        "Production source discovery reached its inspection limit." -> CONFIGURATION_LIMIT;
                case "REST client discovery reached its inspection limit." -> CLIENT_LIMIT;
                case "Application declaration metadata could not be resolved." -> UNRESOLVED_DECLARATION;
                case "Application declaration resource is invalid or unreadable." -> INVALID_RESOURCE;
                case "ORM and JDBC capability evidence is unavailable." -> CAPABILITY_UNAVAILABLE;
                case "REST client registration evidence is unavailable." -> CLIENT_UNAVAILABLE;
                case "Required configuration evidence could not be read or converted." -> CONFIGURATION_UNAVAILABLE;
                case "A schema action could not be classified safely.",
                        "The logging level could not be classified safely.",
                        "The shutdown duration is invalid.",
                        "A managed REST client timer could not be classified safely." -> INVALID_CONFIGURATION;
                default -> UNKNOWN;
            };
        }
    }

    private record Check(
            String id,
            String name,
            String category,
            String severity,
            String description,
            String recommendation,
            String guide,
            Set<String> accepted,
            Set<String> triggers) {
        boolean configuration() {
            return !accepted.isEmpty();
        }

        SpringRuleResultDto result(String effectiveSeverity, String status, int count, List<String> samples) {
            return new SpringRuleResultDto(
                    id,
                    name,
                    category,
                    effectiveSeverity,
                    description,
                    status,
                    count,
                    samples,
                    recommendation,
                    GUIDE + guide);
        }
    }
}
