package io.github.jdubois.bootui.quarkus.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.quarkus.StubConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarkusHibernatePropertyLookupTest {

    private static QuarkusHibernatePropertyLookup lookup(Map<String, String> properties) {
        return new QuarkusHibernatePropertyLookup(new StubConfig(properties));
    }

    @Test
    void mapsDdlAutoKeysToQuarkusSchemaStrategy() {
        QuarkusHibernatePropertyLookup lookup =
                lookup(Map.of("quarkus.hibernate-orm.schema-management.strategy", "validate"));

        assertThat(lookup.apply("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.hbm2ddl.auto")).isEqualTo("validate");
        assertThat(lookup.apply("hibernate.hbm2ddl.auto")).isEqualTo("validate");
    }

    @Test
    void fallsBackToTheDeprecatedGenerationKeyWhenTheNewKeyIsAbsent() {
        // quarkus.hibernate-orm.database.generation is deprecated in Quarkus 3.33 but still honored; an app
        // that has not migrated must still have its setting read by the advisor.
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of("quarkus.hibernate-orm.database.generation", "validate"));

        assertThat(lookup.apply("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void prefersTheNewSchemaStrategyKeyOverTheDeprecatedGenerationKey() {
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of(
                "quarkus.hibernate-orm.schema-management.strategy", "none",
                "quarkus.hibernate-orm.database.generation", "drop-and-create"));

        assertThat(lookup.apply("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
    }

    @Test
    void translatesQuarkusDropAndCreateToTheJpaCreateDropValue() {
        QuarkusHibernatePropertyLookup lookup =
                lookup(Map.of("quarkus.hibernate-orm.schema-management.strategy", "drop-and-create"));

        // The engine only knows the JPA value vocabulary; create-drop is one of its RISKY_VALUES.
        assertThat(lookup.apply("spring.jpa.hibernate.ddl-auto")).isEqualTo("create-drop");
    }

    @Test
    void passesThroughOtherGenerationValuesUnchanged() {
        assertThat(lookup(Map.of("quarkus.hibernate-orm.schema-management.strategy", "create"))
                        .apply("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("create");
        assertThat(lookup(Map.of("quarkus.hibernate-orm.schema-management.strategy", "update"))
                        .apply("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("update");
        assertThat(lookup(Map.of("quarkus.hibernate-orm.schema-management.strategy", "none"))
                        .apply("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("none");
    }

    @Test
    void mapsShowSqlFormatSqlAndBatchSizeKeys() {
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of(
                "quarkus.hibernate-orm.log.sql", "true",
                "quarkus.hibernate-orm.log.format-sql", "true",
                "quarkus.hibernate-orm.jdbc.statement-batch-size", "25"));

        assertThat(lookup.apply("spring.jpa.show-sql")).isEqualTo("true");
        assertThat(lookup.apply("hibernate.show_sql")).isEqualTo("true");
        assertThat(lookup.apply("hibernate.format_sql")).isEqualTo("true");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.format_sql")).isEqualTo("true");
        assertThat(lookup.apply("hibernate.jdbc.batch_size")).isEqualTo("25");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.jdbc.batch_size"))
                .isEqualTo("25");
    }

    @Test
    void neutralizesOpenInViewToFalseRegardlessOfConfig() {
        // Quarkus has no Open-Session-in-View: report its effective state so HIB-CONFIG-001 passes rather than
        // assuming Spring Boot's enabled-by-default web behaviour.
        assertThat(lookup(Map.of()).apply("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(lookup(Map.of("spring.jpa.open-in-view", "true")).apply("spring.jpa.open-in-view"))
                .isEqualTo("false");
        assertThat(lookup(Map.of()).apply(HibernateScanner.OPEN_IN_VIEW_APPLICABLE_PROPERTY))
                .isEqualTo("false");
    }

    @Test
    void reportsHibernateBytecodeEnhancementAsAlwaysEnabledRegardlessOfConfig() {
        // Quarkus enhances every entity unconditionally at build time (HibernateOrmProcessor's
        // enhancerDomainObjects() build step is ungated) and has no config switch to disable it, so
        // HIB-MAP-017/HIB-MAP-018 must see these keys as "true" even when nothing at all is configured.
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of());

        assertThat(lookup.apply("spring.jpa.properties.hibernate.enhancer.enableLazyInitialization"))
                .isEqualTo("true");
        assertThat(lookup.apply("hibernate.enhancer.enableLazyInitialization")).isEqualTo("true");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.bytecode.enhancer.enableLazyInitialization"))
                .isEqualTo("true");
        assertThat(lookup.apply("hibernate.bytecode.enhancer.enableLazyInitialization"))
                .isEqualTo("true");
    }

    @Test
    void returnsNullForUnmappedSpringOnlyKeys() {
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of());

        // Hikari auto-commit, JTA and defer-datasource-initialization have no quarkus.hibernate-orm.* analogue;
        // null leaves those engine rules correctly inert on Quarkus.
        assertThat(lookup.apply("spring.datasource.hikari.auto-commit")).isNull();
        assertThat(lookup.apply("spring.jpa.properties.hibernate.connection.provider_disables_autocommit"))
                .isNull();
        assertThat(lookup.apply("spring.jpa.defer-datasource-initialization")).isNull();
    }

    @Test
    void returnsNullWhenAMappedQuarkusKeyIsAbsent() {
        assertThat(lookup(Map.of()).apply("spring.jpa.hibernate.ddl-auto")).isNull();
        assertThat(lookup(Map.of()).apply("spring.jpa.show-sql")).isNull();
    }

    @Test
    void mapsDefaultBatchFetchSizeToQuarkusFetchBatchSize() {
        // Confirmed by decompiling quarkus-hibernate-orm-deployment-3.33.2.1.jar: this is a real, currently
        // existing quarkus.hibernate-orm.* property (HIB-FETCH-002 previously false-positived on every
        // Quarkus scan because this key was missing from the alias map).
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of("quarkus.hibernate-orm.fetch.batch-size", "16"));

        assertThat(lookup.apply("hibernate.default_batch_fetch_size")).isEqualTo("16");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.default_batch_fetch_size"))
                .isEqualTo("16");
    }

    @Test
    void mapsJdbcTimeZoneToQuarkusJdbcTimezone() {
        // HIB-CONFIG-013.
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of("quarkus.hibernate-orm.jdbc.timezone", "UTC"));

        assertThat(lookup.apply("hibernate.jdbc.time_zone")).isEqualTo("UTC");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.jdbc.time_zone"))
                .isEqualTo("UTC");
    }

    @Test
    void mapsGenerateStatisticsToQuarkusStatistics() {
        // HIB-CONFIG-007.
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of("quarkus.hibernate-orm.statistics", "true"));

        assertThat(lookup.apply("hibernate.generate_statistics")).isEqualTo("true");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.generate_statistics"))
                .isEqualTo("true");
    }

    @Test
    void mapsFailOnPaginationOverCollectionFetchToItsQuarkusEquivalent() {
        // HIB-CONFIG-016.
        QuarkusHibernatePropertyLookup lookup =
                lookup(Map.of("quarkus.hibernate-orm.query.fail-on-pagination-over-collection-fetch", "true"));

        assertThat(lookup.apply("hibernate.query.fail_on_pagination_over_collection_fetch"))
                .isEqualTo("true");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch"))
                .isEqualTo("true");
    }

    @Test
    void mapsInClauseParameterPaddingToItsQuarkusEquivalent() {
        // HIB-CONFIG-009.
        QuarkusHibernatePropertyLookup lookup =
                lookup(Map.of("quarkus.hibernate-orm.query.in-clause-parameter-padding", "true"));

        assertThat(lookup.apply("hibernate.query.in_clause_parameter_padding")).isEqualTo("true");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.query.in_clause_parameter_padding"))
                .isEqualTo("true");
    }

    @Test
    void mapsBothCacheFlagsToTheUnifiedSecondLevelCachingToggle() {
        // HIB-CONFIG-010/HIB-CONFIG-011. Quarkus has a SINGLE quarkus.hibernate-orm.second-level-caching-enabled
        // toggle, not one property per Hibernate cache setting: decompiling HibernateProcessorUtil's
        // configureCaching() shows this one boolean drives both hibernate.cache.use_second_level_cache AND
        // hibernate.cache.use_query_cache via Properties#putIfAbsent(...). There is no separate
        // "query-cache-enabled" property in Quarkus 3.33 (an earlier draft of this fix assumed there was;
        // the generated quarkus-config-model.json confirms only second-level-caching-enabled exists).
        QuarkusHibernatePropertyLookup lookup =
                lookup(Map.of("quarkus.hibernate-orm.second-level-caching-enabled", "false"));

        assertThat(lookup.apply("hibernate.cache.use_query_cache")).isEqualTo("false");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.cache.use_query_cache"))
                .isEqualTo("false");
        assertThat(lookup.apply("hibernate.cache.use_second_level_cache")).isEqualTo("false");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.cache.use_second_level_cache"))
                .isEqualTo("false");
    }

    @Test
    void reportsBindParameterLoggingEnabledFromTheQuarkusNativeConvenienceFlag() {
        // HIB-CONFIG-018. quarkus.hibernate-orm.log.bind-parameters triggers a build-time
        // LogCategoryBuildItem("org.hibernate.orm.jdbc.bind", Level.TRACE, ...) (confirmed by decompiling
        // HibernateOrmProcessor#produceLoggingCategories()), so the neutral engine key must read back "trace".
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of("quarkus.hibernate-orm.log.bind-parameters", "true"));

        assertThat(lookup.apply("logging.level.org.hibernate.orm.jdbc.bind")).isEqualTo("trace");
    }

    @Test
    void reportsBindParameterLoggingEnabledFromTheDeprecatedQuarkusFlag() {
        // quarkus.hibernate-orm.log.bind-param is deprecated but still honored (HibernateOrmConfigLog's
        // isAnyPropertySet() ORs bindParam() and bindParameters() together).
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of("quarkus.hibernate-orm.log.bind-param", "true"));

        assertThat(lookup.apply("logging.level.org.hibernate.orm.jdbc.bind")).isEqualTo("trace");
    }

    @Test
    void doesNotReportBindParameterLoggingEnabledWhenNeitherQuarkusFlagIsSet() {
        assertThat(lookup(Map.of()).apply("logging.level.org.hibernate.orm.jdbc.bind"))
                .isNull();
        assertThat(lookup(Map.of("quarkus.hibernate-orm.log.bind-parameters", "false"))
                        .apply("logging.level.org.hibernate.orm.jdbc.bind"))
                .isNull();
    }

    @Test
    void fallsBackToTheUnsupportedPropertiesEscapeHatchForHibernateKeysWithNoQuarkusEquivalent() {
        // Confirmed by decompiling FastBootMetadataBuilder: RecordedConfig#getQuarkusConfigUnsupportedProperties()
        // is merged verbatim (Map#putAll) into Hibernate's real bootstrap settings, so a key configured this way
        // genuinely reaches Hibernate. hibernate.order_inserts/order_updates (HIB-CONFIG-005) and
        // hibernate.cache.region.factory_class (HIB-CONFIG-010) have no first-class quarkus.hibernate-orm.*
        // option, but ARE readable if set through this passthrough.
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of(
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.order_inserts\"", "true",
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.order_updates\"", "true",
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.cache.region.factory_class\"",
                        "org.hibernate.cache.jcache.internal.JCacheRegionFactory"));

        assertThat(lookup.apply("hibernate.order_inserts")).isEqualTo("true");
        assertThat(lookup.apply("spring.jpa.properties.hibernate.order_updates"))
                .isEqualTo("true");
        assertThat(lookup.apply("hibernate.cache.region.factory_class"))
                .isEqualTo("org.hibernate.cache.jcache.internal.JCacheRegionFactory");
    }

    @Test
    void returnsNullFromTheUnsupportedPropertiesFallbackWhenNothingIsConfigured() {
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of());

        assertThat(lookup.apply("hibernate.order_inserts")).isNull();
        assertThat(lookup.apply("hibernate.cache.region.factory_class")).isNull();
    }

    @Test
    void doesNotApplyTheUnsupportedPropertiesFallbackToNonHibernateKeys() {
        // The fallback only ever targets hibernate.* keys (after stripping any spring.jpa.properties. prefix);
        // a key like spring.datasource.hikari.auto-commit must stay null, not spuriously probe
        // quarkus.hibernate-orm.unsupported-properties."spring.datasource.hikari.auto-commit".
        QuarkusHibernatePropertyLookup lookup = lookup(Map.of(
                "quarkus.hibernate-orm.unsupported-properties.\"spring.datasource.hikari.auto-commit\"", "false"));

        assertThat(lookup.apply("spring.datasource.hikari.auto-commit")).isNull();
    }
}
