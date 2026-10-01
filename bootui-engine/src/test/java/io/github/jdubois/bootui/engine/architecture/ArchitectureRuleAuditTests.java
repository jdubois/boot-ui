package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.architecture.modulefixtures.moduletwo.ModuleTwoConsumer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regression tests for the Architecture advisor audit: platform applicability, narrowed matching, and the
 * ARCH-SPRING-023 / ARCH-SPRING-024 rules, including how they share findings with the field-injection rules.
 */
class ArchitectureRuleAuditTests {

    @TempDir
    Path tempDir;

    // ARCH-CODE-003

    @Test
    void javaUtilLoggingIsReportedOnSpringButNotEvaluatedOnQuarkus() {
        ArchitectureRuleResultDto spring =
                evaluate(ArchitecturePlatform.SPRING, new NoJavaUtilLoggingRule(), JulUser.class);
        ArchitectureRuleResultDto quarkus =
                evaluate(ArchitecturePlatform.QUARKUS, new NoJavaUtilLoggingRule(), JulUser.class);

        assertThat(spring.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(quarkus.status())
                .as("JUL is a built-in Quarkus logging API, and @LoggingFilter must implement java.util.logging.Filter")
                .isEqualTo(ArchitectureRuleSupport.SKIPPED);
    }

    // ARCH-CODE-013

    @Test
    void testFrameworkUseIsReportedFromMainOutputButNotFromTestOutput() {
        ArchitectureRuleResultDto main = evaluate(
                ArchitecturePlatform.SPRING,
                new NoTestFrameworkDependenciesRule(),
                CompiledOutputFixtures.importFrom(tempDir.resolve("maven"), "target/classes", JunitHelper.class));
        ArchitectureRuleResultDto mavenTest = evaluate(
                ArchitecturePlatform.SPRING,
                new NoTestFrameworkDependenciesRule(),
                CompiledOutputFixtures.importFrom(tempDir.resolve("maven"), "target/test-classes", JunitHelper.class));
        ArchitectureRuleResultDto gradleTest = evaluate(
                ArchitecturePlatform.SPRING,
                new NoTestFrameworkDependenciesRule(),
                CompiledOutputFixtures.importFrom(
                        tempDir.resolve("gradle"), "build/classes/java/test", JunitHelper.class));

        assertThat(main.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(mavenTest.status())
                .as("spring-boot:test-run puts target/test-classes on the classpath; tests may use JUnit")
                .isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(gradleTest.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void testFrameworkUseFromAnUnrecognizedLayoutStaysReported() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                new NoTestFrameworkDependenciesRule(),
                CompiledOutputFixtures.importFrom(tempDir, "out/production/app", JunitHelper.class));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
    }

    // ARCH-SPRING-008

    @Test
    void servicesMayThrowWebExceptionsWithoutDependingOnRequestTypes() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                new ServicesAndRepositoriesShouldNotDependOnServletTypesRule(),
                ServiceThrowingResponseStatusException.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    // ARCH-MOD-001

    @Test
    void internalPackageAccessIsReportedOncePerDependencyWithItsOwnLine() {
        JavaClasses classes =
                new ClassFileImporter().importPackages("io.github.jdubois.bootui.engine.architecture.modulefixtures");
        ArchitectureRuleResultDto result = new InternalPackagesShouldNotBeAccessedExternallyRule()
                .evaluate(new ArchitectureContext(
                        classes,
                        List.of("io.github.jdubois.bootui.engine.architecture.modulefixtures"),
                        ArchitecturePlatform.SPRING));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .hasSize(3)
                .doesNotHaveDuplicates()
                .allSatisfy(sample -> assertThat(sample)
                        .startsWith("Internal package")
                        .contains(ModuleTwoConsumer.class.getSimpleName()));
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleLocations())
                .as("code accesses point at their own line; a field's type has no executable line")
                .filteredOn(location -> location.line() != null)
                .extracting(AdvisorViolationLocationDto::memberName, AdvisorViolationLocationDto::line)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("<init>", 8),
                        org.assertj.core.groups.Tuple.tuple("borrow", 11));
    }

    // ARCH-CODE-015

    @Test
    void factoryMethodHoldersAndComposedStereotypesAreNotUtilityClasses() {
        for (Class<?> holder : List.of(
                StaticBeanMethodsHolder.class,
                StaticBeanMethodsConfiguration.class,
                StaticProducerHolder.class,
                StaticOnlyApplicationScopedBean.class)) {
            assertThat(evaluate(
                                    ArchitecturePlatform.SPRING,
                                    new UtilityClassesShouldBeFinalWithPrivateConstructorRule(),
                                    holder)
                            .status())
                    .as(holder.getSimpleName())
                    .isEqualTo(ArchitectureRuleSupport.PASS);
        }
        assertThat(evaluate(
                                ArchitecturePlatform.SPRING,
                                new UtilityClassesShouldBeFinalWithPrivateConstructorRule(),
                                PlainUtility.class)
                        .status())
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
    }

    // ARCH-SPRING-011

    @Test
    void asyncReturnTypesAreJudgedOnlyOnInterceptableMethods() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING, new AsyncMethodsShouldHaveSupportedSignaturesRule(), AsyncService.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.violationCount())
                .as("only the public String method reaches the interceptor; private, static and final ones do not")
                .isEqualTo(1);
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("lookup")
                .contains("Spring's @Async interceptor throws");
    }

    // Proxy annotation set: ARCH-SPRING-004, ARCH-SPRING-010, ARCH-SPRING-018

    @Test
    void selfInvocationCoversResilienceAndMethodSecurityAnnotations() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                new NoSelfInvocationOfProxiedMethodsRule(),
                SelfInvokingResilientBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("retried"))
                .anySatisfy(sample -> assertThat(sample).contains("throttled"))
                .anySatisfy(sample -> assertThat(sample).contains("authorized"));
    }

    @Test
    void classLevelMethodSecurityDoesNotTurnEverySelfCallIntoAFinding() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING, new NoSelfInvocationOfProxiedMethodsRule(), ClassLevelSecuredBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void privateResilientOrSecuredMethodsAreNotInterceptable() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                new ProxiedMethodsShouldNotBePrivateOrStaticRule(),
                PrivateResilientBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.violationCount()).isEqualTo(2);
    }

    @Test
    void rolesAllowedFollowsArcInterceptionOnQuarkus() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.QUARKUS,
                new ProxiedMethodsShouldNotBePrivateOrStaticRule(),
                RolesAllowedCdiBean.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount())
                .as("Arc intercepts static methods but never private ones")
                .isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("privateCheck");
    }

    @Test
    void retryableLifecycleCallbacksAreReported() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                new LifecycleCallbacksShouldNotBeProxyDrivenRule(),
                RetryableInitializer.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
    }

    // ARCH-SPRING-022

    @Test
    void legacyJavaxTransactionalMessageNamesBothRuntimes() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.QUARKUS,
                new LegacyJavaxTransactionalShouldBeMigratedRule(),
                LegacyJavaxTransactionalBean.class);

        assertThat(result.sampleViolations()).singleElement().asString().contains("Quarkus 3");
    }

    // ARCH-SPRING-023

    @Test
    void staticInjectionPointsAreReported() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                new StaticInjectionPointsAreIgnoredRule(),
                StaticInjectionComponent.class,
                StaticJakartaInjectComponent.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-023");
        assertThat(result.violationCount()).isEqualTo(4);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("REPOSITORY").contains("@Autowired"))
                .anySatisfy(sample -> assertThat(sample).contains("TIMEOUT").contains("@Value"))
                .anySatisfy(sample -> assertThat(sample).contains("setClock").contains("Static method"))
                .anySatisfy(sample -> assertThat(sample).contains("CLOCK").contains("@Inject"))
                .noneSatisfy(sample -> assertThat(sample).contains("setHolder"))
                .noneSatisfy(sample -> assertThat(sample).contains("instanceField"));
    }

    @Test
    void staticJakartaInjectIsReportedOnQuarkusBeansButNotOnUnmanagedClasses() {
        assertThat(evaluate(
                                ArchitecturePlatform.QUARKUS,
                                new StaticInjectionPointsAreIgnoredRule(),
                                StaticInjectCdiBean.class)
                        .status())
                .as("Arc warns and ignores a static @Inject field")
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(evaluate(
                                ArchitecturePlatform.SPRING,
                                new StaticInjectionPointsAreIgnoredRule(),
                                StaticInjectGuiceStyleClass.class)
                        .status())
                .as("an unmanaged class may rely on Guice static injection")
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void staticInjectionPointsAreNotAlsoReportedAsFieldInjection() {
        assertThat(evaluate(ArchitecturePlatform.SPRING, new NoFieldInjectionRule(), StaticInjectionComponent.class)
                        .sampleViolations())
                .singleElement()
                .asString()
                .contains("instanceField");
        assertThat(evaluate(
                                ArchitecturePlatform.SPRING,
                                new FieldsShouldNotUseStandardInjectionAnnotationsRule(),
                                StaticJakartaInjectComponent.class)
                        .status())
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    // ARCH-SPRING-024

    @Test
    void legacyJavaxCallbacksAndInjectionPointsOnBeansAreReported() {
        ArchitectureRuleResultDto result = evaluate(
                ArchitecturePlatform.SPRING,
                new LegacyJavaxInjectionAnnotationsShouldBeMigratedRule(),
                LegacyJavaxComponent.class,
                LegacyCallbackOnPlainClass.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-SPRING-024");
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.sampleViolations())
                .hasSize(5)
                .anySatisfy(sample -> assertThat(sample).contains("repository").contains("javax.inject.Inject"))
                .anySatisfy(sample -> assertThat(sample).contains("dataSource").contains("javax.annotation.Resource"))
                .anySatisfy(sample -> assertThat(sample).contains("setClock").contains("javax.inject.Inject"))
                .anySatisfy(sample -> assertThat(sample).contains("init").contains("javax.annotation.PostConstruct"))
                .anySatisfy(sample -> assertThat(sample).contains("start").contains("javax.annotation.PostConstruct"))
                .noneSatisfy(sample -> assertThat(sample).contains("migrated"));
    }

    @Test
    void legacyJavaxInjectionOnUnmanagedClassesAndSoleConstructorsIsNotReported() {
        assertThat(evaluate(
                                ArchitecturePlatform.SPRING,
                                new LegacyJavaxInjectionAnnotationsShouldBeMigratedRule(),
                                LegacyJavaxSoleConstructorComponent.class,
                                LegacyJavaxGuiceStyleClass.class)
                        .status())
                .isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(evaluate(
                                ArchitecturePlatform.SPRING,
                                new LegacyJavaxInjectionAnnotationsShouldBeMigratedRule(),
                                LegacyJavaxTwoConstructorComponent.class)
                        .status())
                .as("with two constructors the annotation is what selects one, and it is ignored")
                .isEqualTo(ArchitectureRuleSupport.VIOLATION);
    }

    @Test
    void legacyJavaxFieldsAreReportedUnderExactlyOneRule() {
        ArchitectureRuleResultDto beanFieldInjection = evaluate(
                ArchitecturePlatform.SPRING,
                new FieldsShouldNotUseStandardInjectionAnnotationsRule(),
                LegacyJavaxComponent.class);
        ArchitectureRuleResultDto plainFieldInjection = evaluate(
                ArchitecturePlatform.SPRING,
                new FieldsShouldNotUseStandardInjectionAnnotationsRule(),
                LegacyJavaxGuiceStyleClass.class);

        assertThat(beanFieldInjection.sampleViolations())
                .as("ignored javax annotations on beans belong to ARCH-SPRING-024")
                .noneSatisfy(sample -> assertThat(sample).contains("repository"))
                .noneSatisfy(sample -> assertThat(sample).contains("dataSource"));
        assertThat(plainFieldInjection.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
    }

    // ARCH-CODE-016

    @Test
    void standardFieldInjectionIsMediumOnSpringAndLowOnQuarkus() {
        ArchitectureRuleResultDto spring = evaluate(
                ArchitecturePlatform.SPRING,
                new FieldsShouldNotUseStandardInjectionAnnotationsRule(),
                JakartaFieldInjectedBean.class);
        ArchitectureRuleResultDto quarkus = evaluate(
                ArchitecturePlatform.QUARKUS,
                new FieldsShouldNotUseStandardInjectionAnnotationsRule(),
                JakartaFieldInjectedBean.class);

        assertThat(spring.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(spring.severity()).isEqualTo("MEDIUM");
        assertThat(quarkus.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(quarkus.id()).isEqualTo("ARCH-CODE-016");
        assertThat(quarkus.severity()).isEqualTo("LOW");
    }

    private static ArchitectureRuleResultDto evaluate(
            ArchitecturePlatform platform, ArchitectureRule rule, Class<?>... classes) {
        return evaluate(platform, rule, new ClassFileImporter().importClasses(classes));
    }

    private static ArchitectureRuleResultDto evaluate(
            ArchitecturePlatform platform, ArchitectureRule rule, JavaClasses classes) {
        return rule.evaluate(
                new ArchitectureContext(classes, List.of(ArchitectureRuleAuditTests.class.getPackageName()), platform));
    }

    static class JulUser {

        private static final Logger LOG = Logger.getLogger("audit");

        void log() {
            LOG.info("message");
        }
    }

    static class JunitHelper {

        void check(Object value) {
            Assertions.assertNotNull(value);
        }
    }

    @Service
    static class ServiceThrowingResponseStatusException {

        String find(String id) {
            if (id == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return id;
        }
    }

    static class StaticBeanMethodsHolder {

        @Bean
        static Object first() {
            return new Object();
        }
    }

    @SpringBootConfiguration
    static class StaticBeanMethodsConfiguration {

        static String helper() {
            return "helper";
        }
    }

    static class StaticProducerHolder {

        @Produces
        static Object produce() {
            return new Object();
        }
    }

    @ApplicationScoped
    static class StaticOnlyApplicationScopedBean {

        static String helper() {
            return "helper";
        }
    }

    static class PlainUtility {

        static String helper() {
            return "helper";
        }
    }

    @Async
    static class AsyncService {

        public String lookup() {
            return "value";
        }

        public void fireAndForget() {}

        private String format() {
            return "private";
        }

        static String staticHelper() {
            return "static";
        }

        public final String finalHelper() {
            return "final";
        }
    }

    static class SelfInvokingResilientBean {

        public void entry() {
            retried();
            throttled();
            authorized();
        }

        @Retryable
        public void retried() {}

        @ConcurrencyLimit(1)
        public void throttled() {}

        @PreAuthorize("hasRole('ADMIN')")
        public void authorized() {}
    }

    @PreAuthorize("hasRole('ADMIN')")
    static class ClassLevelSecuredBean {

        public void entry() {
            other();
        }

        public void other() {}
    }

    static class PrivateResilientBean {

        public void entry() {
            retried();
            authorized();
        }

        @Retryable
        private void retried() {}

        @PreAuthorize("hasRole('ADMIN')")
        private void authorized() {}
    }

    static class RolesAllowedCdiBean {

        public void entry() {
            privateCheck();
        }

        @RolesAllowed("admin")
        private void privateCheck() {}

        @RolesAllowed("admin")
        static void staticCheck() {}
    }

    static class RetryableInitializer {

        @PostConstruct
        @Retryable
        void init() {}
    }

    @javax.transaction.Transactional
    static class LegacyJavaxTransactionalBean {}

    @Component
    static class StaticInjectionComponent {

        @Autowired
        static Object REPOSITORY;

        @Value("${timeout:5}")
        static int TIMEOUT;

        static Object holder;

        @Autowired
        Object instanceField;

        @Autowired
        static void setClock(Object clock) {}

        @Autowired
        void setHolder(Object value) {
            holder = value;
        }
    }

    @Component
    static class StaticJakartaInjectComponent {

        @jakarta.inject.Inject
        static Object CLOCK;
    }

    @ApplicationScoped
    static class StaticInjectCdiBean {

        @jakarta.inject.Inject
        static Object clock;
    }

    static class StaticInjectGuiceStyleClass {

        @jakarta.inject.Inject
        static Object clock;
    }

    @Component
    static class LegacyJavaxComponent {

        @javax.inject.Inject
        Object repository;

        @javax.annotation.Resource
        Object dataSource;

        @javax.inject.Inject
        @jakarta.inject.Inject
        Object migrated;

        @javax.inject.Inject
        void setClock(Object clock) {}

        @javax.annotation.PostConstruct
        void init() {}
    }

    static class LegacyCallbackOnPlainClass {

        @javax.annotation.PostConstruct
        void start() {}
    }

    @Component
    static class LegacyJavaxSoleConstructorComponent {

        private final Object dependency;

        @javax.inject.Inject
        LegacyJavaxSoleConstructorComponent(Object dependency) {
            this.dependency = dependency;
        }
    }

    @Component
    static class LegacyJavaxTwoConstructorComponent {

        LegacyJavaxTwoConstructorComponent() {}

        @javax.inject.Inject
        LegacyJavaxTwoConstructorComponent(Object dependency) {}
    }

    static class LegacyJavaxGuiceStyleClass {

        @javax.inject.Inject
        Object repository;
    }

    static class JakartaFieldInjectedBean {

        @jakarta.inject.Inject
        Object repository;
    }
}
