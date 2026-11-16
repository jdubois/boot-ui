package io.github.jdubois.bootui.autoconfigure.insights;

import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityAware;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.insights.CacheCapture;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.ClassUtils;

/** Passive, conservative proof of the standard synchronous Spring cache-manager routing path. */
final class SpringCacheCapture implements Supplier<CacheCapture> {

    static final String CUSTOM_ROUTING =
            "Custom cache resolvers or CachingConfigurer routing cannot prove observable cache access without invoking application callbacks.";
    static final String NO_PROXY =
            "The standard proxy-based Spring cache interceptor is not proven installed; absent or AspectJ caching is not assessed.";
    static final String AMBIGUOUS =
            "Cache capture is unknown because recorder or routing declarations are ambiguous, unresolved, or not initialized.";
    static final String HIERARCHY =
            "Inherited application-context cache routing is not proven observable without invoking ancestor configuration.";
    private static final int MAX_BEANS = 4096;
    private static final String PROXY_CONFIGURATION = "org.springframework.cache.annotation.ProxyCachingConfiguration";

    private final ApplicationContext context;

    SpringCacheCapture(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public CacheCapture get() {
        if (!(context instanceof ConfigurableApplicationContext configurable)) {
            return CacheCapture.notRecorded(CacheCapture.NOT_RECORDED);
        }
        if (context.getParent() != null) {
            return CacheCapture.notRecorded(HIERARCHY);
        }
        ConfigurableListableBeanFactory beans = configurable.getBeanFactory();
        KnownBeans known = knownBeans(beans);
        List<String> recorders = known.names(CacheActivityRecorder.class);
        if (recorders.size() != 1 || known.unresolved()) {
            return CacheCapture.notRecorded(recorders.isEmpty() ? CacheCapture.NOT_RECORDED : AMBIGUOUS);
        }
        Object singleton = beans.getSingleton(recorders.get(0));
        if (!(singleton instanceof CacheActivityRecorder recorder)) {
            return CacheCapture.notRecorded(AMBIGUOUS);
        }
        CacheCapture state = CacheCapture.of(recorder.captureState());
        if (state.reason() != null) {
            return state;
        }
        if (!known.names(CachingConfigurer.class).isEmpty()
                || !known.names(CacheResolver.class).isEmpty()) {
            return CacheCapture.notRecorded(CUSTOM_ROUTING);
        }
        List<String> interceptors = known.names(CacheInterceptor.class);
        if (interceptors.size() != 1 || !standardInterceptor(beans, interceptors.get(0))) {
            return CacheCapture.notRecorded(NO_PROXY);
        }
        List<String> managers = known.names(CacheManager.class);
        Map<String, Long> observable = new LinkedHashMap<>();
        for (String name : managers) {
            Object manager = beans.getSingleton(name);
            String captured = manager instanceof CacheManager cacheManager
                    ? CacheActivityAware.capturedManagerName(cacheManager, recorder)
                    : null;
            Long since = captured == null ? null : state.continuousSince().get(captured);
            if (since != null) {
                observable.put(name, since);
                for (String alias : beans.getAliases(name)) {
                    if (observable.size() >= MAX_BEANS) {
                        return CacheCapture.notRecorded("Cache-manager aliases exceeded the bounded capture proof.");
                    }
                    observable.put(alias, since);
                }
            }
        }
        String defaultManager =
                managers.size() == 1 && observable.containsKey(managers.get(0)) ? managers.get(0) : null;
        return new CacheCapture(observable, defaultManager, state.epoch(), null);
    }

    private static boolean standardInterceptor(ConfigurableListableBeanFactory beans, String name) {
        Object interceptor = beans.getSingleton(name);
        if (interceptor == null
                || interceptor.getClass() != CacheInterceptor.class
                || !beans.containsBeanDefinition(name)) {
            return false;
        }
        BeanDefinition definition = beans.getBeanDefinition(name);
        String factory = definition.getFactoryBeanName();
        return "cacheInterceptor".equals(definition.getFactoryMethodName())
                && factory != null
                && beans.containsBeanDefinition(factory)
                && PROXY_CONFIGURATION.equals(beans.getBeanDefinition(factory).getBeanClassName());
    }

    /**
     * Unlike getBeanNamesForType/getType, this cannot call FactoryBean.getObjectType or initialize a lazy factory.
     * Unknown factory products withhold proof rather than invoking them to discover what they might supply.
     */
    private static KnownBeans knownBeans(ConfigurableListableBeanFactory beans) {
        Map<String, Class<?>> types = new LinkedHashMap<>();
        String[] definitions = beans.getBeanDefinitionNames();
        String[] singletons = beans.getSingletonNames();
        if (definitions.length > MAX_BEANS || singletons.length > MAX_BEANS) {
            return new KnownBeans(Map.of(), true);
        }
        Set<String> unknown = new LinkedHashSet<>();
        for (String name : definitions) {
            BeanDefinition definition = beans.getBeanDefinition(name);
            if (definition.isAbstract()) continue;
            ResolvableType declared = definition.getResolvableType();
            Class<?> type = declared.resolve();
            if (type == null && definition instanceof AnnotatedBeanDefinition annotated) {
                MethodMetadata factory = annotated.getFactoryMethodMetadata();
                String className =
                        factory == null ? annotated.getMetadata().getClassName() : factory.getReturnTypeName();
                type = ClassUtils.resolveClassName(className, beans.getBeanClassLoader());
                declared = ResolvableType.forClass(type);
            }
            if (type == null && definition.getBeanClassName() != null) {
                type = ClassUtils.resolveClassName(definition.getBeanClassName(), beans.getBeanClassLoader());
                declared = ResolvableType.forClass(type);
            }
            if (type != null && FactoryBean.class.isAssignableFrom(type)) {
                type = productType(beans, name, declared);
            }
            if (type == null || type == Object.class) {
                unknown.add(name);
            } else {
                types.put(name, type);
            }
        }
        for (String name : singletons) {
            Object singleton = beans.getSingleton(name);
            if (singleton instanceof FactoryBean<?>) {
                Class<?> product = types.get(name);
                if (product == null) {
                    product = productType(beans, name, ResolvableType.forClass(singleton.getClass()));
                }
                if (product == null || product == Object.class) {
                    unknown.add(name);
                } else {
                    types.put(name, product);
                    unknown.remove(name);
                }
            } else if (singleton != null) {
                types.put(name, singleton.getClass());
                unknown.remove(name);
            }
        }
        return new KnownBeans(Map.copyOf(types), !unknown.isEmpty());
    }

    private static Class<?> productType(ConfigurableListableBeanFactory beans, String name, ResolvableType factory) {
        Object declared = beans.containsBeanDefinition(name)
                ? beans.getBeanDefinition(name).getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE)
                : null;
        if (declared instanceof Class<?> type) return type;
        if (declared instanceof ResolvableType type) return type.resolve();
        return factory.as(FactoryBean.class).getGeneric(0).resolve();
    }

    private record KnownBeans(Map<String, Class<?>> types, boolean unresolved) {
        List<String> names(Class<?> expected) {
            return types.entrySet().stream()
                    .filter(entry -> expected.isAssignableFrom(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
        }
    }
}
