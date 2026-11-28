package io.github.jdubois.bootui.quarkus4.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateApplicationFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateAttributeModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateEntityModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettingsReader;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.lang.reflect.Field;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.Version;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.mapping.ToOne;
import org.junit.jupiter.api.Test;

class QuarkusHibernateFetchDefaultsTest {
    @Test
    void recordsRealBootstrapDefaultsRatherThanForcedRuntimeFetchPlans() {
        for (FetchType defaultType : List.of(FetchType.EAGER, FetchType.LAZY)) {
            Map<String, Object> properties = bootstrapDefaults(defaultType);
            var report = scan(List.of(unit("orders", properties)));
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("HIB-FETCH-001"))
                    .singleElement()
                    .satisfies(result ->
                            assertThat(result.violationCount()).isEqualTo(defaultType == FetchType.EAGER ? 4 : 1));
            assertThat(report.diagnostics())
                    .noneMatch(diagnostic -> diagnostic.source().equals("HIB-FETCH-001"));
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("HIB-FETCH-002"))
                    .singleElement()
                    .satisfies(result ->
                            assertThat(result.violationCount()).isEqualTo(defaultType == FetchType.EAGER ? 1 : 3));
        }
    }

    @Test
    void differentUnitsKeepTheirOwnDefaultInBothOrders() {
        var eager = unit("eager", bootstrapDefaults(FetchType.EAGER));
        var lazy = unit("lazy", bootstrapDefaults(FetchType.LAZY));
        for (var units : List.of(List.of(eager, lazy), List.of(lazy, eager))) {
            var report = scan(units);
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("HIB-FETCH-001"))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.violationCount()).isEqualTo(5);
                        assertThat(result.sampleViolations())
                                .filteredOn(value -> value.startsWith("[lazy]"))
                                .singleElement()
                                .asString()
                                .contains("#explicitEager");
                    });
        }
    }

    @Test
    void unavailableDefaultRetainsExplicitFindingsWithoutClaimingCompleteCoverage() {
        for (Map<String, Object> properties : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of(HibernateFactorySettingsReader.DEFAULT_TO_ONE_FETCH_TYPE, "DEFAULT"))) {
            var report = scan(List.of(unit("unknown", properties)));
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("HIB-FETCH-001"))
                    .singleElement()
                    .satisfies(result -> assertThat(result.violationCount()).isEqualTo(1));
            assertThat(report.diagnostics())
                    .filteredOn(diagnostic -> diagnostic.source().equals("HIB-FETCH-001"))
                    .singleElement()
                    .satisfies(diagnostic -> assertThat(diagnostic.message())
                            .contains("DEFAULT", "persistence-unit defaults")
                            .doesNotContain("XML"));
            assertThat(report.evidence().coverageComplete()).isFalse();
        }
    }

    @Test
    void anUnresolvedDefaultIsNotAnInapplicableBatchFetchCheck() {
        var unit = unit("unknown", Map.of());
        var entity = unit.entities().getFirst();
        var defaultOnly = new HibernateEntityModel(
                entity.name(),
                entity.javaType(),
                entity.attributes().stream()
                        .filter(attribute -> attribute.name().equals("automaticMany"))
                        .toList());
        var report = scan(List.of(new HibernatePersistenceUnitObservation(
                unit.unitKey(),
                unit.label(),
                List.of(defaultOnly),
                List.of(),
                unit.hibernateVersion(),
                unit.settings(),
                true)));
        assertThat(report.diagnostics())
                .filteredOn(diagnostic -> diagnostic.source().equals("HIB-FETCH-002"))
                .singleElement()
                .satisfies(diagnostic ->
                        assertThat(diagnostic.message()).contains("DEFAULT", "persistence-unit defaults"));
        assertThat(report.results()).noneMatch(result -> result.id().equals("HIB-FETCH-002"));
        assertThat(report.evidence().coverageComplete()).isFalse();
    }

    @Test
    void graphFreeJakartaFetchOverridesMatchRealOrmMetadata() {
        for (FetchType defaultType : List.of(FetchType.EAGER, FetchType.LAZY)) {
            var registry = new StandardServiceRegistryBuilder()
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                    .build();
            try {
                Metadata metadata = new MetadataSources(registry)
                        .addAnnotatedClass(FetchOptionsNode.class)
                        .getMetadataBuilder()
                        .applyDefaultToOneFetchType(defaultType)
                        .build();
                var entity = metadata.getEntityBinding(FetchOptionsNode.class.getName());
                for (String field : List.of("overriddenLazy", "namedOnly", "repeatable", "subgraphOnly")) {
                    assertThat(((ToOne) entity.getProperty(field).getValue()).isLazy())
                            .as(field)
                            .isTrue();
                }
                assertThat(((ToOne) entity.getProperty("overriddenEager").getValue()).isLazy())
                        .isFalse();
                assertThat(((ToOne) entity.getProperty("defaultOverride").getValue()).isLazy())
                        .isEqualTo(defaultType == FetchType.LAZY);
                for (String field : List.of("defaultCollection", "defaultElements")) {
                    assertThat(metadata.getCollectionBinding(FetchOptionsNode.class.getName() + "." + field)
                                    .isLazy())
                            .as(field)
                            .isTrue();
                }
                assertThat(metadata.getCollectionBinding(FetchOptionsNode.class.getName() + ".eagerCollection")
                                .isLazy())
                        .isFalse();

                Map<String, Object> properties = new HashMap<>();
                new QuarkusHibernateFetchDefaults().onMetadataInitialized(metadata, null, properties::put);
                var report = scan(List.of(unit("options", properties, FetchOptionsNode.class)));
                assertThat(report.results())
                        .filteredOn(result -> result.id().equals("HIB-FETCH-001"))
                        .singleElement()
                        .satisfies(result -> {
                            assertThat(result.violationCount()).isEqualTo(defaultType == FetchType.EAGER ? 2 : 1);
                            assertThat(result.sampleViolations())
                                    .anyMatch(value -> value.contains("#overriddenEager"))
                                    .noneMatch(value -> value.contains("#overriddenLazy")
                                            || value.contains("#namedOnly")
                                            || value.contains("#repeatable")
                                            || value.contains("#subgraphOnly"));
                        });
                assertThat(report.results())
                        .filteredOn(result -> result.id().equals("HIB-FETCH-010"))
                        .singleElement()
                        .satisfies(result -> {
                            assertThat(result.violationCount()).isEqualTo(1);
                            assertThat(result.sampleViolations())
                                    .singleElement()
                                    .asString()
                                    .contains("#eagerCollection");
                        });
                assertThat(report.diagnostics())
                        .noneMatch(diagnostic -> List.of("HIB-FETCH-001", "HIB-FETCH-002", "HIB-FETCH-010")
                                .contains(diagnostic.source()));
            } finally {
                StandardServiceRegistryBuilder.destroy(registry);
            }
        }
    }

    @Test
    void explicitJakartaFetchAndCollectionDefaultsDoNotNeedAnUnknownToOneDefault() {
        var source = unit("options", Map.of(), FetchOptionsNode.class);
        var entity = source.entities().getFirst();
        var withoutUnitDefault = new HibernateEntityModel(
                entity.name(),
                entity.javaType(),
                entity.attributes().stream()
                        .filter(attribute -> !attribute.name().equals("defaultOverride"))
                        .toList());
        var report = scan(List.of(new HibernatePersistenceUnitObservation(
                source.unitKey(),
                source.label(),
                List.of(withoutUnitDefault),
                List.of(),
                source.hibernateVersion(),
                source.settings(),
                true)));
        assertThat(report.results())
                .filteredOn(result -> result.id().equals("HIB-FETCH-001"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.violationCount()).isEqualTo(1);
                    assertThat(result.sampleViolations())
                            .singleElement()
                            .asString()
                            .contains("#overriddenEager");
                });
        assertThat(report.results())
                .filteredOn(result -> result.id().equals("HIB-FETCH-010"))
                .singleElement()
                .satisfies(result -> assertThat(result.violationCount()).isEqualTo(1));
        assertThat(report.diagnostics())
                .noneMatch(diagnostic -> List.of("HIB-FETCH-001", "HIB-FETCH-002", "HIB-FETCH-010")
                        .contains(diagnostic.source()));
    }

    private static Map<String, Object> bootstrapDefaults(FetchType defaultType) {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .build();
        try {
            Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(Node.class)
                    .getMetadataBuilder()
                    .applyDefaultToOneFetchType(defaultType)
                    .build();
            var entity = metadata.getEntityBinding(Node.class.getName());
            assertThat(((ToOne) entity.getProperty("automaticMany").getValue()).isLazy())
                    .isEqualTo(defaultType == FetchType.LAZY);
            assertThat(((ToOne) entity.getProperty("forcedJoin").getValue()).isLazy())
                    .isFalse();
            Map<String, Object> properties = new HashMap<>();
            new QuarkusHibernateFetchDefaults().onMetadataInitialized(metadata, null, properties::put);
            return properties;
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static HibernatePersistenceUnitObservation unit(String name, Map<String, Object> properties) {
        return unit(name, properties, Node.class);
    }

    private static HibernatePersistenceUnitObservation unit(
            String name, Map<String, Object> properties, Class<?> entityType) {
        SessionFactoryImplementor factory = mock(SessionFactoryImplementor.class);
        when(factory.getProperties()).thenReturn(properties);
        SessionFactoryOptions options = mock(SessionFactoryOptions.class);
        when(factory.getSessionFactoryOptions()).thenReturn(options);
        when(options.getDefaultBatchFetchSize()).thenReturn(0);
        List<HibernateAttributeModel> attributes = Arrays.stream(entityType.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(ManyToOne.class)
                        || field.isAnnotationPresent(OneToOne.class)
                        || field.isAnnotationPresent(OneToMany.class)
                        || field.isAnnotationPresent(ElementCollection.class))
                .map(QuarkusHibernateFetchDefaultsTest::attribute)
                .toList();
        return new HibernatePersistenceUnitObservation(
                name,
                name,
                List.of(new HibernateEntityModel(entityType.getName(), entityType, attributes)),
                List.of(),
                Version.getVersionString(),
                HibernateFactorySettingsReader.read(factory, name, new ArrayList<>()),
                true);
    }

    private static HibernateAttributeModel attribute(Field field) {
        return new HibernateAttributeModel(
                field.getDeclaringClass().getName(),
                field.getName(),
                field.getType(),
                field.getGenericType(),
                field.isAnnotationPresent(OneToOne.class)
                        ? "ONE_TO_ONE"
                        : field.isAnnotationPresent(OneToMany.class)
                                ? "ONE_TO_MANY"
                                : field.isAnnotationPresent(ElementCollection.class)
                                        ? "ELEMENT_COLLECTION"
                                        : "MANY_TO_ONE",
                false,
                true,
                List.of(field.getAnnotations()),
                field.getName());
    }

    private static io.github.jdubois.bootui.core.dto.HibernateReport scan(
            List<HibernatePersistenceUnitObservation> units) {
        return HibernateScanner.observing(
                        () -> new HibernateAdvisorObservation(
                                units, HibernateApplicationFacts.unknown(List.of()), List.of()),
                        Clock.systemUTC())
                .scan();
    }

    @Entity(name = "FetchDefaultsNode")
    static class Node {
        @Id
        Long id;

        @ManyToOne
        Node automaticMany;

        @OneToOne
        Node automaticOne;

        @ManyToOne(fetch = FetchType.EAGER)
        Node explicitEager;

        @ManyToOne(fetch = FetchType.LAZY)
        Node explicitLazy;

        @ManyToOne
        @Fetch(FetchMode.JOIN)
        Node forcedJoin;
    }

    @Entity(name = "FetchOptionsNode")
    @NamedEntityGraph(name = "scopedFetch")
    static class FetchOptionsNode {
        @Id
        Long id;

        @ManyToOne(fetch = FetchType.EAGER)
        @jakarta.persistence.Fetch(type = FetchType.LAZY)
        FetchOptionsNode overriddenLazy;

        @ManyToOne(fetch = FetchType.LAZY)
        @jakarta.persistence.Fetch
        FetchOptionsNode overriddenEager;

        @ManyToOne(fetch = FetchType.LAZY)
        @jakarta.persistence.Fetch(type = FetchType.DEFAULT)
        FetchOptionsNode defaultOverride;

        @ManyToOne(fetch = FetchType.LAZY)
        @jakarta.persistence.Fetch(graph = "scopedFetch")
        FetchOptionsNode namedOnly;

        @ManyToOne(fetch = FetchType.EAGER)
        @jakarta.persistence.Fetch(graph = "scopedFetch")
        @jakarta.persistence.Fetch(type = FetchType.LAZY)
        FetchOptionsNode repeatable;

        @ManyToOne(fetch = FetchType.LAZY)
        @jakarta.persistence.Fetch(subgraph = "scopedFetch")
        FetchOptionsNode subgraphOnly;

        @OneToMany(mappedBy = "overriddenEager", fetch = FetchType.EAGER)
        @jakarta.persistence.Fetch(type = FetchType.DEFAULT)
        List<FetchOptionsNode> defaultCollection;

        @ElementCollection(fetch = FetchType.EAGER)
        @jakarta.persistence.Fetch(type = FetchType.DEFAULT)
        List<String> defaultElements;

        @OneToMany(mappedBy = "overriddenLazy")
        @jakarta.persistence.Fetch
        List<FetchOptionsNode> eagerCollection;
    }
}
