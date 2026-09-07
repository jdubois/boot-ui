package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

/** Explicit effective-factory fixtures. Production legacy property lookup is deliberately not promoted. */
final class HibernateObservationFixtures {
    private HibernateObservationFixtures() {}

    static HibernateFactorySettings settings(TestEnvironment values) {
        HibernateContext declarations = new HibernateContext(List.of(), List.of(), values.lookup(), List.of());
        return new HibernateFactorySettings(
                integer(declarations, "hibernate.jdbc.batch_size", 0),
                integer(declarations, "hibernate.default_batch_fetch_size", 0),
                bool(declarations, "hibernate.order_inserts", false),
                bool(declarations, "hibernate.order_updates", false),
                bool(declarations, "hibernate.cache.use_second_level_cache", true),
                bool(declarations, "hibernate.cache.use_query_cache", false),
                bool(declarations, "hibernate.query.fail_on_pagination_over_collection_fetch", false),
                bool(declarations, "hibernate.generate_statistics", false),
                HibernateFactorySettings.RegionFactory.AVAILABLE,
                HibernateFactorySettings.ConnectionProvider.MANAGED,
                HibernateFactorySettings.SchemaAction.hibernate(
                        declarations.firstProperty("spring.jpa.hibernate.ddl-auto", "hibernate.hbm2ddl.auto") == null
                                ? "none"
                                : declarations.firstProperty(
                                        "spring.jpa.hibernate.ddl-auto", "hibernate.hbm2ddl.auto")),
                bool(declarations, "hibernate.enable_lazy_load_no_trans", false),
                bool(declarations, "hibernate.query.in_clause_parameter_padding", false),
                integer(declarations, "hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS", 0),
                declarations.isPropertyTrue("spring.jpa.show-sql", "hibernate.show_sql"),
                bool(declarations, "hibernate.format_sql", false),
                bool(declarations, "hibernate.use_sql_comments", false),
                declarations.firstProperty("hibernate.jdbc.time_zone", "spring.jpa.properties.hibernate.jdbc.time_zone")
                        != null,
                integer(declarations, "hibernate.jdbc.fetch_size", 0),
                false);
    }

    static HibernateApplicationFacts application(TestEnvironment values) {
        HibernateContext context = new HibernateContext(List.of(), List.of(), values.lookup(), values.activeProfiles());
        HibernateApplicationFacts.OpenInView osiv = context.isOpenInViewApplicable()
                ? Boolean.TRUE.equals(context.booleanProperty("spring.jpa.open-in-view"))
                        ? HibernateApplicationFacts.OpenInView.ENABLED
                        : HibernateApplicationFacts.OpenInView.DISABLED
                : HibernateApplicationFacts.OpenInView.NOT_APPLICABLE;
        return new HibernateApplicationFacts(
                values.activeProfiles(),
                osiv,
                context.isPropertyTrue("spring.jpa.defer-datasource-initialization"),
                context.isStatementLoggingEnabled(),
                context.isBindParameterLoggingEnabled(),
                false);
    }

    static HibernateRepositoryMethodModel verified(HibernateRepositoryMethodModel method) {
        return new HibernateRepositoryMethodModel(
                method.repositoryInterface(),
                method.methodName(),
                method.domainType(),
                method.returnType(),
                method.query(),
                method.nativeQuery(),
                method.countQuery(),
                method.hasPageableParameter(),
                method.modifying(),
                method.modifyingClearsAutomatically(),
                method.modifyingFlushesAutomatically(),
                method.parameterTypes(),
                new HibernateQueryEvidence(
                        true,
                        method.hasQuery(),
                        !method.hasQuery(),
                        false,
                        false,
                        method.domainType(),
                        false,
                        null,
                        null,
                        List.of(":ids", "?1")));
    }

    private static Integer integer(HibernateContext context, String key, int fallback) {
        Integer value = context.firstIntegerProperty("spring.jpa.properties." + key, key);
        return value == null ? fallback : value;
    }

    private static Boolean bool(HibernateContext context, String key, boolean fallback) {
        String value = context.firstProperty("spring.jpa.properties." + key, key);
        return value == null ? fallback : Boolean.valueOf(value);
    }
}
