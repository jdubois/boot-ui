package io.github.jdubois.bootui.quarkus.hibernate;

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
 * </ul>
 *
 * <p>Every other engine key falls through to a raw MicroProfile Config read, which returns {@code null} for
 * the Spring-only concerns the engine also probes (Hikari auto-commit, JTA, defer-datasource-initialization)
 * — correctly leaving those rules inert on Quarkus. A handful of lower-value config rules (slow-query
 * threshold, ordered batching, second-level cache) have no clean {@code quarkus.hibernate-orm.*} equivalent
 * and remain unmapped; their advice still applies but they cite the Spring/native-Hibernate property name.
 * That bounded limitation is documented in {@code docs/FEATURES.md}.</p>
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

    // Engine key -> quarkus.hibernate-orm.* equivalent. Verified against the Quarkus 3.33 Hibernate ORM
    // configuration reference; the first two are exercised by the Quarkus sample app.
    private static final Map<String, String> KEY_ALIASES = Map.of(
            "spring.jpa.hibernate.ddl-auto", SCHEMA_STRATEGY_KEY,
            "spring.jpa.properties.hibernate.hbm2ddl.auto", SCHEMA_STRATEGY_KEY,
            "hibernate.hbm2ddl.auto", SCHEMA_STRATEGY_KEY,
            "spring.jpa.show-sql", "quarkus.hibernate-orm.log.sql",
            "hibernate.show_sql", "quarkus.hibernate-orm.log.sql",
            "hibernate.format_sql", "quarkus.hibernate-orm.log.format-sql",
            "spring.jpa.properties.hibernate.format_sql", "quarkus.hibernate-orm.log.format-sql",
            "hibernate.jdbc.batch_size", "quarkus.hibernate-orm.jdbc.statement-batch-size",
            "spring.jpa.properties.hibernate.jdbc.batch_size", "quarkus.hibernate-orm.jdbc.statement-batch-size");

    private final Config config;

    public QuarkusHibernatePropertyLookup(Config config) {
        this.config = config;
    }

    @Override
    public String apply(String key) {
        if (OPEN_IN_VIEW_KEY.equals(key)) {
            // Quarkus has no Open-Session-in-View; report its effective state so HIB-CONFIG-001 passes
            // rather than assuming Spring Boot's enabled-by-default.
            return "false";
        }
        if (ENHANCEMENT_ENABLED_KEYS.contains(key)) {
            return "true";
        }
        String quarkusKey = KEY_ALIASES.get(key);
        if (SCHEMA_STRATEGY_KEY.equals(quarkusKey)) {
            String value = schemaStrategy();
            return "drop-and-create".equalsIgnoreCase(value) ? "create-drop" : value;
        }
        return raw(quarkusKey != null ? quarkusKey : key);
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

    private String raw(String key) {
        try {
            return config.getOptionalValue(key, String.class).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
