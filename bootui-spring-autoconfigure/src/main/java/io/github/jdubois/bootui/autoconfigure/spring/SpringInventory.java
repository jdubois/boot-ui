package io.github.jdubois.bootui.autoconfigure.spring;

import static io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact.*;

import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityAware;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.CacheManagerRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.AsyncSelection;
import io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact;
import io.github.jdubois.bootui.engine.support.KotlinReflection;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
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
    private static final String ASYNC_PROCESSOR =
            "org.springframework.scheduling.config.internalAsyncAnnotationProcessor";
    private static final String SCHEDULED_PROCESSOR =
            "org.springframework.scheduling.config.internalScheduledAnnotationProcessor";
    private static final String BOOT_ASYNC = "applicationTaskExecutorAsyncConfigurer";
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
            boolean lazy,
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
                        definition != null && definition.isLazyInit(),
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
        int handlers = inspectApplicationTypes(defaultPackage, mutable);
        boolean async = entry(ASYNC_PROCESSOR) != null;
        boolean scheduling = entry(SCHEDULED_PROCESSOR) != null;
        observeAsync(async);
        observeScheduling(scheduling);
        observeBootConfiguration(reactive);
        observeTomcat();
        observeEndpoints();
        observeData();
        observeOsiv(reactive);
        if (complete)
            facts.put(
                    LAZY_DEFINITIONS, (int) entries.stream().filter(Entry::lazy).count());
        return SpringContext.builder(environment)
                .virtualThreadsSupported(Runtime.version().feature() >= 21)
                .beanDefinitionCount(factory.getBeanDefinitionCount())
                .objectMappers(entries.stream()
                        .filter(e -> !e.ref().group().isEmpty())
                        .map(Entry::ref)
                        .toList())
                .taskExecutors(refs("org.springframework.core.task.TaskExecutor"))
                .bootApplicationTaskExecutorPresent(
                        isBoot(entry("applicationTaskExecutor"), "org.springframework.boot.autoconfigure.task."))
                .executors(refs("java.util.concurrent.Executor"))
                .dataSources(refs("javax.sql.DataSource"))
                .pooledTaskExecutorPresent(!typed("org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor")
                        .isEmpty())
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
                .observations(new SpringObservations(facts, List.copyOf(incomplete)))
                .build();
    }

    private void observeFactory() {
        if (factory instanceof DefaultListableBeanFactory listable)
            facts.put(OVERRIDING, listable.isAllowBeanDefinitionOverriding());
        if (factory instanceof AbstractAutowireCapableBeanFactory capable)
            facts.put(CIRCULAR, capable.isAllowCircularReferences());
    }

    private CacheManagerRef cacheManager(Entry entry) {
        Object singleton = factory.getSingleton(entry.name());
        // Never instantiate a lazy manager/FactoryBean or call an application wrapper.
        Class<?> type = singleton instanceof org.springframework.cache.CacheManager manager
                ? CacheActivityAware.unwrap(manager).getClass()
                : entry.type();
        return new CacheManagerRef(entry.name(), type.getName());
    }

    private int inspectApplicationTypes(List<String> packages, List<String> mutable) {
        int handlers = 0;
        for (Entry entry : entries) {
            if (members >= MAX_TOTAL_MEMBERS) {
                partial();
                break;
            }
            Class<?> type = entry.type();
            if (!entry.application()
                    || type.getName().startsWith("io.github.jdubois.bootui.autoconfigure.")
                    || type.getName().startsWith("org.springframework.")
                    || type.isInterface()) continue;
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
                || asyncQualifiers()) return;
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
        if (singleton != null && singleton.getClass() == ThreadPoolTaskExecutor.class) {
            // Reading the configured field alone can be stale after initialization; use the actual queue.
            try {
                var queue = ((ThreadPoolTaskExecutor) singleton)
                        .getThreadPoolExecutor()
                        .getQueue();
                if (queue.getClass() == java.util.concurrent.LinkedBlockingQueue.class
                        || queue.getClass() == java.util.concurrent.ArrayBlockingQueue.class
                        || queue.getClass() == java.util.concurrent.SynchronousQueue.class) {
                    long capacity = (long) queue.size() + queue.remainingCapacity();
                    facts.put(ASYNC_QUEUE_CAPACITY, (int) Math.min(Integer.MAX_VALUE, capacity));
                }
            } catch (IllegalStateException ex) {
                /* uninitialized executor is unknown */
            }
        }
    }

    private boolean asyncQualifiers() {
        for (Entry entry : entries) {
            if (members >= MAX_TOTAL_MEMBERS) {
                partial();
                return true;
            }
            if (!entry.application()) continue;
            try {
                Async typeAsync = AnnotatedElementUtils.findMergedAnnotation(entry.type(), Async.class);
                if (typeAsync != null && !typeAsync.value().isEmpty()) return true;
                Method[] methods = entry.type().getMethods();
                if (!budget(methods.length)) return true;
                for (Method method : methods) {
                    Async annotation = AnnotatedElementUtils.findMergedAnnotation(method, Async.class);
                    if (annotation != null && !annotation.value().isEmpty()) return true;
                }
                Class<?> owner = entry.type();
                int depth = 0;
                while (owner != null && owner != Object.class) {
                    if (++depth > 16) {
                        partial();
                        return true;
                    }
                    Method[] declared = owner.getDeclaredMethods();
                    if (!budget(declared.length)) return true;
                    for (Method method : declared) {
                        if (Modifier.isPublic(method.getModifiers())) continue;
                        Async annotation = AnnotatedElementUtils.findMergedAnnotation(method, Async.class);
                        if (annotation != null && !annotation.value().isEmpty()) return true;
                    }
                    owner = owner.getSuperclass();
                }
            } catch (RuntimeException | LinkageError ex) {
                partial();
                return true;
            }
        }
        return false;
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
                        .allMatch(SpringInventory::isFramework)) {
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
        for (Entry entry : typed("javax.sql.DataSource")) {
            Object singleton = factory.getSingleton(entry.name());
            if (exact(singleton, "com.zaxxer.hikari.HikariDataSource")
                    && typed("javax.sql.DataSource").size() == 1) {
                try {
                    if (singleton.getClass().getMethod("getDataSource").invoke(singleton) != null
                            || singleton
                                            .getClass()
                                            .getMethod("getDataSourceClassName")
                                            .invoke(singleton)
                                    != null) continue;
                    String url = (String)
                            singleton.getClass().getMethod("getJdbcUrl").invoke(singleton);
                    if (url != null && url.length() <= SpringProperties.MAX_TEXT) facts.put(JDBC_KIND, jdbcKind(url));
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    partial();
                }
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
                        String normalized = url.toLowerCase(java.util.Locale.ROOT);
                        if (normalized.contains("?")) return; // URL options may override driver/protocol.
                        facts.put(
                                R2DBC_KIND,
                                normalized.startsWith("r2dbc:h2:mem:") || normalized.startsWith("r2dbc:pool:h2:mem:")
                                        ? "H2 memory"
                                        : "other");
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

    static String jdbcKind(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("jdbc:h2:mem:")) return "H2 memory";
        if (lower.startsWith("jdbc:hsqldb:mem:")) return "HSQLDB memory";
        if (lower.startsWith("jdbc:derby:memory:")) return "Derby memory";
        return "other";
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
