package io.github.jdubois.bootui.engine.quarkusapp;

import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import io.github.jdubois.bootui.engine.support.DetailText;
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

    static final Set<String> RETIRED_RULES = Set.of(
            "QA-CDI-001",
            "QA-CFG-001",
            "QA-RX-001",
            "QA-SCH-001",
            "QA-PROD-001",
            "QA-PROF-001",
            "QA-DB-001",
            "QA-WEB-002");
    private static final String GUIDE = "https://quarkus.io/guides/";
    private static final int MAX_SAMPLES = 20;
    private static final Set<String> PRODUCTION_RULES =
            Set.of("QA-CFG-002", "QA-CFG-003", "QA-CFG-005", "QA-PROD-002", "QA-PROD-003", "QA-WEB-001", "QA-WEB-004");
    private static final List<Check> CHECKS = List.of(
            new Check(
                    "QA-CDI-002",
                    "Public state on a singleton REST resource",
                    "CDI",
                    "MEDIUM",
                    "Application REST resources resolved as singletons, the Quarkus REST default, expose potentially"
                            + " mutable public state shared by concurrent requests. No race or actual mutation has been"
                            + " established.",
                    "Keep request-specific data in method-local variables or parameters, and expose shared state only"
                            + " as an immutable value or an encapsulated operation. Interceptor locks do not protect"
                            + " direct public-field access.",
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
                    "Review ownership and access to public state: expose an immutable value or an encapsulated"
                            + " operation. A final reference alone does not establish deep immutability. Do not move the"
                            + " field to a normal scope such as request scope; see QA-CDI-004.",
                    "cdi-reference",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-CDI-004",
                    "Public field on a normal-scoped bean",
                    "CDI",
                    "MEDIUM",
                    "Resolved normal-scoped beans (application, request, session or custom normal scope) declare"
                            + " non-static public fields. CDI treats this as a definition error that ArC does not enforce."
                            + " Injected references are one shared client proxy with its own copy of each field, so"
                            + " direct field access through them does not reach the current contextual instance and,"
                            + " for request or session scope, can expose writes across requests. Field accesses"
                            + " have not been observed.",
                    "Make the field private and access the state through methods of the bean, which the client proxy"
                            + " delegates to the current contextual instance. Package-private visibility alone still"
                            + " permits direct proxy-field access from the same package.",
                    "cdi#client_proxies",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-CDI-005",
                    "Normal-scoped bean has a deferred runtime UnproxyableResolutionException",
                    "CDI",
                    "HIGH",
                    "A resolved normal-scoped application bean or producer return type is unproxyable (sealed, final"
                            + " with quarkus.arc.transform-unproxyable-classes=false, missing a non-private no-arg"
                            + " constructor when ArC cannot synthesize one because its superclass lacks a no-arg"
                            + " constructor, or a concrete java.* class). When not directly injected at a static"
                            + " injection point, ArC defers the failure and throws UnproxyableResolutionException at"
                            + " runtime or skips final java.* methods on the client proxy.",
                    "Add a package-private or public no-arg constructor, remove the final or sealed modifier, expose"
                            + " the bean through an interface, or change @Produces methods returning concrete JDK or"
                            + " unproxyable third-party types from @ApplicationScoped to @Singleton.",
                    "cdi#client_proxies",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-CDI-006",
                    "Application bean declared in the default package",
                    "CDI",
                    "LOW",
                    "Resolved application beans are declared in the unnamed default package. Classes in the default"
                            + " package cannot be imported by classes in named packages and cannot supply a bounded"
                            + " base-package root for static architecture analysis.",
                    "Move application beans into a named package.",
                    "cdi-reference",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-CDI-007",
                    "ArC build-time safety validation is disabled",
                    "CDI",
                    "MEDIUM",
                    "Configured ArC build-time validation disables quarkus.arc.fail-on-intercepted-private-method or"
                            + " quarkus.arc.detect-wrong-annotations. When fail-on-intercepted-private-method is"
                            + " false, interceptor bindings such as @Transactional, @Retry or @RolesAllowed on private"
                            + " methods do not fail the build and are silently skipped at runtime; when"
                            + " detect-wrong-annotations is false, misplaced CDI or Quarkus annotations are not"
                            + " rejected at build time.",
                    "Keep quarkus.arc.fail-on-intercepted-private-method=true and"
                            + " quarkus.arc.detect-wrong-annotations=true (the Quarkus defaults), and change"
                            + " intercepted methods from private to package-private, protected or public.",
                    "cdi-reference",
                    Set.of("disabled", "enabled"),
                    Set.of("disabled")),
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
                    "An observed nonblank declaration uses the deprecated Hibernate database.generation group,"
                            + " deprecated for removal since Quarkus 3.22. One finding is reported per persistence unit"
                            + " and profile.",
                    "Migrate database.generation to schema-management.strategy, database.generation.create-schemas to"
                            + " schema-management.create-schemas and database.generation.halt-on-error to"
                            + " schema-management.halt-on-error, then remove the legacy declarations; an explicit legacy"
                            + " value takes precedence on this Quarkus baseline.",
                    "hibernate-orm",
                    Set.of("legacy", "clean"),
                    Set.of("legacy")),
            new Check(
                    "QA-CFG-005",
                    "Bind-parameter logging in observed production configuration",
                    "Configuration",
                    "HIGH",
                    "A visible production declaration enables Hibernate ORM bind-parameter logging, which can write"
                            + " every bound value, including personal data, credentials or tokens, to the logs. This"
                            + " build-time setting is fixed when the production application is packaged; it does not"
                            + " prove that a future build uses this declaration or that log handling exposes it.",
                    "Remove the production declaration of quarkus.hibernate-orm.log.bind-parameters (and the deprecated"
                            + " log.bind-param); enable it only temporarily outside production.",
                    "hibernate-orm#quarkus-hibernate-orm_quarkus-hibernate-orm-log-bind-parameters",
                    Set.of("true", "false"),
                    Set.of("true")),
            new Check(
                    "QA-CFG-006",
                    "Deprecated Quarkus 3.x configuration property",
                    "Configuration",
                    "LOW",
                    "An observed nonblank configuration declaration uses a deprecated Quarkus 3.x property name across"
                            + " Vert.x worker pool, Hibernate ORM, core logging, SmallRye Health/OpenAPI/Swagger UI,"
                            + " OIDC proxy, or REST Client proxy configuration.",
                    "Migrate each deprecated property to its current Quarkus 3.x replacement (such as"
                            + " quarkus.thread-pool.max-threads, quarkus.hibernate-orm.fetch.batch-size /"
                            + " fetch.max-depth / validation.mode / quote-identifiers.strategy /"
                            + " log.bind-parameters, *.enabled, or named quarkus.proxy.* configurations) and remove"
                            + " the legacy declaration.",
                    "config-reference",
                    Set.of("deprecated", "clean"),
                    Set.of("deprecated")),
            new Check(
                    "QA-CFG-007",
                    "Config mapping unknown-property validation disabled or config value logging enabled",
                    "Configuration",
                    "MEDIUM",
                    "Configuration disables unknown-property validation for @ConfigMapping interfaces"
                            + " (quarkus.config.mapping.validate-unknown=false or"
                            + " smallrye.config.mapping.validate-unknown=false), allowing misspelled or obsolete mapped"
                            + " keys to be silently ignored, or enables quarkus.config.log.values=true in observed"
                            + " production configuration, which logs resolved configuration values at DEBUG level.",
                    "Keep quarkus.config.mapping.validate-unknown and smallrye.config.mapping.validate-unknown enabled"
                            + " (true), and remove quarkus.config.log.values=true from production configuration.",
                    "config-reference",
                    Set.of(
                            "validate-unknown-disabled",
                            "validate-unknown-enabled",
                            "log-values-enabled",
                            "log-values-disabled"),
                    Set.of("validate-unknown-disabled", "log-values-enabled")),
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
                    "QA-DB-002",
                    "Agroal connection pooling or background validation is disabled",
                    "Production",
                    "MEDIUM",
                    "An observed Agroal JDBC datasource disables connection pooling"
                            + " (quarkus.datasource.[<ds>.]jdbc.pooling-enabled=false), opening and closing a physical"
                            + " connection per operation, or disables background connection validation"
                            + " (background-validation-interval=0) without enabling borrow or idle validation"
                            + " (validate-on-borrow=true or a positive foreground-validation-interval).",
                    "Keep quarkus.datasource.[<ds>.]jdbc.pooling-enabled=true and configure a positive"
                            + " background-validation-interval (the Quarkus default is 2M) or"
                            + " foreground-validation-interval so stale connections are evicted.",
                    "datasource",
                    Set.of("pooling-disabled", "validation-disabled", "enabled"),
                    Set.of("pooling-disabled", "validation-disabled")),
            new Check(
                    "QA-WEB-001",
                    "Application-server compression is disabled",
                    "Web",
                    "INFO",
                    "Application-server response compression is disabled. Upstream compression, response media types,"
                            + " and workload have not been inspected.",
                    "Consider enabling application-server compression only if upstream handling and suitable response"
                            + " media types justify it; avoid unnecessary duplicate compression. An explicit"
                            + " quarkus.http.enable-compression=false records that decision and suppresses this prompt.",
                    "http-reference",
                    Set.of("default-disabled", "disabled", "enabled"),
                    Set.of("default-disabled")),
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
                    Set.of("connect-zero", "read-zero", "positive"),
                    Set.of("connect-zero", "read-zero")),
            new Check(
                    "QA-WEB-004",
                    "HTTP request draining is not configured",
                    "Web",
                    "INFO",
                    "The shutdown timeout is known to be absent or non-positive (zero milliseconds). Quarkus does not"
                            + " wait for in-flight HTTP requests when quarkus.shutdown.timeout is unset or zero; zero"
                            + " does not mean waiting without limit. This does not describe completion of all"
                            + " background operations.",
                    "If request draining is needed, configure a positive duration such as quarkus.shutdown.timeout=10s."
                            + " Removing the override does not enable draining.",
                    "http-reference",
                    Set.of("zero", "positive", "absent"),
                    Set.of("zero", "absent")),
            new Check(
                    "QA-WEB-005",
                    "Unlimited HTTP request body or form buffering, or retained multipart uploads",
                    "Web",
                    "LOW",
                    "Configured Quarkus HTTP server limits remove the request body size cap (an explicitly empty"
                            + " quarkus.http.limits.max-body-size=), allow unlimited in-memory form buffering or form"
                            + " attribute counts (max-form-buffered-bytes=-1 or max-form-fields=-1), or retain"
                            + " temporary multipart upload files on disk after the request completes"
                            + " (quarkus.http.body.delete-uploaded-files-on-end=false).",
                    "Configure a finite quarkus.http.limits.max-body-size (the Quarkus default is 10240K), positive"
                            + " bounds for max-form-buffered-bytes and max-form-fields, and keep"
                            + " quarkus.http.body.delete-uploaded-files-on-end=true unless application code explicitly"
                            + " deletes uploaded files in a finally block.",
                    "http-reference",
                    Set.of(
                            "unlimited-body-size",
                            "unlimited-form-buffered-bytes",
                            "unlimited-form-fields",
                            "retained-uploads",
                            "bounded"),
                    Set.of(
                            "unlimited-body-size",
                            "unlimited-form-buffered-bytes",
                            "unlimited-form-fields",
                            "retained-uploads")),
            new Check(
                    "QA-PERF-002",
                    "Synchronized virtual-thread entry method on JDK 21–23",
                    "Performance",
                    "LOW",
                    "Registered entry methods dispatched on virtual threads are synchronized on the running JDK 21–23."
                            + " Blocking while holding the monitor can pin a carrier thread; blocking and pinning"
                            + " have not been observed. JDK 24+ removes this synchronized-related pinning.",
                    "Review whether these entry methods block while synchronized. Consider targeted locking changes"
                            + " such as @io.quarkus.arc.Lock only when justified, or use JDK 24+; do not replace all"
                            + " synchronization indiscriminately.",
                    "virtual-threads",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-PERF-003",
                    "@RunOnVirtualThread on a reactive method",
                    "Performance",
                    "MEDIUM",
                    "A registered REST endpoint or virtual-thread entry method annotated with @RunOnVirtualThread"
                            + " returns a reactive or asynchronous type (Uni, Multi, CompletionStage,"
                            + " CompletableFuture or Publisher). Quarkus invokes the Java method on a virtual thread"
                            + " only to assemble the reactive pipeline, then terminates the virtual thread and"
                            + " executes pipeline stages on the Vert.x event loop.",
                    "Return a synchronous result when using @RunOnVirtualThread so the operation executes on the"
                            + " virtual thread, or remove @RunOnVirtualThread from reactive methods that belong on"
                            + " the event loop.",
                    "virtual-threads",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-PERF-004",
                    "Review virtual threads on a JDK before 24",
                    "Performance",
                    "INFO",
                    "Virtual-thread entry methods (@RunOnVirtualThread) are declared while running on JDK 21–23."
                            + " Before JDK 24 (JEP 491), blocking inside a synchronized method or block (including"
                            + " inside downstream libraries or JDBC drivers) pins the carrier thread. This is a"
                            + " runtime-version review prompt, not observed pinning.",
                    "Prefer JDK 24 or later (JDK 25 is LTS) for virtual-thread workloads, or load-test with JFR"
                            + " jdk.VirtualThreadPinned events and review libraries that block inside synchronized.",
                    "virtual-threads",
                    Set.of(),
                    Set.of()),
            new Check(
                    "QA-PERF-005",
                    "Core worker thread pool misconfiguration",
                    "Performance",
                    "MEDIUM",
                    "Configured worker thread pool settings under quarkus.thread-pool.* set core-threads above"
                            + " max-threads (which EnhancedQueueExecutor silently clamps to max-threads), set a"
                            + " non-positive max-threads, or set shutdown-interrupt greater than or equal to"
                            + " shutdown-timeout (so worker threads are never interrupted before the shutdown"
                            + " timeout expires).",
                    "Ensure quarkus.thread-pool.max-threads is positive and at least quarkus.thread-pool.core-threads,"
                            + " and keep quarkus.thread-pool.shutdown-interrupt strictly shorter than"
                            + " quarkus.thread-pool.shutdown-timeout.",
                    "all-config#quarkus-core_quarkus-thread-pool",
                    Set.of("core-exceeds-max", "max-non-positive", "interrupt-not-before-timeout", "valid"),
                    Set.of("core-exceeds-max", "max-non-positive", "interrupt-not-before-timeout")));

    private QuarkusAppChecks() {}

    static int ruleCount() {
        return CHECKS.size();
    }

    static List<String> ruleIds() {
        return CHECKS.stream().map(Check::id).toList();
    }

    /**
     * One outcome per rule: a rule with findings is a {@code VIOLATION} in {@code findings}, even when its coverage is
     * incomplete, and is never also an analysis error; a rule that could not be evaluated and found nothing is an
     * {@code ERROR} in {@code errors}. {@code limitations} names every incompletely covered rule either way, so an
     * incomplete scan is never reported as complete.
     */
    record Evaluation(
            List<SpringRuleResultDto> findings,
            List<SpringRuleResultDto> errors,
            List<SpringRuleResultDto> skipped,
            List<String> limitations,
            int rulesEvaluated,
            boolean evidenceInspected,
            boolean usable) {

        boolean coverageComplete() {
            return limitations.isEmpty();
        }
    }

    static Evaluation evaluate(QuarkusAppSnapshot snapshot) {
        return evaluate(snapshot, null);
    }

    static Evaluation evaluate(QuarkusAppSnapshot snapshot, AdvisorViolationCollector collector) {
        List<SpringRuleResultDto> findings = new ArrayList<>();
        List<SpringRuleResultDto> errors = new ArrayList<>();
        List<SpringRuleResultDto> skipped = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
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
                    .filter(field -> "SINGLETON".equals(field.scope()))
                    .map(field -> field.className() + "." + field.fieldName())
                    .forEach(resourceFields::add);
        }
        int evaluated = 0;
        boolean usable = false;
        boolean inspected = metadata != null && metadata.available();
        for (Check check : CHECKS) {
            boolean applicableObservation = false;
            List<String> samples = new ArrayList<>();
            String severity = check.severity();
            if (check.configuration()) {
                if ((snapshot == null || !snapshot.evaluatedConfigurationRules().contains(check.id()))
                        && !failures.containsKey(check.id())) {
                    fail(failures, check.id(), Reason.CONFIGURATION_UNAVAILABLE);
                }
                if (snapshot != null && metadata != null && metadata.available()) {
                    if (check.id().equals("QA-CFG-004") && metadata.hibernateOrmSupported()) {
                        applicableObservation = true;
                    } else if (check.id().equals("QA-DB-002") && metadata.jdbcDatasourceSupported()) {
                        applicableObservation = true;
                    } else if (check.id().equals("QA-WEB-003")
                            && metadata.restClientSupported()
                            && !metadata.restClients().isEmpty()) {
                        applicableObservation = true;
                    } else if (check.id().equals("QA-CDI-007") && metadata.beanCount() > 0) {
                        applicableObservation = true;
                    } else if (check.id().equals("QA-CFG-007") && metadata.configMappingCount() > 0) {
                        applicableObservation = true;
                    } else if ((check.id().equals("QA-WEB-005") || check.id().equals("QA-PERF-005"))
                            && metadata.endpointCount() > 0) {
                        applicableObservation = true;
                    }
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
                        applicableObservation = true;
                        if (check.triggers().contains(setting.value())) {
                            samples.add(setting.target() + ": " + setting.value() + " (" + setting.provenance() + ")");
                            if (check.id().equals("QA-PROD-002")
                                    && Set.of("drop", "drop-and-create").contains(setting.value())) {
                                severity = "CRITICAL";
                            }
                        }
                    }
                }
            } else if ((check.id().equals("QA-PERF-002") || check.id().equals("QA-PERF-004"))
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
                if (metadata != null && metadata.available()) {
                    switch (check.id()) {
                        case "QA-PERF-002" -> {
                            if (snapshot.runtimeJdkMajorVersion() <= 0) {
                                fail(failures, check.id(), Reason.RUNTIME_JDK_UNAVAILABLE);
                            } else {
                                samples.addAll(metadata.synchronizedVirtualThreadMethods());
                                applicableObservation = metadata.endpointCount() > 0
                                        || !metadata.synchronizedVirtualThreadMethods()
                                                .isEmpty();
                            }
                        }
                        case "QA-PERF-004" -> {
                            if (snapshot.runtimeJdkMajorVersion() <= 0) {
                                fail(failures, check.id(), Reason.RUNTIME_JDK_UNAVAILABLE);
                            } else {
                                samples.addAll(metadata.virtualThreadMethods());
                                applicableObservation = metadata.endpointCount() > 0
                                        || !metadata.virtualThreadMethods().isEmpty();
                            }
                        }
                        case "QA-PERF-003" -> {
                            samples.addAll(metadata.reactiveVirtualThreadMethods());
                            applicableObservation = metadata.endpointCount() > 0
                                    || !metadata.virtualThreadMethods().isEmpty()
                                    || !metadata.reactiveVirtualThreadMethods().isEmpty();
                        }
                        case "QA-CDI-005" -> {
                            samples.addAll(metadata.unproxyableNormalScopedBeans());
                            applicableObservation = metadata.beanCount() > 0
                                    || !metadata.unproxyableNormalScopedBeans().isEmpty();
                        }
                        case "QA-CDI-006" -> {
                            samples.addAll(metadata.defaultPackageBeans());
                            applicableObservation = metadata.beanCount() > 0
                                    || !metadata.defaultPackageBeans().isEmpty();
                        }
                        default -> {
                            applicableObservation = check.id().equals("QA-CDI-002")
                                    ? metadata.endpointCount() > 0 && metadata.beanCount() > 0
                                    : metadata.beanCount() > 0;
                            for (QuarkusAppMetadata.SharedField field : metadata.sharedFields()) {
                                String fieldRule = fieldRule(field);
                                if (fieldRule == null) {
                                    fail(failures, check.id(), Reason.UNRESOLVED_DECLARATION);
                                    continue;
                                }
                                String identity = field.className() + "." + field.fieldName();
                                if (check.id().equals(fieldRule)
                                        && (!fieldRule.equals("QA-CDI-003") || !resourceFields.contains(identity))) {
                                    samples.add(identity);
                                    applicableObservation = true;
                                }
                            }
                        }
                    }
                }
            }
            List<String> unique = samples.stream().distinct().sorted().toList();
            Set<Reason> reasons = failures.get(check.id());
            if (!unique.isEmpty()) {
                usable = true;
                inspected = true;
                if (collector != null) {
                    collector.record(check.id(), unique.size(), unique, DetailText::sanitize);
                }
                String description = reasons == null
                        ? check.description()
                        : check.description() + " Coverage is incomplete: " + limitation(check, reasons);
                findings.add(check.result(
                        severity,
                        description,
                        "VIOLATION",
                        unique.size(),
                        unique.stream().limit(MAX_SAMPLES).toList()));
            }
            if (reasons != null) {
                // A finding is the rule's outcome; its incomplete coverage stays visible in the limitations.
                if (unique.isEmpty()) {
                    if (reasons.equals(Set.of(Reason.PRODUCTION_UNOBSERVED))) {
                        skipped.add(skipped(check, reasons));
                    } else {
                        errors.add(error(check, reasons));
                    }
                }
                limitations.add(check.id() + ": " + limitation(check, reasons));
            } else {
                evaluated++;
                usable |= applicableObservation;
                inspected = true;
            }
        }
        return new Evaluation(
                List.copyOf(findings),
                List.copyOf(errors),
                List.copyOf(skipped),
                List.copyOf(limitations),
                evaluated,
                inspected,
                usable);
    }

    /** Normal scopes share the client-proxy mechanism; only singleton fields are reached directly. */
    private static String fieldRule(QuarkusAppMetadata.SharedField field) {
        if ("APPLICATION".equals(field.scope()) || "NORMAL".equals(field.scope())) {
            return "QA-CDI-004";
        }
        if ("SINGLETON".equals(field.scope())) {
            return field.resource() ? "QA-CDI-002" : "QA-CDI-003";
        }
        return null;
    }

    private static void recordProblem(Map<String, Set<Reason>> failures, QuarkusAppEvidenceProblem problem) {
        fail(failures, problem.ruleId(), Reason.classify(problem.message()));
    }

    private static void fail(Map<String, Set<Reason>> failures, String ruleId, Reason reason) {
        failures.computeIfAbsent(ruleId, ignored -> EnumSet.noneOf(Reason.class))
                .add(reason);
    }

    private static String limitation(Check check, Set<Reason> reasons) {
        String description = reasons.stream().map(reason -> reason.description).collect(Collectors.joining(" "));
        if (PRODUCTION_RULES.contains(check.id())) {
            description += " Loaded declarations cannot reconstruct a future production deployment's effective"
                    + " configuration, external overrides, or unloaded profile-aware files.";
        }
        return description;
    }

    private static SpringRuleResultDto error(Check check, Set<Reason> reasons) {
        return diagnostic(check, reasons, "ERROR");
    }

    private static SpringRuleResultDto skipped(Check check, Set<Reason> reasons) {
        return diagnostic(check, reasons, "SKIPPED");
    }

    private static SpringRuleResultDto diagnostic(Check check, Set<Reason> reasons, String status) {
        String recommendation =
                "Restore the required application metadata or configuration evidence and run the checks again.";
        if (PRODUCTION_RULES.contains(check.id())) {
            recommendation = "Review loaded production declarations separately from the intended deployment's"
                    + " configuration. " + recommendation;
        }
        return new SpringRuleResultDto(
                check.id(),
                check.name(),
                check.category(),
                check.severity(),
                limitation(check, reasons),
                status,
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
                        "A managed REST client timer could not be classified safely.",
                        "An ArC validation setting could not be classified safely.",
                        "A worker thread pool setting could not be classified safely.",
                        "A configuration validation or logging setting could not be classified safely.",
                        "An Agroal pool setting could not be classified safely.",
                        "An HTTP limit setting could not be classified safely." -> INVALID_CONFIGURATION;
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

        SpringRuleResultDto result(
                String effectiveSeverity, String effectiveDescription, String status, int count, List<String> samples) {
            return new SpringRuleResultDto(
                    id,
                    name,
                    category,
                    effectiveSeverity,
                    effectiveDescription,
                    status,
                    count,
                    samples,
                    recommendation,
                    GUIDE + guide);
        }
    }
}
