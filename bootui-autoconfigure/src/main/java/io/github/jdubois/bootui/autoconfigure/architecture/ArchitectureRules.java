package io.github.jdubois.bootui.autoconfigure.architecture;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.ProxyRules;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Base class for curated architecture rules backed by a single ArchUnit {@link ArchRule}.
 *
 * <p>Subclasses build the rule for the current context; any failure to build or evaluate it is
 * captured and reported as an {@code ERROR} outcome so one broken rule never aborts the scan.</p>
 */
abstract class AbstractArchitectureRule implements ArchitectureRule {

    private final ArchitectureRuleDefinition definition;

    AbstractArchitectureRule(ArchitectureRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final ArchitectureRuleDefinition definition() {
        return definition;
    }

    /**
     * Builds the ArchUnit rule for this evaluation, or returns {@code null} to skip the rule.
     */
    abstract ArchRule rule(ArchitectureContext context);

    @Override
    public ArchitectureRuleResultDto evaluate(ArchitectureContext context) {
        try {
            ArchRule rule = rule(context);
            if (rule == null) {
                return ArchitectureRuleSupport.skipped(definition, "Rule is not applicable to the imported classes.");
            }
            return ArchitectureRuleSupport.evaluate(definition, rule, context);
            // Catch LinkageError as well as RuntimeException so one rule that trips over an unresolvable class
            // reports an ERROR result instead of aborting the whole scan; VirtualMachineError still propagates.
        } catch (RuntimeException | LinkageError ex) {
            return ArchitectureRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }
}

/**
 * Spring stereotype annotation type names, matched by name so the rules work even when the
 * annotation classes themselves are not on the BootUI classpath.
 */
final class SpringStereotypes {

    static final String CONTROLLER = "org.springframework.stereotype.Controller";
    static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    static final String REPOSITORY = "org.springframework.stereotype.Repository";
    static final String COMPONENT = "org.springframework.stereotype.Component";
    static final String SERVICE = "org.springframework.stereotype.Service";
    static final String CONFIGURATION = "org.springframework.context.annotation.Configuration";

    static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";
    static final String ASYNC = "org.springframework.scheduling.annotation.Async";
    static final String CACHEABLE = "org.springframework.cache.annotation.Cacheable";
    static final String SCHEDULED = "org.springframework.scheduling.annotation.Scheduled";
    static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";

    static final DescribedPredicate<CanBeAnnotated> CONTROLLER_ANNOTATED = annotatedWith(CONTROLLER)
            .or(annotatedWith(REST_CONTROLLER))
            .as("annotated with @Controller or @RestController");

    static final DescribedPredicate<CanBeAnnotated> REPOSITORY_ANNOTATED =
            annotatedWith(REPOSITORY).as("annotated with @Repository");

    static final DescribedPredicate<CanBeAnnotated> SERVICE_ANNOTATED =
            annotatedWith(SERVICE).as("annotated with @Service");

    static final DescribedPredicate<CanBeAnnotated> SERVICE_OR_REPOSITORY_ANNOTATED =
            annotatedWith(SERVICE).or(annotatedWith(REPOSITORY)).as("annotated with @Service or @Repository");

    static final DescribedPredicate<CanBeAnnotated> CONFIGURATION_ANNOTATED =
            annotatedWith(CONFIGURATION).as("annotated with @Configuration");

    static final DescribedPredicate<CanBeAnnotated> STEREOTYPE_ANNOTATED = annotatedWith(COMPONENT)
            .or(annotatedWith(SERVICE))
            .or(annotatedWith(REPOSITORY))
            .or(annotatedWith(CONTROLLER))
            .or(annotatedWith(REST_CONTROLLER))
            .or(annotatedWith(CONFIGURATION))
            .as("annotated with a Spring stereotype");

    static final DescribedPredicate<CanBeAnnotated> TRANSACTIONAL_ANNOTATED = annotatedWith(TRANSACTIONAL)
            .or(annotatedWith(JAKARTA_TRANSACTIONAL))
            .as("annotated with @Transactional");

    static final DescribedPredicate<CanBeAnnotated> ASYNC_ANNOTATED =
            annotatedWith(ASYNC).as("annotated with @Async");

    static final DescribedPredicate<CanBeAnnotated> SCHEDULED_ANNOTATED =
            annotatedWith(SCHEDULED).as("annotated with @Scheduled");

    static final DescribedPredicate<CanBeAnnotated> CONFIGURATION_PROPERTIES_ANNOTATED =
            annotatedWith(CONFIGURATION_PROPERTIES).as("annotated with @ConfigurationProperties");

    static final DescribedPredicate<CanBeAnnotated> PROXIED_METHOD_ANNOTATED = TRANSACTIONAL_ANNOTATED
            .or(annotatedWith(ASYNC))
            .or(annotatedWith(CACHEABLE))
            .as("annotated with @Transactional, @Async, or @Cacheable");

    private SpringStereotypes() {}
}

/**
 * Flags cyclic dependencies between the top-level package slices under each application base
 * package. Cycles are the single highest-value, project-agnostic architecture smell.
 */
final class FreeOfPackageCyclesRule extends AbstractArchitectureRule {

    private static final int MAX_SAMPLES = 10;

    FreeOfPackageCyclesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-PKG-001",
                        "Packages should be free of cycles",
                        ArchitectureCategory.PACKAGE_STRUCTURE,
                        "MEDIUM",
                        "Detects cyclic dependencies between the top-level package slices under the application base package.",
                        "Break the dependency cycle by extracting shared types or inverting one of the dependencies so packages form a directed acyclic graph."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return null; // Evaluation is handled per base package below.
    }

    @Override
    public ArchitectureRuleResultDto evaluate(ArchitectureContext context) {
        try {
            int totalViolations = 0;
            List<String> samples = new ArrayList<>();
            for (String basePackage : context.basePackages()) {
                ArchRule rule = SlicesRuleDefinition.slices()
                        .matching(basePackage + ".(*)..")
                        .should()
                        .beFreeOfCycles()
                        .allowEmptyShould(true);
                EvaluationResult evaluation = rule.evaluate(context.classes());
                if (!evaluation.hasViolation()) {
                    continue;
                }
                List<String> details = evaluation.getFailureReport().getDetails();
                totalViolations += details.size();
                for (String detail : details) {
                    if (samples.size() >= MAX_SAMPLES) {
                        break;
                    }
                    samples.add(ArchitectureRuleSupport.detail(detail));
                }
            }
            if (totalViolations == 0) {
                return ArchitectureRuleSupport.pass(definition());
            }
            return ArchitectureRuleSupport.result(
                    definition(), ArchitectureRuleSupport.VIOLATION, totalViolations, samples);
            // See AbstractArchitectureRule#evaluate: LinkageError is caught to degrade to an ERROR result rather
            // than aborting the scan; VirtualMachineError (e.g. OutOfMemoryError) is intentionally not caught.
        } catch (RuntimeException | LinkageError ex) {
            return ArchitectureRuleSupport.error(definition(), "Rule could not be evaluated: " + ex.getMessage());
        }
    }
}

/**
 * Flags direct use of {@code System.out} / {@code System.err}, which bypasses structured logging.
 */
final class NoStandardStreamsRule extends AbstractArchitectureRule {

    NoStandardStreamsRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-001",
                        "Classes should not access standard streams",
                        ArchitectureCategory.CODING_PRACTICES,
                        "LOW",
                        "Detects direct use of System.out or System.err instead of a logging framework.",
                        "Replace System.out / System.err calls with a logger (e.g. SLF4J) so output is structured and configurable."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
    }
}

/**
 * Flags throwing generic exception types such as {@code Exception} or {@code RuntimeException}.
 */
final class NoGenericExceptionsRule extends AbstractArchitectureRule {

    NoGenericExceptionsRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-002",
                "Classes should not throw generic exceptions",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects throwing of generic exception types such as Exception, RuntimeException, or Throwable.",
                "Throw specific, meaningful exception types so callers can handle failures precisely."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
    }
}

/**
 * Flags use of {@code java.util.logging} instead of a logging facade such as SLF4J.
 */
final class NoJavaUtilLoggingRule extends AbstractArchitectureRule {

    NoJavaUtilLoggingRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-003",
                        "Classes should not use java.util.logging",
                        ArchitectureCategory.CODING_PRACTICES,
                        "LOW",
                        "Detects direct use of java.util.logging instead of the project logging facade.",
                        "Use the project logging facade (SLF4J over Logback by default in Spring Boot) for consistent logging."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
    }
}

/**
 * Flags use of the legacy Joda-Time library instead of {@code java.time}.
 */
final class NoJodaTimeRule extends AbstractArchitectureRule {

    NoJodaTimeRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-004",
                "Classes should not use Joda-Time",
                ArchitectureCategory.CODING_PRACTICES,
                "INFO",
                "Detects use of the legacy Joda-Time library instead of the java.time API.",
                "Migrate Joda-Time usage to the standard java.time API."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;
    }
}

/**
 * Flags calls to {@code Throwable.printStackTrace()}, which writes to the standard error stream and
 * bypasses structured logging.
 */
final class NoPrintStackTraceRule extends AbstractArchitectureRule {

    NoPrintStackTraceRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-005",
                        "Classes should not call Throwable.printStackTrace()",
                        ArchitectureCategory.CODING_PRACTICES,
                        "LOW",
                        "Detects calls to Throwable.printStackTrace(), which write to System.err and bypass structured logging.",
                        "Log the exception through the project logging facade (e.g. SLF4J) instead of calling printStackTrace()."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("Throwable.printStackTrace() is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        return target.getName().equals("printStackTrace")
                                && target.getOwner().isAssignableTo(Throwable.class);
                    }
                })
                .as("Classes should not call Throwable.printStackTrace()");
    }
}

/**
 * Flags calls to {@code System.exit(int)}, which abruptly terminates the JVM.
 */
final class NoSystemExitRule extends AbstractArchitectureRule {

    NoSystemExitRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-006",
                "Classes should not call System.exit",
                ArchitectureCategory.CODING_PRACTICES,
                "MEDIUM",
                "Detects calls to System.exit(int), which abruptly terminate the JVM and bypass orderly shutdown.",
                "Let the container or application framework manage the lifecycle instead of calling System.exit()."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses().should().callMethod(System.class, "exit", int.class);
    }
}

/**
 * Flags dependencies on unsupported JDK-internal packages ({@code sun..}, {@code com.sun..},
 * {@code jdk.internal..}), which are not part of the public API and may change or disappear.
 */
final class NoJdkInternalApiRule extends AbstractArchitectureRule {

    NoJdkInternalApiRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-007",
                "Classes should not access JDK-internal APIs",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects dependencies on unsupported JDK-internal packages such as sun.., com.sun.., or jdk.internal...",
                "Depend only on public, supported APIs so the code stays portable across JDK versions."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses().should().dependOnClassesThat().resideInAnyPackage("sun..", "com.sun..", "jdk.internal..");
    }
}

/**
 * Flags use of the legacy {@code java.util.Date} / {@code Calendar} family instead of
 * {@code java.time}.
 */
final class NoLegacyDateTimeRule extends AbstractArchitectureRule {

    NoLegacyDateTimeRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-008",
                        "Classes should not use legacy date and time classes",
                        ArchitectureCategory.CODING_PRACTICES,
                        "INFO",
                        "Detects use of legacy date/time classes such as java.util.Date, Calendar, GregorianCalendar, or java.sql date types.",
                        "Prefer the java.time API (LocalDate, Instant, ZonedDateTime, ...) for clearer, immutable date/time handling."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return GeneralCodingRules.OLD_DATE_AND_TIME_CLASSES_SHOULD_NOT_BE_USED;
    }
}

/**
 * Flags use of APIs marked {@code @Deprecated}.
 */
final class NoDeprecatedApiRule extends AbstractArchitectureRule {

    NoDeprecatedApiRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-009",
                "Classes should not use deprecated APIs",
                ArchitectureCategory.CODING_PRACTICES,
                "INFO",
                "Detects access to members or types annotated with @Deprecated.",
                "Migrate to the recommended replacement API; deprecated members may be removed in future releases."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return GeneralCodingRules.DEPRECATED_API_SHOULD_NOT_BE_USED;
    }
}

/**
 * Flags field injection ({@code @Autowired} / {@code @Inject} / {@code @Value} / {@code @Resource}
 * on fields), which hampers testability and hides required dependencies.
 */
final class NoFieldInjectionRule extends AbstractArchitectureRule {

    NoFieldInjectionRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-001",
                "Classes should not use field injection",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Autowired, @Inject, @Value, or @Resource on fields instead of constructor injection.",
                "Prefer constructor injection so dependencies are explicit, final, and easy to test."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
    }
}

/**
 * Flags Spring controllers that depend directly on {@code @Repository} beans, skipping a service
 * layer.
 */
final class ControllersShouldNotDependOnRepositoriesRule extends AbstractArchitectureRule {

    ControllersShouldNotDependOnRepositoriesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-002",
                        "Controllers should not depend on repositories",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "LOW",
                        "Detects @Controller / @RestController classes that depend directly on @Repository beans, bypassing a service layer.",
                        "Introduce a service layer between controllers and repositories to keep web and persistence concerns separated."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(SpringStereotypes.CONTROLLER_ANNOTATED)
                .should()
                .dependOnClassesThat(SpringStereotypes.REPOSITORY_ANNOTATED);
    }
}

/**
 * Flags {@code @Repository} beans that depend on Spring controllers, an inverted-dependency smell.
 */
final class RepositoriesShouldNotDependOnControllersRule extends AbstractArchitectureRule {

    RepositoriesShouldNotDependOnControllersRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-003",
                        "Repositories should not depend on controllers",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Repository beans that depend on @Controller / @RestController classes, inverting the expected layering.",
                        "Keep persistence code free of web concerns; dependencies should flow from controllers toward repositories, not back."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(SpringStereotypes.REPOSITORY_ANNOTATED)
                .should()
                .dependOnClassesThat(SpringStereotypes.CONTROLLER_ANNOTATED);
    }
}

/**
 * Flags Spring beans that invoke their own {@code @Transactional} / {@code @Async} /
 * {@code @Cacheable} methods directly ({@code this.method()}), which bypasses the Spring proxy so
 * the transaction, async, or caching behaviour is silently lost.
 */
final class NoSelfInvocationOfProxiedMethodsRule extends AbstractArchitectureRule {

    NoSelfInvocationOfProxiedMethodsRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-004",
                        "Beans should not self-invoke their own proxied methods",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "HIGH",
                        "Detects direct self-invocation of @Transactional, @Async, or @Cacheable methods, which bypasses the Spring proxy and silently disables the behaviour.",
                        "Move the proxied method to a separate bean (or inject a self-reference) so the call goes through the Spring proxy."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return ProxyRules.no_classes_should_directly_call_other_methods_declared_in_the_same_class_that(
                SpringStereotypes.PROXIED_METHOD_ANNOTATED);
    }
}

/**
 * Flags Spring stereotype beans that reside in the default (unnamed) package, where component
 * scanning does not work reliably.
 */
final class StereotypesShouldNotResideInDefaultPackageRule extends AbstractArchitectureRule {

    StereotypesShouldNotResideInDefaultPackageRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-005",
                        "Spring stereotypes should not reside in the default package",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Component / @Service / @Repository / @Controller / @Configuration classes in the default (unnamed) package.",
                        "Move Spring stereotype beans into a named package so component scanning and proxying work as expected."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .that(SpringStereotypes.STEREOTYPE_ANNOTATED)
                .should()
                .haveNameMatching(".*\\..*")
                .as("Spring stereotypes should not reside in the default package");
    }
}

/**
 * Flags services that depend directly on controllers.
 */
final class ServicesShouldNotDependOnControllersRule extends AbstractArchitectureRule {

    ServicesShouldNotDependOnControllersRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-006",
                        "Services should not depend on controllers",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Service beans that depend on @Controller / @RestController classes, violating the expected layering.",
                        "Keep service layer free of web concerns; dependencies should flow from controllers toward services, not back."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(SpringStereotypes.SERVICE_ANNOTATED)
                .should()
                .dependOnClassesThat(SpringStereotypes.CONTROLLER_ANNOTATED);
    }
}

/**
 * Flags exceptions that do not have an 'Exception' suffix.
 */
final class ExceptionsShouldBeNamedExceptionRule extends AbstractArchitectureRule {

    ExceptionsShouldBeNamedExceptionRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-010",
                "Exceptions should be named ending with Exception",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects classes extending Exception or RuntimeException that do not have names ending with 'Exception'.",
                "Rename the class to end with 'Exception' so its purpose is immediately clear."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes().that().areAssignableTo(Exception.class).should().haveSimpleNameEndingWith("Exception");
    }
}

/**
 * Flags interfaces that have an 'Interface' suffix.
 */
final class InterfacesShouldNotHaveInterfaceSuffixRule extends AbstractArchitectureRule {

    InterfacesShouldNotHaveInterfaceSuffixRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-011",
                "Interfaces should not have names ending with 'Interface'",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects interfaces with names ending in 'Interface', which is an unnecessary naming convention.",
                "Rename the interface to describe its behavior or role without the 'Interface' suffix."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses().that().areInterfaces().should().haveSimpleNameEndingWith("Interface");
    }
}

/**
 * Flags loggers that are not private static final.
 */
final class LoggersShouldBePrivateStaticFinalRule extends AbstractArchitectureRule {

    LoggersShouldBePrivateStaticFinalRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-012",
                        "Loggers should be private static final",
                        ArchitectureCategory.CODING_PRACTICES,
                        "LOW",
                        "Detects logger fields that are not private, static, and final.",
                        "Make logger fields private, static, and final to avoid visibility issues and unnecessary allocations."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return fields().that()
                .haveRawType("org.slf4j.Logger")
                .or()
                .haveRawType("java.util.logging.Logger")
                .should()
                .bePrivate()
                .andShould()
                .beStatic()
                .andShould()
                .beFinal()
                .allowEmptyShould(true);
    }
}

/**
 * Flags accidental dependencies from application classes to test-only APIs.
 */
final class NoTestFrameworkDependenciesRule extends AbstractArchitectureRule {

    NoTestFrameworkDependenciesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-013",
                        "Application classes should not depend on test frameworks",
                        ArchitectureCategory.CODING_PRACTICES,
                        "MEDIUM",
                        "Detects dependencies from application classes to common test-only APIs such as JUnit, Mockito, AssertJ, Spring Test, Hamcrest, or Testcontainers.",
                        "Move test helpers and assertions to test sources; production code should not depend on test frameworks."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.junit..",
                        "org.mockito..",
                        "org.assertj..",
                        "org.hamcrest..",
                        "org.springframework.boot.test..",
                        "org.springframework.test..",
                        "org.testcontainers..");
    }
}

/**
 * Flags repository beans that depend on service beans, which reverses the usual persistence-to-
 * domain dependency direction.
 */
final class RepositoriesShouldNotDependOnServicesRule extends AbstractArchitectureRule {

    RepositoriesShouldNotDependOnServicesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-007",
                        "Repositories should not depend on services",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Repository beans that depend directly on @Service beans, coupling persistence code back to business services.",
                        "Keep repository beans focused on persistence concerns; dependencies should flow from services toward repositories, not back."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(SpringStereotypes.REPOSITORY_ANNOTATED)
                .should()
                .dependOnClassesThat(SpringStereotypes.SERVICE_ANNOTATED);
    }
}

/**
 * Flags service and repository beans that depend on servlet request/response infrastructure.
 */
final class ServicesAndRepositoriesShouldNotDependOnServletTypesRule extends AbstractArchitectureRule {

    ServicesAndRepositoriesShouldNotDependOnServletTypesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-008",
                        "Services and repositories should not depend on servlet types",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Service or @Repository beans that depend on servlet or Spring web request types.",
                        "Extract request data in the web layer and pass plain application values into services and repositories."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(SpringStereotypes.SERVICE_OR_REPOSITORY_ANNOTATED)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.servlet..", "javax.servlet..", "org.springframework.web.context.request..");
    }
}

/**
 * Flags transaction annotations on interfaces, which Spring recommends avoiding because behaviour
 * differs between proxy modes and can be silently ignored with AspectJ weaving.
 */
final class TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule extends AbstractArchitectureRule {

    TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-009",
                        "Transactional annotations should not be declared on interfaces",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Transactional on interfaces or interface methods.",
                        "Declare transaction semantics on concrete implementation classes or methods so proxy and weaving modes behave consistently."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not declare @Transactional on interfaces") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        if (!javaClass.isInterface()) {
                            return;
                        }
                        if (hasTransactionalAnnotation(javaClass)) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    "Interface " + javaClass.getName() + " is annotated with @Transactional"));
                        }
                        for (JavaMethod method : javaClass.getMethods()) {
                            if (hasTransactionalAnnotation(method)) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Interface method " + method.getFullName()
                                                + " is annotated with @Transactional"));
                            }
                        }
                    }
                })
                .as("Transactional annotations should not be declared on interfaces");
    }

    private static boolean hasTransactionalAnnotation(CanBeAnnotated annotated) {
        return annotated.isAnnotatedWith(SpringStereotypes.TRANSACTIONAL)
                || annotated.isAnnotatedWith(SpringStereotypes.JAKARTA_TRANSACTIONAL);
    }
}

/**
 * Flags private or static methods annotated with proxy-driven Spring annotations. Such methods
 * cannot be invoked through the Spring proxy and the annotation is therefore misleading.
 */
final class ProxiedMethodsShouldNotBePrivateOrStaticRule extends AbstractArchitectureRule {

    ProxiedMethodsShouldNotBePrivateOrStaticRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-010",
                "Proxy-driven methods should not be private or static",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects private or static methods annotated with @Transactional, @Async, or @Cacheable.",
                "Move the annotation to an instance method that can be invoked through a Spring proxy."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not declare private or static proxy-driven methods") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : javaClass.getMethods()) {
                            if (SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(method) && isPrivateOrStatic(method)) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Method " + method.getFullName()
                                                + " is annotated with a proxy-driven Spring annotation but is "
                                                + visibilityProblem(method)));
                            }
                        }
                    }
                })
                .as("Proxy-driven methods should not be private or static");
    }

    private static boolean isPrivateOrStatic(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return modifiers.contains(JavaModifier.PRIVATE) || modifiers.contains(JavaModifier.STATIC);
    }

    private static String visibilityProblem(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        if (modifiers.contains(JavaModifier.PRIVATE) && modifiers.contains(JavaModifier.STATIC)) {
            return "private and static";
        }
        if (modifiers.contains(JavaModifier.PRIVATE)) {
            return "private";
        }
        return "static";
    }
}

/**
 * Flags {@code @Async} methods with unsupported return types.
 */
final class AsyncMethodsShouldHaveSupportedSignaturesRule extends AbstractArchitectureRule {

    AsyncMethodsShouldHaveSupportedSignaturesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-011",
                        "Async methods should return void or Future",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Async methods that return a value type other than java.util.concurrent.Future.",
                        "Use void for fire-and-forget async work, or return Future/CompletableFuture when callers need a result."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("declare only supported @Async method signatures") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        boolean asyncClass = SpringStereotypes.ASYNC_ANNOTATED.test(javaClass);
                        for (JavaMethod method : javaClass.getMethods()) {
                            if ((asyncClass || SpringStereotypes.ASYNC_ANNOTATED.test(method))
                                    && !returnsVoidOrFuture(method)) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Async method " + method.getFullName()
                                                + " returns "
                                                + method.getRawReturnType().getName()
                                                + " instead of void or java.util.concurrent.Future"));
                            }
                        }
                    }
                })
                .as("Async methods should return void or Future");
    }

    private static boolean returnsVoidOrFuture(JavaMethod method) {
        JavaClass returnType = method.getRawReturnType();
        return returnType.isEquivalentTo(void.class) || returnType.isAssignableTo(java.util.concurrent.Future.class);
    }
}

/**
 * Flags scheduled methods with parameters or unsupported return types.
 */
final class ScheduledMethodsShouldHaveSupportedSignaturesRule extends AbstractArchitectureRule {

    ScheduledMethodsShouldHaveSupportedSignaturesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-012",
                        "Scheduled methods should have supported signatures",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Scheduled methods that accept parameters or return non-void, non-reactive values that Spring ignores.",
                        "Declare scheduled methods without parameters and return void unless using a supported deferred reactive Publisher type."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("declare only supported @Scheduled method signatures") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : javaClass.getMethods()) {
                            if (SpringStereotypes.SCHEDULED_ANNOTATED.test(method)) {
                                checkParameters(method, events);
                                checkReturnType(method, events);
                            }
                        }
                    }
                })
                .as("Scheduled methods should have supported signatures");
    }

    private static void checkParameters(JavaMethod method, ConditionEvents events) {
        if (!method.getRawParameterTypes().isEmpty()) {
            events.add(SimpleConditionEvent.violated(
                    method,
                    "Scheduled method " + method.getFullName()
                            + " declares parameters, but Spring invokes @Scheduled methods without arguments"));
        }
    }

    private static void checkReturnType(JavaMethod method, ConditionEvents events) {
        JavaClass returnType = method.getRawReturnType();
        if (returnType.isEquivalentTo(void.class) || isKnownReactiveReturnType(returnType)) {
            return;
        }
        events.add(SimpleConditionEvent.violated(
                method,
                "Scheduled method " + method.getFullName()
                        + " returns " + returnType.getName()
                        + "; synchronous @Scheduled return values are ignored"));
    }

    private static boolean isKnownReactiveReturnType(JavaClass returnType) {
        String name = returnType.getName();
        return returnType.isAssignableTo("org.reactivestreams.Publisher")
                || name.startsWith("reactor.core.publisher.")
                || name.equals("kotlinx.coroutines.flow.Flow")
                || name.equals("kotlinx.coroutines.Deferred")
                || name.startsWith("io.reactivex.")
                || name.startsWith("io.reactivex.rxjava3.");
    }
}

/**
 * Flags {@code @Async} usage in configuration classes, which Spring explicitly does not support.
 */
final class AsyncShouldNotBeUsedInConfigurationClassesRule extends AbstractArchitectureRule {

    AsyncShouldNotBeUsedInConfigurationClassesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-013",
                        "Async should not be used in configuration classes",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Async on @Configuration classes or methods declared within them.",
                        "Move asynchronous work to a regular Spring bean; @Async is not supported on methods declared in @Configuration classes."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not use @Async in @Configuration classes") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        if (!SpringStereotypes.CONFIGURATION_ANNOTATED.test(javaClass)) {
                            return;
                        }
                        if (SpringStereotypes.ASYNC_ANNOTATED.test(javaClass)) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    "Configuration class " + javaClass.getName() + " is annotated with @Async"));
                        }
                        for (JavaMethod method : javaClass.getMethods()) {
                            if (SpringStereotypes.ASYNC_ANNOTATED.test(method)) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Configuration method " + method.getFullName() + " is annotated with @Async"));
                            }
                        }
                    }
                })
                .as("Async should not be used in configuration classes");
    }
}

/**
 * Flags use of {@code AopContext.currentProxy()}, which couples application code directly to
 * Spring AOP internals and requires exposing proxies.
 */
final class NoAopContextCurrentProxyRule extends AbstractArchitectureRule {

    NoAopContextCurrentProxyRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-014",
                        "Classes should not call AopContext.currentProxy",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "LOW",
                        "Detects calls to AopContext.currentProxy(), which couples code to Spring AOP proxy internals.",
                        "Prefer refactoring to avoid self-invocation, or inject a self-reference when a proxy call is truly required."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("AopContext.currentProxy() is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        return target.getName().equals("currentProxy")
                                && target.getOwner().getName().equals("org.springframework.aop.framework.AopContext");
                    }
                })
                .as("Classes should not call AopContext.currentProxy()");
    }
}

/**
 * Flags {@code public static} fields that are not {@code final}, which expose shared, globally
 * reachable mutable state.
 */
final class NoPublicMutableStaticFieldsRule extends AbstractArchitectureRule {

    NoPublicMutableStaticFieldsRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-014",
                        "Classes should not have public mutable static fields",
                        ArchitectureCategory.CODING_PRACTICES,
                        "MEDIUM",
                        "Detects public static fields that are not final, which expose shared, globally reachable mutable state.",
                        "Make the field final so it cannot be reassigned, reduce its visibility, or move the mutable state into a managed bean."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noFields()
                .that()
                .areStatic()
                .and()
                .areNotFinal()
                .should()
                .bePublic()
                .as("Classes should not have public mutable static fields")
                .allowEmptyShould(true);
    }
}

/**
 * Flags utility classes (classes that expose only static members) that are not {@code final} or that
 * allow instantiation through a non-private constructor.
 */
final class UtilityClassesShouldBeFinalWithPrivateConstructorRule extends AbstractArchitectureRule {

    UtilityClassesShouldBeFinalWithPrivateConstructorRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-015",
                        "Utility classes should be final with a private constructor",
                        ArchitectureCategory.CODING_PRACTICES,
                        "LOW",
                        "Detects classes that expose only static members but are not final or can be instantiated through a non-private constructor.",
                        "Make utility classes final and give them a single private constructor so they cannot be instantiated or subclassed."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(
                        new ArchCondition<JavaClass>(
                                "be final with a private constructor when only static members are exposed") {
                            @Override
                            public void check(JavaClass javaClass, ConditionEvents events) {
                                if (!isUtilityClass(javaClass)) {
                                    return;
                                }
                                if (!javaClass.getModifiers().contains(JavaModifier.FINAL)) {
                                    events.add(SimpleConditionEvent.violated(
                                            javaClass, "Utility class " + javaClass.getName() + " is not final"));
                                }
                                for (JavaConstructor constructor : javaClass.getConstructors()) {
                                    if (isSynthetic(constructor)) {
                                        continue;
                                    }
                                    if (!constructor.getModifiers().contains(JavaModifier.PRIVATE)) {
                                        events.add(SimpleConditionEvent.violated(
                                                constructor,
                                                "Utility class " + javaClass.getName()
                                                        + " has a non-private constructor "
                                                        + constructor.getFullName()));
                                    }
                                }
                            }
                        })
                .as("Utility classes should be final with a private constructor");
    }

    private static boolean isUtilityClass(JavaClass javaClass) {
        if (javaClass.isInterface()
                || javaClass.isEnum()
                || javaClass.isRecord()
                || javaClass.isAnonymousClass()
                || javaClass.isLocalClass()
                || javaClass.getModifiers().contains(JavaModifier.ABSTRACT)) {
            return false;
        }
        if (SpringStereotypes.STEREOTYPE_ANNOTATED.test(javaClass)) {
            return false;
        }
        boolean hasStaticMethod = false;
        for (JavaMethod method : javaClass.getMethods()) {
            if (isSynthetic(method)) {
                continue; // ignore compiler-generated methods such as lambda bodies or bridges
            }
            if (method.getModifiers().contains(JavaModifier.STATIC)) {
                if (method.getName().equals("main")) {
                    return false; // application/entry-point classes are not utility classes
                }
                hasStaticMethod = true;
            } else {
                return false; // an instance method means the class is not a pure utility holder
            }
        }
        if (!hasStaticMethod) {
            return false;
        }
        for (JavaField field : javaClass.getFields()) {
            if (isSynthetic(field)) {
                continue; // ignore compiler-generated fields such as the synthetic outer-class reference
            }
            if (!field.getModifiers().contains(JavaModifier.STATIC)) {
                return false; // instance state means the class is not a pure utility holder
            }
        }
        return true;
    }

    private static boolean isSynthetic(JavaMember member) {
        return member.getModifiers().contains(JavaModifier.SYNTHETIC)
                || member.getModifiers().contains(JavaModifier.BRIDGE);
    }
}

/**
 * Flags {@code @ConfigurationProperties} classes whose instance fields are not {@code final}, which
 * makes their bound configuration mutable. Spring Boot favours immutable configuration through
 * records or constructor binding.
 */
final class ConfigurationPropertiesShouldBeImmutableRule extends AbstractArchitectureRule {

    ConfigurationPropertiesShouldBeImmutableRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-015",
                        "Configuration properties classes should be immutable",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "INFO",
                        "Detects @ConfigurationProperties classes with non-final instance fields instead of immutable constructor binding or records.",
                        "Bind configuration through a record or constructor with final fields so configuration state is immutable; Spring Boot favours immutable @ConfigurationProperties."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return fields().that()
                .areDeclaredInClassesThat(SpringStereotypes.CONFIGURATION_PROPERTIES_ANNOTATED)
                .and()
                .areNotStatic()
                .should()
                .beFinal()
                .as("Configuration properties classes should be immutable")
                .allowEmptyShould(true);
    }
}

/**
 * Flags dependencies among {@code @Controller} / {@code @RestController}, {@code @Service}, and
 * {@code @Repository} beans that do not follow the canonical web -&gt; service -&gt; repository
 * direction.
 *
 * <p>This is the holistic, slice-based complement to the individual stereotype dependency rules:
 * each stereotype layer may be accessed only from itself or the layer immediately above it, so the
 * dependency graph between the three layers stays directed and downward. Only dependencies whose
 * source and target are both annotated stereotypes are considered, so plain classes never trigger
 * a violation.</p>
 */
final class LayeredArchitectureDirectionRule extends AbstractArchitectureRule {

    LayeredArchitectureDirectionRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-016",
                        "Layered architecture dependencies should flow from web to service to repository",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects dependencies among @Controller/@RestController, @Service, and @Repository beans that violate the web -> service -> repository direction.",
                        "Keep dependencies flowing downward: controllers depend on services, services depend on repositories, and lower layers never depend on higher ones."));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("Web")
                .definedBy(SpringStereotypes.CONTROLLER_ANNOTATED)
                .layer("Service")
                .definedBy(SpringStereotypes.SERVICE_ANNOTATED)
                .layer("Persistence")
                .definedBy(SpringStereotypes.REPOSITORY_ANNOTATED)
                .whereLayer("Web")
                .mayOnlyBeAccessedByLayers("Web")
                .whereLayer("Service")
                .mayOnlyBeAccessedByLayers("Web", "Service")
                .whereLayer("Persistence")
                .mayOnlyBeAccessedByLayers("Service", "Persistence")
                .as("Layered architecture dependencies should flow from web to service to repository");
    }
}
