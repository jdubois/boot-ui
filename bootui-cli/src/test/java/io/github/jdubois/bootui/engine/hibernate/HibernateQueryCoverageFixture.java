package io.github.jdubois.bootui.engine.hibernate;

import java.time.Clock;
import java.util.List;

/** Two real rules over controlled metadata, not a claim of whole-catalogue or native ORM coverage. */
public final class HibernateQueryCoverageFixture {
    private HibernateQueryCoverageFixture() {}

    public static HibernateScanner scanner(boolean readableBulk) {
        HibernateEntityModel entity = new HibernateEntityModel(
                QueryOrder.class.getName(), QueryOrder.class, List.of(collection("items"), collection("tags")));
        List<HibernateRepositoryMethodModel> methods = List.of(
                method("parallel", "select o from QueryOrder o join fetch o.items join fetch o.tags", false),
                method(
                        "bulk",
                        readableBulk ? "delete from QueryOrder o" : "delete from QueryOrder o /* unclosed",
                        true));
        HibernateFactorySettings settings = new HibernateFactorySettings(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                HibernateFactorySettings.ConnectionProvider.BUILT_IN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        HibernatePersistenceUnitObservation unit = new HibernatePersistenceUnitObservation(
                "orders",
                "orders",
                List.of(entity),
                List.of(new HibernateRepositoryModel("OrderRepository", QueryOrder.class, methods)),
                "7.4.5.Final",
                settings,
                false);
        return new HibernateScanner(
                () -> new HibernateAdvisorObservation(
                        List.of(unit), HibernateApplicationFacts.unknown(List.of()), List.of()),
                Clock.systemUTC(),
                List.of(new MultipleCollectionJoinFetchRule(), new HibernateBuiltinPoolRule()));
    }

    private static HibernateAttributeModel collection(String name) {
        return new HibernateAttributeModel(
                QueryOrder.class.getName(), name, List.class, List.class, "ONE_TO_MANY", false, true, List.of());
    }

    private static HibernateRepositoryMethodModel method(String name, String query, boolean bulk) {
        return new HibernateRepositoryMethodModel(
                "OrderRepository",
                name,
                QueryOrder.class,
                bulk ? int.class : List.class,
                query,
                false,
                null,
                false,
                bulk,
                false,
                false,
                List.of(),
                new HibernateQueryEvidence(
                        true,
                        true,
                        false,
                        false,
                        false,
                        bulk ? int.class : QueryOrder.class,
                        false,
                        null,
                        null,
                        List.of()));
    }

    static class QueryOrder {}
}
