package io.github.jdubois.bootui.autoconfigure.hibernate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

record HibernateContext(
        List<HibernateEntityModel> entities,
        List<HibernateRepositoryModel> repositories,
        Environment environment,
        HibernateRuntimeVersion hibernateVersion) {

    HibernateContext(
            List<HibernateEntityModel> entities, List<HibernateRepositoryModel> repositories, Environment environment) {
        this(entities, repositories, environment, HibernateRuntimeVersion.detect());
    }

    HibernateContext(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Environment environment,
            String hibernateVersion) {
        this(entities, repositories, environment, HibernateRuntimeVersion.parse(hibernateVersion));
    }

    HibernateContext {
        entities = List.copyOf(entities);
        repositories = List.copyOf(repositories);
        hibernateVersion = hibernateVersion == null ? HibernateRuntimeVersion.unknown() : hibernateVersion;
    }

    boolean hasAssociations() {
        return entities.stream()
                .flatMap(entity -> entity.attributes().stream())
                .anyMatch(HibernateAttributeModel::isAssociation);
    }

    Integer defaultBatchFetchSize() {
        for (String key : List.of(
                "spring.jpa.properties.hibernate.default_batch_fetch_size", "hibernate.default_batch_fetch_size")) {
            Integer value = integerProperty(key);
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
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
            Integer value = integerProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    boolean isPropertyTrue(String... keys) {
        String value = firstProperty(keys);
        return value != null && "true".equalsIgnoreCase(value);
    }

    boolean isPropertyFalse(String... keys) {
        String value = firstProperty(keys);
        return value != null && "false".equalsIgnoreCase(value);
    }

    private Integer integerProperty(String key) {
        try {
            return environment.getProperty(key, Integer.class);
        } catch (RuntimeException ex) {
            return null;
        }
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

    boolean hasHibernateCollectionFetchPaginationFix() {
        return hibernateVersion.isAtLeastMajorMinor(7, 4);
    }

    String hibernateVersionDisplay() {
        return hibernateVersion.display();
    }

    boolean isHibernateEnhancementEnabled() {
        return isPropertyTrue(
                        "spring.jpa.properties.hibernate.enhancer.enableLazyInitialization",
                        "hibernate.enhancer.enableLazyInitialization")
                || isPropertyTrue(
                        "spring.jpa.properties.hibernate.bytecode.enhancer.enableLazyInitialization",
                        "hibernate.bytecode.enhancer.enableLazyInitialization");
    }

    boolean isSqlLoggingEnabled() {
        if (isPropertyTrue("spring.jpa.show-sql", "hibernate.show_sql")) {
            return true;
        }
        for (String key : List.of(
                "logging.level.org.hibernate.SQL",
                "logging.level.org.hibernate.orm.jdbc.bind",
                "logging.level.org.hibernate.type.descriptor.sql.BasicBinder")) {
            String value = firstProperty(key);
            if (value != null) {
                String normalized = value.toLowerCase(Locale.ROOT);
                if (normalized.equals("debug") || normalized.equals("trace")) {
                    return true;
                }
            }
        }
        return false;
    }
}

record HibernateRuntimeVersion(String display, Integer major, Integer minor) {

    private static final Pattern VERSION_PREFIX = Pattern.compile("^(\\d+)(?:\\.(\\d+))?.*");

    static HibernateRuntimeVersion detect() {
        return parse(detectedVersionString());
    }

    static HibernateRuntimeVersion parse(String version) {
        if (version == null || version.isBlank()) {
            return unknown();
        }
        String sanitized = version.trim();
        Matcher matcher = VERSION_PREFIX.matcher(sanitized);
        if (!matcher.matches()) {
            return new HibernateRuntimeVersion(sanitized, null, null);
        }
        return new HibernateRuntimeVersion(sanitized, parseInteger(matcher.group(1)), parseInteger(matcher.group(2)));
    }

    static HibernateRuntimeVersion unknown() {
        return new HibernateRuntimeVersion("unknown", null, null);
    }

    boolean isAfterMajorMinor(int targetMajor, int targetMinor) {
        if (major == null) {
            return false;
        }
        if (major > targetMajor) {
            return true;
        }
        return major == targetMajor && minor != null && minor > targetMinor;
    }

    boolean isAtLeastMajorMinor(int targetMajor, int targetMinor) {
        if (major == null) {
            return false;
        }
        if (major > targetMajor) {
            return true;
        }
        return major == targetMajor && minor != null && minor >= targetMinor;
    }

    private static String detectedVersionString() {
        try {
            Class<?> versionType = Class.forName("org.hibernate.Version");
            Method getVersionString = versionType.getMethod("getVersionString");
            Object value = getVersionString.invoke(null);
            if (value instanceof String version && !version.isBlank()) {
                return version;
            }
            Package versionPackage = versionType.getPackage();
            return versionPackage == null ? null : versionPackage.getImplementationVersion();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
