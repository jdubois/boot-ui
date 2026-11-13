package io.github.jdubois.bootui.engine.architecture;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.conditions.ArchConditions.beAnnotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static io.github.jdubois.bootui.engine.architecture.ArchitectureRuleSupport.observed;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.archunit.KotlinBytecode;
import io.github.jdubois.bootui.engine.source.LocalSourceModule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

abstract class AbstractArchitectureRule implements ArchitectureRule {

    private final ArchitectureRuleDefinition definition;

    AbstractArchitectureRule(ArchitectureRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final ArchitectureRuleDefinition definition() {
        return definition;
    }

    abstract ArchRule rule(ArchitectureContext context);

    @Override
    public ArchitectureRuleResultDto evaluate(ArchitectureContext context) {
        try {
            if (context != null) {
                context.evidence().reset();
                context = context.forCategory(definition.category());
            }
            ArchRule rule = rule(context);
            if (rule == null) {
                if (context != null) context.evidence().complete(false);
                return ArchitectureRuleSupport.skipped(definition, "Rule is not applicable to the imported classes.");
            }
            return ArchitectureRuleSupport.evaluate(definition, rule, context);
        } catch (RuntimeException | LinkageError ex) {
            return ArchitectureRuleSupport.error(
                    definition, "Rule could not be evaluated (" + ex.getClass().getSimpleName() + ").");
        }
    }
}

final class SpringStereotypes {

    static final String CONTROLLER = "org.springframework.stereotype.Controller";
    static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    static final String REPOSITORY = "org.springframework.stereotype.Repository";
    static final String COMPONENT = "org.springframework.stereotype.Component";
    static final String SERVICE = "org.springframework.stereotype.Service";
    static final String CONFIGURATION = "org.springframework.context.annotation.Configuration";
    static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    static final String VALUE = "org.springframework.beans.factory.annotation.Value";

    static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";
    static final String JAVAX_TRANSACTIONAL = "javax.transaction.Transactional";
    static final String ASYNC = "org.springframework.scheduling.annotation.Async";
    static final String CACHEABLE = "org.springframework.cache.annotation.Cacheable";
    static final String CACHE_EVICT = "org.springframework.cache.annotation.CacheEvict";
    static final String CACHE_PUT = "org.springframework.cache.annotation.CachePut";
    static final String CACHING = "org.springframework.cache.annotation.Caching";
    static final String SCHEDULED = "org.springframework.scheduling.annotation.Scheduled";
    static final String EVENT_LISTENER = "org.springframework.context.event.EventListener";
    static final String TRANSACTIONAL_EVENT_LISTENER =
            "org.springframework.transaction.event.TransactionalEventListener";
    static final String APPLICATION_MODULE_LISTENER = "org.springframework.modulith.events.ApplicationModuleListener";
    static final String LEGACY_APPLICATION_MODULE_LISTENER = "org.springframework.modulith.ApplicationModuleListener";
    static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";
    static final String BEAN = "org.springframework.context.annotation.Bean";
    static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";
    static final String SPRING_BOOT_CONFIGURATION = "org.springframework.boot.SpringBootConfiguration";
    static final String AUTO_CONFIGURATION = "org.springframework.boot.autoconfigure.AutoConfiguration";
    static final String POST_CONSTRUCT = "jakarta.annotation.PostConstruct";
    static final String PRE_DESTROY = "jakarta.annotation.PreDestroy";
    static final String BEAN_FACTORY_POST_PROCESSOR =
            "org.springframework.beans.factory.config.BeanFactoryPostProcessor";
    static final String BEAN_POST_PROCESSOR = "org.springframework.beans.factory.config.BeanPostProcessor";

    static final String RETRYABLE = "org.springframework.resilience.annotation.Retryable";
    static final String CONCURRENCY_LIMIT = "org.springframework.resilience.annotation.ConcurrencyLimit";
    static final String SPRING_RETRY_RETRYABLE = "org.springframework.retry.annotation.Retryable";

    static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";
    static final String POST_AUTHORIZE = "org.springframework.security.access.prepost.PostAuthorize";
    static final String PRE_FILTER = "org.springframework.security.access.prepost.PreFilter";
    static final String POST_FILTER = "org.springframework.security.access.prepost.PostFilter";
    static final String SECURED = "org.springframework.security.access.annotation.Secured";
    static final String ROLES_ALLOWED = "jakarta.annotation.security.RolesAllowed";

    static final String JAKARTA_INJECT = "jakarta.inject.Inject";
    static final String JAVAX_INJECT = "javax.inject.Inject";
    static final String JAVAX_RESOURCE = "javax.annotation.Resource";
    static final String JAVAX_POST_CONSTRUCT = "javax.annotation.PostConstruct";
    static final String JAVAX_PRE_DESTROY = "javax.annotation.PreDestroy";

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

    static final DescribedPredicate<CanBeAnnotated> TRANSACTIONAL_ANNOTATED = annotatedWith(TRANSACTIONAL)
            .or(annotatedWith(JAKARTA_TRANSACTIONAL))
            .as("annotated with @Transactional");

    static final DescribedPredicate<CanBeAnnotated> ASYNC_ANNOTATED =
            annotatedWith(ASYNC).as("annotated with @Async");

    static final DescribedPredicate<CanBeAnnotated> CACHE_OPERATION_ANNOTATED = annotatedWith(CACHEABLE)
            .or(annotatedWith(CACHE_EVICT))
            .or(annotatedWith(CACHE_PUT))
            .or(annotatedWith(CACHING))
            .as("annotated with a Spring cache operation");

    static final DescribedPredicate<CanBeAnnotated> CONFIGURATION_PROPERTIES_ANNOTATED =
            annotatedWith(CONFIGURATION_PROPERTIES).as("annotated with @ConfigurationProperties");

    static final DescribedPredicate<CanBeAnnotated> BEAN_ANNOTATED =
            annotatedWith(BEAN).as("annotated with @Bean");

    static final DescribedPredicate<CanBeAnnotated> LIFECYCLE_CALLBACK_ANNOTATED = annotatedWith(POST_CONSTRUCT)
            .or(annotatedWith(PRE_DESTROY))
            .as("annotated with @PostConstruct or @PreDestroy");

    static final DescribedPredicate<CanBeAnnotated> RESILIENCE_ANNOTATED = annotatedWith(RETRYABLE)
            .or(annotatedWith(CONCURRENCY_LIMIT))
            .or(annotatedWith(SPRING_RETRY_RETRYABLE))
            .as("annotated with @Retryable or @ConcurrencyLimit");

    static final DescribedPredicate<CanBeAnnotated> METHOD_SECURITY_ANNOTATED = annotatedWith(PRE_AUTHORIZE)
            .or(annotatedWith(POST_AUTHORIZE))
            .or(annotatedWith(PRE_FILTER))
            .or(annotatedWith(POST_FILTER))
            .or(annotatedWith(SECURED))
            .or(annotatedWith(ROLES_ALLOWED))
            .as("annotated with a method security annotation");

    static final DescribedPredicate<CanBeAnnotated> PROXIED_METHOD_ANNOTATED = TRANSACTIONAL_ANNOTATED
            .or(annotatedWith(ASYNC))
            .or(CACHE_OPERATION_ANNOTATED)
            .or(RESILIENCE_ANNOTATED)
            .or(METHOD_SECURITY_ANNOTATED)
            .as("annotated with @Transactional, @Async, a Spring cache operation, @Retryable, @ConcurrencyLimit,"
                    + " or a method security annotation");

    static final DescribedPredicate<CanBeAnnotated> CLASS_LEVEL_PROXY_ANNOTATED = annotatedWith(ASYNC)
            .or(CACHE_OPERATION_ANNOTATED)
            .as("a class annotated with @Async or a Spring cache operation");

    private SpringStereotypes() {}
}

final class ContainerBeans {

    private static final String SCOPE = "jakarta.inject.Scope";
    private static final String NORMAL_SCOPE = "jakarta.enterprise.context.NormalScope";
    private static final String STEREOTYPE = "jakarta.enterprise.inject.Stereotype";
    private static final String PRODUCES = "jakarta.enterprise.inject.Produces";

    private static final Set<String> BEAN_DEFINING_ANNOTATIONS = Set.of(
            SpringStereotypes.SERVICE,
            SpringStereotypes.REPOSITORY,
            SpringStereotypes.CONTROLLER,
            SpringStereotypes.REST_CONTROLLER,
            SpringStereotypes.CONFIGURATION,
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.SessionScoped",
            "jakarta.enterprise.context.ConversationScoped",
            "jakarta.enterprise.context.Dependent",
            "jakarta.inject.Singleton",
            "jakarta.ws.rs.Path",
            "jakarta.ws.rs.ext.Provider");

    private final Set<String> producedTypes = new HashSet<>();

    ContainerBeans(Iterable<JavaClass> classes) {
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                if (method.isAnnotatedWith(SpringStereotypes.BEAN) || method.isAnnotatedWith(PRODUCES)) {
                    producedTypes.add(method.getRawReturnType().getName());
                }
            }
        }
    }

    boolean isManaged(JavaClass javaClass) {
        return producedTypes.contains(javaClass.getName()) || isAnnotatedBean(javaClass);
    }

    static boolean isAnnotatedBean(JavaClass javaClass) {
        if (javaClass.isAnnotatedWith(SpringStereotypes.COMPONENT)
                || javaClass.isMetaAnnotatedWith(SpringStereotypes.COMPONENT)) {
            return true;
        }
        for (JavaAnnotation<?> annotation : javaClass.getAnnotations()) {
            JavaClass type = annotation.getRawType();
            if (BEAN_DEFINING_ANNOTATIONS.contains(type.getName())
                    || type.isAnnotatedWith(SCOPE)
                    || type.isAnnotatedWith(NORMAL_SCOPE)
                    || type.isAnnotatedWith(STEREOTYPE)) {
                return true;
            }
        }
        return false;
    }

    static boolean declaresFactoryMethods(JavaClass javaClass) {
        return ArchitectureRuleSupport.declaredMethods(javaClass).stream()
                .anyMatch(method -> method.isAnnotatedWith(SpringStereotypes.BEAN) || method.isAnnotatedWith(PRODUCES));
    }
}

final class LegacyJavaxInjection {

    private LegacyJavaxInjection() {}

    static Optional<String> ignoredAnnotation(JavaMember member, ContainerBeans beans) {
        if (member.isAnnotatedWith(SpringStereotypes.JAVAX_POST_CONSTRUCT)
                && !member.isAnnotatedWith(SpringStereotypes.POST_CONSTRUCT)) {
            return Optional.of("javax.annotation.PostConstruct");
        }
        if (member.isAnnotatedWith(SpringStereotypes.JAVAX_PRE_DESTROY)
                && !member.isAnnotatedWith(SpringStereotypes.PRE_DESTROY)) {
            return Optional.of("javax.annotation.PreDestroy");
        }
        if (!beans.isManaged(member.getOwner())) {
            return Optional.empty();
        }
        if (member.isAnnotatedWith(SpringStereotypes.JAVAX_INJECT)
                && !member.isAnnotatedWith(SpringStereotypes.JAKARTA_INJECT)
                && !member.isAnnotatedWith(SpringStereotypes.AUTOWIRED)
                && !isImplicitlyInjectedConstructor(member)) {
            return Optional.of("javax.inject.Inject");
        }
        if (member.isAnnotatedWith(SpringStereotypes.JAVAX_RESOURCE)
                && !member.isAnnotatedWith("jakarta.annotation.Resource")) {
            return Optional.of("javax.annotation.Resource");
        }
        return Optional.empty();
    }

    private static boolean isImplicitlyInjectedConstructor(JavaMember member) {
        return member instanceof JavaConstructor
                && ArchitectureRuleSupport.declaredConstructors(member.getOwner())
                                .size()
                        == 1;
    }
}

final class FreeOfPackageCyclesRule extends AbstractArchitectureRule {

    private static final int MAX_SAMPLES = 10;

    FreeOfPackageCyclesRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-PKG-001",
                "Packages should be free of cycles",
                ArchitectureCategory.PACKAGE_STRUCTURE,
                "HIGH",
                "Detects cyclic dependencies between the top-level package slices under the application base package.",
                "Break the dependency cycle by extracting shared types or inverting one of the dependencies so"
                        + " packages form a directed acyclic graph.",
                "https://www.archunit.org/userguide/html/000_Index.html#_cycle_checks"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return null; // Evaluation is handled per base package below.
    }

    @Override
    public ArchitectureRuleResultDto evaluate(ArchitectureContext context) {
        try {
            if (context != null) context.evidence().reset();
            int totalViolations = 0;
            List<String> samples = new ArrayList<>();
            List<String> retained = new ArrayList<>();
            int retentionLimit = context.violationCollector().remainingCapacity();
            for (String basePackage : sliceRoots(context.basePackages())) {
                Set<String> slices = new HashSet<>();
                for (JavaClass type : context.classes()) {
                    if (type.getPackageName().startsWith(basePackage + ".")) {
                        slices.add(type.getPackageName()
                                .substring(basePackage.length() + 1)
                                .split("\\.", 2)[0]);
                    }
                }
                if (slices.size() < 2) continue;
                context.evidence().observed();
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
                context.evidence().markUsableIf(!details.isEmpty());
                totalViolations += details.size();
                for (String detail : details) {
                    if (samples.size() < MAX_SAMPLES) samples.add(ArchitectureRuleSupport.detail(detail));
                    if (retained.size() < retentionLimit) retained.add(ArchitectureRuleSupport.detail(detail));
                    if (samples.size() >= MAX_SAMPLES && retained.size() >= retentionLimit) break;
                }
            }
            context.evidence().complete(totalViolations > 0);
            if (totalViolations == 0) {
                return ArchitectureRuleSupport.pass(definition());
            }
            context.violationCollector()
                    .record(definition().id(), totalViolations, retained, java.util.function.UnaryOperator.identity());
            return ArchitectureRuleSupport.result(
                    definition(), ArchitectureRuleSupport.VIOLATION, totalViolations, samples);
        } catch (RuntimeException | LinkageError ex) {
            return ArchitectureRuleSupport.error(
                    definition(),
                    "Rule could not be evaluated (" + ex.getClass().getSimpleName() + ").");
        }
    }

    static List<String> sliceRoots(List<String> basePackages) {
        List<String> roots = new ArrayList<>(basePackages);
        Set<String> seenParents = new LinkedHashSet<>(basePackages);
        for (int i = 0; i < basePackages.size(); i++) {
            String left = basePackages.get(i);
            for (int j = i + 1; j < basePackages.size(); j++) {
                String right = basePackages.get(j);
                String common = commonPackagePrefix(left, right);
                if (common.indexOf('.') > 0
                        && !common.equals(left)
                        && !common.equals(right)
                        && seenParents.add(common)) {
                    roots.add(common);
                }
            }
        }
        return roots;
    }

    private static String commonPackagePrefix(String left, String right) {
        String[] leftSegments = left.split("\\.");
        String[] rightSegments = right.split("\\.");
        int min = Math.min(leftSegments.length, rightSegments.length);
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < min; i++) {
            if (!leftSegments[i].equals(rightSegments[i])) {
                break;
            }
            if (i > 0) {
                prefix.append('.');
            }
            prefix.append(leftSegments[i]);
        }
        return prefix.toString();
    }
}

final class NoStandardStreamsRule extends AbstractArchitectureRule {

    NoStandardStreamsRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-001",
                "Classes should not access standard streams",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects direct use of System.out or System.err instead of a logging framework.",
                "Replace System.out / System.err calls with a logger (e.g. SLF4J) so output is structured and"
                        + " configurable.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        if (!context.classes().isEmpty()) context.evidence().observed();
        return GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
    }
}

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
        if (!context.classes().isEmpty()) context.evidence().observed();
        return GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
    }
}

final class NoJavaUtilLoggingRule extends AbstractArchitectureRule {

    NoJavaUtilLoggingRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-003",
                "Classes should not use java.util.logging",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects application code that assigns a field of a java.util.logging type, typically a"
                        + " java.util.logging.Logger field, instead of using the project logging facade on Spring."
                        + " Tuning a JUL-based library's logger without storing it is not reported. Not evaluated on"
                        + " Quarkus, where java.util.logging is a built-in logging API backed by JBoss LogManager.",
                "Use the project logging facade (SLF4J over Logback by default in Spring Boot) for consistent logging.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        if (context.platform() == ArchitecturePlatform.QUARKUS) {
            return null;
        }
        if (!context.classes().isEmpty()) context.evidence().observed();
        return GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
    }
}

final class NoSystemExitRule extends AbstractArchitectureRule {

    NoSystemExitRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-006",
                "Classes should not forcibly terminate the JVM",
                ArchitectureCategory.CODING_PRACTICES,
                "HIGH",
                "Detects calls to System.exit(int), Runtime.exit(int), or Runtime.halt(int), which abruptly"
                        + " terminate the JVM and bypass orderly shutdown. Exempts System.exit(int) called directly"
                        + " from a main entry point, which is Spring Boot's documented"
                        + " System.exit(SpringApplication.exit(context, ...)) exit-code idiom for CLI/batch apps.",
                "Let the container or application framework manage the lifecycle instead of calling System.exit() or"
                        + " Runtime.exit()/halt(). If you do need to propagate a process exit code from a CLI/batch"
                        + " application, call System.exit(SpringApplication.exit(context, ...)) from the main"
                        + " entry point only.",
                "https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#exit(int)"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(observed(DescribedPredicate.alwaysTrue(), context))
                .should()
                .callMethodWhere(
                        new DescribedPredicate<JavaMethodCall>(
                                "System.exit(int) is called from a method other than a static main entry point") {
                            @Override
                            public boolean test(JavaMethodCall call) {
                                return isSystemExit(call.getTarget()) && !isStaticMainMethod(call.getOrigin());
                            }
                        })
                .orShould()
                .callMethod(Runtime.class, "exit", int.class)
                .orShould()
                .callMethod(Runtime.class, "halt", int.class)
                .as("Classes should not forcibly terminate the JVM");
    }

    private static boolean isSystemExit(MethodCallTarget target) {
        return target.getName().equals("exit")
                && target.getOwner().isEquivalentTo(System.class)
                && target.getRawParameterTypes().size() == 1
                && target.getRawParameterTypes().get(0).isEquivalentTo(int.class);
    }

    private static boolean isStaticMainMethod(JavaCodeUnit origin) {
        if (!(origin instanceof JavaMethod method) || !method.getName().equals("main")) {
            return false;
        }
        Set<JavaModifier> modifiers = method.getModifiers();
        if (modifiers.contains(JavaModifier.PRIVATE)
                || modifiers.contains(JavaModifier.ABSTRACT)
                || !method.getRawReturnType().isEquivalentTo(void.class)) {
            return false;
        }
        List<JavaClass> parameters = method.getRawParameterTypes();
        return parameters.isEmpty()
                || (parameters.size() == 1 && parameters.get(0).isEquivalentTo(String[].class));
    }
}

final class NoJdkInternalApiRule extends AbstractArchitectureRule {

    NoJdkInternalApiRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-007",
                "Classes should not access JDK-internal APIs",
                ArchitectureCategory.CODING_PRACTICES,
                "MEDIUM",
                "Detects dependencies on unsupported JDK-internal packages such as sun.., jdk.internal.., or"
                        + " com.sun..internal.. subtrees. Since JDK 17 most of them are strongly encapsulated and need"
                        + " --add-exports or --add-opens, and sun.misc.Unsafe memory access is deprecated for removal.",
                "Depend only on public, supported APIs so the code stays portable across JDK versions: for example"
                        + " VarHandle or the Foreign Function & Memory API instead of sun.misc.Unsafe memory access.",
                "https://openjdk.org/jeps/260"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(observed(DescribedPredicate.alwaysTrue(), context))
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("sun..", "jdk.internal..", "com.sun..internal..");
    }
}

final class NoLegacyDateTimeRule extends AbstractArchitectureRule {

    private static final Set<String> LEGACY_TYPES =
            Set.of("java.util.Date", "java.util.Calendar", "java.sql.Date", "java.sql.Time", "java.sql.Timestamp");

    private static final Map<String, String> JAVA_TIME_BRIDGES = Map.ofEntries(
            Map.entry("java.util.Date.toInstant()", "java.time.Instant"),
            Map.entry("java.util.Date.from(java.time.Instant)", "java.util.Date"),
            Map.entry("java.util.Calendar.toInstant()", "java.time.Instant"),
            Map.entry("java.sql.Date.toLocalDate()", "java.time.LocalDate"),
            Map.entry("java.sql.Date.valueOf(java.time.LocalDate)", "java.sql.Date"),
            Map.entry("java.sql.Time.toLocalTime()", "java.time.LocalTime"),
            Map.entry("java.sql.Time.valueOf(java.time.LocalTime)", "java.sql.Time"),
            Map.entry("java.sql.Timestamp.toInstant()", "java.time.Instant"),
            Map.entry("java.sql.Timestamp.toLocalDateTime()", "java.time.LocalDateTime"),
            Map.entry("java.sql.Timestamp.from(java.time.Instant)", "java.sql.Timestamp"),
            Map.entry("java.sql.Timestamp.valueOf(java.time.LocalDateTime)", "java.sql.Timestamp"));

    NoLegacyDateTimeRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-008",
                "Classes should not use legacy date and time classes",
                ArchitectureCategory.CODING_PRACTICES,
                "INFO",
                "Detects dependencies on java.util.Date, java.util.Calendar, java.sql.Date, java.sql.Time, and"
                        + " java.sql.Timestamp, except calls and method references to their standard java.time"
                        + " conversion bridges. Legacy declarations, construction, and other operations remain findings.",
                "Prefer the java.time API (LocalDate, Instant, ZonedDateTime, ...) for clearer, immutable date/time"
                        + " handling. Convert values at legacy API boundaries with the standard java.time bridges"
                        + " rather than keeping legacy types in application fields or signatures.",
                "https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/package-summary.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .that(observed(DescribedPredicate.alwaysTrue(), context))
                .should(new ArchCondition<>("avoid legacy date/time dependencies except java.time conversion bridges") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                            if (LEGACY_TYPES.contains(
                                            dependency.getTargetClass().getName())
                                    && !isJavaTimeBridge(dependency)) {
                                events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                            }
                        }
                    }
                });
    }

    private static boolean isJavaTimeBridge(Dependency dependency) {
        return dependency.convertTo(JavaMethodCall.class).stream().anyMatch(call -> isJavaTimeBridge(call.getTarget()))
                || dependency.convertTo(JavaMethodReference.class).stream()
                        .anyMatch(reference -> isJavaTimeBridge(reference.getTarget()));
    }

    private static boolean isJavaTimeBridge(CodeUnitAccessTarget target) {
        String returnType = target.getRawReturnType().getName();
        if (returnType.equals(JAVA_TIME_BRIDGES.get(target.getFullName()))) {
            return true;
        }
        return target.resolveMember()
                .filter(method -> returnType.equals(JAVA_TIME_BRIDGES.get(method.getFullName())))
                .isPresent();
    }
}

final class NoDeprecatedApiRule extends AbstractArchitectureRule {

    private static final String KOTLIN_DEPRECATED = "kotlin.Deprecated";

    NoDeprecatedApiRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-009",
                "Classes should not use deprecated APIs",
                ArchitectureCategory.CODING_PRACTICES,
                "INFO",
                "Detects access to members or types annotated with @Deprecated, excluding compiler-generated"
                        + " dispatch bridges and uses inside a @Deprecated class or member.",
                "Migrate to the recommended replacement API; deprecated members may be removed in future releases.",
                "https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Deprecated.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .that(observed(DescribedPredicate.alwaysTrue(), context))
                .should(
                        new ArchCondition<JavaClass>(
                                "not access @Deprecated members or depend on @Deprecated classes") {
                            @Override
                            public void check(JavaClass javaClass, ConditionEvents events) {
                                boolean classDeprecated = isInDeprecatedClassScope(javaClass);
                                for (JavaAccess<?> access : javaClass.getAccessesFromSelf()) {
                                    checkAccess(access, classDeprecated, events);
                                }
                                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                                    checkDependency(javaClass, dependency, classDeprecated, events);
                                }
                            }
                        })
                .as("Classes should not use deprecated APIs");
    }

    private static void checkAccess(JavaAccess<?> access, boolean classDeprecated, ConditionEvents events) {
        if (KotlinBytecode.isGeneratedDispatchBridge(access.getOrigin())) {
            return;
        }
        JavaCodeUnit origin = KotlinBytecode.unwrapSuspendImplOrigin(access.getOrigin());
        Optional<? extends JavaMember> resolved = access.getTarget().resolveMember();
        JavaMember targetMember = resolved.orElse(null);
        if (targetMember != null && ArchitectureRuleSupport.isCompilerGenerated(targetMember)) {
            if (targetMember instanceof JavaMethod targetMethod) {
                targetMember = KotlinBytecode.defaultArgumentDispatchTarget(targetMethod)
                        .orElse(null);
            } else {
                targetMember = null;
            }
            if (targetMember == null) {
                return;
            }
        }
        CanBeAnnotated target = targetMember != null ? targetMember : access.getTarget();
        if (!isDeprecated(target)) {
            return;
        }
        if (!isDeprecatedForRemoval(target)
                && (classDeprecated || isInDeprecatedMemberScope(origin, new HashSet<>()))) {
            return;
        }
        String description = rewriteAccessDescription(
                access.getDescription(), access.getOrigin(), origin, resolved.orElse(null), targetMember);
        events.add(SimpleConditionEvent.violated(access, description));
    }

    private static void checkDependency(
            JavaClass javaClass, Dependency dependency, boolean classDeprecated, ConditionEvents events) {
        JavaClass targetClass = dependency.getTargetClass();
        if (!isDeprecated(targetClass)) {
            return;
        }
        boolean forRemoval = isDeprecatedForRemoval(targetClass);
        if (!forRemoval && classDeprecated) {
            return;
        }
        Set<JavaAccess> accesses = dependency.convertTo(JavaAccess.class);
        if (!accesses.isEmpty()) {
            String description = dependency.getDescription();
            boolean actionable = false;
            for (JavaAccess<?> access : accesses) {
                if (KotlinBytecode.isGeneratedDispatchBridge(access.getOrigin())) {
                    continue;
                }
                JavaCodeUnit origin = KotlinBytecode.unwrapSuspendImplOrigin(access.getOrigin());
                if (!forRemoval && isInDeprecatedMemberScope(origin, new HashSet<>())) {
                    continue;
                }
                Optional<? extends JavaMember> resolved = access.getTarget().resolveMember();
                JavaMember targetMember = resolved.orElse(null);
                if (targetMember != null && ArchitectureRuleSupport.isCompilerGenerated(targetMember)) {
                    if (targetMember instanceof JavaMethod targetMethod) {
                        targetMember = KotlinBytecode.defaultArgumentDispatchTarget(targetMethod)
                                .orElse(null);
                    } else {
                        targetMember = null;
                    }
                    if (targetMember == null) {
                        continue;
                    }
                }
                actionable = true;
                description = rewriteAccessDescription(
                        description, access.getOrigin(), origin, resolved.orElse(null), targetMember);
            }
            if (actionable) {
                events.add(SimpleConditionEvent.violated(dependency, description));
            }
            return;
        }
        String description = dependency.getDescription();
        for (JavaMember member : javaClass.getMembers()) {
            if (!description.contains(member.getDescription())) {
                continue;
            }
            if (member instanceof JavaMethod method) {
                Optional<JavaMethod> suspendDeclared = KotlinBytecode.suspendImplDeclaredMethod(method);
                if (suspendDeclared.isPresent()) {
                    if (dependency.getSourceCodeLocation().getLineNumber() <= 0
                            || (!forRemoval && isInDeprecatedMemberScope(suspendDeclared.get(), new HashSet<>()))) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(
                            dependency,
                            description.replace(
                                    method.getFullName(), suspendDeclared.get().getFullName())));
                    return;
                }
            }
            if (ArchitectureRuleSupport.isCompilerGenerated(member)) {
                return;
            }
            if (!forRemoval
                    && ((member instanceof JavaCodeUnit codeUnit
                                    && isInDeprecatedMemberScope(codeUnit, new HashSet<>()))
                            || isDeprecated(member))) {
                return;
            }
        }
        events.add(SimpleConditionEvent.violated(dependency, description));
    }

    private static String rewriteAccessDescription(
            String description,
            JavaCodeUnit rawOrigin,
            JavaCodeUnit unwrappedOrigin,
            JavaMember rawTarget,
            JavaMember unwrappedTarget) {
        String updated = description;
        if (!rawOrigin.equals(unwrappedOrigin)) {
            updated = updated.replace(rawOrigin.getFullName(), unwrappedOrigin.getFullName());
        }
        if (rawTarget != null && unwrappedTarget != null && !rawTarget.equals(unwrappedTarget)) {
            updated = updated.replace(rawTarget.getFullName(), unwrappedTarget.getFullName());
        }
        return updated;
    }

    private static boolean isInDeprecatedClassScope(JavaClass javaClass) {
        Optional<JavaClass> current = Optional.of(javaClass);
        while (current.isPresent()) {
            JavaClass type = current.get();
            if (isDeprecated(type)) {
                return true;
            }
            if (type.getEnclosingCodeUnit()
                    .filter(unit -> isInDeprecatedMemberScope(unit, new HashSet<>()))
                    .isPresent()) {
                return true;
            }
            current = type.getEnclosingClass();
        }
        return false;
    }

    private static boolean isInDeprecatedMemberScope(JavaCodeUnit origin, Set<JavaMethod> visiting) {
        origin = KotlinBytecode.unwrapSuspendImplOrigin(origin);
        if (isDeprecated(origin)) {
            return true;
        }
        if (!(origin instanceof JavaMethod method)
                || !ArchitectureRuleSupport.isCompilerGenerated(method)
                || !visiting.add(method)) {
            return false;
        }
        Set<JavaCodeUnitAccess<?>> callers = new HashSet<>(method.getCallsOfSelf());
        callers.addAll(method.getReferencesToSelf());
        if (callers.isEmpty()) {
            return false;
        }
        for (JavaCodeUnitAccess<?> caller : callers) {
            if (!caller.getOriginOwner().equals(method.getOwner())
                    || !isInDeprecatedMemberScope(caller.getOrigin(), visiting)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDeprecated(CanBeAnnotated annotated) {
        return annotated.isAnnotatedWith(Deprecated.class) || annotated.isAnnotatedWith(KOTLIN_DEPRECATED);
    }

    private static boolean isDeprecatedForRemoval(CanBeAnnotated annotated) {
        if (!(annotated instanceof com.tngtech.archunit.core.domain.properties.HasAnnotations<?> hasAnnotations)) {
            return false;
        }
        return hasAnnotations
                .tryGetAnnotationOfType(Deprecated.class.getName())
                .flatMap(annotation -> annotation.get("forRemoval"))
                .filter(Boolean.TRUE::equals)
                .isPresent();
    }
}

final class NoFieldInjectionRule extends AbstractArchitectureRule {

    private static final List<String> SPRING_INJECTION_ANNOTATIONS =
            List.of(SpringStereotypes.AUTOWIRED, SpringStereotypes.VALUE);

    private static final ArchCondition<JavaField> BE_ANNOTATED_WITH_SPRING_INJECTION_ANNOTATION =
            ArchConditions.<JavaField>beAnnotatedWith(SpringStereotypes.AUTOWIRED)
                    .or(beAnnotatedWith(SpringStereotypes.VALUE))
                    .as("be annotated with @Autowired or @Value");

    NoFieldInjectionRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-001",
                "Classes should not use field injection",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Autowired or @Value on fields instead of constructor injection.",
                "Prefer constructor injection so dependencies are explicit, final, and easy to test; in Kotlin, take"
                        + " the dependency as a constructor val instead of an @Autowired lateinit var.",
                "https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noFields()
                .that()
                .areNotStatic()
                .and(new DescribedPredicate<JavaField>("are not Kotlin constructor-property backing fields") {
                    @Override
                    public boolean test(JavaField field) {
                        return !isConstructorPropertyBackingField(field);
                    }
                })
                .and(observed(DescribedPredicate.alwaysTrue(), context))
                .should(BE_ANNOTATED_WITH_SPRING_INJECTION_ANNOTATION)
                .as("no classes should use Spring field injection");
    }

    private static boolean isConstructorPropertyBackingField(JavaField field) {
        boolean copied = false;
        for (String annotation : SPRING_INJECTION_ANNOTATIONS) {
            if (!field.isAnnotatedWith(annotation)) continue;
            if (!KotlinBytecode.isConstructorPropertyAnnotation(field, annotation)) return false;
            copied = true;
        }
        return copied;
    }
}

final class FieldsShouldNotUseStandardInjectionAnnotationsRule extends AbstractArchitectureRule {

    private static final List<String> STANDARD_INJECTION_ANNOTATIONS = List.of(
            "jakarta.inject.Inject",
            "javax.inject.Inject",
            "jakarta.annotation.Resource",
            "javax.annotation.Resource",
            "com.google.inject.Inject");

    private static final ArchCondition<JavaField> BE_ANNOTATED_WITH_STANDARD_INJECTION_ANNOTATION =
            ArchConditions.<JavaField>beAnnotatedWith("jakarta.inject.Inject")
                    .or(beAnnotatedWith("javax.inject.Inject"))
                    .or(beAnnotatedWith("jakarta.annotation.Resource"))
                    .or(beAnnotatedWith("javax.annotation.Resource"))
                    .or(beAnnotatedWith("com.google.inject.Inject"))
                    .as("be annotated with a standard injection annotation");

    private static final FieldsShouldNotUseStandardInjectionAnnotationsRule QUARKUS_LOW =
            new FieldsShouldNotUseStandardInjectionAnnotationsRule("LOW", true);

    private static final FieldsShouldNotUseStandardInjectionAnnotationsRule QUARKUS_INFO =
            new FieldsShouldNotUseStandardInjectionAnnotationsRule("INFO", false);

    private final boolean onlyPrivate;

    FieldsShouldNotUseStandardInjectionAnnotationsRule() {
        this("MEDIUM", false);
    }

    private FieldsShouldNotUseStandardInjectionAnnotationsRule(String severity, boolean onlyPrivate) {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-016",
                "Classes should not use standard-annotation field injection",
                ArchitectureCategory.CODING_PRACTICES,
                severity,
                "Detects jakarta.inject.Inject, javax.inject.Inject, jakarta.annotation.Resource, "
                        + "javax.annotation.Resource, or com.google.inject.Inject on non-static fields instead of "
                        + "constructor injection. This is the CDI/Quarkus and Guice equivalent of Spring's @Autowired "
                        + "field injection. MEDIUM on Spring; on Quarkus, where package-private @Inject field injection "
                        + "is idiomatic, LOW when a private field is injected and INFO otherwise. "
                        + "Legacy javax annotations that the container ignores are reported by ARCH-SPRING-024 instead.",
                "Prefer constructor injection so dependencies are explicit, final, and easy to test; CDI containers "
                        + "such as Quarkus' Arc inject constructor parameters just as readily as fields. In Kotlin, "
                        + "take the dependency as a constructor val instead of an injected lateinit var.",
                "https://quarkus.io/guides/cdi"));
        this.onlyPrivate = onlyPrivate;
    }

    @Override
    public ArchitectureRuleResultDto evaluate(ArchitectureContext context) {
        if (context != null
                && context.platform() == ArchitecturePlatform.QUARKUS
                && this != QUARKUS_LOW
                && this != QUARKUS_INFO) {
            ArchitectureContext categoryContext =
                    context.forCategory(definition().category());
            ContainerBeans beans = new ContainerBeans(categoryContext.classes());
            return (hasPrivateStandardInjectedField(categoryContext.classes(), beans) ? QUARKUS_LOW : QUARKUS_INFO)
                    .evaluate(context);
        }
        return super.evaluate(context);
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        ContainerBeans beans = new ContainerBeans(context.classes());
        return noFields()
                .that()
                .areNotStatic()
                .and(
                        new DescribedPredicate<JavaField>(
                                "are not Kotlin constructor-property backing fields or reported by ARCH-SPRING-023/024") {
                            @Override
                            public boolean test(JavaField field) {
                                if (onlyPrivate && !field.getModifiers().contains(JavaModifier.PRIVATE)) {
                                    return false;
                                }
                                return !isConstructorPropertyBackingField(field)
                                        && LegacyJavaxInjection.ignoredAnnotation(field, beans)
                                                .isEmpty();
                            }
                        })
                .and(observed(DescribedPredicate.alwaysTrue(), context))
                .should(BE_ANNOTATED_WITH_STANDARD_INJECTION_ANNOTATION)
                .as("no classes should use standard-annotation field injection");
    }

    private static boolean hasPrivateStandardInjectedField(JavaClasses classes, ContainerBeans beans) {
        for (JavaClass javaClass : classes) {
            for (JavaField field : javaClass.getFields()) {
                if (field.getModifiers().contains(JavaModifier.STATIC)
                        || !field.getModifiers().contains(JavaModifier.PRIVATE)
                        || isConstructorPropertyBackingField(field)
                        || LegacyJavaxInjection.ignoredAnnotation(field, beans).isPresent()) {
                    continue;
                }
                if (STANDARD_INJECTION_ANNOTATIONS.stream().anyMatch(field::isAnnotatedWith)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isConstructorPropertyBackingField(JavaField field) {
        boolean copied = false;
        for (String annotation : STANDARD_INJECTION_ANNOTATIONS) {
            if (!field.isAnnotatedWith(annotation)) continue;
            if (!KotlinBytecode.isConstructorPropertyAnnotation(field, annotation)) return false;
            copied = true;
        }
        return copied;
    }
}

final class ControllersShouldNotDependOnRepositoriesRule extends AbstractArchitectureRule {

    ControllersShouldNotDependOnRepositoriesRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-002",
                "Controllers should not depend on repositories",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "LOW",
                "Detects @Controller / @RestController classes that depend directly on @Repository beans, bypassing a"
                        + " service layer. This is a layering convention, not a defect: simple CRUD and vertical-slice"
                        + " designs legitimately call repositories from controllers.",
                "When business rules, transactions, or reuse across entry points are involved, introduce a service"
                        + " layer between controllers and repositories; for thin CRUD endpoints, dismiss the rule if"
                        + " direct repository access is the intended design.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(observed(SpringStereotypes.CONTROLLER_ANNOTATED, context))
                .should()
                .dependOnClassesThat(SpringStereotypes.REPOSITORY_ANNOTATED);
    }
}

final class RepositoriesShouldNotDependOnControllersRule extends AbstractArchitectureRule {

    RepositoriesShouldNotDependOnControllersRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-003",
                "Repositories should not depend on controllers",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Repository beans that depend on @Controller / @RestController classes, inverting the"
                        + " expected layering.",
                "Keep persistence code free of web concerns; dependencies should flow from controllers toward"
                        + " repositories, not back.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(observed(SpringStereotypes.REPOSITORY_ANNOTATED, context))
                .should()
                .dependOnClassesThat(SpringStereotypes.CONTROLLER_ANNOTATED);
    }
}

final class NoSelfInvocationOfProxiedMethodsRule extends AbstractArchitectureRule {

    NoSelfInvocationOfProxiedMethodsRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-004",
                "Beans should not self-invoke their own proxied methods",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "HIGH",
                "Detects direct self-invocation of methods that are proxied through @Transactional, @Async, or"
                        + " @Cacheable (declared on the method, or @Async/@Cacheable declared on the class), which"
                        + " bypasses the Spring proxy and silently disables the behaviour. A call from a method that"
                        + " already runs in a transaction to a @Transactional method that would only join it is not"
                        + " reported.",
                "Refactor so the call goes through the Spring proxy: move the proxied method to a separate bean, or,"
                        + " only if necessary, inject a @Lazy self-reference and call through it. Kotlin behaves"
                        + " identically here: marking the function open does not make a this-call go through the proxy.",
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        ContainerBeans beans = new ContainerBeans(context.classes());
        return noClasses()
                .should(
                        new ArchCondition<JavaClass>(
                                "directly call other methods declared in the same class that are proxied through"
                                        + " @Transactional, @Async, or @Cacheable on the method (or @Async/@Cacheable on"
                                        + " the declaring class)") {
                            @Override
                            public void check(JavaClass javaClass, ConditionEvents events) {
                                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                                    events.add(new SimpleConditionEvent(
                                            call, bypassesProxy(context, beans, call), describe(call)));
                                }
                                for (JavaMethodReference reference : javaClass.getMethodReferencesFromSelf()) {
                                    events.add(new SimpleConditionEvent(
                                            reference, bypassesProxy(context, beans, reference), describe(reference)));
                                }
                            }
                        })
                .because("it bypasses the proxy mechanism");
    }

    private static boolean bypassesProxy(
            ArchitectureContext context, ContainerBeans beans, JavaCodeUnitAccess<?> access) {
        if (context.platform() != ArchitecturePlatform.SPRING
                || !access.getOriginOwner().equals(access.getTargetOwner())
                || KotlinBytecode.isGeneratedDispatchBridge(access.getOrigin())) {
            return false;
        }
        CodeUnitAccessTarget target = access.getTarget();
        context.evidence().observed();
        Optional<JavaMethod> resolved =
                target.resolveMember().filter(JavaMethod.class::isInstance).map(JavaMethod.class::cast);
        if (resolved.isEmpty()) {
            if (couldBeProxied(access.getTargetOwner(), target, beans)) {
                context.evidence().markRequiredUnknown();
            }
            return SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(target);
        }
        if (ArchitectureRuleSupport.isCompilerGenerated(resolved.get())) {
            resolved = KotlinBytecode.defaultArgumentDispatchTarget(resolved.get());
            if (resolved.isEmpty()) {
                return false;
            }
        }
        JavaMethod resolvedMethod = resolved.get();
        boolean methodAnnotated = SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(resolvedMethod);
        boolean classAnnotated = SpringStereotypes.CLASS_LEVEL_PROXY_ANNOTATED.test(access.getTargetOwner());
        if (!methodAnnotated && !classAnnotated) {
            return false;
        }
        if (!methodAnnotated && !AsyncMethodsShouldHaveSupportedSignaturesRule.isInterceptable(resolvedMethod)) {
            return false;
        }
        return !(access instanceof JavaMethodCall call && TransactionJoin.joinsCallerTransaction(call, resolvedMethod));
    }

    private static boolean couldBeProxied(JavaClass owner, CodeUnitAccessTarget target, ContainerBeans beans) {
        if (owner.getModifiers().contains(JavaModifier.FINAL)
                && owner.getRawInterfaces().isEmpty()) {
            return false;
        }
        if (beans.isManaged(owner)
                || SpringStereotypes.CLASS_LEVEL_PROXY_ANNOTATED.test(owner)
                || SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(owner)
                || SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(target)) {
            return true;
        }
        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(owner)) {
            if (SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(method)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(JavaCodeUnitAccess<?> access) {
        String description = access.getDescription();
        JavaCodeUnit origin = KotlinBytecode.unwrapSuspendImplOrigin(access.getOrigin());
        if (!origin.equals(access.getOrigin())) {
            description = description.replace(access.getOrigin().getFullName(), origin.getFullName());
        }
        if (!access.getOriginOwner().equals(access.getTargetOwner())) {
            return description;
        }
        Optional<JavaMethod> bridge = access.getTarget()
                .resolveMember()
                .filter(JavaMethod.class::isInstance)
                .map(JavaMethod.class::cast);
        if (bridge.isEmpty()) {
            return description;
        }
        String current = description;
        return KotlinBytecode.defaultArgumentDispatchTarget(bridge.get())
                .map(declared -> current.replace(bridge.get().getFullName(), declared.getFullName()))
                .orElse(current);
    }
}

final class ServicesShouldNotDependOnControllersRule extends AbstractArchitectureRule {

    ServicesShouldNotDependOnControllersRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-006",
                "Services should not depend on controllers",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Service beans that depend on @Controller / @RestController classes, violating the expected"
                        + " layering.",
                "Keep service layer free of web concerns; dependencies should flow from controllers toward services,"
                        + " not back.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(observed(SpringStereotypes.SERVICE_ANNOTATED, context))
                .should()
                .dependOnClassesThat(SpringStereotypes.CONTROLLER_ANNOTATED);
    }
}

final class ExceptionsShouldBeNamedExceptionRule extends AbstractArchitectureRule {

    ExceptionsShouldBeNamedExceptionRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-010",
                "Exceptions should be named ending with Exception",
                ArchitectureCategory.CODING_PRACTICES,
                "INFO",
                "Detects classes extending Exception or RuntimeException that do not have names ending with"
                        + " 'Exception'. A nested variant of an exception hierarchy is exempt when an enclosing class"
                        + " carries the suffix, since it is already read as ClaimException.AlreadyAssigned at every call"
                        + " site.",
                "Rename the class to end with 'Exception' so its purpose is immediately clear, or nest it inside the"
                        + " exception type it specialises.",
                "https://docs.oracle.com/javase/tutorial/essential/exceptions/creating.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .that()
                .areAssignableTo(Exception.class)
                .should(new ArchCondition<JavaClass>("have a simple name ending with 'Exception'") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        context.evidence().observed();
                        events.add(new SimpleConditionEvent(
                                javaClass,
                                isNamedAsException(javaClass),
                                "Class " + javaClass.getName() + " does not end with 'Exception'"));
                    }
                })
                .as("Exceptions should be named ending with Exception");
    }

    private static boolean isNamedAsException(JavaClass javaClass) {
        Optional<JavaClass> current = Optional.of(javaClass);
        while (current.isPresent()) {
            if (current.get().getSimpleName().endsWith("Exception")) {
                return true;
            }
            current = current.get().getEnclosingClass();
        }
        return false;
    }
}

final class LoggersShouldBePrivateStaticFinalRule extends AbstractArchitectureRule {

    private static final Set<String> CONTAINER_MANAGED_ANNOTATIONS = Set.of(
            "jakarta.inject.Inject",
            "javax.inject.Inject",
            "org.springframework.beans.factory.annotation.Autowired",
            "jakarta.annotation.Resource");

    LoggersShouldBePrivateStaticFinalRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-012",
                "Loggers should be private final or container-managed",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects logger fields (SLF4J, Log4j2, Commons Logging, JBoss Logging, java.util.logging, or Logback)"
                        + " that are not private and final; static is optional. Exempts container-managed injection points"
                        + " (@Inject/@Autowired/jakarta.annotation.Resource, e.g. Quarkus's `@Inject Logger log;`, and"
                        + " Quarkus @LoggerName JBoss logger fields) and a protected, non-static, final logger declared in"
                        + " an abstract base class and initialized via LoggerFactory.getLogger(getClass()) so each"
                        + " subclass logs under its own name.",
                "Make logger fields private and final; either static or instance loggers are valid. For a logger"
                        + " shared with subclasses, declare it protected, non-static, and final in an abstract base class,"
                        + " initialized with LoggerFactory.getLogger(getClass()). Container-managed logger injection"
                        + " points are exempt because the container wires them, not the class itself.",
                "https://www.slf4j.org/faq.html#declared_static"));
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
                .should(
                        new ArchCondition<JavaField>("be private and final; a container-managed injection"
                                + " point; or a protected instance logger in an abstract base class") {
                            @Override
                            public void check(JavaField field, ConditionEvents events) {
                                context.evidence().observed();
                                if (isContainerManagedInjectionPoint(field, context.platform())
                                        || isPrivateFinal(field)
                                        || isProtectedAbstractBaseClassLogger(field)) {
                                    return;
                                }
                                events.add(SimpleConditionEvent.violated(
                                        field,
                                        "Logger field " + field.getFullName()
                                                + " should be private and final, with static optional (or, for a"
                                                + " base-class logger shared with subclasses, protected, final, and"
                                                + " initialized via LoggerFactory.getLogger(getClass()) in an abstract"
                                                + " class)"));
                            }
                        })
                .allowEmptyShould(true);
    }

    private static boolean isContainerManagedInjectionPoint(JavaField field, ArchitecturePlatform platform) {
        return CONTAINER_MANAGED_ANNOTATIONS.stream().anyMatch(field::isAnnotatedWith)
                || (platform == ArchitecturePlatform.QUARKUS
                        && field.getRawType().getName().equals("org.jboss.logging.Logger")
                        && field.isAnnotatedWith("io.quarkus.logging.LoggerName")
                        && !field.isAnnotatedWith("jakarta.enterprise.inject.Produces")
                        && !field.getModifiers().contains(JavaModifier.STATIC)
                        && !field.getModifiers().contains(JavaModifier.FINAL));
    }

    private static boolean isPrivateFinal(JavaField field) {
        Set<JavaModifier> modifiers = field.getModifiers();
        return modifiers.contains(JavaModifier.PRIVATE) && modifiers.contains(JavaModifier.FINAL);
    }

    private static boolean isProtectedAbstractBaseClassLogger(JavaField field) {
        Set<JavaModifier> modifiers = field.getModifiers();
        if (!modifiers.contains(JavaModifier.PROTECTED)
                || modifiers.contains(JavaModifier.STATIC)
                || !modifiers.contains(JavaModifier.FINAL)) {
            return false;
        }
        if (!field.getOwner().getModifiers().contains(JavaModifier.ABSTRACT)) {
            return false;
        }
        return field.getAccessesToSelf().stream()
                .filter(access -> access.getAccessType() == JavaFieldAccess.AccessType.SET)
                .map(JavaFieldAccess::getOrigin)
                .anyMatch(LoggersShouldBePrivateStaticFinalRule::callsGetClass);
    }

    private static boolean callsGetClass(JavaCodeUnit origin) {
        return origin.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getTarget().getName().equals("getClass")
                        && call.getTarget().getRawParameterTypes().isEmpty());
    }
}

final class NoTestFrameworkDependenciesRule extends AbstractArchitectureRule {

    NoTestFrameworkDependenciesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-013",
                        "Application classes should not depend on test frameworks",
                        ArchitectureCategory.CODING_PRACTICES,
                        "MEDIUM",
                        "Detects dependencies from application classes to common test-only APIs such as JUnit, Mockito,"
                                + " AssertJ, Hamcrest, Testcontainers, Spring Test, Quarkus's @QuarkusTest, or RestAssured."
                                + " Classes compiled into a local test output directory (target/test-classes,"
                                + " build/classes/*/test), which are on the classpath when the application runs from its tests,"
                                + " are not judged.",
                        "Move test helpers and assertions to test sources; production code should not depend on test"
                                + " frameworks.",
                        "https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(new DescribedPredicate<JavaClass>("are not compiled into a local test output directory") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return !isTestOutput(javaClass);
                    }
                })
                .and(observed(DescribedPredicate.alwaysTrue(), context))
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.junit..",
                        "org.mockito..",
                        "org.assertj..",
                        "org.hamcrest..",
                        "org.springframework.boot.test..",
                        "org.springframework.test..",
                        "org.testcontainers..",
                        "io.quarkus.test..",
                        "io.restassured..");
    }

    static boolean isTestOutput(JavaClass javaClass) {
        return javaClass
                .getSource()
                .flatMap(source -> LocalSourceModule.of(javaClass.getName(), source.getUri()))
                .filter(module -> module.sourceSet().equals("test"))
                .isPresent();
    }
}

final class RepositoriesShouldNotDependOnServicesRule extends AbstractArchitectureRule {

    RepositoriesShouldNotDependOnServicesRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-007",
                "Repositories should not depend on services",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Repository beans that depend directly on @Service beans, coupling persistence code back to"
                        + " business services.",
                "Keep repository beans focused on persistence concerns; dependencies should flow from services toward"
                        + " repositories, not back.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(observed(SpringStereotypes.REPOSITORY_ANNOTATED, context))
                .should()
                .dependOnClassesThat(SpringStereotypes.SERVICE_ANNOTATED);
    }
}

final class ServicesAndRepositoriesShouldNotDependOnServletTypesRule extends AbstractArchitectureRule {

    ServicesAndRepositoriesShouldNotDependOnServletTypesRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-008",
                "Services and repositories should not depend on web request types",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects @Service or @Repository beans that depend on servlet or reactive Spring web request types."
                        + " Exception types in those packages, such as ResponseStatusException, are not request state"
                        + " and are not reported.",
                "Extract request data in the web layer and pass plain application values into services and"
                        + " repositories.",
                "https://www.archunit.org/userguide/html/000_Index.html#_layer_checks"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(observed(SpringStereotypes.SERVICE_OR_REPOSITORY_ANNOTATED, context))
                .should()
                .dependOnClassesThat(JavaClass.Predicates.resideInAnyPackage(
                                "jakarta.servlet..",
                                "javax.servlet..",
                                "org.springframework.web.context.request..",
                                "org.springframework.web.reactive.function.server..",
                                "org.springframework.web.server..",
                                "org.springframework.http.server.reactive..")
                        .and(DescribedPredicate.not(JavaClass.Predicates.assignableTo(Throwable.class)))
                        .as("are web request types (excluding exceptions)"));
    }
}

final class TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule extends AbstractArchitectureRule {

    TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-009",
                        "Transactional annotations should not be declared on interfaces",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Transactional on interfaces or interface methods, excluding Spring Data repositories"
                                + " and their inherited fragments on Spring.",
                        "Declare transaction semantics on concrete implementation classes or methods so proxy and"
                                + " weaving modes behave consistently. Spring Data repository interfaces and their"
                                + " inherited fragments are supported transaction declaration sites; see"
                                + " https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html#transactional-query-methods",
                        "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        Set<JavaClass> repositoryInterfaces = springDataRepositoryInterfaces(context);
        return classes()
                .should(new ArchCondition<JavaClass>("not declare @Transactional on interfaces") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        if (!javaClass.isInterface() || repositoryInterfaces.contains(javaClass)) {
                            return;
                        }
                        context.evidence().observed();
                        if (hasTransactionalAnnotation(javaClass)) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    "Interface " + javaClass.getName() + " is annotated with @Transactional"));
                        }
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
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

    private static Set<JavaClass> springDataRepositoryInterfaces(ArchitectureContext context) {
        if (context.platform() != ArchitecturePlatform.SPRING) {
            return Set.of();
        }
        Set<JavaClass> repositoryInterfaces = new HashSet<>();
        for (JavaClass javaClass : context.classes()) {
            if (javaClass.isInterface() && isSpringDataRepository(javaClass)) {
                repositoryInterfaces.add(javaClass);
                repositoryInterfaces.addAll(javaClass.getAllRawInterfaces());
            }
        }
        return repositoryInterfaces;
    }

    private static boolean isSpringDataRepository(JavaClass javaClass) {
        return javaClass.isAssignableTo("org.springframework.data.repository.Repository")
                || hasRepositoryDefinition(javaClass)
                || javaClass.getAllRawInterfaces().stream()
                        .anyMatch(TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule::hasRepositoryDefinition);
    }

    private static boolean hasRepositoryDefinition(JavaClass javaClass) {
        return javaClass.isMetaAnnotatedWith("org.springframework.data.repository.RepositoryDefinition");
    }

    private static boolean hasTransactionalAnnotation(CanBeAnnotated annotated) {
        return annotated.isAnnotatedWith(SpringStereotypes.TRANSACTIONAL)
                || annotated.isAnnotatedWith(SpringStereotypes.JAKARTA_TRANSACTIONAL);
    }
}

final class ProxiedMethodsShouldNotBePrivateOrStaticRule extends AbstractArchitectureRule {

    ProxiedMethodsShouldNotBePrivateOrStaticRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-010",
                "Proxy-driven methods should be interceptable",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Detects proxy-driven annotations (@Transactional, @Async, Spring cache operations, @Retryable,"
                        + " @ConcurrencyLimit, or method security such as @PreAuthorize) on methods the active runtime"
                        + " cannot intercept. Spring class-based proxies cannot intercept private, static, or final"
                        + " methods. Quarkus Arc cannot intercept"
                        + " private methods, but supports static interception and transforms final intercepted methods by"
                        + " default.",
                "Use a non-private, non-static, non-final method for portable Spring proxy behaviour; keep it public"
                        + " when the application uses interface-based JDK proxies. In Kotlin, classes and members are"
                        + " final by default: mark them open, or apply the kotlin-spring compiler plugin, which opens"
                        + " Spring-annotated classes for you. On Quarkus, avoid private interceptor-bound methods.",
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not declare unproxyable proxy-driven methods") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                            if (!SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(method)) continue;
                            context.evidence().observed();
                            proxyabilityProblem(method, context.platform())
                                    .ifPresent(reason -> events.add(SimpleConditionEvent.violated(
                                            method,
                                            "Method " + method.getFullName()
                                                    + " is annotated with a proxy-driven annotation but is "
                                                    + reason)));
                        }
                    }
                })
                .as("Proxy-driven methods should be publicly overridable");
    }

    private static Optional<String> proxyabilityProblem(JavaMethod method, ArchitecturePlatform platform) {
        boolean springAnnotated = method.isAnnotatedWith(SpringStereotypes.TRANSACTIONAL)
                || method.isAnnotatedWith(SpringStereotypes.ASYNC)
                || SpringStereotypes.CACHE_OPERATION_ANNOTATED.test(method)
                || SpringStereotypes.RESILIENCE_ANNOTATED.test(method)
                || SpringStereotypes.METHOD_SECURITY_ANNOTATED.test(method);
        if (springAnnotated && platform == ArchitecturePlatform.SPRING) {
            return visibilityProblem(method, false);
        }
        if (method.isAnnotatedWith(SpringStereotypes.JAKARTA_TRANSACTIONAL)
                || method.isAnnotatedWith(SpringStereotypes.ROLES_ALLOWED)) {
            return visibilityProblem(method, platform == ArchitecturePlatform.QUARKUS);
        }
        return Optional.empty();
    }

    private static Optional<String> visibilityProblem(JavaMethod method, boolean quarkus) {
        Set<JavaModifier> modifiers = method.getModifiers();
        List<String> problems = new ArrayList<>();
        if (modifiers.contains(JavaModifier.PRIVATE)) {
            problems.add("private");
        }
        if (!quarkus && modifiers.contains(JavaModifier.STATIC)) {
            problems.add("static");
        }
        if (!quarkus && modifiers.contains(JavaModifier.FINAL)) {
            problems.add("final");
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join(" and ", problems));
    }
}

final class AsyncMethodsShouldHaveSupportedSignaturesRule extends AbstractArchitectureRule {

    AsyncMethodsShouldHaveSupportedSignaturesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-011",
                        "Async methods should return void or Future",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "HIGH",
                        "Detects interceptable @Async methods (on the method or declaring class) that return a type"
                                + " other than void or java.util.concurrent.Future, including Kotlin suspending functions."
                                + " Spring's async interceptor throws IllegalArgumentException on every such call.",
                        "Use void for fire-and-forget async work, or return Future/CompletableFuture when callers need"
                                + " a result. In Kotlin, launch the work in a coroutine (for example"
                                + " withContext(Dispatchers.IO)) instead of annotating a suspending function with @Async.",
                        "https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/Async.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("declare only supported @Async method signatures") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        boolean asyncClass = SpringStereotypes.ASYNC_ANNOTATED.test(javaClass);
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                            if (!asyncClass && !SpringStereotypes.ASYNC_ANNOTATED.test(method)) {
                                continue;
                            }
                            if (!isInterceptable(method)) {
                                continue;
                            }
                            context.evidence().observed();
                            if (KotlinBytecode.isSuspendFunction(method)) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Async method " + method.getFullName()
                                                + " is a Kotlin suspending function; Spring's @Async interceptor"
                                                + " throws IllegalArgumentException on every call"));
                            } else if (!returnsVoidOrFuture(method)) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Async method " + method.getFullName()
                                                + " returns "
                                                + method.getRawReturnType().getName()
                                                + " instead of void or java.util.concurrent.Future; Spring's @Async"
                                                + " interceptor throws IllegalArgumentException on every call"));
                            }
                        }
                    }
                })
                .as("Async methods should return void or Future");
    }

    static boolean returnsVoidOrFuture(JavaMethod method) {
        JavaClass returnType = method.getRawReturnType();
        return returnType.isEquivalentTo(void.class)
                || KotlinBytecode.isUnit(returnType.getName())
                || returnType.isAssignableTo(java.util.concurrent.Future.class);
    }

    static boolean isInterceptable(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return !modifiers.contains(JavaModifier.PRIVATE)
                && !modifiers.contains(JavaModifier.STATIC)
                && !modifiers.contains(JavaModifier.FINAL);
    }

    static boolean isReportedByArchSpring011(JavaMethod method) {
        return isInterceptable(method) && (KotlinBytecode.isSuspendFunction(method) || !returnsVoidOrFuture(method));
    }
}

final class ScheduledMethodsShouldHaveSupportedSignaturesRule extends AbstractArchitectureRule {

    private static final String SCHEDULES = "org.springframework.scheduling.annotation.Schedules";
    private static final Set<String> DEFERRED_TYPES = Set.of(
            "org.reactivestreams.Publisher",
            "java.util.concurrent.Flow$Publisher",
            "reactor.core.publisher.Mono",
            "reactor.core.publisher.Flux",
            "io.reactivex.rxjava3.core.Flowable",
            "io.reactivex.rxjava3.core.Observable",
            "io.reactivex.rxjava3.core.Single",
            "io.reactivex.rxjava3.core.Maybe",
            "io.reactivex.rxjava3.core.Completable",
            "kotlinx.coroutines.flow.Flow",
            "kotlinx.coroutines.Deferred",
            "io.smallrye.mutiny.Uni",
            "io.smallrye.mutiny.Multi");

    ScheduledMethodsShouldHaveSupportedSignaturesRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-012",
                "Scheduled methods should have supported signatures",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "MEDIUM",
                "Reviews direct, repeated and composed @Scheduled signatures: source parameters, non-deferred"
                        + " CompletionStage returns, and non-void returns not recognized as standard reactive types."
                        + " Kotlin suspend results are supported. Runtime adapters and scheduling activation are not"
                        + " inspected.",
                "Declare scheduled methods without source parameters. Use void/Unit, a Kotlin suspend function (with"
                        + " kotlinx-coroutines-reactor), or a supported deferred reactive type. CompletionStage is not"
                        + " deferred by Spring's standard adapter; ordinary synchronous values are ignored. Verify custom"
                        + " adapters separately.",
                "https://docs.spring.io/spring-framework/reference/integration/scheduling.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("declare only supported @Scheduled method signatures") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                            if (hasSchedule(method.getAnnotations(), new HashSet<>())) {
                                context.evidence().observed();
                                checkParameters(method, events);
                                checkReturnType(method, events);
                            }
                        }
                    }
                })
                .as("Scheduled methods should have supported signatures");
    }

    private static boolean hasSchedule(Iterable<? extends JavaAnnotation<?>> annotations, Set<String> visitedTypes) {
        for (JavaAnnotation<?> annotation : annotations) {
            String name = annotation.getRawType().getName();
            if (name.equals(SpringStereotypes.SCHEDULED)) {
                return true;
            }
            if (name.equals(SCHEDULES)) {
                Object value = annotation.get("value").orElse(null);
                if (value instanceof JavaAnnotation<?>[] schedules && schedules.length > 0) {
                    return true;
                }
            } else if (visitedTypes.add(name)
                    && hasSchedule(annotation.getRawType().getAnnotations(), visitedTypes)) {
                return true;
            }
        }
        return false;
    }

    private static void checkParameters(JavaMethod method, ConditionEvents events) {
        int sourceParameters = method.getRawParameterTypes().size() - (isSuspendFunction(method) ? 1 : 0);
        if (sourceParameters > 0) {
            events.add(SimpleConditionEvent.violated(
                    method,
                    "Scheduled method " + method.getFullName()
                            + " declares parameters, but Spring invokes @Scheduled methods without arguments"));
        }
    }

    private static void checkReturnType(JavaMethod method, ConditionEvents events) {
        if (isSuspendFunction(method)) {
            return;
        }
        JavaClass returnType = method.getRawReturnType();
        if (returnType.isEquivalentTo(void.class)
                || KotlinBytecode.isUnit(returnType.getName())
                || isKnownReactiveReturnType(returnType)) {
            return;
        }
        events.add(SimpleConditionEvent.violated(
                method,
                "Scheduled method " + method.getFullName()
                        + " returns " + returnType.getName()
                        + (returnType.isAssignableTo(java.util.concurrent.CompletionStage.class)
                                ? "; Spring's standard CompletionStage adapter is non-deferred and is rejected for"
                                        + " reactive scheduling when active; without that adapter the return value is"
                                        + " ignored"
                                : "; not recognized as a standard deferred reactive type; synchronous @Scheduled"
                                        + " return values are ignored (custom adapters are not inspected)")));
    }

    private static boolean isKnownReactiveReturnType(JavaClass returnType) {
        return DEFERRED_TYPES.stream().anyMatch(returnType::isAssignableTo);
    }

    private static boolean isSuspendFunction(JavaMethod method) {
        return KotlinBytecode.isKotlinClass(method.getOwner())
                && method.getRawReturnType().isEquivalentTo(Object.class)
                && KotlinBytecode.isSuspendFunction(method);
    }
}

final class AsyncShouldNotBeUsedInConfigurationClassesRule extends AbstractArchitectureRule {

    AsyncShouldNotBeUsedInConfigurationClassesRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-013",
                        "Async should not be used in configuration classes",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @Async on @Configuration classes or methods declared within them.",
                        "Move asynchronous work to a regular Spring bean; @Async is not supported on methods declared"
                                + " in @Configuration classes.",
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
                        context.evidence().observed();
                        if (SpringStereotypes.ASYNC_ANNOTATED.test(javaClass)) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    "Configuration class " + javaClass.getName() + " is annotated with @Async"));
                        }
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
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

final class NoAopContextCurrentProxyRule extends AbstractArchitectureRule {

    NoAopContextCurrentProxyRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-014",
                "Classes should not call AopContext.currentProxy",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "LOW",
                "Detects calls to AopContext.currentProxy(), which couples code to Spring AOP proxy internals.",
                "Prefer refactoring to avoid self-invocation, or inject a self-reference when a proxy call is truly"
                        + " required.",
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noClasses()
                .that(observed(DescribedPredicate.alwaysTrue(), context))
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

final class NoPublicMutableStaticFieldsRule extends AbstractArchitectureRule {

    NoPublicMutableStaticFieldsRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-014",
                "Classes should not have public mutable static fields",
                ArchitectureCategory.CODING_PRACTICES,
                "MEDIUM",
                "Detects public static fields that are not final, which expose shared, globally reachable mutable"
                        + " state.",
                "Make the field final so it cannot be reassigned, reduce its visibility, or move the mutable state"
                        + " into a managed bean.",
                "https://www.oracle.com/java/technologies/javase/seccodeguide.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return noFields()
                .that()
                .areStatic()
                .and()
                .areNotFinal()
                .and(observed(DescribedPredicate.alwaysTrue(), context))
                .should()
                .bePublic()
                .as("Classes should not have public mutable static fields")
                .allowEmptyShould(true);
    }
}

final class UtilityClassesShouldBeFinalWithPrivateConstructorRule extends AbstractArchitectureRule {

    UtilityClassesShouldBeFinalWithPrivateConstructorRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-CODE-015",
                "Utility classes should be final with a private constructor",
                ArchitectureCategory.CODING_PRACTICES,
                "LOW",
                "Detects classes that expose only static members but are not final or can be instantiated through a"
                        + " non-private constructor.",
                "Make utility classes final and give them a single private constructor so they cannot be instantiated"
                        + " or subclassed.",
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
                                context.evidence().observed();
                                if (!javaClass.getModifiers().contains(JavaModifier.FINAL)) {
                                    events.add(SimpleConditionEvent.violated(
                                            javaClass, "Utility class " + javaClass.getName() + " is not final"));
                                }
                                for (JavaConstructor constructor :
                                        ArchitectureRuleSupport.declaredConstructors(javaClass)) {
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
        if (KotlinBytecode.isCompilerGenerated(javaClass)) {
            return false;
        }
        if (javaClass.isInterface()
                || javaClass.isEnum()
                || javaClass.isRecord()
                || javaClass.isAnonymousClass()
                || javaClass.isLocalClass()
                || javaClass.getModifiers().contains(JavaModifier.ABSTRACT)) {
            return false;
        }
        if (!javaClass
                        .getRawSuperclass()
                        .map(superclass -> superclass.isEquivalentTo(Object.class))
                        .orElse(true)
                || !javaClass.getRawInterfaces().isEmpty()
                || !javaClass.getSubclasses().isEmpty()) {
            return false;
        }
        if (ContainerBeans.isAnnotatedBean(javaClass) || ContainerBeans.declaresFactoryMethods(javaClass)) {
            return false;
        }
        boolean hasStaticMethod = false;
        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
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
        for (JavaField field : ArchitectureRuleSupport.declaredFields(javaClass)) {
            if (!field.getModifiers().contains(JavaModifier.STATIC)) {
                return false; // instance state means the class is not a pure utility holder
            }
        }
        return true;
    }
}

final class ConfigurationPropertiesShouldBeImmutableRule extends AbstractArchitectureRule {

    ConfigurationPropertiesShouldBeImmutableRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-015",
                        "Configuration properties classes should be immutable",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "INFO",
                        "Detects @ConfigurationProperties classes with non-final instance fields instead of immutable"
                                + " constructor binding or records.",
                        "Bind configuration through a record or constructor with final fields so configuration state"
                                + " is immutable; Spring Boot favours immutable @ConfigurationProperties.",
                        "https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.constructor-binding"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return fields().that()
                .areDeclaredInClassesThat(SpringStereotypes.CONFIGURATION_PROPERTIES_ANNOTATED)
                .and()
                .areNotStatic()
                .and(observed(DescribedPredicate.alwaysTrue(), context))
                .should()
                .beFinal()
                .as("Configuration properties classes should be immutable")
                .allowEmptyShould(true);
    }
}

final class LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule extends AbstractArchitectureRule {

    LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-017",
                        "Lite-mode @Bean methods should not call sibling @Bean methods",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "HIGH",
                        "Detects direct calls between @Bean methods declared in the same class when that class is not"
                                + " a full @Configuration(proxyBeanMethods=true) (including @AutoConfiguration and"
                                + " composed proxyBeanMethods=false annotations), or when the target sibling @Bean"
                                + " method is static (which CGLIB cannot intercept). The call is a plain Java"
                                + " invocation that bypasses the container, so it creates whatever the factory method"
                                + " returns instead of resolving the managed bean.",
                        "Declare the class as @Configuration (the default proxyBeanMethods=true) with non-static @Bean"
                                + " methods, or pass the dependency as a @Bean method parameter instead of calling the"
                                + " sibling @Bean method directly.",
                        "https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Bean.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not call sibling @Bean methods from lite-mode @Bean methods") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        Set<JavaMethod> beanMethods = new HashSet<>();
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                            if (SpringStereotypes.BEAN_ANNOTATED.test(method)) {
                                beanMethods.add(method);
                            }
                        }
                        if (beanMethods.isEmpty()) {
                            return;
                        }
                        boolean fullConfiguration = isFullConfiguration(javaClass);
                        if (fullConfiguration
                                && beanMethods.stream()
                                        .noneMatch(
                                                method -> method.getModifiers().contains(JavaModifier.STATIC))) {
                            return;
                        }
                        context.evidence().observed();
                        for (JavaMethod beanMethod : beanMethods) {
                            for (JavaMethodCall call : beanMethod.getMethodCallsFromSelf()) {
                                if (!call.getOriginOwner().equals(call.getTargetOwner())) {
                                    continue;
                                }
                                Optional<JavaMethod> target = call.getTarget().resolveMember();
                                if (target.isEmpty()) {
                                    context.evidence().markRequiredUnknown();
                                    continue;
                                }
                                JavaMethod targetMethod = target.get();
                                if (ArchitectureRuleSupport.isCompilerGenerated(targetMethod)) {
                                    targetMethod = KotlinBytecode.defaultArgumentDispatchTarget(targetMethod)
                                            .orElse(null);
                                    if (targetMethod == null) {
                                        continue;
                                    }
                                }
                                if (targetMethod.equals(beanMethod) || !beanMethods.contains(targetMethod)) {
                                    continue;
                                }
                                if (fullConfiguration
                                        && !targetMethod.getModifiers().contains(JavaModifier.STATIC)) {
                                    continue;
                                }
                                events.add(SimpleConditionEvent.violated(
                                        call,
                                        "@Bean method " + beanMethod.getFullName()
                                                + " directly calls sibling @Bean method " + targetMethod.getFullName()
                                                + (fullConfiguration
                                                        ? " (declared static, which CGLIB cannot intercept)"
                                                        : " in lite mode")
                                                + ", bypassing the Spring container"));
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
        return hasProxyBeanMethodsDisabled(javaClass.getAnnotations(), new HashSet<>());
    }

    private static boolean hasProxyBeanMethodsDisabled(
            Iterable<? extends JavaAnnotation<?>> annotations, Set<String> visited) {
        for (JavaAnnotation<?> annotation : annotations) {
            JavaClass rawType = annotation.getRawType();
            String type = rawType.getName();
            if (type.equals(SpringStereotypes.AUTO_CONFIGURATION)) {
                return true;
            }
            if (type.equals(SpringStereotypes.CONFIGURATION)
                    || type.equals(SpringStereotypes.SPRING_BOOT_APPLICATION)
                    || type.equals(SpringStereotypes.SPRING_BOOT_CONFIGURATION)) {
                Object value = annotation.get("proxyBeanMethods").orElse(Boolean.TRUE);
                if (Boolean.FALSE.equals(value)) {
                    return true;
                }
            } else if (Boolean.FALSE.equals(annotation.get("proxyBeanMethods").orElse(null))) {
                return true;
            }
            if (visited.add(type) && hasProxyBeanMethodsDisabled(rawType.getAnnotations(), visited)) {
                return true;
            }
        }
        return false;
    }
}

final class LifecycleCallbacksShouldNotBeProxyDrivenRule extends AbstractArchitectureRule {

    LifecycleCallbacksShouldNotBeProxyDrivenRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-018",
                "Lifecycle callbacks should not be proxy-driven",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "HIGH",
                "Detects @PostConstruct or @PreDestroy methods that are also annotated with @Transactional, @Async,"
                        + " @Cacheable, @CachePut, @CacheEvict, or @Caching. The proxy is not active during bean"
                        + " initialization or destruction, so the transactional, asynchronous, or caching behaviour is"
                        + " silently lost.",
                "Move the transactional, asynchronous, or cached work to a separate proxied bean method and invoke it"
                        + " after initialization rather than annotating the lifecycle callback itself.",
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not annotate lifecycle callbacks with proxy-driven annotations") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                            if (SpringStereotypes.LIFECYCLE_CALLBACK_ANNOTATED.test(method)) {
                                context.evidence().observed();
                                if (!SpringStereotypes.PROXIED_METHOD_ANNOTATED.test(method)) continue;
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Lifecycle callback " + method.getFullName()
                                                + " is annotated with a proxy-driven annotation"
                                                + " (@Transactional, @Async, or a Spring cache operation),"
                                                + " which does not apply during initialization or"
                                                + " destruction"));
                            }
                        }
                    }
                })
                .as("Lifecycle callbacks should not be proxy-driven");
    }
}

final class AsyncAndTransactionalShouldNotBeCombinedRule extends AbstractArchitectureRule {

    private static final String BEFORE_COMMIT_PHASE = "BEFORE_COMMIT";

    private static final String AFTER_COMMIT_PHASE = "AFTER_COMMIT";

    AsyncAndTransactionalShouldNotBeCombinedRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-019",
                        "Async and transactional semantics on one method should be reviewed",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects methods annotated with both @Async and @Transactional. The transaction runs on the"
                                + " async worker thread, so the caller's transaction and security context do not"
                                + " propagate. Transactional event listeners that run after the publishing transaction"
                                + " completed are excluded, including Spring Modulith's @ApplicationModuleListener,"
                                + " because a separate transaction is the intended shape there; a BEFORE_COMMIT listener"
                                + " is still reported.",
                        "Review the design: usually the transactional work belongs in a separate bean method that the"
                                + " @Async method calls, so the transaction is scoped correctly on the async thread. For"
                                + " an asynchronous BEFORE_COMMIT listener, move to the AFTER_COMMIT phase (or drop"
                                + " @Async) so the listener no longer runs beside the transaction it observes.",
                        "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not combine @Async and @Transactional on one method") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                            if (!SpringStereotypes.ASYNC_ANNOTATED.test(method)
                                    || !SpringStereotypes.TRANSACTIONAL_ANNOTATED.test(method)) {
                                continue;
                            }
                            context.evidence().observed();
                            Optional<String> phase = transactionalEventListenerPhase(method);
                            if (phase.isEmpty()) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Method " + method.getFullName()
                                                + " combines @Async and @Transactional; the transaction runs"
                                                + " on the async thread and the caller's context does not"
                                                + " propagate"));
                            } else if (BEFORE_COMMIT_PHASE.equals(phase.get())) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Asynchronous transactional event listener " + method.getFullName()
                                                + " listens in the BEFORE_COMMIT phase; it runs on the async"
                                                + " thread while the publishing transaction is still open, so"
                                                + " its own transaction observes state that transaction has"
                                                + " not committed"));
                            }
                        }
                    }
                })
                .as("Async and transactional semantics on one method should be reviewed");
    }

    private static Optional<String> transactionalEventListenerPhase(JavaMethod method) {
        Optional<String> direct = method.tryGetAnnotationOfType(SpringStereotypes.TRANSACTIONAL_EVENT_LISTENER)
                .map(AsyncAndTransactionalShouldNotBeCombinedRule::phaseOf);
        if (direct.isPresent()) {
            return direct;
        }
        for (JavaAnnotation<?> annotation : method.getAnnotations()) {
            JavaClass annotationType = annotation.getRawType();
            if (SpringStereotypes.APPLICATION_MODULE_LISTENER.equals(annotationType.getName())
                    || SpringStereotypes.LEGACY_APPLICATION_MODULE_LISTENER.equals(annotationType.getName())) {
                return Optional.of(AFTER_COMMIT_PHASE);
            }
            Optional<String> composed = annotationType
                    .tryGetAnnotationOfType(SpringStereotypes.TRANSACTIONAL_EVENT_LISTENER)
                    .map(AsyncAndTransactionalShouldNotBeCombinedRule::phaseOf);
            if (composed.isPresent()) {
                return composed;
            }
            if (annotationType.isMetaAnnotatedWith(SpringStereotypes.TRANSACTIONAL_EVENT_LISTENER)) {
                return Optional.of(AFTER_COMMIT_PHASE);
            }
        }
        return Optional.empty();
    }

    private static String phaseOf(JavaAnnotation<?> annotation) {
        Object phase = annotation.get("phase").orElse(null);
        if (phase instanceof JavaEnumConstant enumConstant) {
            return enumConstant.name();
        }
        return phase == null ? AFTER_COMMIT_PHASE : String.valueOf(phase);
    }
}

final class AsyncEventListenersShouldReturnVoidRule extends AbstractArchitectureRule {

    AsyncEventListenersShouldReturnVoidRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-020",
                        "Async event listeners should return void",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects @EventListener methods that run through @Async (on the method or declaring class) and"
                                + " return a Future (or a non-void value not already reported by ARCH-SPRING-011)."
                                + " Spring cannot publish that value as a follow-up event for an asynchronous"
                                + " listener.",
                        "Return void from the asynchronous listener. To publish a follow-up event, inject"
                                + " ApplicationEventPublisher and publish it explicitly.",
                        "https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/event/EventListener.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("declare void-returning asynchronous event listeners") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        boolean classLevelAsync = SpringStereotypes.ASYNC_ANNOTATED.test(javaClass);
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                            if (method.isAnnotatedWith(SpringStereotypes.EVENT_LISTENER)
                                    && (classLevelAsync || SpringStereotypes.ASYNC_ANNOTATED.test(method))) {
                                if (AsyncMethodsShouldHaveSupportedSignaturesRule.isReportedByArchSpring011(method)) {
                                    continue;
                                }
                                context.evidence().observed();
                                JavaClass returnType = method.getRawReturnType();
                                if (returnType.isEquivalentTo(void.class)
                                        || KotlinBytecode.isUnit(returnType.getName())) {
                                    continue;
                                }
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Asynchronous event listener " + method.getFullName()
                                                + " returns "
                                                + returnType.getName()
                                                + "; Spring cannot publish return values from asynchronous listeners"));
                            }
                        }
                    }
                })
                .as("Async event listeners should return void");
    }
}

final class LegacyJavaxTransactionalShouldBeMigratedRule extends AbstractArchitectureRule {

    LegacyJavaxTransactionalShouldBeMigratedRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-022",
                        "Legacy javax.transaction.Transactional should be migrated",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "HIGH",
                        "Detects javax.transaction.Transactional on classes or methods. Spring Framework 7 and Quarkus 3"
                                + " only recognize the Jakarta EE annotations, so the intended transaction boundary is"
                                + " ignored.",
                        "Replace javax.transaction.Transactional with"
                                + " org.springframework.transaction.annotation.Transactional or"
                                + " jakarta.transaction.Transactional and use the corresponding Jakarta-era dependency.",
                        "https://github.com/spring-projects/spring-framework/blob/v7.0.8/spring-tx/src/main/java/org/springframework/transaction/annotation/AnnotationTransactionAttributeSource.java"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        return classes()
                .should(new ArchCondition<JavaClass>("not use legacy javax.transaction.Transactional") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        context.evidence().observed();
                        if (javaClass.isAnnotatedWith(SpringStereotypes.JAVAX_TRANSACTIONAL)) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    "Class " + javaClass.getName()
                                            + " uses legacy javax.transaction.Transactional, which Spring"
                                            + " Framework 7 and Quarkus 3 ignore"));
                        }
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                            if (method.isAnnotatedWith(SpringStereotypes.JAVAX_TRANSACTIONAL)) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Method " + method.getFullName()
                                                + " uses legacy javax.transaction.Transactional, which Spring"
                                                + " Framework 7 and Quarkus 3 ignore"));
                            }
                        }
                    }
                })
                .as("Legacy javax.transaction.Transactional should be migrated");
    }
}

final class BeanPostProcessorFactoryMethodsShouldBeStaticRule extends AbstractArchitectureRule {

    BeanPostProcessorFactoryMethodsShouldBeStaticRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-021",
                        "BeanPostProcessor and BeanFactoryPostProcessor @Bean methods should be static",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "MEDIUM",
                        "Detects non-static @Bean methods that return a BeanPostProcessor or BeanFactoryPostProcessor,"
                                + " which forces the configuration class to be instantiated before post-processing is set"
                                + " up and can disable post-processing of other beans.",
                        "Declare these @Bean methods static so the post-processor can be created without instantiating"
                                + " the surrounding configuration class.",
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
                                for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                                    if (!SpringStereotypes.BEAN_ANNOTATED.test(method)) {
                                        continue;
                                    }
                                    JavaClass returnType = method.getRawReturnType();
                                    if (returnType.isAssignableTo(SpringStereotypes.BEAN_FACTORY_POST_PROCESSOR)
                                            || returnType.isAssignableTo(SpringStereotypes.BEAN_POST_PROCESSOR)) {
                                        context.evidence().observed();
                                        if (method.getModifiers().contains(JavaModifier.STATIC)) continue;
                                        events.add(SimpleConditionEvent.violated(
                                                method,
                                                "@Bean method " + method.getFullName() + " returns "
                                                        + returnType.getName()
                                                        + " but is not static; post-processor factory methods"
                                                        + " should be static"));
                                    }
                                }
                            }
                        })
                .as("BeanPostProcessor and BeanFactoryPostProcessor @Bean methods should be static");
    }
}

final class InternalPackagesShouldNotBeAccessedExternallyRule extends AbstractArchitectureRule {

    InternalPackagesShouldNotBeAccessedExternallyRule() {
        super(new ArchitectureRuleDefinition(
                "ARCH-MOD-001",
                "Internal packages should not be accessed from other modules",
                ArchitectureCategory.PACKAGE_STRUCTURE,
                "HIGH",
                "Detects dependencies that reach into another module's 'internal' package, e.g. base.order accessing"
                        + " base.inventory.internal. Reaching across an internal boundary couples modules to each other's"
                        + " implementation details.",
                "Depend only on a module's public API (the packages outside its 'internal' subpackage), or move the"
                        + " shared type into a published package.",
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
                            String modulePrefix = owningModulePrefix(targetPackage, basePackages);
                            if (modulePrefix == null) {
                                continue;
                            }
                            context.evidence().observed();
                            if (originPackage.equals(modulePrefix) || originPackage.startsWith(modulePrefix + ".")) {
                                continue;
                            }
                            events.add(SimpleConditionEvent.violated(
                                    dependency,
                                    "Internal package " + targetPackage + " of another module is accessed: "
                                            + dependency.getDescription()));
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

    private static String owningModulePrefix(String packageName, List<String> basePackages) {
        String matchedBase = longestMatchingBasePackage(packageName, basePackages);
        if (!basePackages.isEmpty() && matchedBase == null) {
            return null;
        }
        if (matchedBase != null && !matchedBase.isEmpty()) {
            if (!packageName.startsWith(matchedBase + ".")) {
                return null;
            }
            String relative = packageName.substring(matchedBase.length() + 1);
            String[] segments = relative.split("\\.");
            for (int i = 0; i < segments.length; i++) {
                if (segments[i].equals("internal")) {
                    StringBuilder prefix = new StringBuilder(matchedBase);
                    for (int j = 0; j < i; j++) {
                        prefix.append('.').append(segments[j]);
                    }
                    return prefix.toString();
                }
            }
            return null;
        }
        String[] segments = packageName.split("\\.");
        for (int i = 1; i < segments.length; i++) {
            if (segments[i].equals("internal")) {
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

    private static String longestMatchingBasePackage(String packageName, List<String> basePackages) {
        String longest = null;
        for (String base : basePackages) {
            if (packageName.equals(base) || packageName.startsWith(base + ".")) {
                if (longest == null || base.length() > longest.length()) {
                    longest = base;
                }
            }
        }
        return longest;
    }
}

final class NoDirectThreadInstantiationRule extends AbstractArchitectureRule {

    NoDirectThreadInstantiationRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-CODE-017",
                        "Classes should not directly instantiate Thread",
                        ArchitectureCategory.CODING_PRACTICES,
                        "MEDIUM",
                        "Reviews new Thread(...) construction, including Thread subclasses, outside an actual"
                                + " ThreadFactory.newThread(Runnable) implementation or verified ThreadFactory lambda."
                                + " Direct construction can bypass"
                                + " executor lifecycle and context management; this check does not prove the thread is"
                                + " started.",
                        "Prefer Spring's TaskExecutor/@Async or Quarkus's ManagedExecutor, or an application-owned"
                                + " ExecutorService with explicit shutdown. ThreadFactory implementations may construct"
                                + " threads for an executor; plain Executors factories are not automatically"
                                + " container-managed.",
                        "https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadFactory.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        ThreadFactoryLambdaAnalysis threadFactories = new ThreadFactoryLambdaAnalysis(context.classes());
        return classes()
                .that(observed(DescribedPredicate.alwaysTrue(), context))
                .should(new ArchCondition<>("not directly instantiate Thread outside a ThreadFactory") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        for (JavaConstructorCall call : threadFactories.violations(type)) {
                            events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                        }
                    }
                })
                .as("Classes should not directly instantiate Thread");
    }
}

final class StaticInjectionPointsAreIgnoredRule extends AbstractArchitectureRule {

    StaticInjectionPointsAreIgnoredRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-023",
                        "Injection annotations on static members are ignored",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "HIGH",
                        "Detects @Autowired, @Value, or (on Spring or CDI beans) jakarta.inject.Inject on static fields or"
                                + " static methods. Spring skips them with an INFO log and Quarkus Arc ignores them with a"
                                + " warning, so the container never populates the member.",
                        "Inject into an instance field, or preferably a constructor parameter. If a static holder is truly"
                                + " required, assign it from a non-static setter or @PostConstruct method of a managed bean.",
                        "https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/annotation/Autowired.html"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        ContainerBeans beans = new ContainerBeans(context.classes());
        return classes()
                .should(new ArchCondition<JavaClass>("not declare injection annotations on static members") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        context.evidence().observed();
                        boolean managed = beans.isManaged(javaClass);
                        for (JavaField field : ArchitectureRuleSupport.declaredFields(javaClass)) {
                            if (field.getModifiers().contains(JavaModifier.STATIC)) {
                                check(field, managed, events);
                            }
                        }
                        for (JavaMethod method : ArchitectureRuleSupport.declaredMethods(javaClass)) {
                            if (method.getModifiers().contains(JavaModifier.STATIC)) {
                                check(method, managed, events);
                            }
                        }
                    }

                    private void check(JavaMember member, boolean managed, ConditionEvents events) {
                        Optional<String> annotation = injectionAnnotation(member, managed);
                        if (annotation.isEmpty()) return;
                        events.add(SimpleConditionEvent.violated(
                                member,
                                "Static " + (member instanceof JavaField ? "field " : "method ")
                                        + member.getFullName() + " is annotated with @" + annotation.get()
                                        + ", which the container ignores on static members"));
                    }
                })
                .as("Injection annotations on static members are ignored");
    }

    private static Optional<String> injectionAnnotation(JavaMember member, boolean managed) {
        if (member.isAnnotatedWith(SpringStereotypes.AUTOWIRED)) return Optional.of("Autowired");
        if (member.isAnnotatedWith(SpringStereotypes.VALUE)) return Optional.of("Value");
        if (managed && member.isAnnotatedWith(SpringStereotypes.JAKARTA_INJECT)) return Optional.of("Inject");
        return Optional.empty();
    }
}

final class LegacyJavaxInjectionAnnotationsShouldBeMigratedRule extends AbstractArchitectureRule {

    LegacyJavaxInjectionAnnotationsShouldBeMigratedRule() {
        super(
                new ArchitectureRuleDefinition(
                        "ARCH-SPRING-024",
                        "Legacy javax injection and lifecycle annotations should be migrated",
                        ArchitectureCategory.SPRING_STEREOTYPES,
                        "HIGH",
                        "Detects javax.annotation.PostConstruct and PreDestroy on any class, and javax.inject.Inject or"
                                + " javax.annotation.Resource on Spring or CDI beans. Spring Framework 7 and Quarkus 3 only"
                                + " recognize the jakarta.* annotations, so the callback never runs and the injection point is"
                                + " never populated. Members that also carry the Jakarta annotation, and a sole constructor"
                                + " the container injects implicitly, are not reported.",
                        "Switch the imports to jakarta.annotation.* and jakarta.inject.*, and depend on the Jakarta APIs"
                                + " (jakarta.annotation-api, jakarta.inject-api). If Dagger or Guice deliberately owns this"
                                + " class's javax.inject wiring, dismiss the finding.",
                        "https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/context/annotation/CommonAnnotationBeanPostProcessor.java"));
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        ContainerBeans beans = new ContainerBeans(context.classes());
        return classes()
                .should(new ArchCondition<JavaClass>("not use legacy javax injection or lifecycle annotations") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        context.evidence().observed();
                        List<JavaMember> members = new ArrayList<>(ArchitectureRuleSupport.declaredFields(javaClass));
                        members.addAll(ArchitectureRuleSupport.declaredMethods(javaClass));
                        members.addAll(ArchitectureRuleSupport.declaredConstructors(javaClass));
                        for (JavaMember member : members) {
                            Optional<String> annotation = LegacyJavaxInjection.ignoredAnnotation(member, beans);
                            if (annotation.isEmpty()) continue;
                            events.add(SimpleConditionEvent.violated(
                                    member,
                                    member.getFullName() + " uses legacy " + annotation.get()
                                            + ", which Spring Framework 7 and Quarkus 3 do not recognize"));
                        }
                    }
                })
                .as("Legacy javax injection and lifecycle annotations should be migrated");
    }
}

final class HttpClientsShouldUseBootBuildersRule extends AbstractArchitectureRule {

    static final String REST_CLIENT = "org.springframework.web.client.RestClient";
    static final String WEB_CLIENT = "org.springframework.web.reactive.function.client.WebClient";
    static final String REST_CLIENT_MODULE_MARKER = "org.springframework.boot.restclient.RestClientCustomizer";
    static final String WEB_CLIENT_MODULE_MARKER = "org.springframework.boot.webclient.WebClientCustomizer";

    private static final Set<String> FACTORY_METHODS = Set.of("builder", "create");
    private static final Set<String> REST_CLIENT_BUILDER_CONFIGURERS = Set.of(
            "org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer",
            "org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer");

    private final java.util.function.Predicate<String> classPresent;

    HttpClientsShouldUseBootBuildersRule() {
        this(HttpClientsShouldUseBootBuildersRule::isClassPresent);
    }

    HttpClientsShouldUseBootBuildersRule(java.util.function.Predicate<String> classPresent) {
        super(new ArchitectureRuleDefinition(
                "ARCH-SPRING-025",
                "HTTP clients should be built from Spring Boot's auto-configured builder",
                ArchitectureCategory.SPRING_STEREOTYPES,
                "LOW",
                "Detects calls to RestClient.builder(..), RestClient.create(..), WebClient.builder(), or"
                        + " WebClient.create(..) when Spring Boot's matching builder is available. Clients built"
                        + " this way skip the context's message converters, the ObservationRegistry"
                        + " (http.client.requests metrics and trace propagation), and every RestClientCustomizer"
                        + " or WebClientCustomizer, so their calls are invisible to metrics, traces, and BootUI.",
                "Inject Spring Boot's RestClient.Builder or WebClient.Builder and build from it. The builder bean"
                        + " is a prototype; call clone() before building several clients from one instance. To"
                        + " start from Boot's defaults on a fresh builder, use RestClientBuilderConfigurer.",
                "https://docs.spring.io/spring-boot/reference/io/rest-client.html"));
        this.classPresent = classPresent;
    }

    @Override
    ArchRule rule(ArchitectureContext context) {
        if (context.platform() != ArchitecturePlatform.SPRING) {
            return null;
        }
        Set<String> owners = new HashSet<>();
        if (classPresent.test(REST_CLIENT_MODULE_MARKER)) owners.add(REST_CLIENT);
        if (classPresent.test(WEB_CLIENT_MODULE_MARKER)) owners.add(WEB_CLIENT);
        if (owners.isEmpty()) {
            return null;
        }
        JavaClasses handwritten = context.codingClasses();
        DescribedPredicate<JavaClass> handwrittenClasses =
                new DescribedPredicate<>("are not positively identified as generated") {
                    @Override
                    public boolean test(JavaClass type) {
                        return handwritten.contain(type.getName());
                    }
                };
        return classes()
                .that(observed(handwrittenClasses, context))
                .should(new ArchCondition<JavaClass>("not build HTTP clients from static factories") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        Set<JavaMethodCall> consumedConfigurers = new HashSet<>();
                        for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                            report(call, call.getTarget(), consumedConfigurers, events);
                        }
                        for (JavaMethodReference reference : javaClass.getMethodReferencesFromSelf()) {
                            report(reference, reference.getTarget(), consumedConfigurers, events);
                        }
                    }

                    private void report(
                            JavaCodeUnitAccess<?> access,
                            CodeUnitAccessTarget target,
                            Set<JavaMethodCall> consumedConfigurers,
                            ConditionEvents events) {
                        String owner = target.getOwner().getName();
                        if (!owners.contains(owner) || !FACTORY_METHODS.contains(target.getName())) return;
                        if (access instanceof JavaMethodCall call
                                && REST_CLIENT.equals(owner)
                                && target.getName().equals("builder")
                                && target.getRawParameterTypes().isEmpty()
                                && isConfiguredByRestClientBuilderConfigurer(call, consumedConfigurers)) {
                            return;
                        }
                        JavaCodeUnit origin = KotlinBytecode.unwrapSuspendImplOrigin(access.getOrigin());
                        String client = owner.substring(owner.lastIndexOf('.') + 1);
                        events.add(SimpleConditionEvent.violated(
                                access,
                                origin.getFullName() + " builds a " + client + " with " + client + "."
                                        + target.getName() + "(..) instead of Spring Boot's auto-configured " + client
                                        + ".Builder " + access.getSourceCodeLocation()));
                    }
                })
                .as("HTTP clients should be built from Spring Boot's auto-configured builder");
    }

    private static boolean isConfiguredByRestClientBuilderConfigurer(
            JavaMethodCall builderCall, Set<JavaMethodCall> consumedConfigurers) {
        int builderLine = builderCall.getLineNumber();
        if (builderLine <= 0) {
            return false;
        }
        for (JavaMethodCall call : builderCall.getOrigin().getMethodCallsFromSelf()) {
            if (call.getLineNumber() == builderLine
                    && isRestClientConfigurerConfigureTarget(call.getTarget())
                    && consumedConfigurers.add(call)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRestClientConfigurerConfigureTarget(CodeUnitAccessTarget target) {
        if (!target.getName().equals("configure")) {
            return false;
        }
        String ownerName = target.getOwner().getName();
        return REST_CLIENT_BUILDER_CONFIGURERS.contains(ownerName)
                || ownerName.endsWith(".RestClientBuilderConfigurer")
                || ownerName.endsWith("$RestClientBuilderConfigurer");
    }

    private static boolean isClassPresent(String name) {
        ClassLoader[] loaders = {
            HttpClientsShouldUseBootBuildersRule.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                Class.forName(name, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError ex) {
            }
        }
        return false;
    }
}
