package io.github.jdubois.bootui.engine.architecture;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
    static final String BEAN = "org.springframework.context.annotation.Bean";
    static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";
    static final String SPRING_BOOT_CONFIGURATION = "org.springframework.boot.SpringBootConfiguration";
    static final String POST_CONSTRUCT = "jakarta.annotation.PostConstruct";
    static final String PRE_DESTROY = "jakarta.annotation.PreDestroy";
    static final String BEAN_FACTORY_POST_PROCESSOR =
            "org.springframework.beans.factory.config.BeanFactoryPostProcessor";
    static final String BEAN_POST_PROCESSOR = "org.springframework.beans.factory.config.BeanPostProcessor";

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

    static final DescribedPredicate<CanBeAnnotated> BEAN_ANNOTATED =
            annotatedWith(BEAN).as("annotated with @Bean");

    static final DescribedPredicate<CanBeAnnotated> LIFECYCLE_CALLBACK_ANNOTATED = annotatedWith(POST_CONSTRUCT)
            .or(annotatedWith(PRE_DESTROY))
            .as("annotated with @PostConstruct or @PreDestroy");

    static final DescribedPredicate<CanBeAnnotated> PROXIED_METHOD_ANNOTATED = TRANSACTIONAL_ANNOTATED
            .or(annotatedWith(ASYNC))
            .or(annotatedWith(CACHEABLE))
            .as("annotated with @Transactional, @Async, or @Cacheable");

    // Class-level @Async / @Cacheable make every method proxied, so self-invocation always loses the
    // behaviour. Class-level @Transactional is deliberately excluded here: a self-call to another method
    // of the same class simply joins the existing class-level transaction, so flagging it is noise.
    static final DescribedPredicate<CanBeAnnotated> CLASS_LEVEL_PROXY_ANNOTATED =
            annotatedWith(ASYNC).or(annotatedWith(CACHEABLE)).as("a class annotated with @Async or @Cacheable");

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
                "HIGH",
                "Detects cyclic dependencies between the top-level package slices under the application base package.",
                "Break the dependency cycle by extracting shared types or inverting one of the dependencies so packages form a directed acyclic graph.",
                "https://www.archunit.org/userguide/html/000_Index.html#_cycle_checks"));
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
                "Replace System.out / System.err calls with a logger (e.g. SLF4J) so output is structured and configurable.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html"));
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
                "Throw specific, meaningful exception types so callers can handle failures precisely.",
                "https://docs.oracle.com/javase/tutorial/essential/exceptions/index.html"));
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
                "Use the project logging facade (SLF4J over Logback by default in Spring Boot) for consistent logging.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html"));
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
                "Migrate Joda-Time usage to the standard java.time API.",
                "https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/package-summary.html"));
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
                "Log the exception through the project logging facade (e.g. SLF4J) instead of calling printStackTrace().",
                "https://docs.spring.io/spring-boot/reference/features/logging.html"));
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
 * Flags calls that forcibly terminate the JVM ({@code System.exit(int)}, {@code Runtime.exit(int)},
 * or {@code Runtime.halt(int)}), which bypass orderly application and container shutdown.
 */
final class NoSystemExitRule extends AbstractArchitectureRule {

    NoSystemExitRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-006",
                "Classes should not forcibly terminate the JVM",
                ArchitectureCategory.CODING_PRACTICES,
                "HIGH",
                "Detects calls to System.exit(int), Runtime.exit(int), or Runtime.halt(int), which abruptly terminate the JVM and bypass orderly shutdown.",
                "Let the container or application framework manage the lifecycle instead of calling System.exit() or Runtime.exit()/halt().",
                "https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#exit(int)"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .should()
                .callMethod(System.class, "exit", int.class)
                .orShould()
                .callMethod(Runtime.class, "exit", int.class)
                .orShould()
                .callMethod(Runtime.class, "halt", int.class)
                .as("Classes should not forcibly terminate the JVM");
    }
}

/**
 * Flags dependencies on unsupported JDK-internal packages ({@code sun..}, {@code jdk.internal..},
 * and the internal {@code com.sun..internal..} subtrees), which are not part of the public API and
 * may change or disappear. Exported {@code com.sun.*} APIs (for example {@code com.sun.net.httpserver}
 * or {@code com.sun.management}) are intentionally not flagged.
 */
final class NoJdkInternalApiRule extends AbstractArchitectureRule {

    NoJdkInternalApiRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-007",
                "Classes should not access JDK-internal APIs",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects dependencies on unsupported JDK-internal packages such as sun.., jdk.internal.., or com.sun..internal.. subtrees.",
                "Depend only on public, supported APIs so the code stays portable across JDK versions.",
                "https://openjdk.org/jeps/260"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("sun..", "jdk.internal..", "com.sun..internal..");
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
                "Prefer the java.time API (LocalDate, Instant, ZonedDateTime, ...) for clearer, immutable date/time handling.",
                "https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/package-summary.html"));
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
                "Migrate to the recommended replacement API; deprecated members may be removed in future releases.",
                "https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Deprecated.html"));
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
                "Prefer constructor injection so dependencies are explicit, final, and easy to test.",
                "https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html"));
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
                "MEDIUM",
                "Detects @Controller / @RestController classes that depend directly on @Repository beans, bypassing a service layer.",
                "Introduce a service layer between controllers and repositories to keep web and persistence concerns separated.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
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
                "Keep persistence code free of web concerns; dependencies should flow from controllers toward repositories, not back.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
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
                "Detects direct self-invocation of methods that are proxied through @Transactional, @Async, or @Cacheable (declared on the method, or @Async/@Cacheable declared on the class), which bypasses the Spring proxy and silently disables the behaviour.",
                "Refactor so the call goes through the Spring proxy: move the proxied method to a separate bean, or, only if necessary, inject a @Lazy self-reference and call through it.",
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return ProxyRules.no_classes_should_directly_call_other_methods_declared_in_the_same_class_that(
                new DescribedPredicate<MethodCallTarget>(
                        "are proxied through @Transactional, @Async, or @Cacheable on the method (or @Async/@Cacheable on the declaring class)") {
                    @Override
                    public boolean test(MethodCallTarget target) {
                        return SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(target)
                                || SpringStereotypes.CLASS_LEVEL_PROXY_ANNOTATED.test(target.getOwner());
                    }
                });
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
                "Move Spring stereotype beans into a named package so component scanning and proxying work as expected.",
                "https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html"));
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
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-006",
                "Services should not depend on controllers",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Service beans that depend on @Controller / @RestController classes, violating the expected layering.",
                "Keep service layer free of web concerns; dependencies should flow from controllers toward services, not back.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
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
                "Rename the class to end with 'Exception' so its purpose is immediately clear.",
                "https://www.archunit.org/userguide/html/000_Index.html#_naming_rules"));
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
                "Rename the interface to describe its behavior or role without the 'Interface' suffix.",
                "https://www.archunit.org/userguide/html/000_Index.html#_naming_rules"));
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
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-012",
                "Loggers should be private static final",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects logger fields (SLF4J, Log4j2, Commons Logging, JBoss Logging, java.util.logging, or Logback) that are not private, static, and final.",
                "Make logger fields private, static, and final to avoid visibility issues and unnecessary allocations.",
                "https://www.slf4j.org/manual.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return fields().that()
                .haveRawType("org.slf4j.Logger")
                .or()
                .haveRawType("java.util.logging.Logger")
                .or()
                .haveRawType("org.apache.logging.log4j.Logger")
                .or()
                .haveRawType("org.apache.commons.logging.Log")
                .or()
                .haveRawType("org.jboss.logging.Logger")
                .or()
                .haveRawType("ch.qos.logback.classic.Logger")
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
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-013",
                "Application classes should not depend on test frameworks",
                ArchitectureCategory.CODING_PRACTICES,
                "MEDIUM",
                "Detects dependencies from application classes to common test-only APIs such as JUnit, Mockito, AssertJ, Spring Test, Hamcrest, or Testcontainers.",
                "Move test helpers and assertions to test sources; production code should not depend on test frameworks.",
                "https://www.archunit.org/userguide/html/000_Index.html"));
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
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-007",
                "Repositories should not depend on services",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Repository beans that depend directly on @Service beans, coupling persistence code back to business services.",
                "Keep repository beans focused on persistence concerns; dependencies should flow from services toward repositories, not back.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
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
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-008",
                "Services and repositories should not depend on servlet types",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Service or @Repository beans that depend on servlet or Spring web request types.",
                "Extract request data in the web layer and pass plain application values into services and repositories.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
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
                        "Declare transaction semantics on concrete implementation classes or methods so proxy and weaving modes behave consistently.",
                        "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html"));
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
 * Flags non-public or static methods annotated with proxy-driven Spring annotations. Interface-based
 * proxies and the default transaction advisor only apply to public instance methods, so the proxy
 * behaviour can be silently skipped and the annotation is therefore misleading.
 */
final class ProxiedMethodsShouldNotBePrivateOrStaticRule extends AbstractArchitectureRule {

    ProxiedMethodsShouldNotBePrivateOrStaticRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-010",
                "Proxy-driven methods should be public and non-static",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects non-public or static methods annotated with @Transactional, @Async, or @Cacheable. Interface-based proxies and the default transaction advisor only apply to public instance methods, so the proxy behaviour can be silently skipped.",
                "Make the annotated method public and non-static so it can be invoked through a Spring proxy, or move the annotation to a method that can be.",
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not declare non-public or static proxy-driven methods") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : javaClass.getMethods()) {
                            if (SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(method)
                                    && isNonPublicOrStatic(method)) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Method " + method.getFullName()
                                                + " is annotated with a proxy-driven Spring annotation but is "
                                                + visibilityProblem(method)));
                            }
                        }
                    }
                })
                .as("Proxy-driven methods should be public and non-static");
    }

    private static boolean isNonPublicOrStatic(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return modifiers.contains(JavaModifier.STATIC) || !modifiers.contains(JavaModifier.PUBLIC);
    }

    private static String visibilityProblem(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        List<String> problems = new ArrayList<>();
        if (!modifiers.contains(JavaModifier.PUBLIC)) {
            if (modifiers.contains(JavaModifier.PRIVATE)) {
                problems.add("private");
            } else if (modifiers.contains(JavaModifier.PROTECTED)) {
                problems.add("protected");
            } else {
                problems.add("package-private");
            }
        }
        if (modifiers.contains(JavaModifier.STATIC)) {
            problems.add("static");
        }
        return String.join(" and ", problems);
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
                        "Use void for fire-and-forget async work, or return Future/CompletableFuture when callers need a result.",
                        "https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/Async.html"));
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
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-012",
                "Scheduled methods should have supported signatures",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Scheduled methods that accept parameters or return non-void, non-reactive values that Spring ignores.",
                "Declare scheduled methods without parameters and return void unless using a supported deferred reactive Publisher type.",
                "https://docs.spring.io/spring-framework/reference/integration/scheduling.html"));
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
                        "Move asynchronous work to a regular Spring bean; @Async is not supported on methods declared in @Configuration classes.",
                        "https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/Async.html"));
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
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-014",
                "Classes should not call AopContext.currentProxy",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "LOW",
                "Detects calls to AopContext.currentProxy(), which couples code to Spring AOP proxy internals.",
                "Prefer refactoring to avoid self-invocation, or inject a self-reference when a proxy call is truly required.",
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
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
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-014",
                "Classes should not have public mutable static fields",
                ArchitectureCategory.CODING_PRACTICES,
                "MEDIUM",
                "Detects public static fields that are not final, which expose shared, globally reachable mutable state.",
                "Make the field final so it cannot be reassigned, reduce its visibility, or move the mutable state into a managed bean.",
                "https://www.oracle.com/java/technologies/javase/seccodeguide.html"));
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
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-015",
                "Utility classes should be final with a private constructor",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects classes that expose only static members but are not final or can be instantiated through a non-private constructor.",
                "Make utility classes final and give them a single private constructor so they cannot be instantiated or subclassed.",
                "https://www.oracle.com/java/technologies/javase/seccodeguide.html"));
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
                        "Bind configuration through a record or constructor with final fields so configuration state is immutable; Spring Boot favours immutable @ConfigurationProperties.",
                        "https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.constructor-binding"));
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
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-016",
                "Layered architecture dependencies should flow from web to service to repository",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects dependencies among @Controller/@RestController, @Service, and @Repository beans that violate the web -> service -> repository direction.",
                "Keep dependencies flowing downward: controllers depend on services, services depend on repositories, and lower layers never depend on higher ones.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
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

/**
 * Flags direct calls between {@code @Bean} methods declared in the same class when that class is not
 * a full {@code @Configuration(proxyBeanMethods=true)}. In lite mode such a call is a plain method
 * invocation, so the Spring container does not intercept it and a second, unmanaged instance is
 * created instead of returning the shared singleton.
 */
final class LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule extends AbstractArchitectureRule {

    LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-017",
                        "Lite-mode @Bean methods should not call sibling @Bean methods",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "HIGH",
                        "Detects direct calls between @Bean methods declared in the same class when that class is not a full @Configuration(proxyBeanMethods=true). In lite mode the call bypasses the Spring container, creating a second unmanaged instance instead of the shared singleton.",
                        "Declare the class as @Configuration (the default proxyBeanMethods=true), or pass the dependency as a @Bean method parameter instead of calling the sibling @Bean method directly.",
                        "https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Bean.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not call sibling @Bean methods from lite-mode @Bean methods") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        if (isFullConfiguration(javaClass)) {
                            return;
                        }
                        Set<JavaMethod> beanMethods = new HashSet<>();
                        for (JavaMethod method : javaClass.getMethods()) {
                            if (SpringStereotypes.BEAN_ANNOTATED.test(method)) {
                                beanMethods.add(method);
                            }
                        }
                        if (beanMethods.isEmpty()) {
                            return;
                        }
                        for (JavaMethod beanMethod : beanMethods) {
                            for (JavaMethodCall call : beanMethod.getMethodCallsFromSelf()) {
                                Optional<JavaMethod> target = call.getTarget().resolveMember();
                                if (target.isEmpty()) {
                                    continue;
                                }
                                JavaMethod targetMethod = target.get();
                                if (targetMethod.equals(beanMethod) || !beanMethods.contains(targetMethod)) {
                                    continue;
                                }
                                events.add(SimpleConditionEvent.violated(
                                        beanMethod,
                                        "@Bean method " + beanMethod.getFullName()
                                                + " directly calls sibling @Bean method " + targetMethod.getFullName()
                                                + " in lite mode, bypassing the Spring container"));
                            }
                        }
                    }
                })
                .as("Lite-mode @Bean methods should not call sibling @Bean methods");
    }

    private static boolean isFullConfiguration(JavaClass javaClass) {
        boolean configuration = javaClass.isAnnotatedWith(SpringStereotypes.CONFIGURATION)
                || javaClass.isMetaAnnotatedWith(SpringStereotypes.CONFIGURATION);
        return configuration && !proxyBeanMethodsExplicitlyDisabled(javaClass);
    }

    private static boolean proxyBeanMethodsExplicitlyDisabled(JavaClass javaClass) {
        for (JavaAnnotation<?> annotation : javaClass.getAnnotations()) {
            String type = annotation.getRawType().getName();
            if (type.equals(SpringStereotypes.CONFIGURATION)
                    || type.equals(SpringStereotypes.SPRING_BOOT_APPLICATION)
                    || type.equals(SpringStereotypes.SPRING_BOOT_CONFIGURATION)) {
                Object value = annotation.get("proxyBeanMethods").orElse(Boolean.TRUE);
                if (Boolean.FALSE.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }
}

/**
 * Flags {@code @PostConstruct} / {@code @PreDestroy} lifecycle callbacks that are also annotated with
 * a proxy-driven annotation ({@code @Transactional}, {@code @Async}, or {@code @Cacheable}). Spring
 * invokes lifecycle callbacks before the bean is wrapped in its proxy (and after it is unwrapped at
 * destruction), so the proxy behaviour never applies.
 */
final class LifecycleCallbacksShouldNotBeProxyDrivenRule extends AbstractArchitectureRule {

    LifecycleCallbacksShouldNotBeProxyDrivenRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-018",
                "Lifecycle callbacks should not be proxy-driven",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "HIGH",
                "Detects @PostConstruct or @PreDestroy methods that are also annotated with @Transactional, @Async, or @Cacheable. The proxy is not active during bean initialization or destruction, so the transactional, asynchronous, or caching behaviour is silently lost.",
                "Move the transactional, asynchronous, or cached work to a separate proxied bean method and invoke it after initialization rather than annotating the lifecycle callback itself.",
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not annotate lifecycle callbacks with proxy-driven annotations") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : javaClass.getMethods()) {
                            if (SpringStereotypes.LIFECYCLE_CALLBACK_ANNOTATED.test(method)
                                    && SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(method)) {
                                events.add(
                                        SimpleConditionEvent.violated(
                                                method,
                                                "Lifecycle callback " + method.getFullName()
                                                        + " is annotated with a proxy-driven annotation (@Transactional, @Async, or @Cacheable), which does not apply during initialization or destruction"));
                            }
                        }
                    }
                })
                .as("Lifecycle callbacks should not be proxy-driven");
    }
}

/**
 * Flags methods annotated with both {@code @Async} and {@code @Transactional}. The transaction runs
 * on the async worker thread, so the caller's transaction and security context do not propagate and
 * the returned {@code Future} completes outside the transaction boundary; the combination is usually
 * unintended.
 */
final class AsyncAndTransactionalShouldNotBeCombinedRule extends AbstractArchitectureRule {

    AsyncAndTransactionalShouldNotBeCombinedRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-019",
                        "Async and transactional semantics on one method should be reviewed",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects methods annotated with both @Async and @Transactional. The transaction runs on the async worker thread, so the caller's transaction and security context do not propagate.",
                        "Review the design: usually the transactional work belongs in a separate bean method that the @Async method calls, so the transaction is scoped correctly on the async thread.",
                        "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not combine @Async and @Transactional on one method") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : javaClass.getMethods()) {
                            if (SpringStereotypes.ASYNC_ANNOTATED.test(method)
                                    && SpringStereotypes.TRANSACTIONAL_ANNOTATED.test(method)) {
                                events.add(
                                        SimpleConditionEvent.violated(
                                                method,
                                                "Method " + method.getFullName()
                                                        + " combines @Async and @Transactional; the transaction runs on the async thread and the caller's context does not propagate"));
                            }
                        }
                    }
                })
                .as("Async and transactional semantics on one method should be reviewed");
    }
}

/**
 * Flags non-static {@code @Bean} methods that return a {@code BeanFactoryPostProcessor} or
 * {@code BeanPostProcessor}. A non-static post-processor factory method forces its configuration
 * class to be instantiated very early, before bean post-processing is fully set up, which can
 * disable post-processing of other beans and trigger autowiring warnings.
 */
final class BeanPostProcessorFactoryMethodsShouldBeStaticRule extends AbstractArchitectureRule {

    BeanPostProcessorFactoryMethodsShouldBeStaticRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-021",
                        "BeanPostProcessor and BeanFactoryPostProcessor @Bean methods should be static",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects non-static @Bean methods that return a BeanPostProcessor or BeanFactoryPostProcessor, which forces the configuration class to be instantiated before post-processing is set up and can disable post-processing of other beans.",
                        "Declare these @Bean methods static so the post-processor can be created without instantiating the surrounding configuration class.",
                        "https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Bean.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(
                        new ArchCondition<JavaClass>(
                                "declare BeanPostProcessor/BeanFactoryPostProcessor @Bean methods static") {
                            @Override
                            public void check(JavaClass javaClass, ConditionEvents events) {
                                for (JavaMethod method : javaClass.getMethods()) {
                                    if (!SpringStereotypes.BEAN_ANNOTATED.test(method)
                                            || method.getModifiers().contains(JavaModifier.STATIC)) {
                                        continue;
                                    }
                                    JavaClass returnType = method.getRawReturnType();
                                    if (returnType.isAssignableTo(SpringStereotypes.BEAN_FACTORY_POST_PROCESSOR)
                                            || returnType.isAssignableTo(SpringStereotypes.BEAN_POST_PROCESSOR)) {
                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        method,
                                                        "@Bean method " + method.getFullName() + " returns "
                                                                + returnType.getName()
                                                                + " but is not static; post-processor factory methods should be static"));
                                    }
                                }
                            }
                        })
                .as("BeanPostProcessor and BeanFactoryPostProcessor @Bean methods should be static");
    }
}

/**
 * Flags dependencies that reach into another module's {@code internal} package (for example
 * {@code base.order} accessing {@code base.inventory.internal}). The literal {@code internal} segment
 * marks an encapsulation boundary; only code within the owning module (the packages above its
 * {@code internal} subpackage) may depend on it.
 */
final class InternalPackagesShouldNotBeAccessedExternallyRule extends AbstractArchitectureRule {

    InternalPackagesShouldNotBeAccessedExternallyRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-MOD-001",
                "Internal packages should not be accessed from other modules",
                ArchitectureCategory.PACKAGE_STRUCTURE,
                "HIGH",
                "Detects dependencies that reach into another module's 'internal' package, e.g. base.order accessing base.inventory.internal. Reaching across an internal boundary couples modules to each other's implementation details.",
                "Depend only on a module's public API (the packages outside its 'internal' subpackage), or move the shared type into a published package.",
                "https://docs.spring.io/spring-modulith/reference/verification.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        List<String> basePackages = context.basePackages();
        return classes()
                .should(new ArchCondition<JavaClass>("not access internal packages of other modules") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        String originPackage = javaClass.getPackageName();
                        for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                            JavaClass target = dependency.getTargetClass();
                            if (target.equals(javaClass)) {
                                continue;
                            }
                            String targetPackage = target.getPackageName();
                            if (!isUnderBasePackages(targetPackage, basePackages)) {
                                continue;
                            }
                            String modulePrefix = owningModulePrefix(targetPackage);
                            if (modulePrefix == null) {
                                continue;
                            }
                            if (originPackage.equals(modulePrefix) || originPackage.startsWith(modulePrefix + ".")) {
                                continue;
                            }
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName() + " accesses internal package " + targetPackage
                                            + " of another module (" + target.getName() + ")"));
                        }
                    }
                })
                .as("Internal packages should not be accessed from other modules");
    }

    private static boolean isUnderBasePackages(String packageName, List<String> basePackages) {
        if (basePackages.isEmpty()) {
            return true;
        }
        for (String base : basePackages) {
            if (packageName.equals(base) || packageName.startsWith(base + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String owningModulePrefix(String packageName) {
        String[] segments = packageName.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].equals("internal")) {
                if (i == 0) {
                    return null; // no owning module sits above a leading 'internal' segment
                }
                StringBuilder prefix = new StringBuilder();
                for (int j = 0; j < i; j++) {
                    if (j > 0) {
                        prefix.append('.');
                    }
                    prefix.append(segments[j]);
                }
                return prefix.toString();
            }
        }
        return null;
    }
}
