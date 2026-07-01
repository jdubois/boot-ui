package io.github.jdubois.bootui.quarkus.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

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
}
