package io.github.jdubois.bootui.autoconfigure.springadvisor;

import io.github.jdubois.bootui.autoconfigure.springadvisor.SpringAdvisorModel.BeanRef;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * Read-only inputs handed to every Spring Advisor rule: a snapshot of the running application
 * context (selected bean groups and feature flags) plus the application {@link Environment}.
 */
record SpringAdvisorContext(
        Environment environment,
        boolean virtualThreadsSupported,
        int beanDefinitionCount,
        List<BeanRef> objectMappers,
        List<BeanRef> taskExecutors,
        List<BeanRef> dataSources,
        boolean pooledTaskExecutorPresent,
        boolean asyncEnabled,
        boolean devToolsPresent,
        boolean hikariDataSourcePresent) {

    SpringAdvisorContext {
        objectMappers = List.copyOf(objectMappers);
        taskExecutors = List.copyOf(taskExecutors);
        dataSources = List.copyOf(dataSources);
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
}
