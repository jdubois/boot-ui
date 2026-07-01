package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Architecture is unusual among BootUI's advisors: it runs the exact same ArchUnit ruleset,
 * unmodified, on both Spring and Quarkus applications (see {@code docs/QUARKUS-SUPPORT.md}). These
 * tests pin the single most important correctness property that follows from that design: every
 * {@code SPRING_STEREOTYPES} ({@code ARCH-SPRING-*}) rule must degrade gracefully — {@code PASS} or
 * {@code SKIPPED}, never a {@code VIOLATION} or {@code ERROR} — when scanning a pure-CDI/Quarkus
 * application that has no Spring classes or annotations anywhere on its classpath or in its code.
 *
 * <p>A small number of {@code ARCH-SPRING-*} rules are intentionally dual-framework: they also match
 * the shared {@code jakarta.transaction.Transactional} / {@code jakarta.annotation.PostConstruct} /
 * {@code PreDestroy} annotations, because CDI containers such as Quarkus' Arc have the exact same
 * proxy self-invocation and lifecycle-callback pitfalls as Spring. The fixtures below are written the
 * idiomatic, correct way (constructor injection, no self-invocation across the proxy boundary, no
 * lifecycle callback combined with a proxy-driven annotation) so that even those dual-framework rules
 * correctly report no violation — proving the rule set is safe to run unmodified on Quarkus.
 */
class ArchitectureCdiNeutralityTests {

    @Test
    void everySpringStereotypeRulePassesOrSkipsOnAnIdiomaticCdiApplication() {
        ArchitectureContext context = importCdiFixtures();

        for (ArchitectureRule rule : ArchitectureRuleRegistry.activeRules()) {
            ArchitectureRuleResultDto result = rule.evaluate(context);
            if (rule.definition().category() == ArchitectureCategory.SPRING_STEREOTYPES) {
                assertThat(result.status())
                        .as(
                                "Spring-stereotype rule %s (%s) must never VIOLATION/ERROR on a pure-CDI application",
                                result.id(), result.name())
                        .isIn(ArchitectureRuleSupport.PASS, ArchitectureRuleSupport.SKIPPED);
            } else {
                // Framework-neutral rules may legitimately fire (the field-injected bean below is a
                // deliberate ARCH-CODE-016 positive), but must never blow up while evaluating CDI code.
                assertThat(result.status())
                        .as(
                                "Rule %s (%s) must not ERROR while scanning a pure-CDI application",
                                result.id(), result.name())
                        .isNotEqualTo(ArchitectureRuleSupport.ERROR);
            }
        }
    }

    @Test
    void fieldInjectedCdiBeanTriggersTheStandardAnnotationRuleNotTheSpringOnlyOne() {
        ArchitectureRuleResultDto standardRuleResult =
                evaluate(new FieldsShouldNotUseStandardInjectionAnnotationsRule(), CdiFieldInjectedBean.class);
        assertThat(standardRuleResult.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(standardRuleResult.id()).isEqualTo("ARCH-CODE-016");
        assertThat(standardRuleResult.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("CdiFieldInjectedBean"));

        ArchitectureRuleResultDto springOnlyRuleResult =
                evaluate(new NoFieldInjectionRule(), CdiFieldInjectedBean.class);
        assertThat(springOnlyRuleResult.status())
                .as("plain @Inject must never trigger the Spring-only field injection rule")
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void transactionalSelfInvocationStillFiresOnPlainJakartaTransactional() {
        // Documents that ARCH-SPRING-004 is an intentional dual-framework true positive: CDI/JTA
        // self-invocation bypasses the container-managed transaction interceptor exactly like a Spring
        // proxy, so this is not a false positive to guard against, unlike the field-injection case above.
        ArchitectureRuleResultDto result =
                evaluate(new NoSelfInvocationOfProxiedMethodsRule(), CdiSelfInvokingTransactionalBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-004");
    }

    @Test
    void lifecycleCallbackRuleStillFiresOnPlainJakartaTransactionalPostConstruct() {
        // Per the jakarta.transaction.Transactional Javadoc ("Jakarta Transactions" spec): "The
        // Transactional interceptor interposes on business method invocations only and not on
        // lifecycle events. Lifecycle methods are invoked in an unspecified transaction context." So a
        // @PostConstruct method also annotated @Transactional silently runs without a transaction on
        // Quarkus/CDI exactly as it does on Spring — another intentional dual-framework true positive.
        ArchitectureRuleResultDto result =
                evaluate(new LifecycleCallbacksShouldNotBeProxyDrivenRule(), CdiTransactionalPostConstructBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-018");
    }

    private static ArchitectureContext importCdiFixtures() {
        JavaClasses importedClasses = new ClassFileImporter()
                .importClasses(CdiResource.class, CdiService.class, CdiRepository.class, CdiFieldInjectedBean.class);
        return new ArchitectureContext(importedClasses, List.of(ArchitectureCdiNeutralityTests.class.getPackageName()));
    }

    private static ArchitectureRuleResultDto evaluate(ArchitectureRule rule, Class<?>... classes) {
        JavaClasses importedClasses = new ClassFileImporter().importClasses(classes);
        return rule.evaluate(new ArchitectureContext(
                importedClasses, List.of(ArchitectureCdiNeutralityTests.class.getPackageName())));
    }

    /** A JAX-RS resource, constructor-injected — the idiomatic CDI/Quarkus entry point. */
    @Path("/cdi")
    private static class CdiResource {

        private final CdiService service;

        @Inject
        CdiResource(CdiService service) {
            this.service = service;
        }

        @GET
        String get() {
            return service.read();
        }
    }

    /** A CDI service bean using constructor injection, a shared Jakarta lifecycle callback, and a
     * shared Jakarta transaction annotation — all used correctly (no self-invocation). */
    private static class CdiService {

        private final CdiRepository repository;

        @Inject
        CdiService(CdiRepository repository) {
            this.repository = repository;
        }

        @Transactional
        String read() {
            return repository.find();
        }

        @PostConstruct
        void init() {}

        @PreDestroy
        void destroy() {}
    }

    /** A CDI-managed persistence-style bean with no Spring stereotype anywhere. */
    private static class CdiRepository {

        String find() {
            return "ok";
        }
    }

    /** The one deliberate anti-pattern: standard-annotation field injection, mirroring the exact
     * shape of the repo's own {@code bootui-quarkus-sample-app} fixture. Must trigger ARCH-CODE-016
     * only, never the Spring-only ARCH-SPRING-001. */
    private static class CdiFieldInjectedBean {

        @Inject
        CdiRepository repository;
    }

    /** A CDI bean that self-invokes its own {@code @Transactional} method — a genuine, intentional
     * dual-framework true positive for ARCH-SPRING-004. */
    private static class CdiSelfInvokingTransactionalBean {

        void outer() {
            inner();
        }

        @Transactional
        void inner() {}
    }

    /** A CDI bean whose {@code @PostConstruct} callback is also annotated {@code @Transactional} — a
     * genuine, intentional dual-framework true positive for ARCH-SPRING-018 (the JTA interceptor does
     * not apply to lifecycle callbacks, same as Spring's AOP proxy). */
    private static class CdiTransactionalPostConstructBean {

        @PostConstruct
        @Transactional
        void init() {}
    }
}
