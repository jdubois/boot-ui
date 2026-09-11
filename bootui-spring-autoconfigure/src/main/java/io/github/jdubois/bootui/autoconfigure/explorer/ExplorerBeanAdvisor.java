package io.github.jdubois.bootui.autoconfigure.explorer;

import io.github.jdubois.bootui.autoconfigure.beans.SpringBeanClassification;
import io.github.jdubois.bootui.autoconfigure.exceptions.BootUiExceptionHandlerResolver;
import io.github.jdubois.bootui.autoconfigure.exceptions.BootUiExceptionLogAppender;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.explorer.LocalInvocationCapture;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.spi.InvocationContextProvider;
import java.io.Closeable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyConfig;
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.framework.autoproxy.ProxyCreationContext;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * One infrastructure advisor shared with Spring's existing auto-proxy creator. Its initialization
 * callback caches singleton metadata without requesting any application bean. Already-advised
 * FactoryBean products (notably Spring Data) receive this same advisor in-place, not another proxy.
 */
public final class ExplorerBeanAdvisor extends StaticMethodMatcherPointcutAdvisor
        implements BeanPostProcessor, AopInfrastructureBean {

    private static final List<String> INFRASTRUCTURE_TYPES = List.of(
            "org.springframework.beans.factory.Aware",
            "org.springframework.beans.factory.InitializingBean",
            "org.springframework.beans.factory.DisposableBean",
            "org.springframework.beans.factory.SmartInitializingSingleton",
            "org.springframework.beans.factory.config.BeanFactoryPostProcessor",
            "org.springframework.context.ApplicationListener",
            "org.springframework.context.Lifecycle",
            "org.springframework.transaction.TransactionManager",
            "org.springframework.cache.Cache",
            "org.springframework.cache.CacheManager",
            "java.util.concurrent.Executor",
            "javax.sql.DataSource",
            "java.sql.Connection",
            "java.sql.Statement");

    private final ConfigurableListableBeanFactory beanFactory;
    private final ObjectProvider<SpringInvocationContext> contexts;
    private final Map<Object, Metadata> metadata = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<Class<?>> targetClasses = ConcurrentHashMap.newKeySet();
    private final Map<String, Class<?>> proxyCandidates = new ConcurrentHashMap<>();
    private final InvocationContextProvider provider;
    private volatile List<String> packages;
    private volatile Boolean classProxying;

    public ExplorerBeanAdvisor(
            ConfigurableListableBeanFactory beanFactory, ObjectProvider<SpringInvocationContext> contexts) {
        this.beanFactory = beanFactory;
        this.contexts = contexts;
        this.provider = () -> {
            SpringInvocationContext context = contexts.getIfAvailable();
            return context == null ? InvocationContextProvider.EMPTY : context.current();
        };
        setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        setAdvice((MethodInterceptor) this::invoke);
        setClassFilter(type -> !Proxy.isProxyClass(type)
                && ClassUtils.getUserClass(type) == type
                && targetClasses.contains(type)
                && eligibleCurrentBean(type));
    }

    /**
     * Outside proxy creation the proxy already exists, so advising it cannot change the type the
     * application injects. While the shared creator is building the first proxy for a bean, this
     * advisor requires this bean's own safe registration, then preserves its concrete assignability.
     * A compatible existing proxy must not make another bean of its target class eligible.
     */
    private boolean eligibleCurrentBean(Class<?> type) {
        String name = ProxyCreationContext.getCurrentProxiedBeanName();
        return name == null
                || (proxyCandidates.get(name) == type
                        && eligibleDefinition(name)
                        && preservesInjectionType(name, type));
    }

    /**
     * Mirrors the creator's own proxy-kind decision. An interface proxy introduced only for
     * observation would break existing constructor injection and {@code getBean(Impl.class)}.
     */
    private boolean preservesInjectionType(String name, Class<?> type) {
        if (AutoProxyUtils.shouldProxyTargetClass(beanFactory, name)) {
            return true;
        }
        if (beanFactory.getBeanDefinition(name).getAttribute(AutoProxyUtils.EXPOSED_INTERFACES_ATTRIBUTE) != null) {
            return false;
        }
        return classProxying() || !hasProxyableInterface(type);
    }

    private boolean classProxying() {
        Boolean known = classProxying;
        if (known != null) {
            return known;
        }
        boolean creators = false;
        boolean classProxies = true;
        for (String name : beanFactory.getBeanNamesForType(AbstractAutoProxyCreator.class, true, false)) {
            if (beanFactory.getSingleton(name) instanceof ProxyConfig creator) {
                creators = true;
                classProxies &= creator.isProxyTargetClass();
            }
        }
        if (creators) {
            classProxying = classProxies;
        }
        return creators && classProxies;
    }

    private boolean hasProxyableInterface(Class<?> type) {
        return ClassUtils.getAllInterfacesForClassAsSet(type, beanFactory.getBeanClassLoader()).stream()
                .anyMatch(candidate -> candidate.getMethods().length > 0
                        && candidate != InitializingBean.class
                        && candidate != DisposableBean.class
                        && candidate != Closeable.class
                        && candidate != AutoCloseable.class
                        && !ObjectUtils.containsElement(candidate.getInterfaces(), Aware.class)
                        && !candidate.getName().equals("groovy.lang.GroovyObject")
                        && !candidate.getName().endsWith(".cglib.proxy.Factory")
                        && !candidate.getName().endsWith(".bytebuddy.MockAccess"));
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return Modifier.isPublic(method.getModifiers())
                && !Modifier.isStatic(method.getModifiers())
                && !method.isBridge()
                && !method.isSynthetic()
                && !ReflectionUtils.isObjectMethod(method);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        try {
            if (!(bean instanceof Advised)) {
                register(bean, beanName, bean.getClass(), false, false);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Unavailable optional type metadata must not prevent application startup.
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof SqlTraceRecorder recorder) {
            recorder.setInvocationContextProvider(provider);
        } else if (bean instanceof CacheActivityRecorder recorder) {
            recorder.setInvocationContextProvider(provider);
        } else if (bean instanceof BootUiExceptionHandlerResolver resolver) {
            resolver.setInvocationContextProvider(provider);
        } else if (bean.getClass()
                .getName()
                .equals("io.github.jdubois.bootui.autoconfigure.exceptions.BootUiExceptionLogAppender")) {
            LogbackContext.install(bean, provider);
        }
        attach(bean, beanName);
        return bean;
    }

    /**
     * Advises a proxy another post-processor already created. The proxy's exposed type is fixed by
     * then, so joining its chain never changes how the application injects or resolves the bean.
     */
    void attach(Object bean, String beanName) {
        try {
            if (bean instanceof Advised advised
                    && !advised.isFrozen()
                    && beanFactory.isSingleton(beanName)
                    && advised.getTargetSource() instanceof SingletonTargetSource source
                    && !advises(bean)) {
                Class<?> repository = repositoryInterface(bean.getClass());
                Object target = source.getTarget();
                Class<?> type = repository != null ? repository : AopUtils.getTargetClass(bean);
                if (register(target, beanName, type, repository != null, true)) {
                    advised.addAdvisor(0, this);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Keep an existing proxy unchanged when its metadata/advisor contract is unavailable.
        }
    }

    /** One target is observed once: an outer proxy is never advised when an inner one already is. */
    private boolean advises(Object bean) {
        Object current = bean;
        while (current instanceof Advised advised) {
            if (Arrays.stream(advised.getAdvisors()).anyMatch(advisor -> advisor == this)) {
                return true;
            }
            if (!(advised.getTargetSource() instanceof SingletonTargetSource source)) {
                return false;
            }
            current = source.getTarget();
        }
        return false;
    }

    private boolean register(Object bean, String name, Class<?> type, boolean repository, boolean existingProxy) {
        if (bean == null
                || bean instanceof FactoryBean<?>
                || bean instanceof AopInfrastructureBean
                || bean instanceof BeanPostProcessor
                || !eligibleDefinition(name)
                || !applicationType(type)
                || AnnotatedElementUtils.hasAnnotation(type, Configuration.class)
                || (!existingProxy && !canIntroduceClassProxy(type))
                || INFRASTRUCTURE_TYPES.stream().anyMatch(excluded -> implementsType(type, excluded))) {
            return false;
        }
        String role = repository || AnnotatedElementUtils.hasAnnotation(type, Repository.class)
                ? "REPOSITORY"
                : AnnotatedElementUtils.hasAnnotation(type, Controller.class)
                        ? "CONTROLLER"
                        : AnnotatedElementUtils.hasAnnotation(type, Service.class) ? "SERVICE" : "COMPONENT";
        metadata.put(bean, new Metadata(name, type.getName(), role));
        targetClasses.add(bean.getClass());
        if (!existingProxy) {
            proxyCandidates.put(name, type);
        }
        return true;
    }

    private static boolean canIntroduceClassProxy(Class<?> type) {
        return !Modifier.isFinal(type.getModifiers())
                && !type.isSealed()
                && Arrays.stream(type.getDeclaredConstructors())
                        .anyMatch(constructor -> !Modifier.isPrivate(constructor.getModifiers()))
                && !hasCallableFinalMethod(type);
    }

    private static boolean hasCallableFinalMethod(Class<?> type) {
        // A new Objenesis/CGLIB proxy has no initialized target fields. Its inherited final methods
        // cannot delegate to the real target, even when excluded from this advisor's pointcut.
        return Arrays.stream(ReflectionUtils.getAllDeclaredMethods(type))
                .anyMatch(method -> method.getDeclaringClass() != Object.class
                        && Modifier.isFinal(method.getModifiers())
                        && !Modifier.isPrivate(method.getModifiers())
                        && !Modifier.isStatic(method.getModifiers()));
    }

    private boolean eligibleDefinition(String name) {
        if (name == null || name.startsWith("scopedTarget.") || !beanFactory.containsBeanDefinition(name)) {
            return false;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(name);
        return definition.isSingleton() && definition.getRole() == BeanDefinition.ROLE_APPLICATION;
    }

    private boolean applicationType(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("io.github.jdubois.bootui.autoconfigure.")
                || name.startsWith("io.github.jdubois.bootui.engine.")
                || name.startsWith("io.github.jdubois.bootui.core.")
                || name.startsWith("io.github.jdubois.bootui.spi.")
                || !"APPLICATION".equals(SpringBeanClassification.classify(name, false))
                || name.startsWith("io.opentelemetry.")) {
            return false;
        }
        List<String> roots = packages;
        if (roots == null) {
            roots = AutoConfigurationPackages.has(beanFactory)
                    ? List.copyOf(AutoConfigurationPackages.get(beanFactory))
                    : List.of();
            packages = roots;
        }
        return roots.stream().anyMatch(root -> !root.isBlank() && name.startsWith(root + "."));
    }

    private Class<?> repositoryInterface(Class<?> type) {
        List<Class<?>> candidates = ClassUtils.getAllInterfacesForClassAsSet(type).stream()
                .filter(this::applicationType)
                .filter(iface -> implementsType(iface, "org.springframework.data.repository.Repository"))
                .toList();
        List<Class<?>> specific = candidates.stream()
                .filter(candidate ->
                        candidates.stream().noneMatch(other -> candidate != other && candidate.isAssignableFrom(other)))
                .toList();
        return specific.size() == 1 ? specific.get(0) : null;
    }

    private static boolean implementsType(Class<?> type, String name) {
        if (type == null) {
            return false;
        }
        if (name.equals(type.getName()) || implementsType(type.getSuperclass(), name)) {
            return true;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (implementsType(iface, name)) {
                return true;
            }
        }
        return false;
    }

    private Object invoke(MethodInvocation invocation) throws Throwable {
        Metadata bean = metadata.get(invocation.getThis());
        SpringInvocationContext context = null;
        LocalInvocationCapture.Invocation captured = null;
        if (bean != null) {
            try {
                context = contexts.getIfAvailable();
                if (context != null) {
                    captured = context.capture()
                            .enter(
                                    context.request(),
                                    bean.name,
                                    bean.type,
                                    bean.method(invocation.getMethod()),
                                    bean.role);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Observation is optional; preserve the target's own return/throw behavior.
            }
        }
        Throwable failure = null;
        try {
            return invocation.proceed();
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            if (context != null) {
                try {
                    context.capture().exit(captured, failure);
                } catch (RuntimeException | LinkageError ignored) {
                    // Never replace an application exception with a diagnostics failure.
                }
            }
        }
    }

    private static final class Metadata {
        private final String name;
        private final String type;
        private final String role;
        private final Map<Method, String> methods = new ConcurrentHashMap<>();

        private Metadata(String name, String type, String role) {
            this.name = name;
            this.type = type;
            this.role = role;
        }

        private String method(Method method) {
            return methods.computeIfAbsent(
                    method,
                    candidate -> candidate.getName() + "("
                            + String.join(
                                    ",",
                                    Arrays.stream(candidate.getParameterTypes())
                                            .map(Class::getTypeName)
                                            .toList())
                            + ")");
        }
    }

    private static final class LogbackContext {
        private static void install(Object bean, InvocationContextProvider provider) {
            ((BootUiExceptionLogAppender) bean).setInvocationContextProvider(provider);
        }
    }

    /**
     * Registered without an order so it runs after every ordered post-processor. A proxy another
     * post-processor creates after this advisor's own callback, such as an async or validation
     * proxy, is then still joined in place, while the chain check keeps one target observed once.
     */
    public static final class ProxyAttachment implements BeanPostProcessor, AopInfrastructureBean {

        private final ObjectProvider<ExplorerBeanAdvisor> advisors;

        public ProxyAttachment(ObjectProvider<ExplorerBeanAdvisor> advisors) {
            this.advisors = advisors;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            ExplorerBeanAdvisor advisor = advisors.getIfAvailable();
            if (advisor != null) {
                advisor.attach(bean, beanName);
            }
            return bean;
        }
    }
}
