package io.github.jdubois.bootui.quarkus.hibernate;

import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.microprofile.config.Config;

/**
 * Translates the engine Hibernate advisor's configuration keys into the {@code quarkus.hibernate-orm.*}
 * namespace, so the shared {@code HibernateScanner}'s config-driven rules evaluate correctly on Quarkus.
 *
 * <p>The engine rules read Spring / native-Hibernate property keys ({@code spring.jpa.*},
 * {@code hibernate.*}); Quarkus does not expose those keys through MicroProfile Config and uses its own
 * {@code quarkus.hibernate-orm.*} surface instead. A naive {@code config.getOptionalValue(key, ...)} would
 * therefore make every config rule a silent false-negative. This lookup ("swap the API" — the engine rules
 * stay unchanged) maps the high-confidence keys to their Quarkus equivalents, and additionally:</p>
 *
 * <ul>
 *   <li><strong>Translates the {@code ddl-auto} value</strong> {@code drop-and-create} &rarr;
 *       {@code create-drop} so the engine's {@code RISKY_VALUES}/{@code creates} classification (which only
 *       knows the JPA value vocabulary) flags Quarkus' riskiest schema-generation setting. Other generation
 *       values pass through unchanged ({@code create}/{@code update} are already risky; {@code none}/
 *       {@code validate} are safe).</li>
 *   <li><strong>Neutralizes the Spring-only Open-Session-in-View concept</strong>: Quarkus has no OSIV, so
 *       {@code spring.jpa.open-in-view} resolves to {@code "false"} (its effective state). Without this the
 *       {@code HIB-CONFIG-001} rule — which treats an <em>absent</em> value as Spring Boot's web default of
 *       {@code true} — would render a hard false-positive on every Quarkus scan.</li>
 *   <li><strong>Reports Hibernate bytecode enhancement as always enabled</strong>: Quarkus enhances every entity
 *       at build time unconditionally (see {@code HibernateOrmProcessor.enhancerDomainObjects()}, an ungated
 *       build step) — there is no {@code quarkus.hibernate-orm.enhancement.*} switch to turn it off. The engine's
 *       {@code HIB-MAP-017}/{@code HIB-MAP-018} rules read the enable-lazy-initialization keys above to decide
 *       whether the enhancer is active; without this mapping they would read {@code null} (falsy) and flag every
 *       lazy/non-owning {@code @OneToOne} as a false positive on every Quarkus scan.</li>
 *   <li><strong>Maps batch fetching, JDBC time zone, statistics, IN-clause padding, pagination-over-collection
 *       and second-level/query caching</strong> to their real {@code quarkus.hibernate-orm.*} equivalents (all
 *       confirmed by decompiling the {@code quarkus-hibernate-orm}/{@code -deployment} 3.33.2.1 jars — see the
 *       per-alias notes on {@link #KEY_ALIASES}). Before this mapping existed, {@code HIB-FETCH-002} and
 *       {@code HIB-CONFIG-007}/{@code -009}/{@code -010}/{@code -011}/{@code -013}/{@code -016} could never
 *       observe an operator's setting on Quarkus, no matter which native property name they used.</li>
 *   <li><strong>Reads the Quarkus-native {@code quarkus.hibernate-orm.log.bind-parameters} convenience
 *       flag</strong> (and its deprecated {@code .bind-param} alias) as the synthetic {@code "trace"} value of
 *       the neutral {@code logging.level.org.hibernate.orm.jdbc.bind} key, matching Hibernate's own
 *       {@code JdbcBindingLogging}, which only ever logs bound parameter values when
 *       {@code Logger.isTraceEnabled()} — so {@code HIB-CONFIG-018} sees bind-parameter logging as enabled
 *       however the operator configured it.</li>
 *   <li><strong>Falls back to the {@code quarkus.hibernate-orm.unsupported-properties."..."} escape
 *       hatch</strong> for any {@code hibernate.*} key with no first-class Quarkus config option. Decompiling
 *       {@code FastBootMetadataBuilder#createBuildTimeSettings} confirms Quarkus merges this map verbatim into
 *       Hibernate's real bootstrap settings, so a key set this way genuinely reaches Hibernate (unlike a bare,
 *       un-namespaced property in {@code application.properties}, which Quarkus never forwards). This is how an
 *       operator's {@code hibernate.order_inserts}/{@code order_updates} (HIB-CONFIG-005) or
 *       {@code hibernate.cache.region.factory_class} (HIB-CONFIG-010) setting can be read back even though
 *       Quarkus has no dedicated config property for either — see {@link #unsupportedPropertiesFallback}.</li>
 * </ul>
 *
 * <p>Every other engine key falls through to a raw MicroProfile Config read, which returns {@code null} for
 * the Spring-only concerns the engine also probes (Hikari auto-commit, JTA, defer-datasource-initialization)
 * — correctly leaving those rules inert on Quarkus. A few config rules genuinely have no
 * {@code quarkus.hibernate-orm.*} equivalent <em>and</em> no realistic {@code unsupported-properties} path
 * either, because they key off a Hikari-specific property Agroal has no equivalent for at all (
 * {@code spring.datasource.hikari.auto-commit}, read directly by {@code HIB-CONFIG-008}) or a pool
 * implementation-specific concept (Hibernate's built-in pool vs. Agroal, {@code HIB-CONFIG-014}'s
 * {@code hibernate.connection.pool_size} advice still applies verbatim since that property, if force-set via
 * {@code unsupported-properties}, reaches Hibernate exactly as documented — only the Hikari-specific
 * auto-commit *signal* this rule also inspects has no Agroal analogue). That bounded limitation is documented
 * in {@code docs/FEATURES.md}.</p>
 */
public final class QuarkusHibernatePropertyLookup implements Function<String, String> {

    // Quarkus 3.33 renamed quarkus.hibernate-orm.database.generation to
    // quarkus.hibernate-orm.schema-management.strategy (same value vocabulary); the legacy key is still
    // honored but deprecated. We read the new key first and fall back to the legacy one (see schemaStrategy()).
    static final String SCHEMA_STRATEGY_KEY = "quarkus.hibernate-orm.schema-management.strategy";

    static final String LEGACY_GENERATION_KEY = "quarkus.hibernate-orm.database.generation";

    private static final String OPEN_IN_VIEW_KEY = "spring.jpa.open-in-view";

    // The engine's HibernateContext.isHibernateEnhancementEnabled() ORs these four keys together to decide
    // whether Hibernate bytecode enhancement is active. Quarkus enhances every entity unconditionally at build
    // time (HibernateOrmProcessor.enhancerDomainObjects() is an ungated @BuildStep) and exposes no config
    // property to disable it, so all four report "true" regardless of what (if anything) is actually set.
    private static final Set<String> ENHANCEMENT_ENABLED_KEYS = Set.of(
            "spring.jpa.properties.hibernate.enhancer.enableLazyInitialization",
            "hibernate.enhancer.enableLazyInitialization",
            "spring.jpa.properties.hibernate.bytecode.enhancer.enableLazyInitialization",
            "hibernate.bytecode.enhancer.enableLazyInitialization");

    // The engine's HibernateContext exposes bind-parameter-value logging as the neutral property key
    // "logging.level.org.hibernate.orm.jdbc.bind", checked for a "trace" value (Hibernate's own
    // JdbcBindingLogging only ever logs bound values when Logger.isTraceEnabled()). Quarkus offers a
    // dedicated convenience flag instead of a log-category level; either name enables it (confirmed via the
    // quarkus-hibernate-orm-deployment 3.33.2.1 config model: both quarkus.hibernate-orm.log.bind-param
    // (deprecated) and .bind-parameters (current) exist, and HibernateOrmConfigLog#isAnyPropertySet() ORs
    // them together).
    private static final String BIND_PARAMETER_LOGGING_KEY = "logging.level.org.hibernate.orm.jdbc.bind";

    private static final String BIND_PARAMETERS_PROPERTY = "quarkus.hibernate-orm.log.bind-parameters";

    private static final String LEGACY_BIND_PARAM_PROPERTY = "quarkus.hibernate-orm.log.bind-param";

    // Hibernate property keys have no first-class quarkus.hibernate-orm.* option but ARE genuinely readable
    // if an operator sets them through Quarkus' generic "unsupported properties" escape hatch, which
    // FastBootMetadataBuilder merges verbatim into Hibernate's real bootstrap settings
    // (RecordedConfig.getQuarkusConfigUnsupportedProperties() -> Map.putAll(...), confirmed by decompiling
    // quarkus-hibernate-orm-3.33.2.1.jar). unsupportedPropertiesFallback() below tries this for any
    // otherwise-unmapped hibernate.* key.
    private static final String UNSUPPORTED_PROPERTIES_PREFIX = "quarkus.hibernate-orm.unsupported-properties.\"";

    private static final String SPRING_JPA_PROPERTIES_PREFIX = "spring.jpa.properties.";

    // second-level caching: Quarkus has a single quarkus.hibernate-orm.second-level-caching-enabled toggle,
    // not one property per Hibernate cache setting. Decompiling HibernateProcessorUtil#configureCaching()
    // confirms this ONE boolean drives both hibernate.cache.use_second_level_cache AND
    // hibernate.cache.use_query_cache via Properties#putIfAbsent(...); there is no separate
    // "query-cache-enabled" property in Quarkus (verified against the generated config-doc model — only
    // second-level-caching-enabled exists).
    private static final String SECOND_LEVEL_CACHING_KEY = "quarkus.hibernate-orm.second-level-caching-enabled";

    // Engine key -> quarkus.hibernate-orm.* equivalent. Every mapping below is verified against the actual
    // quarkus-hibernate-orm{,-deployment} 3.33.2.1 jars (bytecode disassembly plus the generated
    // META-INF/quarkus-config-doc/quarkus-config-model.json), not just documentation.
    private static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("spring.jpa.hibernate.ddl-auto", SCHEMA_STRATEGY_KEY),
            Map.entry("spring.jpa.properties.hibernate.hbm2ddl.auto", SCHEMA_STRATEGY_KEY),
            Map.entry("hibernate.hbm2ddl.auto", SCHEMA_STRATEGY_KEY),
            Map.entry("spring.jpa.show-sql", "quarkus.hibernate-orm.log.sql"),
            Map.entry("hibernate.show_sql", "quarkus.hibernate-orm.log.sql"),
            Map.entry("hibernate.format_sql", "quarkus.hibernate-orm.log.format-sql"),
            Map.entry("spring.jpa.properties.hibernate.format_sql", "quarkus.hibernate-orm.log.format-sql"),
            Map.entry("hibernate.jdbc.batch_size", "quarkus.hibernate-orm.jdbc.statement-batch-size"),
            Map.entry(
                    "spring.jpa.properties.hibernate.jdbc.batch_size",
                    "quarkus.hibernate-orm.jdbc.statement-batch-size"),
            Map.entry("hibernate.default_batch_fetch_size", "quarkus.hibernate-orm.fetch.batch-size"),
            Map.entry(
                    "spring.jpa.properties.hibernate.default_batch_fetch_size",
                    "quarkus.hibernate-orm.fetch.batch-size"),
            Map.entry("hibernate.jdbc.time_zone", "quarkus.hibernate-orm.jdbc.timezone"),
            Map.entry("spring.jpa.properties.hibernate.jdbc.time_zone", "quarkus.hibernate-orm.jdbc.timezone"),
            Map.entry("hibernate.generate_statistics", "quarkus.hibernate-orm.statistics"),
            Map.entry("spring.jpa.properties.hibernate.generate_statistics", "quarkus.hibernate-orm.statistics"),
            Map.entry(
                    "hibernate.query.fail_on_pagination_over_collection_fetch",
                    "quarkus.hibernate-orm.query.fail-on-pagination-over-collection-fetch"),
            Map.entry(
                    "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch",
                    "quarkus.hibernate-orm.query.fail-on-pagination-over-collection-fetch"),
            Map.entry(
                    "hibernate.query.in_clause_parameter_padding",
                    "quarkus.hibernate-orm.query.in-clause-parameter-padding"),
            Map.entry(
                    "spring.jpa.properties.hibernate.query.in_clause_parameter_padding",
                    "quarkus.hibernate-orm.query.in-clause-parameter-padding"),
            Map.entry("hibernate.cache.use_query_cache", SECOND_LEVEL_CACHING_KEY),
            Map.entry("spring.jpa.properties.hibernate.cache.use_query_cache", SECOND_LEVEL_CACHING_KEY),
            Map.entry("hibernate.cache.use_second_level_cache", SECOND_LEVEL_CACHING_KEY),
            Map.entry("spring.jpa.properties.hibernate.cache.use_second_level_cache", SECOND_LEVEL_CACHING_KEY));

    private final Config config;

    public QuarkusHibernatePropertyLookup(Config config) {
        this.config = config;
    }

    @Override
    public String apply(String key) {
        if (HibernateScanner.OPEN_IN_VIEW_APPLICABLE_PROPERTY.equals(key)) {
            return "false";
        }
        if (OPEN_IN_VIEW_KEY.equals(key)) {
            // Quarkus has no Open-Session-in-View; report its effective state so HIB-CONFIG-001 passes
            // rather than assuming Spring Boot's enabled-by-default.
            return "false";
        }
        if (ENHANCEMENT_ENABLED_KEYS.contains(key)) {
            return "true";
        }
        if (BIND_PARAMETER_LOGGING_KEY.equals(key)) {
            return isBindParameterLoggingEnabled() ? "trace" : raw(key);
        }
        String quarkusKey = KEY_ALIASES.get(key);
        if (SCHEMA_STRATEGY_KEY.equals(quarkusKey)) {
            String value = schemaStrategy();
            return "drop-and-create".equalsIgnoreCase(value) ? "create-drop" : value;
        }
        if (quarkusKey != null) {
            return raw(quarkusKey);
        }
        String direct = raw(key);
        return direct != null ? direct : unsupportedPropertiesFallback(key);
    }

    /**
     * Reads the schema-generation strategy, preferring the Quarkus 3.33+
     * {@code quarkus.hibernate-orm.schema-management.strategy} key and falling back to the
     * deprecated-but-still-supported {@code quarkus.hibernate-orm.database.generation} so the advisor reads
     * the operator's setting whichever name they use.
     */
    private String schemaStrategy() {
        String value = raw(SCHEMA_STRATEGY_KEY);
        return value != null ? value : raw(LEGACY_GENERATION_KEY);
    }

    /**
     * Reports whether Quarkus' convenience bind-parameter-logging flag is enabled, checking both the current
     * and deprecated property names (Quarkus itself treats them as equivalent).
     */
    private boolean isBindParameterLoggingEnabled() {
        return "true".equalsIgnoreCase(raw(BIND_PARAMETERS_PROPERTY))
                || "true".equalsIgnoreCase(raw(LEGACY_BIND_PARAM_PROPERTY));
    }

    /**
     * Falls back to Quarkus' generic {@code quarkus.hibernate-orm.unsupported-properties."..."} escape hatch
     * for a native Hibernate property key with no first-class {@code quarkus.hibernate-orm.*} option. Only
     * applies to keys that are (once any {@code spring.jpa.properties.} prefix is stripped) a bare
     * {@code hibernate.*} property, since that is the only key shape this passthrough can plausibly carry.
     */
    private String unsupportedPropertiesFallback(String key) {
        String hibernateKey = key.startsWith(SPRING_JPA_PROPERTIES_PREFIX)
                ? key.substring(SPRING_JPA_PROPERTIES_PREFIX.length())
                : key;
        if (!hibernateKey.startsWith("hibernate.")) {
            return null;
        }
        return raw(UNSUPPORTED_PROPERTIES_PREFIX + hibernateKey + "\"");
    }

    private String raw(String key) {
        try {
            return config.getOptionalValue(key, String.class).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
