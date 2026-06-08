package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.CacheManagerRef;
import java.util.List;
import java.util.Locale;
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
        List<BeanRef> dataSources,
        boolean pooledTaskExecutorPresent,
        boolean asyncEnabled,
        boolean devToolsPresent,
        boolean hikariDataSourcePresent,
        boolean asyncConfigurerPresent,
        List<BeanRef> transactionManagers,
        boolean transactionManagementConfigurerPresent,
        List<BeanRef> restTemplates,
        boolean restClientBeanPresent,
        boolean cachingEnabled,
        List<CacheManagerRef> cacheManagers,
        boolean schedulingEnabled,
        boolean entityManagerFactoryPresent,
        boolean dispatcherServletPresent,
        List<String> defaultPackageBeans) {

    SpringContext {
        objectMappers = List.copyOf(objectMappers);
        taskExecutors = List.copyOf(taskExecutors);
        dataSources = List.copyOf(dataSources);
        transactionManagers = List.copyOf(transactionManagers);
        restTemplates = List.copyOf(restTemplates);
        cacheManagers = List.copyOf(cacheManagers);
        defaultPackageBeans = List.copyOf(defaultPackageBeans);
    }

    String firstProperty(String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    Integer firstIntegerProperty(String... keys) {
        for (String key : keys) {
            try {
                Integer value = environment.getProperty(key, Integer.class);
                if (value != null) {
                    return value;
                }
            } catch (RuntimeException ex) {
                // Ignore unparseable values and try the next key.
            }
        }
        return null;
    }

    boolean isPropertyTrue(String... keys) {
        String value = firstProperty(keys);
        return value != null && "true".equalsIgnoreCase(value);
    }

    boolean hasProperty(String key) {
        try {
            return environment.containsProperty(key);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    boolean isVirtualThreadsEnabled() {
        return isPropertyTrue("spring.threads.virtual.enabled");
    }

    String[] activeProfiles() {
        try {
            return environment.getActiveProfiles();
        } catch (RuntimeException ex) {
            return new String[0];
        }
    }

    boolean isProductionProfileActive() {
        for (String profile : activeProfiles()) {
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

    /**
     * True when Actuator endpoints share the application's HTTP port (so any web exposure is on the
     * same, typically public, connector). A distinct {@code management.server.port} moves them to a
     * separate connector.
     */
    boolean managementOnApplicationPort() {
        if (managementWebDisabled()) {
            return false;
        }
        Integer managementPort = firstIntegerProperty("management.server.port");
        if (managementPort == null) {
            return true;
        }
        Integer serverPort = firstIntegerProperty("server.port");
        int effectiveServerPort = serverPort != null ? serverPort : 8080;
        return managementPort == effectiveServerPort;
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
        private List<BeanRef> dataSources = List.of();
        private boolean pooledTaskExecutorPresent;
        private boolean asyncEnabled;
        private boolean devToolsPresent;
        private boolean hikariDataSourcePresent;
        private boolean asyncConfigurerPresent;
        private List<BeanRef> transactionManagers = List.of();
        private boolean transactionManagementConfigurerPresent;
        private List<BeanRef> restTemplates = List.of();
        private boolean restClientBeanPresent;
        private boolean cachingEnabled;
        private List<CacheManagerRef> cacheManagers = List.of();
        private boolean schedulingEnabled;
        private boolean entityManagerFactoryPresent;
        private boolean dispatcherServletPresent;
        private List<String> defaultPackageBeans = List.of();

        private Builder(Environment environment) {
            this.environment = environment;
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

        Builder hikariDataSourcePresent(boolean value) {
            this.hikariDataSourcePresent = value;
            return this;
        }

        Builder asyncConfigurerPresent(boolean value) {
            this.asyncConfigurerPresent = value;
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

        Builder defaultPackageBeans(List<String> value) {
            this.defaultPackageBeans = value;
            return this;
        }

        SpringContext build() {
            return new SpringContext(
                    environment,
                    virtualThreadsSupported,
                    beanDefinitionCount,
                    objectMappers,
                    taskExecutors,
                    dataSources,
                    pooledTaskExecutorPresent,
                    asyncEnabled,
                    devToolsPresent,
                    hikariDataSourcePresent,
                    asyncConfigurerPresent,
                    transactionManagers,
                    transactionManagementConfigurerPresent,
                    restTemplates,
                    restClientBeanPresent,
                    cachingEnabled,
                    cacheManagers,
                    schedulingEnabled,
                    entityManagerFactoryPresent,
                    dispatcherServletPresent,
                    defaultPackageBeans);
        }
    }
}
