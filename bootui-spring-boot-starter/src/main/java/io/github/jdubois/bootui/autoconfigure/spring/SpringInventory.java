package io.github.jdubois.bootui.autoconfigure.spring;

import static io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact.*;

import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityAware;
import io.github.jdubois.bootui.autoconfigure.datasource.DelegatingDataSources;
import io.github.jdubois.bootui.autoconfigure.datasource.SpringAopDataSources;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.CacheManagerRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.PooledExecutorRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.AsyncSelection;
import io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact;
import io.github.jdubois.bootui.engine.datasource.PhysicalDataSources;
import io.github.jdubois.bootui.engine.support.KotlinReflection;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * One bounded inventory, shared by all rules. Only getType(name,false), definitions and already
 * registered exact framework singleton metadata are read. No FactoryBean product, configurer,
 * customizer, endpoint supplier, database connection or runnable is invoked.
 */
final class SpringInventory {
    static final int MAX_BEANS = 4096;
    static final int MAX_MEMBERS = 512;
    static final int MAX_TOTAL_MEMBERS = 20_000;
    private static final int MAX_OWNER_DEPTH = 32;
    private static final String ASYNC_PROCESSOR =
            "org.springframework.scheduling.config.internalAsyncAnnotationProcessor";
    private static final String SCHEDULED_PROCESSOR =
            "org.springframework.scheduling.config.internalScheduledAnnotationProcessor";
    private static final String BOOT_ASYNC = "applicationTaskExecutorAsyncConfigurer";
    static final String JACKSON2_AUTOCONFIGURATION_CLASS =
            "org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration";
    static final String PROPERTIES_MIGRATOR_LISTENER =
            "org.springframework.boot.context.properties.migrator.PropertiesMigrationListener";
    private static final String DISPATCHER_REGISTRATION = "dispatcherServletRegistration";
    private static final Set<String> BOOTUI_WEBFLUX_CONFIGURERS = Set.of(
            "io.github.jdubois.bootui.autoconfigure.reactive.BootUiJsonWebFluxConfigurer",
            "io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiStaticResourceConfigurer");
    private static final Set<String> BOOTUI_SECURITY_CHAIN_NAMES =
            Set.of("bootUiSecurityFilterChain", "bootUiReactiveSecurityWebFilterChain", "bootUiSecurityWebFilterChain");
    private static final Set<String> BOOTUI_SECURITY_AUTOCONFIGURATIONS = Set.of(
            "io.github.jdubois.bootui.autoconfigure.BootUiSpringSecurityAutoConfiguration",
            "io.github.jdubois.bootui.autoconfigure.BootUiReactiveSpringSecurityAutoConfiguration");
    private static final Set<String> INJECTED = Set.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.beans.factory.annotation.Value",
            "jakarta.inject.Inject",
            "jakarta.annotation.Resource",
            "jakarta.persistence.PersistenceContext",
            "jakarta.persistence.PersistenceUnit");

    private record Entry(
            String name,
            Class<?> type,
            BeanRef ref,
            boolean application,
            boolean singleton,
            String factory,
            String method,
            boolean configurationProperties) {}

    private final ConfigurableListableBeanFactory factory;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Fact, Object> facts = new EnumMap<>(Fact.class);
    private final Set<String> incomplete = new LinkedHashSet<>();
    private int members;
    private boolean complete = true;

    private SpringInventory(ConfigurableListableBeanFactory factory) {
        this.factory = factory;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String[] definitions = factory.getBeanDefinitionNames();
        String[] singletons = factory.getSingletonNames();
        if (definitions.length + singletons.length > MAX_BEANS * 2) partial();
        for (int i = 0; i < Math.min(definitions.length, MAX_BEANS); i++) names.add(definitions[i]);
        for (int i = 0; i < Math.min(singletons.length, MAX_BEANS); i++) {
            if (names.size() >= MAX_BEANS && !names.contains(singletons[i])) {
                partial();
                break;
            }
            names.add(singletons[i]);
        }
        if (definitions.length > MAX_BEANS) partial();
        for (String name : names) {
            if (name.length() > 256) {
                partial();
                continue;
            }
            try {
                Class<?> type = factory.getType(name, false);
                if (type == null) {
                    partial();
                    continue;
                }
                type = ClassUtils.getUserClass(type);
                BeanDefinition definition =
                        factory.containsBeanDefinition(name) ? factory.getBeanDefinition(name) : null;
                String[] aliases = factory.getAliases(name);
                if (aliases.length > 100 || Arrays.stream(aliases).anyMatch(alias -> alias.length() > 256)) {
                    partial();
                    continue;
                }
                BeanRef ref = new BeanRef(
                        name,
                        definition != null && definition.isPrimary(),
                        definition != null && definition.isAutowireCandidate(),
                        definition != null && definition.isFallback(),
                        definition instanceof AbstractBeanDefinition abd && abd.isDefaultCandidate(),
                        definition instanceof AbstractBeanDefinition,
                        List.of(aliases),
                        mapperGroup(type));
                String owner = definition == null ? null : definition.getFactoryBeanName();
                if (owner != null) {
                    Class<?> ownerType = factory.getType(owner, false);
                    owner = ownerType == null
                            ? owner
                            : ClassUtils.getUserClass(ownerType).getName();
                } else if (definition != null && definition.getFactoryMethodName() != null) {
                    owner = definition.getBeanClassName(); // static @Bean factory metadata
                }
                boolean bound =
                        hasAnnotation(type, "org.springframework.boot.context.properties.ConfigurationProperties");
                entries.add(new Entry(
                        name,
                        type,
                        ref,
                        definition != null && definition.getRole() == BeanDefinition.ROLE_APPLICATION,
                        definition != null && definition.isSingleton(),
                        owner,
                        definition == null ? null : definition.getFactoryMethodName(),
                        bound));
            } catch (RuntimeException | LinkageError ex) {
                partial();
            }
        }
    }

    static SpringContext discover(ConfigurableListableBeanFactory factory, Environment environment, boolean reactive) {
        if (factory == null) return null;
        SpringInventory inventory = new SpringInventory(factory);
        return inventory.context(environment, reactive);
    }

    private SpringContext context(Environment environment, boolean reactive) {
        observeFactory();
        List<String> defaultPackage = new ArrayList<>();
        List<String> mutable = new ArrayList<>();
        List<String> dormantResilience = new ArrayList<>();
        List<String> dormantInfrastructure = new ArrayList<>();
        int handlers = inspectApplicationTypes(defaultPackage, mutable, dormantResilience, dormantInfrastructure);
        boolean async = entry(ASYNC_PROCESSOR) != null;
        boolean scheduling = entry(SCHEDULED_PROCESSOR) != null;
        boolean taskExecutionPropertiesPresent = !typed(
                        "org.springframework.boot.autoconfigure.task.TaskExecutionProperties")
                .isEmpty();
        facts.put(TASK_EXECUTION_PROPERTIES, taskExecutionPropertiesPresent);
        Entry appTaskExecutor = entry("applicationTaskExecutor");
        boolean bootApplicationTaskExecutorPresent =
                isBoot(appTaskExecutor, "org.springframework.boot.autoconfigure.task.");
        if (bootApplicationTaskExecutorPresent
                && "org.springframework.core.task.SimpleAsyncTaskExecutor"
                        .equals(appTaskExecutor.type().getName())) {
            facts.put(BOOT_VIRTUAL_TASK_EXECUTOR, true);
        }
        Entry taskScheduler = entry("taskScheduler");
        if (isBoot(taskScheduler, "org.springframework.boot.autoconfigure.task.")
                && "org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler"
                        .equals(taskScheduler.type().getName())) {
            facts.put(BOOT_VIRTUAL_TASK_SCHEDULER, true);
        }
        observeAsync(async);
        observeScheduling(scheduling);
        observeBootConfiguration(reactive);
        observeTomcat();
        observeEndpoints();
        observeData();
        observeOsiv(reactive);
        observeMigrationModules();
        observeMultipart(reactive);
        boolean applicationSecurityChain = hasApplicationSecurityChain();
        facts.put(APPLICATION_SECURITY_CHAIN, applicationSecurityChain);
        return SpringContext.builder(environment)
                .virtualThreadsSupported(Runtime.version().feature() >= 21)
                .beanDefinitionCount(factory.getBeanDefinitionCount())
                .objectMappers(entries.stream()
                        .filter(e -> !e.ref().group().isEmpty())
                        .map(Entry::ref)
                        .toList())
                .taskExecutors(refs("org.springframework.core.task.TaskExecutor"))
                .bootApplicationTaskExecutorPresent(bootApplicationTaskExecutorPresent)
                .executors(refs("java.util.concurrent.Executor"))
                .dataSources(refs("javax.sql.DataSource"))
                .pooledTaskExecutors(typed("org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor").stream()
                        .map(this::pooledExecutor)
                        .toList())
                .asyncEnabled(async)
                .devToolsPresent(present("org.springframework.boot.devtools.restart.Restarter") != null)
                .customAsyncConfigurerPresent(customAsyncConfigurer())
                .transactionManagers(refs("org.springframework.transaction.PlatformTransactionManager"))
                .transactionManagementConfigurerPresent(
                        !typed("org.springframework.transaction.annotation.TransactionManagementConfigurer")
                                .isEmpty())
                .restTemplates(refs("org.springframework.web.client.RestTemplate"))
                .restClientBeanPresent(
                        !typed("org.springframework.web.client.RestClient").isEmpty())
                .cachingEnabled(entry("org.springframework.cache.config.internalCacheAdvisor") != null)
                .cacheManagers(typed("org.springframework.cache.CacheManager").stream()
                        .map(this::cacheManager)
                        .toList())
                .schedulingEnabled(scheduling)
                .entityManagerFactoryPresent(
                        !typed("jakarta.persistence.EntityManagerFactory").isEmpty())
                .dispatcherServletPresent(!typed("org.springframework.web.servlet.DispatcherServlet")
                        .isEmpty())
                .reactive(reactive)
                .tomcatWebServerPresent(!typed("org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory")
                        .isEmpty())
                .webClientBeanPresent(!typed("org.springframework.web.reactive.function.client.WebClient")
                        .isEmpty())
                .reactiveHandlerMethodCount(handlers)
                .defaultPackageBeans(defaultPackage)
                .mutableSingletonFields(mutable)
                .dormantResilienceBeans(dormantResilience)
                .dormantInfrastructureBeans(dormantInfrastructure)
                .taskExecutionPropertiesPresent(taskExecutionPropertiesPresent)
                .applicationSecurityChainPresent(applicationSecurityChain)
                .observations(new SpringObservations(facts, List.copyOf(incomplete)))
                .build();
    }

    private void observeFactory() {
        if (factory instanceof DefaultListableBeanFactory listable)
            facts.put(OVERRIDING, listable.isAllowBeanDefinitionOverriding());
        if (factory instanceof AbstractAutowireCapableBeanFactory capable)
            facts.put(CIRCULAR, capable.isAllowCircularReferences());
    }

    /**
     * Attributes a pool to the class that declares its definition. A {@code @Bean} method inherited from a
     * Spring configuration class (for example {@code AbstractMessageBrokerConfiguration}'s STOMP channel
     * executors, even through an application subclass) is framework-owned; an application override is not.
     * Unresolvable declarations are attributed to the application so they are still reported.
     */
    private PooledExecutorRef pooledExecutor(Entry entry) {
        String declaring = null;
        try {
            declaring = declaringClass(entry);
        } catch (RuntimeException | LinkageError ex) {
            partial();
        }
        boolean framework = declaring != null
                && declaring.startsWith("org.springframework.")
                && !declaring.startsWith("org.springframework.samples.");
        return new PooledExecutorRef(entry.name(), declaring, entry.type().getName(), framework);
    }

    private String declaringClass(Entry entry) {
        if (!factory.containsBeanDefinition(entry.name())) return null;
        BeanDefinition raw = factory.getBeanDefinition(entry.name());
        BeanDefinition definition = factory.getMergedBeanDefinition(entry.name());
        if (definition instanceof RootBeanDefinition root && root.getResolvedFactoryMethod() != null)
            return root.getResolvedFactoryMethod().getDeclaringClass().getName();
        String method = definition.getFactoryMethodName();
        if (method == null) return null; // registered bean class: its declaring configuration is unknown
        // Configuration-class parsing records the @Bean method's declaring class, including for ASM-read classes.
        MethodMetadata metadata = raw instanceof AnnotatedBeanDefinition annotated
                ? annotated.getFactoryMethodMetadata()
                : definition instanceof AnnotatedBeanDefinition annotated ? annotated.getFactoryMethodMetadata() : null;
        String beanMethodClass = metadata == null ? null : metadata.getDeclaringClassName();
        Class<?> owner = null;
        if (definition.getFactoryBeanName() != null) owner = factory.getType(definition.getFactoryBeanName(), false);
        else if (definition instanceof AbstractBeanDefinition abd && abd.hasBeanClass()) owner = abd.getBeanClass();
        else if (definition.getBeanClassName() != null)
            owner = ClassUtils.resolveClassName(definition.getBeanClassName(), factory.getBeanClassLoader());
        if (owner == null) return beanMethodClass;
        boolean instance = definition.getFactoryBeanName() != null;
        // Most-derived declaration per parameter signature, so an application override wins over the
        // framework @Bean method it overrides even when only the superclass carries @Bean.
        Map<List<Class<?>>, Method> mostDerived = new LinkedHashMap<>();
        Set<List<Class<?>>> beanMethodSignatures = new HashSet<>();
        int depth = 0;
        Class<?> userOwner = ClassUtils.getUserClass(owner);
        for (Class<?> type = userOwner; type != null; type = type.getSuperclass()) {
            Method[] methods = type.getDeclaredMethods();
            // Own small allowance: this runs per pool and must not depend on the shared inventory budget.
            if (++depth > MAX_OWNER_DEPTH || methods.length > MAX_MEMBERS) {
                partial();
                return beanMethodClass;
            }
            boolean factoryClass = type == userOwner || type.getName().equals(beanMethodClass);
            for (Method candidate : methods) {
                if (!candidate.getName().equals(method)
                        || candidate.isBridge()
                        || candidate.isSynthetic()
                        || Modifier.isStatic(candidate.getModifiers()) == instance
                        // A private method elsewhere in the hierarchy cannot override the factory method.
                        || (Modifier.isPrivate(candidate.getModifiers()) && !factoryClass)) continue;
                List<Class<?>> signature = List.of(candidate.getParameterTypes());
                mostDerived.putIfAbsent(signature, candidate);
                if (type.getName().equals(beanMethodClass)
                        && AnnotatedElementUtils.hasAnnotation(candidate, Bean.class))
                    beanMethodSignatures.add(signature);
            }
        }
        List<Method> candidates = mostDerived.entrySet().stream()
                .filter(e -> beanMethodClass == null
                        ? compatible(e.getValue().getReturnType(), entry.type())
                        : beanMethodSignatures.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        Set<String> declaring = candidates.stream()
                .map(candidate -> candidate.getDeclaringClass().getName())
                .collect(java.util.stream.Collectors.toSet());
        // Overloads declared by one class still identify it; differing classes are ambiguous.
        if (declaring.size() == 1) return declaring.iterator().next();
        // Metadata alone is trusted only when no same-named method could override the recorded one.
        return mostDerived.isEmpty() ? beanMethodClass : null;
    }

    /** Target metadata may be narrower (covariant override) or wider (declared Executor) than the method. */
    private static boolean compatible(Class<?> returnType, Class<?> beanType) {
        return returnType.isAssignableFrom(beanType) || beanType.isAssignableFrom(returnType);
    }

    private CacheManagerRef cacheManager(Entry entry) {
        Object singleton = factory.getSingleton(entry.name());
        // Never instantiate a lazy manager/FactoryBean or call an application wrapper.
        Class<?> type = singleton instanceof org.springframework.cache.CacheManager manager
                ? CacheActivityAware.unwrap(manager).getClass()
                : entry.type();
        return new CacheManagerRef(entry.name(), type.getName());
    }

    private record DormantAnnotationChecks(
            Class<? extends Annotation> coreRetryable,
            Class<? extends Annotation> concurrencyLimit,
            Class<? extends Annotation> springRetryable,
            Class<? extends Annotation> asyncAnnotation,
            List<Class<? extends Annotation>> scheduledAnnotations,
            List<Class<? extends Annotation>> cacheAnnotations,
            List<Class<? extends Annotation>> txAnnotations,
            boolean txAdvisorActive,
            boolean txManagerPresent) {
        boolean anyNeeded() {
            return coreRetryable != null
                    || concurrencyLimit != null
                    || springRetryable != null
                    || asyncAnnotation != null
                    || !scheduledAnnotations.isEmpty()
                    || !cacheAnnotations.isEmpty()
                    || !txAnnotations.isEmpty();
        }
    }

    private DormantAnnotationChecks buildDormantAnnotationChecks() {
        boolean coreRetryEnabled = entry("org.springframework.resilience.annotation.internalRetryAdvisor") != null
                || !typed("org.springframework.resilience.annotation.RetryAnnotationBeanPostProcessor")
                        .isEmpty();
        boolean concurrencyLimitEnabled =
                entry("org.springframework.resilience.annotation.internalConcurrencyLimitAdvisor") != null
                        || !typed("org.springframework.resilience.annotation.ConcurrencyLimitBeanPostProcessor")
                                .isEmpty();
        boolean springRetryEnabled = entry("org.springframework.retry.annotation.internalAutoProxyCreator") != null
                || !typed("org.springframework.retry.annotation.RetryConfiguration")
                        .isEmpty();
        boolean asyncActive = entry(ASYNC_PROCESSOR) != null
                || !typed("org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor")
                        .isEmpty()
                || !typed("org.springframework.aop.interceptor.AsyncExecutionAspectSupport")
                        .isEmpty();
        boolean schedulingActive = entry(SCHEDULED_PROCESSOR) != null
                || !typed("org.springframework.scheduling.config.ScheduledTaskHolder")
                        .isEmpty();
        boolean cachingActive = entry("org.springframework.cache.config.internalCacheAdvisor") != null
                || !typed("org.springframework.cache.interceptor.CacheAspectSupport")
                        .isEmpty()
                || !typed("org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor")
                        .isEmpty();
        boolean txAdvisorActive = entry("org.springframework.transaction.config.internalTransactionAdvisor") != null
                || !typed("org.springframework.transaction.interceptor.TransactionAspectSupport")
                        .isEmpty()
                || !typed("org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor")
                        .isEmpty();
        boolean txManagerPresent =
                !typed("org.springframework.transaction.TransactionManager").isEmpty()
                        || !typed("org.springframework.transaction.annotation.TransactionManagementConfigurer")
                                .isEmpty();
        return new DormantAnnotationChecks(
                coreRetryEnabled ? null : presentAnnotation("org.springframework.resilience.annotation.Retryable"),
                concurrencyLimitEnabled
                        ? null
                        : presentAnnotation("org.springframework.resilience.annotation.ConcurrencyLimit"),
                springRetryEnabled ? null : presentAnnotation("org.springframework.retry.annotation.Retryable"),
                asyncActive ? null : presentAnnotation("org.springframework.scheduling.annotation.Async"),
                schedulingActive
                        ? List.of()
                        : presentAnnotations(
                                "org.springframework.scheduling.annotation.Scheduled",
                                "org.springframework.scheduling.annotation.Schedules"),
                cachingActive
                        ? List.of()
                        : presentAnnotations(
                                "org.springframework.cache.annotation.Cacheable",
                                "org.springframework.cache.annotation.CachePut",
                                "org.springframework.cache.annotation.CacheEvict",
                                "org.springframework.cache.annotation.Caching"),
                (txAdvisorActive && txManagerPresent)
                        ? List.of()
                        : presentAnnotations(
                                "org.springframework.transaction.annotation.Transactional",
                                "jakarta.transaction.Transactional"),
                txAdvisorActive,
                txManagerPresent);
    }

    private int inspectApplicationTypes(
            List<String> packages,
            List<String> mutable,
            List<String> dormantResilience,
            List<String> dormantInfrastructure) {
        int handlers = 0;
        DormantAnnotationChecks dormantChecks = buildDormantAnnotationChecks();
        for (Entry entry : entries) {
            if (members >= MAX_TOTAL_MEMBERS) {
                partial();
                break;
            }
            Class<?> type = entry.type();
            if (entry.application() && java.lang.reflect.Proxy.isProxyClass(type)) {
                Class<?> targetType = resolveProxyTargetType(entry.name());
                if (targetType != null) type = targetType;
            }
            if (!entry.application()
                    || type.getName().startsWith("io.github.jdubois.bootui.autoconfigure.")
                    || type.getName().startsWith("org.springframework.")
                    || type.isInterface()
                    || java.lang.reflect.Proxy.isProxyClass(type)) continue;
            if (type.getPackageName().isEmpty()) {
                if (packages.size() < 50) packages.add(entry.name());
                else partial();
            }
            try {
                boolean controller = AnnotatedElementUtils.hasAnnotation(type, Controller.class);
                Method[] methods = type.getMethods();
                if (!budget(methods.length)) continue;
                if (controller)
                    for (Method method : methods) {
                        if (handlers < 50
                                && AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)
                                && Set.of("reactor.core.publisher.Mono", "reactor.core.publisher.Flux")
                                        .contains(method.getReturnType().getName())) handlers++;
                    }
                if (dormantChecks.anyNeeded() && !type.getName().startsWith("java.")) {
                    inspectDormantAnnotations(
                            entry, type, methods, dormantChecks, dormantResilience, dormantInfrastructure);
                }
                if (!entry.singleton() || entry.configurationProperties() || boundFactoryMethod(entry)) continue;
                Field[] fields = type.getFields(); // includes inherited public fields
                if (!budget(fields.length)) continue;
                for (Field field : fields) {
                    int flags = field.getModifiers();
                    if (mutable.size() >= 50) {
                        partial();
                        break;
                    }
                    if (Modifier.isFinal(flags)
                            || Modifier.isStatic(flags)
                            || field.isSynthetic()
                            || KotlinReflection.isAccessorBackedProperty(field.getDeclaringClass(), field.getName()))
                        continue;
                    boolean injection = Arrays.stream(field.getAnnotations())
                            .anyMatch(annotation -> INJECTED.contains(
                                    annotation.annotationType().getName()));
                    if (!injection) mutable.add(SpringRuleSupport.detail(type.getName() + "#" + field.getName()));
                }
            } catch (RuntimeException | LinkageError ex) {
                partial();
            }
        }
        return handlers;
    }

    private void inspectDormantAnnotations(
            Entry entry,
            Class<?> type,
            Method[] publicMethods,
            DormantAnnotationChecks checks,
            List<String> dormantResilience,
            List<String> dormantInfrastructure) {
        List<Method> methods = null;
        if (checks.coreRetryable() != null) {
            if (AnnotatedElementUtils.hasAnnotation(type, checks.coreRetryable())) {
                addBoundedFinding(
                        dormantResilience,
                        entry.name() + " (" + type.getName()
                                + "): @Retryable (Spring Framework 7) is present without RetryAnnotationBeanPostProcessor; add @EnableResilientMethods.");
            } else {
                methods = methods != null ? methods : candidateMethods(type, publicMethods);
                recordMethodAnnotations(
                        entry,
                        type,
                        methods,
                        List.of(checks.coreRetryable()),
                        false,
                        dormantResilience,
                        ": @Retryable (Spring Framework 7) is present without RetryAnnotationBeanPostProcessor; add @EnableResilientMethods.");
            }
        }
        if (checks.concurrencyLimit() != null) {
            if (AnnotatedElementUtils.hasAnnotation(type, checks.concurrencyLimit())) {
                addBoundedFinding(
                        dormantResilience,
                        entry.name() + " (" + type.getName()
                                + "): @ConcurrencyLimit is present without ConcurrencyLimitBeanPostProcessor; add @EnableResilientMethods.");
            } else {
                methods = methods != null ? methods : candidateMethods(type, publicMethods);
                recordMethodAnnotations(
                        entry,
                        type,
                        methods,
                        List.of(checks.concurrencyLimit()),
                        false,
                        dormantResilience,
                        ": @ConcurrencyLimit is present without ConcurrencyLimitBeanPostProcessor; add @EnableResilientMethods.");
            }
        }
        if (checks.springRetryable() != null) {
            if (AnnotatedElementUtils.hasAnnotation(type, checks.springRetryable())) {
                addBoundedFinding(
                        dormantResilience,
                        entry.name() + " (" + type.getName()
                                + "): @Retryable (Spring Retry) is present without RetryConfiguration; add @EnableRetry.");
            } else {
                methods = methods != null ? methods : candidateMethods(type, publicMethods);
                recordMethodAnnotations(
                        entry,
                        type,
                        methods,
                        List.of(checks.springRetryable()),
                        false,
                        dormantResilience,
                        ": @Retryable (Spring Retry) is present without RetryConfiguration; add @EnableRetry.");
            }
        }
        if (checks.asyncAnnotation() != null) {
            if (AnnotatedElementUtils.hasAnnotation(type, checks.asyncAnnotation())) {
                addBoundedFinding(
                        dormantInfrastructure,
                        entry.name() + " (" + type.getName()
                                + "): @Async is present without AsyncAnnotationBeanPostProcessor or AsyncExecutionAspectSupport; add @EnableAsync.");
            } else {
                methods = methods != null ? methods : candidateMethods(type, publicMethods);
                recordMethodAnnotations(
                        entry,
                        type,
                        methods,
                        List.of(checks.asyncAnnotation()),
                        false,
                        dormantInfrastructure,
                        ": @Async is present without AsyncAnnotationBeanPostProcessor or AsyncExecutionAspectSupport; add @EnableAsync.");
            }
        }
        if (!checks.scheduledAnnotations().isEmpty()) {
            if (hasAnyAnnotation(type, checks.scheduledAnnotations())) {
                addBoundedFinding(
                        dormantInfrastructure,
                        entry.name() + " (" + type.getName()
                                + "): @Scheduled is present without ScheduledAnnotationBeanPostProcessor; add @EnableScheduling.");
            } else {
                methods = methods != null ? methods : candidateMethods(type, publicMethods);
                recordMethodAnnotations(
                        entry,
                        type,
                        methods,
                        checks.scheduledAnnotations(),
                        true,
                        dormantInfrastructure,
                        ": @Scheduled is present without ScheduledAnnotationBeanPostProcessor; add @EnableScheduling.");
            }
        }
        if (!checks.cacheAnnotations().isEmpty()) {
            if (hasAnyAnnotation(type, checks.cacheAnnotations())) {
                addBoundedFinding(
                        dormantInfrastructure,
                        entry.name() + " (" + type.getName()
                                + "): Spring cache annotation is present without CacheAspectSupport/internalCacheAdvisor; add @EnableCaching.");
            } else {
                methods = methods != null ? methods : candidateMethods(type, publicMethods);
                recordMethodAnnotations(
                        entry,
                        type,
                        methods,
                        checks.cacheAnnotations(),
                        false,
                        dormantInfrastructure,
                        ": Spring cache annotation is present without CacheAspectSupport/internalCacheAdvisor; add @EnableCaching.");
            }
        }
        if (!checks.txAnnotations().isEmpty()) {
            String suffix = !checks.txAdvisorActive() && !checks.txManagerPresent()
                    ? ": @Transactional is present without TransactionAspectSupport or a TransactionManager bean; add @EnableTransactionManagement and configure a TransactionManager."
                    : !checks.txAdvisorActive()
                            ? ": @Transactional is present without TransactionAspectSupport/internalTransactionAdvisor; add @EnableTransactionManagement."
                            : ": @Transactional is present without a TransactionManager bean in the context.";
            if (hasAnyAnnotation(type, checks.txAnnotations())) {
                addBoundedFinding(dormantInfrastructure, entry.name() + " (" + type.getName() + ")" + suffix);
            } else {
                methods = methods != null ? methods : candidateMethods(type, publicMethods);
                recordMethodAnnotations(
                        entry, type, methods, checks.txAnnotations(), false, dormantInfrastructure, suffix);
            }
        }
    }

    private void recordMethodAnnotations(
            Entry entry,
            Class<?> type,
            List<Method> methods,
            List<Class<? extends Annotation>> annotations,
            boolean includePrivate,
            List<String> targetList,
            String messageSuffix) {
        Set<String> seenMethodNames = new HashSet<>();
        for (Method method : methods) {
            if (!includePrivate && Modifier.isPrivate(method.getModifiers())) continue;
            if (hasAnyAnnotation(method, annotations) && seenMethodNames.add(method.getName())) {
                if (!addBoundedFinding(
                        targetList,
                        entry.name() + " (" + type.getName() + "#" + method.getName() + ")" + messageSuffix)) {
                    return;
                }
            }
        }
    }

    private boolean addBoundedFinding(List<String> list, String detail) {
        if (list.size() >= 50) {
            partial();
            return false;
        }
        list.add(SpringRuleSupport.detail(detail));
        return true;
    }

    private List<Method> candidateMethods(Class<?> type, Method[] publicMethods) {
        List<Method> result = new ArrayList<>(publicMethods.length);
        for (Method method : publicMethods) {
            if (method.getDeclaringClass() != Object.class
                    && !method.isBridge()
                    && !method.isSynthetic()
                    && !Modifier.isStatic(method.getModifiers())) {
                result.add(method);
            }
        }
        Class<?> owner = type;
        int depth = 0;
        while (owner != null && owner != Object.class && !owner.getName().startsWith("java.")) {
            if (++depth > 16) {
                partial();
                break;
            }
            Method[] declared = owner.getDeclaredMethods();
            int nonPublic = 0;
            for (Method method : declared) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    nonPublic++;
                }
            }
            if (declared.length > MAX_MEMBERS || !budget(nonPublic)) {
                break;
            }
            for (Method method : declared) {
                int mods = method.getModifiers();
                if (Modifier.isPublic(mods) || Modifier.isStatic(mods) || method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                result.add(method);
            }
            owner = owner.getSuperclass();
        }
        result.sort(java.util.Comparator.comparing(Method::getName)
                .thenComparing(m -> Arrays.toString(m.getParameterTypes())));
        return result;
    }

    private static boolean hasAnyAnnotation(
            java.lang.reflect.AnnotatedElement element, List<Class<? extends Annotation>> annotations) {
        for (Class<? extends Annotation> annotation : annotations) {
            if (AnnotatedElementUtils.hasAnnotation(element, annotation)) {
                return true;
            }
        }
        return false;
    }

    private boolean boundFactoryMethod(Entry entry) {
        if (entry.factory() == null || entry.method() == null) return false;
        Class<?> owner = present(entry.factory());
        if (owner == null) return false;
        Method[] methods = owner.getDeclaredMethods();
        if (!budget(methods.length)) return true;
        for (Method method : methods)
            if (method.getName().equals(entry.method())
                    && Arrays.stream(method.getAnnotations())
                            .anyMatch(a -> a.annotationType()
                                    .getName()
                                    .equals("org.springframework.boot.context.properties.ConfigurationProperties")))
                return true;
        return false;
    }

    private void observeAsync(boolean enabled) {
        Entry processor = entry(ASYNC_PROCESSOR);
        if (!enabled
                || !complete
                || processor == null
                || !"org.springframework.scheduling.annotation.ProxyAsyncConfiguration".equals(processor.factory())
                || customAsyncConfigurer()
                || !asyncDefaultExecutorApplicable()) return;
        Entry selected = null;
        if (isBoot(entry(BOOT_ASYNC), "org.springframework.boot.autoconfigure.task.")) {
            selected = entry("applicationTaskExecutor");
            if (selected == null) return;
        } else {
            List<Entry> tasks = typed("org.springframework.core.task.TaskExecutor");
            if (!byTypeSelectionObservable(tasks)) return;
            selected = selectedByType(tasks);
            if (selected == null) {
                selected = named(typed("java.util.concurrent.Executor"), "taskExecutor");
                if (selected == null && tasks.isEmpty()) {
                    facts.put(ASYNC_SELECTION, AsyncSelection.FRAMEWORK_FALLBACK);
                    return;
                }
            }
            if (selected == null) {
                if (tasks.stream().allMatch(e -> e.ref().metadataKnown()))
                    facts.put(ASYNC_SELECTION, AsyncSelection.AMBIGUOUS);
                return;
            }
        }
        facts.put(ASYNC_SELECTION, AsyncSelection.SELECTED);
        Object singleton = factory.getSingleton(selected.name());
        if (exact(singleton, "org.springframework.core.task.SimpleAsyncTaskExecutor")
                || exact(singleton, "org.springframework.core.task.VirtualThreadTaskExecutor")) {
            facts.put(ASYNC_NON_POOL, true);
            return;
        }
        if (singleton != null && singleton.getClass() == ThreadPoolTaskExecutor.class) {
            // Reading the configured field alone can be stale after initialization; use the actual queue.
            try {
                var executor = ((ThreadPoolTaskExecutor) singleton).getThreadPoolExecutor();
                var queue = executor.getQueue();
                if (queue.getClass() == java.util.concurrent.LinkedBlockingQueue.class
                        || queue.getClass() == java.util.concurrent.ArrayBlockingQueue.class
                        || queue.getClass() == java.util.concurrent.SynchronousQueue.class) {
                    long capacity = (long) queue.size() + queue.remainingCapacity();
                    int queueCapacity = (int) Math.min(Integer.MAX_VALUE, capacity);
                    facts.put(ASYNC_QUEUE_CAPACITY, queueCapacity);
                    int corePoolSize = executor.getCorePoolSize();
                    int maxPoolSize = executor.getMaximumPoolSize();
                    if (queueCapacity == Integer.MAX_VALUE
                            && maxPoolSize > corePoolSize
                            && maxPoolSize < Integer.MAX_VALUE) {
                        facts.put(ASYNC_UNREACHABLE_MAX_POOL_SIZE, maxPoolSize);
                    }
                }
            } catch (IllegalStateException ex) {
                /* uninitialized executor is unknown */
            }
            return;
        }
        if (singleton == null
                && "applicationTaskExecutor".equals(selected.name())
                && isBoot(selected, "org.springframework.boot.autoconfigure.task.")) {
            if ("org.springframework.core.task.SimpleAsyncTaskExecutor"
                    .equals(selected.type().getName())) {
                facts.put(ASYNC_NON_POOL, true);
            } else if (selected.type() == ThreadPoolTaskExecutor.class) {
                observeLazyBootTaskExecutorBuilder();
            }
        }
    }

    private void observeLazyBootTaskExecutorBuilder() {
        Entry builderEntry = entry("threadPoolTaskExecutorBuilder");
        if (!isBoot(builderEntry, "org.springframework.boot.autoconfigure.task.")
                || !typed("org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer").stream()
                        .allMatch(SpringInventory::isFramework)) return;
        Object builder = factory.getSingleton(builderEntry.name());
        if (!exact(builder, "org.springframework.boot.task.ThreadPoolTaskExecutorBuilder")) return;
        try {
            Object customizers = field(builder, builder.getClass(), "customizers");
            if (customizers != null && (!(customizers instanceof java.util.Collection<?> list) || !list.isEmpty()))
                return;
            Object queueCapacity = field(builder, builder.getClass(), "queueCapacity");
            Object corePoolSize = field(builder, builder.getClass(), "corePoolSize");
            Object maxPoolSize = field(builder, builder.getClass(), "maxPoolSize");
            if (queueCapacity instanceof Integer qc && qc >= 0) {
                facts.put(ASYNC_QUEUE_CAPACITY, qc);
                if (qc == Integer.MAX_VALUE
                        && corePoolSize instanceof Integer core
                        && maxPoolSize instanceof Integer max
                        && max > core
                        && max < Integer.MAX_VALUE) {
                    facts.put(ASYNC_UNREACHABLE_MAX_POOL_SIZE, max);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            partial();
        }
    }

    private boolean asyncDefaultExecutorApplicable() {
        boolean[] flags = new boolean[2]; // [0] = hasUnqualifiedAsync, [1] = hasQualifiedAsync
        for (Entry entry : entries) {
            if (members >= MAX_TOTAL_MEMBERS) {
                partial();
                return false;
            }
            if (!entry.application()) continue;
            if (!inspectAsyncType(entry.type(), flags)) return false;
            if (java.lang.reflect.Proxy.isProxyClass(entry.type())) {
                Class<?> targetType = resolveProxyTargetType(entry.name());
                if (targetType != null
                        && targetType != entry.type()
                        && !java.lang.reflect.Proxy.isProxyClass(targetType)) {
                    if (!inspectAsyncType(targetType, flags)) return false;
                }
            }
        }
        if (flags[1] && !flags[0]) {
            facts.put(ASYNC_QUALIFIED_ONLY, true);
            return false;
        }
        return true;
    }

    private boolean inspectAsyncType(Class<?> type, boolean[] flags) {
        try {
            Async typeAsync = AnnotatedElementUtils.findMergedAnnotation(type, Async.class);
            if (typeAsync != null) recordAsyncQualifier(typeAsync.value(), flags);
            Method[] methods = type.getMethods();
            if (!budget(methods.length)) return false;
            for (Method method : methods) {
                Async annotation = AnnotatedElementUtils.findMergedAnnotation(method, Async.class);
                if (annotation != null) recordAsyncQualifier(annotation.value(), flags);
            }
            Class<?> owner = type;
            int depth = 0;
            while (owner != null && owner != Object.class) {
                if (++depth > 16) {
                    partial();
                    return false;
                }
                Method[] declared = owner.getDeclaredMethods();
                if (!budget(declared.length)) return false;
                for (Method method : declared) {
                    if (Modifier.isPublic(method.getModifiers())) continue;
                    Async annotation = AnnotatedElementUtils.findMergedAnnotation(method, Async.class);
                    if (annotation != null) recordAsyncQualifier(annotation.value(), flags);
                }
                owner = owner.getSuperclass();
            }
            return true;
        } catch (RuntimeException | LinkageError ex) {
            partial();
            return false;
        }
    }

    private static void recordAsyncQualifier(String qualifier, boolean[] flags) {
        if (qualifier.isEmpty() || "applicationTaskExecutor".equals(qualifier) || "taskExecutor".equals(qualifier)) {
            flags[0] = true;
        } else {
            flags[1] = true;
        }
    }

    private Class<?> resolveProxyTargetType(String name) {
        try {
            if (factory.containsBeanDefinition(name)) {
                BeanDefinition definition = factory.getMergedBeanDefinition(name);
                if (definition.getFactoryMethodName() == null) {
                    if (definition instanceof AbstractBeanDefinition abd && abd.hasBeanClass()) {
                        Class<?> beanClass = ClassUtils.getUserClass(abd.getBeanClass());
                        if (!beanClass.isInterface() && !java.lang.reflect.Proxy.isProxyClass(beanClass)) {
                            return beanClass;
                        }
                    }
                    if (definition.getBeanClassName() != null) {
                        Class<?> resolved = ClassUtils.getUserClass(ClassUtils.resolveClassName(
                                definition.getBeanClassName(), factory.getBeanClassLoader()));
                        if (!resolved.isInterface() && !java.lang.reflect.Proxy.isProxyClass(resolved)) {
                            return resolved;
                        }
                    }
                }
                if (definition instanceof RootBeanDefinition root && root.getTargetType() != null) {
                    Class<?> targetType = ClassUtils.getUserClass(root.getTargetType());
                    if (!targetType.isInterface() && !java.lang.reflect.Proxy.isProxyClass(targetType)) {
                        return targetType;
                    }
                }
            }
            Object singleton = factory.getSingleton(name);
            Class<?> advised = present("org.springframework.aop.framework.Advised");
            Class<?> targetSourceType = present("org.springframework.aop.TargetSource");
            if (singleton != null && advised != null && targetSourceType != null && advised.isInstance(singleton)) {
                Object targetSource = advised.getMethod("getTargetSource").invoke(singleton);
                if (targetSource != null
                        && Boolean.TRUE.equals(
                                targetSourceType.getMethod("isStatic").invoke(targetSource))) {
                    Object target = targetSourceType.getMethod("getTarget").invoke(targetSource);
                    if (target != null && target != singleton) {
                        return ClassUtils.getUserClass(target.getClass());
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            /* best-effort non-eager target resolution */
        }
        return null;
    }

    private boolean customAsyncConfigurer() {
        return typed("org.springframework.scheduling.annotation.AsyncConfigurer").stream()
                .anyMatch(e -> !isBoot(e, "org.springframework.boot.autoconfigure.task."));
    }

    private void observeScheduling(boolean enabled) {
        if (!enabled
                || !complete
                || typed("org.springframework.scheduling.annotation.SchedulingConfigurer").stream()
                        .anyMatch(e -> !Set.of(
                                        "io.github.jdubois.bootui.autoconfigure.scheduled.BootUiSchedulingConfigurer",
                                        "org.springframework.boot.micrometer.observation.autoconfigure.ScheduledTasksObservationAutoConfiguration$ObservabilitySchedulingConfigurer")
                                .contains(e.type().getName()))) return;
        Object processor = factory.getSingleton(SCHEDULED_PROCESSOR);
        if (processor == null || processor.getClass() != ScheduledAnnotationBeanPostProcessor.class) return;
        Object registrar;
        try {
            registrar = field(processor, ScheduledAnnotationBeanPostProcessor.class, "registrar");
            if (!exact(registrar, "org.springframework.scheduling.config.ScheduledTaskRegistrar")) return;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return;
        }
        var tasks = ((ScheduledAnnotationBeanPostProcessor) processor).getScheduledTasks();
        if (tasks.size() > MAX_MEMBERS) {
            partial();
            return;
        }
        int count = 0;
        for (var task : tasks) {
            Runnable runnable = task.getTask().getRunnable();
            if (runnable.getClass()
                    .getName()
                    .equals("org.springframework.scheduling.config.Task$OutcomeTrackingRunnable")) {
                try {
                    Field delegate = runnable.getClass().getDeclaredField("runnable");
                    if (!delegate.trySetAccessible()) return;
                    runnable = (Runnable) delegate.get(runnable);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    partial();
                    return;
                }
            }
            if (runnable.getClass() != ScheduledMethodRunnable.class) return;
            ScheduledMethodRunnable method = (ScheduledMethodRunnable) runnable;
            if (method.getQualifier() != null && !method.getQualifier().isEmpty()) return;
            // BootUI has no @Scheduled housekeeping tasks. A package prefix is not ownership:
            // applications (including the sample) can legitimately share that prefix.
            count++;
        }
        facts.put(SCHEDULED_TASK_COUNT, count);
        if (count < 2) return;
        List<Entry> schedulers = typed("org.springframework.scheduling.TaskScheduler");
        if (!byTypeSelectionObservable(schedulers)) return;
        Entry selected = selectedByType(schedulers);
        if (selected == null) selected = named(schedulers, "taskScheduler");
        if (selected == null) return;
        Object singleton = factory.getSingleton(selected.name());
        // Check the registrar's actual selected instance, not just today's candidate metadata.
        // Read the router's already cached value; never invoke its lazy supplier (which resolves beans).
        try {
            Object scheduler = field(registrar, registrar.getClass(), "taskScheduler");
            if (exact(scheduler, "org.springframework.scheduling.config.TaskSchedulerRouter")) {
                Object supplier = field(scheduler, scheduler.getClass(), "defaultScheduler");
                if (!exact(supplier, "org.springframework.util.function.SingletonSupplier")) return;
                scheduler = field(supplier, supplier.getClass(), "singletonInstance");
            }
            if (singleton == null || scheduler != singleton) return;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return;
        }
        if (exact(singleton, "org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler")) {
            facts.put(SCHEDULER_NON_POOL, true);
            return;
        }
        if (singleton != null && singleton.getClass() == ThreadPoolTaskScheduler.class) {
            try {
                facts.put(
                        SCHEDULER_POOL_SIZE,
                        ((ThreadPoolTaskScheduler) singleton)
                                .getScheduledThreadPoolExecutor()
                                .getCorePoolSize());
            } catch (IllegalStateException ex) {
                /* not initialized */
            }
        }
    }

    private void observeBootConfiguration(boolean reactive) {
        List<Entry> servers = typed("org.springframework.boot.web.server.WebServerFactory");
        if (complete
                && servers.size() == 1
                && isBoot(servers.get(0), "org.springframework.boot.")
                && typed("org.springframework.boot.web.server.WebServerFactoryCustomizer").stream()
                        .allMatch(e -> isFramework(e))) facts.put(BOOT_WEB_SERVER, true);
        if (entries.stream()
                .anyMatch(e -> isBoot(e, "org.springframework.boot.jackson.autoconfigure.")
                        && e.ref().group().equals("jackson3"))) facts.put(JACKSON3_CONFIGURATION, true);
        if (entries.stream()
                .anyMatch(e -> isBoot(e, "org.springframework.boot.")
                        && (isType(e.type(), "org.springframework.web.client.RestClient$Builder")
                                || isType(
                                        e.type(), "org.springframework.web.reactive.function.client.WebClient$Builder")
                                || isType(e.type(), "org.springframework.boot.restclient.RestTemplateBuilder"))))
            facts.put(HTTP_CLIENT_DEFAULTS, true);
        if (entries.stream()
                .anyMatch(
                        e -> isBoot(e, "org.springframework.boot.http.client.autoconfigure.service.")
                                && e.type()
                                        .getName()
                                        .equals(
                                                "org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties")))
            facts.put(HTTP_SERVICE_GROUPS, true);
        if (entries.stream()
                .anyMatch(
                        e -> isBoot(e, "org.springframework.boot.")
                                && (e.type()
                                                .getName()
                                                .equals(
                                                        "org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController")
                                        || e.type()
                                                .getName()
                                                .equals(
                                                        "org.springframework.boot.webflux.autoconfigure.error.DefaultErrorWebExceptionHandler"))))
            facts.put(BOOT_ERROR_HANDLING, true);
        if (reactive
                && complete
                && entry("defaultCodecCustomizer") != null
                && isBoot(entry("defaultCodecCustomizer"), "org.springframework.boot.http.codec.autoconfigure.")
                && typed("org.springframework.http.codec.ServerCodecConfigurer").size() == 1
                && isBoot(
                        only(typed("org.springframework.http.codec.ServerCodecConfigurer")),
                        "org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration$EnableWebFluxConfiguration")
                && typed("org.springframework.boot.webflux.autoconfigure.WebFluxRegistrations")
                        .isEmpty()
                && typed("org.springframework.boot.http.codec.CodecCustomizer").stream()
                        .allMatch(SpringInventory::isFramework)
                && typed("org.springframework.web.reactive.config.WebFluxConfigurer").stream()
                        .allMatch(SpringInventory::isKnownNonCodecWebFluxConfigurer)) {
            Entry properties = only(typed("org.springframework.boot.http.codec.autoconfigure.HttpCodecsProperties"));
            Object singleton = properties == null ? null : factory.getSingleton(properties.name());
            if (exact(singleton, "org.springframework.boot.http.codec.autoconfigure.HttpCodecsProperties")) {
                try {
                    Object size =
                            singleton.getClass().getMethod("getMaxInMemorySize").invoke(singleton);
                    facts.put(BOOT_CODEC_CONFIGURATION, true);
                    if (size instanceof org.springframework.util.unit.DataSize dataSize)
                        facts.put(CODEC_LIMIT, dataSize.toBytes());
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    partial();
                }
            }
        }
    }

    private void observeData() {
        // Exact Hikari singleton metadata does not open connections and wins over a stale Environment URL.
        List<Entry> dataSources = typed("javax.sql.DataSource");
        if (!dataSources.isEmpty()) {
            List<String> inMemory = new ArrayList<>();
            String firstMemoryKind = null;
            boolean anyOther = false;
            boolean incompleteJdbc = false;
            Set<Object> seenPhysical = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Entry entry : dataSources) {
                Object raw = factory.getSingleton(entry.name());
                Object singleton = raw instanceof javax.sql.DataSource ds
                        ? PhysicalDataSources.physical(ds, SpringInventory::wrappedDataSource)
                        : raw;
                if (!exact(singleton, "com.zaxxer.hikari.HikariDataSource")) {
                    incompleteJdbc = true;
                    continue;
                }
                if (!seenPhysical.add(singleton)) {
                    continue;
                }
                try {
                    if (singleton.getClass().getMethod("getDataSource").invoke(singleton) != null
                            || singleton
                                            .getClass()
                                            .getMethod("getDataSourceClassName")
                                            .invoke(singleton)
                                    != null) {
                        incompleteJdbc = true;
                        continue;
                    }
                    String url = (String)
                            singleton.getClass().getMethod("getJdbcUrl").invoke(singleton);
                    if (url == null || url.length() > SpringProperties.MAX_TEXT) {
                        incompleteJdbc = true;
                        continue;
                    }
                    String kind = jdbcKind(url);
                    if (kind.endsWith(" memory")) {
                        if (firstMemoryKind == null) firstMemoryKind = kind;
                        inMemory.add(
                                "Observed supported JDBC memory storage (" + kind + ") on DataSource '"
                                        + SpringRuleSupport.detail(entry.name())
                                        + "' with a production-like effective profile name (naming heuristic); review durability. URL omitted.");
                    } else {
                        anyOther = true;
                    }
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    incompleteJdbc = true;
                    partial();
                }
            }
            if (firstMemoryKind != null) {
                facts.put(JDBC_KIND, firstMemoryKind);
                facts.put(JDBC_IN_MEMORY_DETAILS, List.copyOf(inMemory));
            } else if (anyOther && !incompleteJdbc) {
                facts.put(JDBC_KIND, "other");
            }
            if (incompleteJdbc) {
                facts.put(JDBC_INCOMPLETE, true);
            }
        }
        // R2DBC providers have no general safe URL getter. Only a proven, uncustomized Boot definition
        // with its property-backed ConnectionDetails can support configured (not runtime) classification.
        List<Entry> connections = typed("io.r2dbc.spi.ConnectionFactory");
        if (connections.size() == 1
                && isBoot(connections.get(0), "org.springframework.boot.r2dbc.autoconfigure.")
                && typed("org.springframework.boot.r2dbc.autoconfigure.R2dbcConnectionDetails")
                                .size()
                        == 1
                && typed("org.springframework.boot.r2dbc.autoconfigure.R2dbcConnectionDetails").stream()
                        .allMatch(
                                e -> e.type()
                                        .getName()
                                        .equals(
                                                "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration$PropertiesR2dbcConnectionDetails"))
                && typed("org.springframework.boot.r2dbc.autoconfigure.ConnectionFactoryOptionsBuilderCustomizer")
                        .isEmpty()
                && typed("org.springframework.boot.r2dbc.ConnectionFactoryDecorator")
                        .isEmpty()) {
            Entry properties = only(typed("org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties"));
            Object singleton = properties == null ? null : factory.getSingleton(properties.name());
            if (exact(singleton, "org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties")) {
                try {
                    Object options =
                            singleton.getClass().getMethod("getProperties").invoke(singleton);
                    if (!(options instanceof Map<?, ?> map) || !map.isEmpty()) return;
                    String url =
                            (String) singleton.getClass().getMethod("getUrl").invoke(singleton);
                    if (url != null && url.length() <= SpringProperties.MAX_TEXT) {
                        facts.put(R2DBC_KIND, r2dbcKind(url));
                    }
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    partial();
                }
            }
        }
    }

    private void observeTomcat() {
        if (!Boolean.TRUE.equals(facts.get(BOOT_WEB_SERVER))
                || Runtime.version().feature() < 21) return;
        Entry entry = only(typed("org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory"));
        Object singleton = entry == null ? null : factory.getSingleton(entry.name());
        if (!exact(singleton, "org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory")
                && !exact(singleton, "org.springframework.boot.tomcat.reactive.TomcatReactiveWebServerFactory")) return;
        try {
            // Inspect registered customizer metadata without ever invoking a customizer. Unknown
            // connector/context/protocol customization can replace the executor and suppresses advice.
            boolean virtual = false;
            for (String getter :
                    List.of("getProtocolHandlerCustomizers", "getConnectorCustomizers", "getContextCustomizers")) {
                Object value = singleton.getClass().getMethod(getter).invoke(singleton);
                if (!(value instanceof java.util.Collection<?> customizers) || customizers.size() > 100) return;
                for (Object customizer : customizers) {
                    String type = customizer.getClass().getName();
                    if (!type.startsWith("org.springframework.boot.tomcat.")) return;
                    if (type.startsWith(
                            "org.springframework.boot.tomcat.autoconfigure.TomcatVirtualThreadsWebServerFactoryCustomizer$$Lambda"))
                        virtual = true;
                }
            }
            if (virtual) facts.put(TOMCAT_VIRTUAL_EXECUTOR, true);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            partial();
        }
    }

    /**
     * Deprecated Boot modules are identified from definition names and classpath presence only: an imported
     * auto-configuration is registered under its fully qualified class name, and the properties migrator registers
     * itself through {@code spring.factories}. Neither class is initialized.
     */
    private void observeMigrationModules() {
        facts.put(JAVA_FEATURE_VERSION, Runtime.version().feature());
        facts.put(PROPERTIES_MIGRATOR, present(PROPERTIES_MIGRATOR_LISTENER) != null);
        if (factory.containsBeanDefinition(JACKSON2_AUTOCONFIGURATION_CLASS))
            facts.put(JACKSON2_AUTOCONFIGURATION, true);
        else if (complete) facts.put(JACKSON2_AUTOCONFIGURATION, false);
    }

    /**
     * Reads the multipart limits Boot's own DispatcherServlet registration carries. The registration is created when
     * the server starts; a missing, lazy or custom registration leaves the limits unknown.
     */
    private void observeMultipart(boolean reactive) {
        if (reactive) return;
        Entry entry = entry(DISPATCHER_REGISTRATION);
        Object registration = entry == null ? null : factory.getSingleton(entry.name());
        if (!isBoot(entry, "org.springframework.boot.webmvc.autoconfigure.")
                || !exact(
                        registration,
                        "org.springframework.boot.webmvc.autoconfigure.DispatcherServletRegistrationBean")) return;
        try {
            Object config =
                    registration.getClass().getMethod("getMultipartConfig").invoke(registration);
            if (config == null) {
                facts.put(MULTIPART_DISABLED, true);
                return;
            }
            if (!exact(config, "jakarta.servlet.MultipartConfigElement")) return;
            facts.put(
                    MULTIPART_MAX_REQUEST_SIZE,
                    config.getClass().getMethod("getMaxRequestSize").invoke(config));
            facts.put(
                    MULTIPART_MAX_FILE_SIZE,
                    config.getClass().getMethod("getMaxFileSize").invoke(config));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            partial();
        }
    }

    static String jdbcKind(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("jdbc:h2:mem") || lower.startsWith("jdbc:h2:mem:") || lower.startsWith("jdbc:h2:mem;"))
            return "H2 memory";
        if (lower.startsWith("jdbc:hsqldb:mem:")) return "HSQLDB memory";
        if (lower.startsWith("jdbc:derby:memory:")) return "Derby memory";
        return "other";
    }

    static String r2dbcKind(String url) {
        String normalized = url.toLowerCase(java.util.Locale.ROOT);
        int query = normalized.indexOf('?');
        String base = query >= 0 ? normalized.substring(0, query) : normalized;
        return base.startsWith("r2dbc:h2:mem:") || base.startsWith("r2dbc:pool:h2:mem:") ? "H2 memory" : "other";
    }

    private static javax.sql.DataSource wrappedDataSource(javax.sql.DataSource dataSource) {
        if (SpringAopDataSources.isProxy(dataSource)) {
            return SpringAopDataSources.staticTarget(dataSource);
        }
        if (DelegatingDataSources.isSingleTarget(dataSource.getClass())) {
            return DelegatingDataSources.hasDistinctReadOnlyTarget(dataSource)
                    ? null
                    : DelegatingDataSources.target(dataSource);
        }
        return null;
    }

    private void observeOsiv(boolean reactive) {
        if (reactive) return;
        boolean coverage = complete;
        String registration = null;
        Entry interceptor = entry("openEntityManagerInViewInterceptor");
        Entry configurer = entry("openEntityManagerInViewInterceptorConfigurer");
        String owner = "org.springframework.boot.jpa.autoconfigure.JpaBaseConfiguration$JpaWebConfiguration";
        boolean bootInterceptor = interceptor != null
                && configurer != null
                && owner.equals(interceptor.factory())
                && owner.equals(configurer.factory());
        Set<Object> registeredFilters = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // A filter bean alone is NOT proof of an active registration. Custom initializers and
        // registration subclasses cannot establish absence without executing application code.
        for (Entry entry : typed("org.springframework.boot.web.servlet.ServletContextInitializer")) {
            Object singleton = factory.getSingleton(entry.name());
            if (exact(singleton, "org.springframework.beans.factory.support.NullBean")) continue;
            try {
                if (exact(singleton, "org.springframework.boot.web.servlet.FilterRegistrationBean")) {
                    Object enabled = singleton.getClass().getMethod("isEnabled").invoke(singleton);
                    Object filter = singleton.getClass().getMethod("getFilter").invoke(singleton);
                    if (filter == null) {
                        coverage = false;
                        continue;
                    }
                    registeredFilters.add(filter);
                    if (Boolean.TRUE.equals(enabled) && isOsivFilter(filter))
                        registration = "custom servlet filter registration (review URL/servlet mappings)";
                    if (Boolean.TRUE.equals(enabled)
                            && isType(filter.getClass(), "org.springframework.web.filter.DelegatingFilterProxy"))
                        coverage = false;
                } else if (exact(
                        singleton, "org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean")) {
                    if (!Boolean.TRUE.equals(
                            singleton.getClass().getMethod("isEnabled").invoke(singleton))) continue;
                    Object name = field(singleton, singleton.getClass(), "targetBeanName");
                    Object target = name instanceof String beanName ? factory.getSingleton(beanName) : null;
                    if (isOsivFilter(target)) {
                        registration = "custom servlet filter registration (review URL/servlet mappings)";
                    } else if ((target = nativeSecurityProxy(target)) != null) {
                        Object chains =
                                target.getClass().getMethod("getFilterChains").invoke(target);
                        if (!(chains instanceof List<?> list) || list.size() > MAX_MEMBERS) {
                            coverage = false;
                            continue;
                        }
                        int filtersInspected = 0;
                        for (Object chain : list) {
                            if (!exact(chain, "org.springframework.security.web.DefaultSecurityFilterChain")) {
                                coverage = false;
                                continue;
                            }
                            Object filters =
                                    chain.getClass().getMethod("getFilters").invoke(chain);
                            if (!(filters instanceof List<?> chainFilters)
                                    || (filtersInspected += chainFilters.size()) > MAX_MEMBERS) {
                                coverage = false;
                                break;
                            }
                            if (chainFilters.stream().anyMatch(SpringInventory::isOsivFilter))
                                registration = "servlet security filter chain registration (review request matchers)";
                        }
                    } else {
                        coverage = false;
                    }
                } else if (singleton == null
                        || !Set.of(
                                        "org.springframework.boot.web.servlet.ServletRegistrationBean",
                                        "org.springframework.boot.webmvc.autoconfigure.DispatcherServletRegistrationBean",
                                        "org.springframework.boot.actuate.endpoint.web.ServletEndpointRegistrar")
                                .contains(singleton.getClass().getName())) {
                    coverage = false;
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                coverage = false;
            }
        }
        // Boot can auto-register Filter beans too; without explicit registration metadata their
        // enabled/mapping state is unknown, not absent.
        for (Entry entry : typed("org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter")) {
            if (!registeredFilters.contains(factory.getSingleton(entry.name()))) coverage = false;
        }
        List<Entry> mappings = typed("org.springframework.web.servlet.HandlerMapping");
        if (mappings.isEmpty()
                && (!typed("jakarta.persistence.EntityManagerFactory").isEmpty()
                        || !typed("org.springframework.web.servlet.DispatcherServlet")
                                .isEmpty())) coverage = false;
        int inspectedInterceptors = 0;
        for (Entry entry : mappings) {
            Object mapping = factory.getSingleton(entry.name());
            // Native MVC factories return a NullBean when no view/default-servlet mapping is needed.
            // This is an observed null factory result, unlike an uninstantiated lazy mapping.
            if (exact(mapping, "org.springframework.beans.factory.support.NullBean")) continue;
            if (mapping == null
                    || !NATIVE_HANDLER_MAPPINGS.contains(mapping.getClass().getName())) {
                coverage = false;
                continue;
            }
            try {
                Object value =
                        mapping.getClass().getMethod("getAdaptedInterceptors").invoke(mapping);
                // AbstractHandlerMapping returns null for its native empty adapted list.
                if (value == null) continue;
                if (!(value instanceof Object[] interceptors)
                        || interceptors.length > MAX_MEMBERS
                        || (inspectedInterceptors += interceptors.length) > MAX_TOTAL_MEMBERS) {
                    coverage = false;
                    continue;
                }
                for (Object registered : interceptors) {
                    Object candidate = registered;
                    if (exact(candidate, "org.springframework.web.servlet.handler.MappedInterceptor"))
                        candidate =
                                candidate.getClass().getMethod("getInterceptor").invoke(candidate);
                    if (!exact(
                            candidate, "org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter")) {
                        if (candidate != null
                                && (isType(
                                                candidate.getClass(),
                                                "org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter")
                                        || isType(
                                                candidate.getClass(),
                                                "org.springframework.web.servlet.handler.MappedInterceptor")))
                            coverage = false;
                        continue;
                    }
                    Field delegate = candidate.getClass().getDeclaredField("requestInterceptor");
                    if (!delegate.trySetAccessible()) {
                        coverage = false;
                        continue;
                    }
                    Object interceptorInstance = delegate.get(candidate);
                    if (interceptorInstance != null
                            && isType(
                                    interceptorInstance.getClass(),
                                    "org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor"))
                        if (registration == null)
                            registration =
                                    bootInterceptor && interceptorInstance == factory.getSingleton(interceptor.name())
                                            ? "Boot servlet interceptor applied to MVC handler mappings"
                                            : "custom servlet interceptor registration (review handler/path mappings)";
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                coverage = false;
            }
        }
        facts.put(OSIV, new SpringObservations.OsivObservation(registration, coverage));
    }

    private static boolean isOsivFilter(Object filter) {
        return filter != null
                && isType(filter.getClass(), "org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter");
    }

    private static Object nativeSecurityProxy(Object candidate) throws ReflectiveOperationException {
        // Same exact native composites as SecurityScanner; string-based here to keep Security optional.
        for (int depth = 0; candidate != null && depth < 8; depth++) {
            if (exact(candidate, "org.springframework.security.web.FilterChainProxy")) return candidate;
            if (!Set.of(
                            "org.springframework.security.config.annotation.web.configuration.WebSecurityConfiguration$CompositeFilterChainProxy",
                            "org.springframework.security.config.annotation.web.configuration.WebMvcSecurityConfiguration$CompositeFilterChainProxy")
                    .contains(candidate.getClass().getName())) return null;
            candidate = field(candidate, candidate.getClass(), "springSecurityFilterChain");
        }
        return null;
    }

    /** Exact native classes inheriting AbstractHandlerMapping's applied-interceptor metadata. */
    private static final Set<String> NATIVE_HANDLER_MAPPINGS = Set.of(
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping",
            "org.springframework.web.servlet.handler.SimpleUrlHandlerMapping",
            "org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping",
            "org.springframework.web.servlet.function.support.RouterFunctionMapping",
            "org.springframework.web.socket.server.support.WebSocketHandlerMapping",
            "org.springframework.boot.webmvc.autoconfigure.WelcomePageHandlerMapping",
            "org.springframework.boot.webmvc.autoconfigure.WelcomePageNotAcceptableHandlerMapping",
            "org.springframework.boot.webmvc.actuate.endpoint.web.AdditionalHealthEndpointPathsWebMvcHandlerMapping",
            "org.springframework.boot.webmvc.actuate.endpoint.web.WebMvcEndpointHandlerMapping",
            "org.springframework.boot.webmvc.actuate.endpoint.web.ControllerEndpointHandlerMapping");

    private void observeEndpoints() {
        List<Entry> resolvers = typed("org.springframework.boot.actuate.endpoint.EndpointAccessResolver");
        if (resolvers.size() != 1
                || resolvers.stream()
                        .anyMatch(e -> !e.type()
                                        .getName()
                                        .equals(
                                                "org.springframework.boot.actuate.autoconfigure.endpoint.PropertiesEndpointAccessResolver")
                                || !isFramework(e))
                || typed("org.springframework.boot.actuate.endpoint.EndpointFilter").stream()
                        .anyMatch(e -> !isFramework(e))) return;
        Set<String> ids = new LinkedHashSet<>();
        for (Entry entry : entries) {
            // Fixed Boot endpoint types only; no suppliers or arbitrary annotation methods invoked.
            String name = entry.type().getName();
            if (!name.startsWith("org.springframework.boot.")) continue;
            String id =
                    switch (entry.type().getSimpleName()) {
                        case "EnvironmentEndpoint" -> "env";
                        case "ConfigurationPropertiesReportEndpoint" -> "configprops";
                        case "BeansEndpoint" -> "beans";
                        case "ThreadDumpEndpoint" -> "threaddump";
                        case "LoggersEndpoint" -> "loggers";
                        case "HttpExchangesEndpoint" -> "httpexchanges";
                        case "StartupEndpoint" -> "startup";
                        case "MappingsEndpoint" -> "mappings";
                        case "HealthEndpoint" -> "health";
                        case "HeapDumpWebEndpoint" -> "heapdump";
                        case "ShutdownEndpoint" -> "shutdown";
                        default -> null;
                    };
            if (id != null) ids.add(id);
        }
        boolean web = entries.stream()
                .anyMatch(
                        e -> isBoot(e, "org.springframework.boot.actuate.autoconfigure.endpoint.web.")
                                && exact(
                                        factory.getSingleton(e.name()),
                                        "org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer")
                                && e.type()
                                        .getName()
                                        .equals(
                                                "org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer"));
        if (web) facts.put(ENDPOINTS, Set.copyOf(ids));
    }

    private static boolean exact(Object value, String type) {
        return value != null && value.getClass().getName().equals(type);
    }

    private static Object field(Object value, Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException();
        return field.get(value);
    }

    private static boolean isBoot(Entry entry, String prefix) {
        return entry != null && entry.factory() != null && entry.factory().startsWith(prefix);
    }

    private static boolean isFramework(Entry entry) {
        return isBoot(entry, "org.springframework.boot.")
                || (entry.factory() == null
                        && entry.method() == null
                        && entry.type().getName().startsWith("org.springframework.boot.")
                        && hasAnnotation(entry.type(), "org.springframework.context.annotation.Configuration"));
    }

    private static boolean isKnownNonCodecWebFluxConfigurer(Entry entry) {
        return isFramework(entry)
                || BOOTUI_WEBFLUX_CONFIGURERS.contains(entry.type().getName());
    }

    private boolean hasApplicationSecurityChain() {
        return typed("org.springframework.security.web.SecurityFilterChain").stream()
                        .anyMatch(entry -> !isBootUiSecurityChain(entry))
                || typed("org.springframework.security.web.server.SecurityWebFilterChain").stream()
                        .anyMatch(entry -> !isBootUiSecurityChain(entry));
    }

    private static boolean isBootUiSecurityChain(Entry entry) {
        return BOOTUI_SECURITY_CHAIN_NAMES.contains(entry.name())
                || entry.ref().aliases().stream().anyMatch(BOOTUI_SECURITY_CHAIN_NAMES::contains)
                || (entry.factory() != null && BOOTUI_SECURITY_AUTOCONFIGURATIONS.contains(entry.factory()));
    }

    private Entry entry(String name) {
        return named(entries, name);
    }

    private static Entry named(List<Entry> entries, String name) {
        return entries.stream()
                .filter(e -> e.name().equals(name) || e.ref().aliases().contains(name))
                .findFirst()
                .orElse(null);
    }

    private static Entry only(List<Entry> entries) {
        return entries.size() == 1 ? entries.get(0) : null;
    }

    private boolean byTypeSelectionObservable(List<Entry> entries) {
        if (!(factory instanceof DefaultListableBeanFactory listable)) return false;
        var comparator = listable.getDependencyComparator();
        return (comparator == null
                        || comparator.getClass() == org.springframework.core.OrderComparator.class
                        || comparator.getClass()
                                == org.springframework.core.annotation.AnnotationAwareOrderComparator.class)
                && entries.stream().noneMatch(e -> isType(e.type(), "org.springframework.core.DecoratingProxy"));
    }

    /** Framework resolveNamedBean ordering, not injection-point default-candidate filtering. */
    private Entry selectedByType(List<Entry> entries) {
        if (entries.stream().anyMatch(e -> !e.ref().metadataKnown())) return null;
        List<Entry> candidates = entries;
        if (entries.size() > 1) {
            var autowire =
                    entries.stream().filter(e -> e.ref().autowireCandidate()).toList();
            if (!autowire.isEmpty()) candidates = autowire;
        }
        if (candidates.size() == 1) return candidates.get(0);
        List<Entry> primaries =
                candidates.stream().filter(e -> e.ref().primary()).toList();
        if (!primaries.isEmpty()) return only(primaries);
        Entry nonFallback =
                only(candidates.stream().filter(e -> !e.ref().fallback()).toList());
        if (nonFallback != null) return nonFallback;
        if (((DefaultListableBeanFactory) factory).getDependencyComparator()
                instanceof org.springframework.core.annotation.AnnotationAwareOrderComparator) {
            // Pass Class metadata, never application instances or decorating-proxy callbacks.
            Map<Entry, Integer> priorities = new java.util.LinkedHashMap<>();
            for (Entry candidate : candidates) {
                Integer priority =
                        org.springframework.core.annotation.AnnotationAwareOrderComparator.INSTANCE.getPriority(
                                candidate.type());
                if (priority != null) priorities.put(candidate, priority);
            }
            if (!priorities.isEmpty()) {
                int highest = java.util.Collections.min(priorities.values());
                return only(priorities.entrySet().stream()
                        .filter(e -> e.getValue() == highest)
                        .map(Map.Entry::getKey)
                        .toList());
            }
        }
        return only(candidates.stream().filter(e -> e.ref().defaultCandidate()).toList());
    }

    private List<Entry> typed(String type) {
        Class<?> target = present(type);
        return target == null
                ? List.of()
                : entries.stream()
                        .filter(e -> target.isAssignableFrom(e.type()))
                        .toList();
    }

    private List<BeanRef> refs(String type) {
        return typed(type).stream().map(Entry::ref).toList();
    }

    private static boolean isType(Class<?> type, String name) {
        Class<?> target = present(name);
        return target != null && target.isAssignableFrom(type);
    }

    private static String mapperGroup(Class<?> type) {
        if (isType(type, "com.fasterxml.jackson.databind.ObjectMapper")) return "jackson2";
        return isType(type, "tools.jackson.databind.json.JsonMapper") ? "jackson3" : "";
    }

    private static Class<?> present(String name) {
        try {
            return ClassUtils.forName(name, SpringInventory.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> presentAnnotation(String name) {
        Class<?> type = present(name);
        return type != null && Annotation.class.isAssignableFrom(type) ? (Class<? extends Annotation>) type : null;
    }

    private static List<Class<? extends Annotation>> presentAnnotations(String... names) {
        List<Class<? extends Annotation>> result = new ArrayList<>(names.length);
        for (String name : names) {
            Class<? extends Annotation> type = presentAnnotation(name);
            if (type != null) result.add(type);
        }
        return List.copyOf(result);
    }

    private static boolean hasAnnotation(Class<?> type, String name) {
        for (Annotation annotation : type.getAnnotations())
            if (annotation.annotationType().getName().equals(name)) return true;
        return false;
    }

    private boolean budget(int count) {
        members += count;
        if (count > MAX_MEMBERS || members > MAX_TOTAL_MEMBERS) {
            partial();
            return false;
        }
        return true;
    }

    private void partial() {
        complete = false;
        incomplete.add("Bean metadata inspection incomplete: unavailable type/metadata or work limit reached.");
    }
}
