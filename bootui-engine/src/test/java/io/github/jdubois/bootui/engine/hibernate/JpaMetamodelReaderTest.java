package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for {@link JpaMetamodelReader}, the sole engine class that reads the
 * {@code jakarta.persistence} metamodel. The metamodel walk itself is exercised end-to-end through the
 * sample-app wiring test; here we pin the two fail-soft guards that keep a misbehaving persistence unit
 * from breaking the advisor.
 */
class JpaMetamodelReaderTest {

    @Test
    void readEntitiesWithNoFactoriesReportsUnavailable() {
        EntityDiscovery discovery = JpaMetamodelReader.readEntities(List.of());

        assertThat(discovery.entities()).isEmpty();
        assertThat(discovery.repositories()).isEmpty();
        assertThat(discovery.errors()).containsExactly("No EntityManagerFactory beans are available.");
    }

    @Test
    void readEntitiesCapturesMetamodelFailureAsError() {
        EntityManagerFactory failing = throwingFactory("metamodel unavailable");

        EntityDiscovery discovery = JpaMetamodelReader.readEntities(List.of(failing));

        assertThat(discovery.entities()).isEmpty();
        assertThat(discovery.errors()).containsExactly("metamodel unavailable");
    }

    private static EntityManagerFactory throwingFactory(String message) {
        return (EntityManagerFactory) Proxy.newProxyInstance(
                JpaMetamodelReaderTest.class.getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class},
                (proxy, method, args) -> {
                    if ("getMetamodel".equals(method.getName())) {
                        throw new IllegalStateException(message);
                    }
                    if ("toString".equals(method.getName())) {
                        return "ThrowingEntityManagerFactory";
                    }
                    Class<?> returnType = method.getReturnType();
                    return returnType.isPrimitive() && returnType == boolean.class ? Boolean.FALSE : null;
                });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void metamodelPersistentMembersHonorInheritedPropertyAccessRatherThanUnmappedFields() throws Exception {
        EntityManagerFactory factory = mock(EntityManagerFactory.class);
        jakarta.persistence.metamodel.Metamodel metamodel = mock(jakarta.persistence.metamodel.Metamodel.class);
        jakarta.persistence.metamodel.EntityType entity = mock(jakarta.persistence.metamodel.EntityType.class);
        jakarta.persistence.metamodel.Attribute id = mock(jakarta.persistence.metamodel.Attribute.class);
        jakarta.persistence.metamodel.Attribute version = mock(jakarta.persistence.metamodel.Attribute.class);
        when(factory.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of(entity));
        when(entity.getJavaType()).thenReturn(PropertyEntity.class);
        when(entity.getAttributes()).thenReturn(Set.of(id, version));
        when(id.getName()).thenReturn("id");
        when(id.getJavaMember()).thenReturn(PropertyBase.class.getMethod("getId"));
        when(id.getPersistentAttributeType())
                .thenReturn(jakarta.persistence.metamodel.Attribute.PersistentAttributeType.BASIC);
        when(version.getName()).thenReturn("version");
        when(version.getJavaMember()).thenReturn(PropertyEntity.class.getMethod("getVersion"));
        when(version.getPersistentAttributeType())
                .thenReturn(jakarta.persistence.metamodel.Attribute.PersistentAttributeType.BASIC);
        EntityDiscovery discovery = JpaMetamodelReader.readEntities(List.of(factory));
        assertThat(discovery.errors()).isEmpty();
        HibernateEntityModel model = discovery.entities().get(0);
        assertThat(model.attributes())
                .extracting(HibernateAttributeModel::name)
                .containsExactlyInAnyOrder("id", "version");
        assertThat(model.attributes()).allMatch(attribute -> !attribute.fieldMember());
        assertThat(model.attributes())
                .filteredOn(HibernateAttributeModel::hasId)
                .hasSize(1);
        assertThat(model.attributes())
                .filteredOn(HibernateAttributeModel::hasVersion)
                .singleElement()
                .satisfies(attribute -> assertThat(attribute.rawType()).isEqualTo(Long.class));
    }

    @jakarta.persistence.MappedSuperclass
    static class PropertyBase {
        @jakarta.persistence.Id
        public Long getId() {
            return null;
        }
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Access(jakarta.persistence.AccessType.PROPERTY)
    static class PropertyEntity extends PropertyBase {
        public String unrelatedPublicField;

        @jakarta.persistence.Version
        public Long getVersion() {
            return null;
        }
    }
}
