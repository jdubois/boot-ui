package io.github.jdubois.bootui.autoconfigure.architecture;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.ProxyRules;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureRuleResultDto;
import java.util.ArrayList;
import java.util.List;

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
    static final String ASYNC = "org.springframework.scheduling.annotation.Async";
    static final String CACHEABLE = "org.springframework.cache.annotation.Cacheable";

    static final DescribedPredicate<CanBeAnnotated> CONTROLLER_ANNOTATED = annotatedWith(CONTROLLER)
            .or(annotatedWith(REST_CONTROLLER))
            .as("annotated with @Controller or @RestController");

    static final DescribedPredicate<CanBeAnnotated> REPOSITORY_ANNOTATED =
            annotatedWith(REPOSITORY).as("annotated with @Repository");

    static final DescribedPredicate<CanBeAnnotated> STEREOTYPE_ANNOTATED = annotatedWith(COMPONENT)
            .or(annotatedWith(SERVICE))
            .or(annotatedWith(REPOSITORY))
            .or(annotatedWith(CONTROLLER))
            .or(annotatedWith(REST_CONTROLLER))
            .or(annotatedWith(CONFIGURATION))
            .as("annotated with a Spring stereotype");

    static final DescribedPredicate<CanBeAnnotated> PROXIED_METHOD_ANNOTATED = annotatedWith(TRANSACTIONAL)
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
        super(new ArchitectureRuleDefinition(
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
        super(new ArchitectureRuleDefinition(
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
        super(new ArchitectureRuleDefinition(
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
        super(new ArchitectureRuleDefinition(
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
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                        "Throwable.printStackTrace() is called") {
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
        return noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("sun..", "com.sun..", "jdk.internal..");
    }
}

/**
 * Flags use of the legacy {@code java.util.Date} / {@code Calendar} family instead of
 * {@code java.time}.
 */
final class NoLegacyDateTimeRule extends AbstractArchitectureRule {

    NoLegacyDateTimeRule() {
        super(new ArchitectureRuleDefinition(
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
        super(new ArchitectureRuleDefinition(
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
        super(new ArchitectureRuleDefinition(
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
        super(new ArchitectureRuleDefinition(
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
        super(new ArchitectureRuleDefinition(
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
