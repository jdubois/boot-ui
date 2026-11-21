package io.github.jdubois.bootui.hibernatefixture;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateApplicationFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettings;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.hibernate.JpaMetamodelReader;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Basic;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

class HibernateEagerFetchMetamodelTest {

    private static final String XML_MAPPINGS = """
            <entity-mappings xmlns="https://jakarta.ee/xml/ns/persistence/orm" version="3.2">
                <entity class="%s" name="EagerPrecisionXmlOwner" access="FIELD">
                    <attributes>
                        <id name="id"/>
                        <many-to-one name="parent" target-entity="%s" fetch="EAGER"/>
                        <element-collection name="labels" target-class="java.lang.String" fetch="EAGER"/>
                    </attributes>
                </entity>
                <entity class="%s" name="EagerPrecisionXmlTarget" access="FIELD">
                    <attributes>
                        <id name="id"/>
                    </attributes>
                </entity>
            </entity-mappings>
            """;

    @Test
    void realHibernateMetamodelSeparatesDefaultAndExplicitToOneFromEagerCollectionsWithoutReadingEntityValues() {
        assertFetchMappings(false);
    }

    @Test
    void realXmlMappingsKeepKnownFindingsButExposeUnreadableFetchDeclarationsAsIncomplete() {
        assertFetchMappings(true);
    }

    private static void assertFetchMappings(boolean includeXmlMappings) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.cache.use_second_level_cache", false)
                .build();
        try (SessionFactory factory = buildFactory(registry, includeXmlMappings)) {
            var discovery = JpaMetamodelReader.readEntities(List.of(factory));
            assertThat(discovery.errors()).isEmpty();
            if (includeXmlMappings) {
                var xmlMetamodel = factory.getMetamodel().entity(XmlFetchOwner.class);
                for (String member : List.of("parent", "labels")) {
                    assertThat(xmlMetamodel.getAttribute(member).getJavaMember())
                            .isNotNull()
                            .satisfies(javaMember ->
                                    assertThat(javaMember.getDeclaringClass()).isEqualTo(XmlFetchOwner.class));
                }
                var xmlOwner = discovery.entities().stream()
                        .filter(entity -> entity.javaType() == XmlFetchOwner.class)
                        .findFirst()
                        .orElseThrow();
                assertThat(xmlOwner.attributes())
                        .filteredOn(attribute -> "parent".equals(attribute.name()))
                        .singleElement()
                        .satisfies(attribute -> {
                            assertThat(attribute.persistentAttributeType()).isEqualTo("MANY_TO_ONE");
                            assertThat(attribute.annotations()).isEmpty();
                        });
                assertThat(xmlOwner.attributes())
                        .filteredOn(attribute -> "labels".equals(attribute.name()))
                        .singleElement()
                        .satisfies(attribute -> {
                            assertThat(attribute.persistentAttributeType()).isEqualTo("ELEMENT_COLLECTION");
                            assertThat(attribute.annotations()).isEmpty();
                        });
            }
            var owner = discovery.entities().stream()
                    .filter(entity -> entity.javaType() == FetchOwner.class)
                    .findFirst()
                    .orElseThrow();
            assertThat(owner.attributes())
                    .filteredOn(attribute -> "labels".equals(attribute.name()))
                    .singleElement()
                    .satisfies(attribute -> {
                        assertThat(attribute.persistentAttributeType()).isEqualTo("ELEMENT_COLLECTION");
                        assertThat(attribute.rawType()).isEqualTo(Map.class);
                        assertThat(attribute.entityName()).isEqualTo(FetchBase.class.getName());
                    });
            assertThat(owner.attributes())
                    .filteredOn(attribute -> "explicitOneToOne".equals(attribute.name()))
                    .singleElement()
                    .satisfies(attribute -> {
                        assertThat(attribute.persistentAttributeType()).isEqualTo("ONE_TO_ONE");
                        assertThat(attribute.fieldMember()).isFalse();
                        assertThat(attribute.javaMemberName()).isEqualTo("getExplicitOneToOne");
                    });

            var unit = new HibernatePersistenceUnitObservation(
                    "fixture",
                    "fixture",
                    discovery.entities(),
                    List.of(),
                    org.hibernate.Version.getVersionString(),
                    HibernateFactorySettings.unknown(),
                    false);
            HibernateScanner scanner = HibernateScanner.observing(
                    () -> new HibernateAdvisorObservation(
                            List.of(unit), HibernateApplicationFacts.unknown(List.of()), List.of()),
                    Clock.systemUTC());
            var report = scanner.scan();
            assertThat(report.results())
                    .filteredOn(result -> "HIB-FETCH-001".equals(result.id()))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.severity()).isEqualTo("MEDIUM");
                        assertThat(result.violationCount()).isEqualTo(4);
                        assertThat(result.sampleViolations())
                                .anySatisfy(text -> assertThat(text).contains("#defaultManyToOne "))
                                .anySatisfy(text -> assertThat(text).contains("#explicitManyToOne "))
                                .anySatisfy(text -> assertThat(text).contains("#defaultOneToOne "))
                                .anySatisfy(text -> assertThat(text).contains("#explicitOneToOne "));
                    });
            assertThat(report.results())
                    .filteredOn(result -> "HIB-FETCH-010".equals(result.id()))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.severity()).isEqualTo("HIGH");
                        assertThat(result.violationCount()).isEqualTo(3);
                        assertThat(result.sampleViolations())
                                .anySatisfy(text -> assertThat(text).contains("#children "))
                                .anySatisfy(text -> assertThat(text).contains("#peers "))
                                .anySatisfy(text -> assertThat(text).contains("#labels "));
                    });
            if (includeXmlMappings) {
                assertThat(report.scan().status()).isEqualTo("PARTIAL");
                assertThat(report.evidence().coverageComplete()).isFalse();
                assertThat(report.diagnostics())
                        .filteredOn(diagnostic ->
                                Set.of("HIB-FETCH-001", "HIB-FETCH-010").contains(diagnostic.source()))
                        .hasSize(2)
                        .allSatisfy(diagnostic -> {
                            assertThat(diagnostic.unit()).isEqualTo("fixture");
                            assertThat(diagnostic.level()).isEqualTo("WARNING");
                            assertThat(diagnostic.message()).contains("fetch declaration is unavailable");
                        });
            } else {
                assertThat(report.diagnostics())
                        .noneMatch(diagnostic ->
                                Set.of("HIB-FETCH-001", "HIB-FETCH-010").contains(diagnostic.source()));
            }
            for (HibernateRuleResultDto result : report.results()) {
                if (!Set.of("HIB-FETCH-001", "HIB-FETCH-010").contains(result.id())) continue;
                assertThat(result.status()).isEqualTo("VIOLATION");
                if (includeXmlMappings) {
                    assertThat(result.coverageNote()).contains("fetch declaration is unavailable");
                } else {
                    assertThat(result.coverageNote()).isNull();
                }
                var details = scanner.ruleViolations(
                        result.id(), report.violationDetails().scanId(), 0, 100);
                assertThat(details.identities())
                        .allMatch(identity ->
                                identity.startsWith("v1:unit=fixture|entity=" + FetchOwner.class.getName()));
                assertThat(details.violations()).allMatch(text -> !text.contains("#lazy") && !text.contains("#text"));
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static SessionFactory buildFactory(StandardServiceRegistry registry, boolean includeXmlMappings) {
        MetadataSources mappings = new MetadataSources(registry)
                .addAnnotatedClass(FetchOwner.class)
                .addAnnotatedClass(FetchTarget.class);
        if (includeXmlMappings) {
            mappings.addInputStream(new ByteArrayInputStream(XML_MAPPINGS
                    .formatted(
                            XmlFetchOwner.class.getName(),
                            XmlFetchTarget.class.getName(),
                            XmlFetchTarget.class.getName())
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return mappings.buildMetadata().buildSessionFactory();
    }

    @MappedSuperclass
    static class FetchBase {
        @ManyToOne
        FetchTarget defaultManyToOne;

        @ElementCollection(fetch = FetchType.EAGER)
        Map<String, String> labels;
    }

    @Entity(name = "EagerPrecisionOwner")
    static class FetchOwner extends FetchBase {
        @Id
        Long id;

        @ManyToOne(fetch = FetchType.EAGER)
        FetchTarget explicitManyToOne;

        @OneToOne
        FetchTarget defaultOneToOne;

        @Transient
        FetchTarget explicitOneToOne;

        @ManyToOne(fetch = FetchType.LAZY)
        FetchTarget lazyManyToOne;

        @OneToOne(fetch = FetchType.LAZY)
        FetchTarget lazyOneToOne;

        @OneToMany(fetch = FetchType.EAGER)
        Set<FetchTarget> children;

        @ManyToMany(fetch = FetchType.EAGER)
        Set<FetchTarget> peers;

        @OneToMany
        Set<FetchTarget> lazyChildren;

        @ManyToMany
        Set<FetchTarget> lazyPeers;

        @ElementCollection
        Set<String> lazyLabels;

        @Basic(fetch = FetchType.EAGER)
        String text;

        @Access(AccessType.PROPERTY)
        @OneToOne(fetch = FetchType.EAGER)
        public FetchTarget getExplicitOneToOne() {
            throw new AssertionError("The advisor must not invoke entity getters.");
        }

        public void setExplicitOneToOne(FetchTarget value) {
            explicitOneToOne = value;
        }
    }

    @Entity(name = "EagerPrecisionTarget")
    static class FetchTarget {
        @Id
        Long id;
    }

    static class XmlFetchOwner {
        Long id;

        XmlFetchTarget parent;

        Set<String> labels;
    }

    static class XmlFetchTarget {
        Long id;
    }
}
