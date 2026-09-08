package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.CacheManagerRef;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.core.env.Environment;

/**
 * Read-only inputs handed to every Spring Advisor rule: a snapshot of the running application
 * context (selected bean groups and feature flags) plus the application {@link Environment}.
 */
record SpringContext(
        Environment environment,
        boolean virtualThreadsSupported,
        int beanDefinitionCount,
        List<BeanRef> objectMappers,
        List<BeanRef> taskExecutors,
        boolean bootApplicationTaskExecutorPresent,
        List<BeanRef> executors,
        List<BeanRef> dataSources,
        boolean pooledTaskExecutorPresent,
        boolean asyncEnabled,
        boolean devToolsPresent,
        boolean customAsyncConfigurerPresent,
        List<BeanRef> transactionManagers,
        boolean transactionManagementConfigurerPresent,
        List<BeanRef> restTemplates,
        boolean restClientBeanPresent,
        boolean cachingEnabled,
        List<CacheManagerRef> cacheManagers,
        boolean schedulingEnabled,
        boolean entityManagerFactoryPresent,
        boolean dispatcherServletPresent,
        boolean reactive,
        boolean tomcatWebServerPresent,
        boolean webClientBeanPresent,
        int reactiveHandlerMethodCount,
        List<String> defaultPackageBeans,
        List<String> mutableSingletonFields,
        SpringObservations observations) {

    SpringContext {
        objectMappers = List.copyOf(objectMappers);
        taskExecutors = List.copyOf(taskExecutors);
        executors = List.copyOf(executors);
        dataSources = List.copyOf(dataSources);
        transactionManagers = List.copyOf(transactionManagers);
        restTemplates = List.copyOf(restTemplates);
        cacheManagers = List.copyOf(cacheManagers);
        defaultPackageBeans = List.copyOf(defaultPackageBeans);
        mutableSingletonFields = List.copyOf(mutableSingletonFields);
    }

    boolean applies(boolean applicable) {
        return observations.evaluation().applies(applicable);
    }

    <T> List<T> targets(List<T> targets) {
        applies(!targets.isEmpty());
        return targets;
    }

    <T> T observed(T value) {
        applies(true);
        return value;
    }

    String firstProperty(String... keys) {
        for (String key : keys) {
            String value = bind(key, String.class);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Like {@link #firstProperty}, but ignores the actuator defaults BootUI contributes itself, so a rule
     * reports only what the host application configured.
     */
    String firstHostProperty(String... keys) {
        for (String key : keys) {
            String value = SpringProperties.bind(environment, true, key, Bindable.of(String.class));
            if (value != null) return value.trim();
        }
        return null;
    }

    <T> T bind(String key, Class<T> type) {
        return SpringProperties.bind(environment, false, key, Bindable.of(type));
    }

    boolean isPropertyFalse(String key) {
        return Boolean.FALSE.equals(bind(key, Boolean.class));
    }

    Integer firstIntegerProperty(String... keys) {
        for (String key : keys) {
            Integer value = bind(key, Integer.class);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    boolean isPropertyTrue(String... keys) {
        for (String key : keys) {
            Boolean value = bind(key, Boolean.class);
            if (value != null) return value;
        }
        return false;
    }

    /**
     * Binds Duration without rounding positive sub-millisecond values to zero. Invalid or unresolved
     * configuration is an analysis error, never an inferred default.
     */
    Duration firstDurationProperty(String... keys) {
        for (String key : keys) {
            Duration value = bind(key, Duration.class);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    boolean hasProperty(String key) {
        return SpringProperties.present(environment, key);
    }

    boolean isVirtualThreadsEnabled() {
        return isPropertyTrue("spring.threads.virtual.enabled");
    }

    String[] effectiveProfiles() {
        try {
            String[] active = environment.getActiveProfiles();
            String[] profiles = active.length == 0 ? environment.getDefaultProfiles() : active;
            if (profiles.length > 100) throw new IllegalStateException();
            for (String profile : profiles)
                if (profile != null && profile.length() > 256) throw new IllegalStateException();
            return profiles;
        } catch (RuntimeException ex) {
            throw new SpringProperties.InspectionFailure("Effective profiles could not be inspected.");
        }
    }

    boolean isProductionProfileActive() {
        for (String profile : effectiveProfiles()) {
            if (profile == null) {
                continue;
            }
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("prod")
                    || normalized.equals("production")
                    || normalized.equals("staging")
                    || normalized.startsWith("prod-")
                    || normalized.endsWith("-prod")
                    || normalized.endsWith("-production")) {
                return true;
            }
        }
        return false;
    }

    /** True when Actuator's web endpoints are disabled because {@code management.server.port=-1}. */
    boolean managementWebDisabled() {
        Integer port = firstIntegerProperty("management.server.port");
        return port != null && port < 0;
    }

    static Builder builder(Environment environment) {
        return new Builder(environment);
    }

    /**
     * Mutable builder used by the scanner's discovery step (and tests) so the wide context record
     * can be assembled with sensible empty/false defaults.
     */
    static final class Builder {

        private final Environment environment;
        private boolean virtualThreadsSupported;
        private int beanDefinitionCount;
        private List<BeanRef> objectMappers = List.of();
        private List<BeanRef> taskExecutors = List.of();
        private boolean bootApplicationTaskExecutorPresent;
        private List<BeanRef> executors = List.of();
        private List<BeanRef> dataSources = List.of();
        private boolean pooledTaskExecutorPresent;
        private boolean asyncEnabled;
        private boolean devToolsPresent;
        private boolean customAsyncConfigurerPresent;
        private List<BeanRef> transactionManagers = List.of();
        private boolean transactionManagementConfigurerPresent;
        private List<BeanRef> restTemplates = List.of();
        private boolean restClientBeanPresent;
        private boolean cachingEnabled;
        private List<CacheManagerRef> cacheManagers = List.of();
        private boolean schedulingEnabled;
        private boolean entityManagerFactoryPresent;
        private boolean dispatcherServletPresent;
        private boolean reactive;
        private boolean tomcatWebServerPresent;
        private boolean webClientBeanPresent;
        private int reactiveHandlerMethodCount;
        private List<String> defaultPackageBeans = List.of();
        private List<String> mutableSingletonFields = List.of();
        private SpringObservations observations = SpringObservations.unknown();

        private Builder(Environment environment) {
            this.environment = environment;
        }

        Builder observations(SpringObservations value) {
            this.observations = value;
            return this;
        }

        Builder virtualThreadsSupported(boolean value) {
            this.virtualThreadsSupported = value;
            return this;
        }

        Builder beanDefinitionCount(int value) {
            this.beanDefinitionCount = value;
            return this;
        }

        Builder objectMappers(List<BeanRef> value) {
            this.objectMappers = value;
            return this;
        }

        Builder taskExecutors(List<BeanRef> value) {
            this.taskExecutors = value;
            return this;
        }

        Builder bootApplicationTaskExecutorPresent(boolean value) {
            this.bootApplicationTaskExecutorPresent = value;
            return this;
        }

        Builder executors(List<BeanRef> value) {
            this.executors = value;
            return this;
        }

        Builder dataSources(List<BeanRef> value) {
            this.dataSources = value;
            return this;
        }

        Builder pooledTaskExecutorPresent(boolean value) {
            this.pooledTaskExecutorPresent = value;
            return this;
        }

        Builder asyncEnabled(boolean value) {
            this.asyncEnabled = value;
            return this;
        }

        Builder devToolsPresent(boolean value) {
            this.devToolsPresent = value;
            return this;
        }

        Builder customAsyncConfigurerPresent(boolean value) {
            this.customAsyncConfigurerPresent = value;
            return this;
        }

        Builder transactionManagers(List<BeanRef> value) {
            this.transactionManagers = value;
            return this;
        }

        Builder transactionManagementConfigurerPresent(boolean value) {
            this.transactionManagementConfigurerPresent = value;
            return this;
        }

        Builder restTemplates(List<BeanRef> value) {
            this.restTemplates = value;
            return this;
        }

        Builder restClientBeanPresent(boolean value) {
            this.restClientBeanPresent = value;
            return this;
        }

        Builder cachingEnabled(boolean value) {
            this.cachingEnabled = value;
            return this;
        }

        Builder cacheManagers(List<CacheManagerRef> value) {
            this.cacheManagers = value;
            return this;
        }

        Builder schedulingEnabled(boolean value) {
            this.schedulingEnabled = value;
            return this;
        }

        Builder entityManagerFactoryPresent(boolean value) {
            this.entityManagerFactoryPresent = value;
            return this;
        }

        Builder dispatcherServletPresent(boolean value) {
            this.dispatcherServletPresent = value;
            return this;
        }

        /**
         * True when the running {@code ApplicationContext} is a {@code ReactiveWebApplicationContext}
         * (the WebFlux adapter), set by the actual context type rather than a classpath heuristic -
         * mirrors {@code PanelsController.isReactive()}.
         */
        Builder reactive(boolean value) {
            this.reactive = value;
            return this;
        }

        Builder tomcatWebServerPresent(boolean value) {
            this.tomcatWebServerPresent = value;
            return this;
        }

        Builder webClientBeanPresent(boolean value) {
            this.webClientBeanPresent = value;
            return this;
        }

        Builder reactiveHandlerMethodCount(int value) {
            this.reactiveHandlerMethodCount = value;
            return this;
        }

        Builder defaultPackageBeans(List<String> value) {
            this.defaultPackageBeans = value;
            return this;
        }

        Builder mutableSingletonFields(List<String> value) {
            this.mutableSingletonFields = value;
            return this;
        }

        SpringContext build() {
            return new SpringContext(
                    environment,
                    virtualThreadsSupported,
                    beanDefinitionCount,
                    objectMappers,
                    taskExecutors,
                    bootApplicationTaskExecutorPresent,
                    executors,
                    dataSources,
                    pooledTaskExecutorPresent,
                    asyncEnabled,
                    devToolsPresent,
                    customAsyncConfigurerPresent,
                    transactionManagers,
                    transactionManagementConfigurerPresent,
                    restTemplates,
                    restClientBeanPresent,
                    cachingEnabled,
                    cacheManagers,
                    schedulingEnabled,
                    entityManagerFactoryPresent,
                    dispatcherServletPresent,
                    reactive,
                    tomcatWebServerPresent,
                    webClientBeanPresent,
                    reactiveHandlerMethodCount,
                    defaultPackageBeans,
                    mutableSingletonFields,
                    observations);
        }
    }
}
