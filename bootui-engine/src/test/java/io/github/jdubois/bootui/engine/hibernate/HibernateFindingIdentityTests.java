package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.advisor.AdvisorFindingIdentity;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolation;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HibernateFindingIdentityTests {

    private static final String SIMPLE = HibernateFindingIdentityTests.class.getName();

    @Test
    void anAttributeFindingIsKeyedOnTheUnitTheConcreteEntityAndTheMember() {
        List<AdvisorViolation> findings = evaluate(
                new IdentityIdentifierRule(), "orders", new TestEnvironment(), List.of(), Stream.of(Plain.class));

        assertThat(findings)
                .extracting(AdvisorViolation::identity)
                .containsExactly("v1:unit=orders|entity=" + SIMPLE + "$Plain|member=id");
    }

    @Test
    void theIdentityIsStableAcrossScansAndNeverDerivedFromTheDisplayText() {
        List<AdvisorViolation> first =
                evaluate(new EagerFetchRule(), "orders", new TestEnvironment(), List.of(), Stream.of(Eager.class));
        List<AdvisorViolation> second =
                evaluate(new EagerFetchRule(), "orders", new TestEnvironment(), List.of(), Stream.of(Eager.class));

        assertThat(first).hasSize(1);
        assertThat(first.get(0).identity()).isEqualTo(second.get(0).identity());
        assertThat(first.get(0).identity()).doesNotContain(" ").doesNotContain("FetchType");
    }

    @Test
    void anInheritedAttributeStaysDistinctPerConcreteEntity() {
        List<AdvisorViolation> findings = evaluate(
                new EagerFetchRule(),
                "orders",
                new TestEnvironment(),
                List.of(),
                Stream.of(EagerChildOne.class, EagerChildTwo.class));

        assertThat(findings).hasSize(2);
        assertThat(findings.stream().map(AdvisorViolation::identity).distinct()).hasSize(2);
        assertThat(findings)
                .extracting(AdvisorViolation::identity)
                .containsExactly(
                        "v1:unit=orders|entity=" + SIMPLE + "$EagerChildOne|member=parent",
                        "v1:unit=orders|entity=" + SIMPLE + "$EagerChildTwo|member=parent");
    }

    @Test
    void theSameFindingInTwoUnitsHasTwoIdentities() {
        List<AdvisorViolation> orders =
                evaluate(new EagerFetchRule(), "orders", new TestEnvironment(), List.of(), Stream.of(Eager.class));
        List<AdvisorViolation> archive =
                evaluate(new EagerFetchRule(), "archive", new TestEnvironment(), List.of(), Stream.of(Eager.class));

        assertThat(orders.get(0).identity()).isNotEqualTo(archive.get(0).identity());
        assertThat(AdvisorFindingIdentity.scope(orders.get(0).identity())).isEqualTo("unit=orders");
    }

    @Test
    void overloadedRepositoryMethodsStayDistinct() {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.Repo",
                Object.class,
                List.of(streamMethod("findAll", List.of()), streamMethod("findAll", List.of(String.class))));

        List<AdvisorViolation> findings = evaluate(
                new StreamReturningMethodRule(),
                "orders",
                new TestEnvironment(),
                List.of(repository),
                Stream.of(Plain.class));

        assertThat(findings)
                .extracting(AdvisorViolation::identity)
                .containsExactly(
                        "v1:unit=orders|repository=com.example.Repo|method=findAll%28%29",
                        "v1:unit=orders|repository=com.example.Repo|method=findAll%28java.lang.String%29");
    }

    @Test
    void aConfigurationFindingIsKeyedOnItsSettingNotOnTheMessage() {
        List<AdvisorViolation> findings =
                evaluate(new JdbcBatchSizeRule(), null, new TestEnvironment(), List.of(), Stream.of(Plain.class));

        assertThat(findings)
                .extracting(AdvisorViolation::identity)
                .containsExactly("v1:unit=application|setting=hibernate.jdbc.batch_size");
    }

    @Test
    void everyIdentityStaysWithinTheDocumentedCharsetAndLength() {
        List<AdvisorViolation> findings = evaluate(
                new IdentityIdentifierRule(), "or ders|1", new TestEnvironment(), List.of(), Stream.of(Plain.class));

        assertThat(findings.get(0).identity())
                .matches(AdvisorFindingIdentity.VALID.pattern())
                .contains("unit=or%20ders%7C1");
    }

    private static HibernateRepositoryMethodModel streamMethod(String name, List<Class<?>> parameters) {
        return new HibernateRepositoryMethodModel(
                "com.example.Repo",
                name,
                Object.class,
                Stream.class,
                null,
                false,
                null,
                false,
                false,
                false,
                false,
                parameters);
    }

    private static List<AdvisorViolation> evaluate(
            HibernateRule rule,
            String unit,
            TestEnvironment environment,
            List<HibernateRepositoryModel> repositories,
            Stream<Class<?>> entities) {
        AdvisorViolationCollector collector = new AdvisorViolationCollector(100);
        HibernateContext context = new HibernateContext(
                        entities.map(HibernateEntityModel::fromClass).toList(),
                        repositories,
                        environment.lookup(),
                        environment.activeProfiles(),
                        "7.3.9.Final")
                .withViolationCollector(collector, unit);
        rule.evaluate(context);
        return collector.retained(rule.definition().id());
    }

    @Entity
    static class Plain {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
    }

    @Entity
    static class Eager {
        @Id
        Long id;

        @ManyToOne(fetch = FetchType.EAGER)
        Plain owner;
    }

    @MappedSuperclass
    static class EagerParent {
        @ManyToOne(fetch = FetchType.EAGER)
        Plain parent;
    }

    @Entity
    static class EagerChildOne extends EagerParent {
        @Id
        Long id;
    }

    @Entity
    static class EagerChildTwo extends EagerParent {
        @Id
        Long id;
    }
}
