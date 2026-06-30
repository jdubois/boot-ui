package io.github.jdubois.bootui.engine.quarkusapp;

import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed Quarkus-native application advisor ruleset (see {@code docs/QUARKUS-ADVISOR-CHECKS.md}). Each rule
 * inspects the neutral {@link QuarkusAppSnapshot} and, when triggered, emits one {@link SpringRuleResultDto}
 * with status {@code VIOLATION}. The full set evaluated is {@link #ruleCount()}; only violations are returned.
 * All signals are build-time computable (CDI scopes, {@code @ConfigProperty}, JAX-RS signatures, profiles) —
 * none require live runtime state or the network.
 */
final class QuarkusAppChecks {

    private static final String VIOLATION = "VIOLATION";
    private static final int RULE_COUNT = 7;
    private static final String GUIDE = "https://quarkus.io/guides/cdi-reference";
    private static final String CONFIG_GUIDE = "https://quarkus.io/guides/config-reference";
    private static final String REACTIVE_GUIDE = "https://quarkus.io/guides/getting-started-reactive";
    private static final String PROFILE_GUIDE = "https://quarkus.io/guides/config-reference#profiles";

    private QuarkusAppChecks() {}

    static int ruleCount() {
        return RULE_COUNT;
    }

    static List<SpringRuleResultDto> evaluate(QuarkusAppSnapshot s) {
        List<SpringRuleResultDto> v = new ArrayList<>();

        if (!s.mutableAppScopedFields().isEmpty()) {
            v.add(rule(
                    "QA-CDI-001",
                    "Shared mutable state on @ApplicationScoped bean",
                    "CDI",
                    "MEDIUM",
                    "@ApplicationScoped beans are single instances shared across threads; public or non-final fields"
                            + " hold unsynchronised shared state.",
                    s.mutableAppScopedFields().size(),
                    s.mutableAppScopedFields(),
                    "Make fields private final, or move per-request state to a @RequestScoped bean.",
                    GUIDE));
        }
        if (s.defaultScopeResourceCount() > 0) {
            v.add(rule(
                    "QA-CDI-002",
                    "JAX-RS resource without an explicit scope",
                    "CDI",
                    "LOW",
                    "JAX-RS resources without an explicit CDI scope default to @Singleton, which can surprise"
                            + " developers expecting per-request beans.",
                    s.defaultScopeResourceCount(),
                    List.of(s.defaultScopeResourceCount()
                            + " resource class(es) with no @ApplicationScoped/@RequestScoped"),
                    "Annotate resources with @ApplicationScoped (stateless) or @RequestScoped (per-request).",
                    GUIDE));
        }
        if (s.beanCount() > 0 && s.configPropertyCount() == 0) {
            v.add(rule(
                    "QA-CFG-001",
                    "No @ConfigProperty usage",
                    "Config",
                    "LOW",
                    "The app declares no @ConfigProperty injection sites, suggesting configuration is read ad hoc"
                            + " rather than through type-safe MicroProfile Config.",
                    1,
                    List.of("0 @ConfigProperty injection sites"),
                    "Inject configuration with @ConfigProperty or a @ConfigMapping interface.",
                    CONFIG_GUIDE));
        }
        if (s.reactiveEndpointCount() > 0 && s.blockingAnnotationCount() == 0) {
            v.add(rule(
                    "QA-RX-001",
                    "Reactive endpoints without @Blocking guards",
                    "Reactive",
                    "INFO",
                    "Endpoints return Uni/Multi but no @Blocking is declared; confirm no blocking call runs on the"
                            + " I/O thread inside those handlers.",
                    s.reactiveEndpointCount(),
                    List.of(s.reactiveEndpointCount() + " reactive endpoint(s), 0 @Blocking"),
                    "Annotate blocking work with @Blocking, or keep handlers non-blocking.",
                    REACTIVE_GUIDE));
        }
        if (s.prodDevServicesEnabled()) {
            v.add(rule(
                    "QA-PROD-001",
                    "Dev Services enabled in the prod profile",
                    "Profiles",
                    "HIGH",
                    "A %prod.*devservices.enabled=true key would start throwaway containers in production.",
                    s.prodProfileKeys().size(),
                    List.of("%prod devservices.enabled=true"),
                    "Remove the %prod devservices override; configure a real datasource/broker for prod.",
                    PROFILE_GUIDE));
        }
        if (s.activeProfiles().isEmpty() && s.prodProfileKeys().isEmpty()) {
            v.add(rule(
                    "QA-PROF-001",
                    "No profile configuration",
                    "Profiles",
                    "LOW",
                    "No active profile and no %prod. overrides were found; production likely shares dev defaults.",
                    1,
                    List.of("no %prod./%dev. keys, no active profile"),
                    "Add %prod. overrides for production-specific config (datasource, logging, dev services).",
                    PROFILE_GUIDE));
        }
        if (s.endpointCount() > 0 && s.beanCount() == 0) {
            v.add(rule(
                    "QA-EP-001",
                    "Endpoints without managed beans",
                    "CDI",
                    "LOW",
                    "JAX-RS endpoints exist but no CDI beans were discovered; logic may live outside Arc's lifecycle.",
                    s.endpointCount(),
                    List.of(s.endpointCount() + " endpoints, 0 managed beans"),
                    "Move business logic into @ApplicationScoped beans injected into resources.",
                    GUIDE));
        }
        return v;
    }

    private static SpringRuleResultDto rule(
            String id,
            String name,
            String category,
            String severity,
            String description,
            int count,
            List<String> samples,
            String recommendation,
            String learnMore) {
        return new SpringRuleResultDto(
                id, name, category, severity, description, VIOLATION, count, samples, recommendation, learnMore);
    }
}
