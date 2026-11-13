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
        assertThat(discovery.knownAbsent()).isTrue();
    }

    @Test
    void readEntitiesCapturesMetamodelFailureAsError() {
        EntityManagerFactory failing = throwingFactory("metamodel unavailable");

        EntityDiscovery discovery = JpaMetamodelReader.readEntities(List.of(failing));

        assertThat(discovery.entities()).isEmpty();
        assertThat(discovery.errors()).containsExactly("metamodel unavailable");
        assertThat(discovery.knownAbsent()).isFalse();
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

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void expandsEmbeddedAttributesWithBoundedDepthAndRespectsAttributeOverrides() throws Exception {
        EntityManagerFactory factory = mock(EntityManagerFactory.class);
        jakarta.persistence.metamodel.Metamodel metamodel = mock(jakarta.persistence.metamodel.Metamodel.class);
        jakarta.persistence.metamodel.EntityType entity = mock(jakarta.persistence.metamodel.EntityType.class);
        jakarta.persistence.metamodel.SingularAttribute id =
                mock(jakarta.persistence.metamodel.SingularAttribute.class);
        jakarta.persistence.metamodel.SingularAttribute embeddedId =
                mock(jakarta.persistence.metamodel.SingularAttribute.class);
        jakarta.persistence.metamodel.SingularAttribute address =
                mock(jakarta.persistence.metamodel.SingularAttribute.class);
        jakarta.persistence.metamodel.EmbeddableType addressType =
                mock(jakarta.persistence.metamodel.EmbeddableType.class);
        jakarta.persistence.metamodel.Attribute street = mock(jakarta.persistence.metamodel.Attribute.class);
        jakarta.persistence.metamodel.Attribute status = mock(jakarta.persistence.metamodel.Attribute.class);
        jakarta.persistence.metamodel.SingularAttribute selfCycle =
                mock(jakarta.persistence.metamodel.SingularAttribute.class);

        when(factory.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of(entity));
        when(entity.getJavaType()).thenReturn(EmbeddedOwnerEntity.class);
        when(entity.getAttributes()).thenReturn(Set.of(id, embeddedId, address));

        when(id.getName()).thenReturn("id");
        when(id.getJavaMember()).thenReturn(EmbeddedOwnerEntity.class.getDeclaredField("id"));
        when(id.getPersistentAttributeType())
                .thenReturn(jakarta.persistence.metamodel.Attribute.PersistentAttributeType.BASIC);
        when(id.isId()).thenReturn(true);

        when(embeddedId.getName()).thenReturn("compositeKey");
        when(embeddedId.getJavaMember()).thenReturn(EmbeddedOwnerEntity.class.getDeclaredField("compositeKey"));
        when(embeddedId.getPersistentAttributeType())
                .thenReturn(jakarta.persistence.metamodel.Attribute.PersistentAttributeType.EMBEDDED);
        when(embeddedId.isId()).thenReturn(true);

        when(address.getName()).thenReturn("address");
        when(address.getJavaMember()).thenReturn(EmbeddedOwnerEntity.class.getDeclaredField("address"));
        when(address.getPersistentAttributeType())
                .thenReturn(jakarta.persistence.metamodel.Attribute.PersistentAttributeType.EMBEDDED);
        when(address.getType()).thenReturn(addressType);

        when(addressType.getJavaType()).thenReturn(EmbeddedAddress.class);
        when(addressType.getAttributes()).thenReturn(Set.of(street, status, selfCycle));

        when(street.getName()).thenReturn("street");
        when(street.getJavaMember()).thenReturn(EmbeddedAddress.class.getDeclaredField("street"));
        when(street.getPersistentAttributeType())
                .thenReturn(jakarta.persistence.metamodel.Attribute.PersistentAttributeType.BASIC);

        when(status.getName()).thenReturn("status");
        when(status.getJavaMember()).thenReturn(EmbeddedAddress.class.getDeclaredField("status"));
        when(status.getPersistentAttributeType())
                .thenReturn(jakarta.persistence.metamodel.Attribute.PersistentAttributeType.BASIC);

        when(selfCycle.getName()).thenReturn("parentAddress");
        when(selfCycle.getJavaMember()).thenReturn(EmbeddedAddress.class.getDeclaredField("parentAddress"));
        when(selfCycle.getPersistentAttributeType())
                .thenReturn(jakarta.persistence.metamodel.Attribute.PersistentAttributeType.EMBEDDED);
        when(selfCycle.getType()).thenReturn(addressType);

        EntityDiscovery discovery = JpaMetamodelReader.readEntities(List.of(factory));
        assertThat(discovery.errors()).isEmpty();
        HibernateEntityModel model = discovery.entities().get(0);
        assertThat(model.attributes())
                .extracting(HibernateAttributeModel::name)
                .contains("id", "compositeKey", "address", "address.street", "address.status", "address.parentAddress")
                .doesNotContain("compositeKey.part", "address.parentAddress.street");

        HibernateAttributeModel streetAttr = model.attributes().stream()
                .filter(attr -> "address.street".equals(attr.name()))
                .findFirst()
                .orElseThrow();
        assertThat(streetAttr.annotationIntValue(streetAttr.columnAnnotation(), "length"))
                .isEqualTo(120);

        HibernateContext context = new HibernateContext(List.of(model), List.of(), key -> null, List.of());
        assertThat(new StringColumnLengthRule().evaluate(context).status()).isEqualTo(HibernateRuleSupport.PASS);
        assertThat(new OrdinalEnumRule().evaluate(context).sampleViolations())
                .singleElement()
                .asString()
                .contains("EmbeddedAddress#address.status");
    }

    enum EmbeddedStatus {
        ACTIVE,
        ARCHIVED
    }

    @jakarta.persistence.Embeddable
    static class EmbeddedKey {
        String part;
    }

    @jakarta.persistence.Embeddable
    static class EmbeddedAddress {
        String street;

        EmbeddedStatus status;

        @jakarta.persistence.Embedded
        EmbeddedAddress parentAddress;
    }

    @jakarta.persistence.Entity
    static class EmbeddedOwnerEntity {
        @jakarta.persistence.Id
        Long id;

        @jakarta.persistence.EmbeddedId
        EmbeddedKey compositeKey;

        @jakarta.persistence.Embedded
        @jakarta.persistence.AttributeOverride(
                name = "street",
                column = @jakarta.persistence.Column(name = "addr_street", length = 120))
        EmbeddedAddress address;
    }
}
