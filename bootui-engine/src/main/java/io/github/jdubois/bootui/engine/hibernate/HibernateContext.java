package io.github.jdubois.bootui.engine.hibernate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record HibernateContext(
        List<HibernateEntityModel> entities,
        List<HibernateRepositoryModel> repositories,
        Function<String, String> propertyLookup,
        List<String> activeProfiles,
        HibernateRuntimeVersion hibernateVersion) {

    HibernateContext(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Function<String, String> propertyLookup,
            List<String> activeProfiles) {
        this(entities, repositories, propertyLookup, activeProfiles, HibernateRuntimeVersion.detect());
    }

    HibernateContext(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Function<String, String> propertyLookup,
            List<String> activeProfiles,
            String hibernateVersion) {
        this(entities, repositories, propertyLookup, activeProfiles, HibernateRuntimeVersion.parse(hibernateVersion));
    }

    HibernateContext {
        entities = List.copyOf(entities);
        repositories = List.copyOf(repositories);
        propertyLookup = propertyLookup == null ? (key -> null) : propertyLookup;
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
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
            String value = propertyLookup.apply(key);
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
        String value = propertyLookup.apply(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    Boolean booleanProperty(String key) {
        String value = propertyLookup.apply(key);
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "on", "yes", "1" -> Boolean.TRUE;
            case "false", "off", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
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

    /**
     * True from Hibernate ORM 7.4 onward, where the "Limits and fetch joins" migration-guide entry documents that a
     * pagination limit on a query with a collection {@code JOIN FETCH} is now applied in the generated SQL itself;
     * the {@code org.hibernate.limitInMemory} query hint opts back into the pre-7.4 in-memory pagination.
     */
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

    boolean isHibernateEnhancementEnabled(HibernateEntityModel entity) {
        return isHibernateEnhancementEnabled() || entity.isBytecodeEnhanced();
    }

    boolean isOpenInViewApplicable() {
        return isPropertyTrue(HibernateScanner.OPEN_IN_VIEW_APPLICABLE_PROPERTY);
    }

    boolean managesSchemaIndexes() {
        String value = firstProperty(
                "spring.jpa.hibernate.ddl-auto",
                "spring.jpa.properties.hibernate.hbm2ddl.auto",
                "hibernate.hbm2ddl.auto",
                "jakarta.persistence.schema-generation.database.action");
        return value != null
                && ("create".equalsIgnoreCase(value)
                        || "create-only".equalsIgnoreCase(value)
                        || "create-drop".equalsIgnoreCase(value)
                        || "drop-and-create".equalsIgnoreCase(value)
                        || "update".equalsIgnoreCase(value));
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

    /**
     * True when Hibernate's bind-parameter binder logger is at TRACE, the only level at which
     * {@code org.hibernate.engine.jdbc.internal.JdbcBindingLogging} actually logs bound parameter values
     * (it gates every value-logging call on {@code Logger.isTraceEnabled()}, never DEBUG). Unlike
     * {@link #isSqlLoggingEnabled()} — which treats DEBUG-or-TRACE on either the SQL or binder category as
     * "some SQL logging is happening", a coarser performance signal — this check is deliberately narrower and
     * TRACE-only, because it exists to catch a security/data-leak risk (bound values, which may hold PII,
     * credentials, or tokens, being written to application logs), not merely verbose statement logging.
     */
    boolean isBindParameterLoggingEnabled() {
        for (String key : List.of(
                "logging.level.org.hibernate.orm.jdbc.bind",
                "logging.level.org.hibernate.type.descriptor.sql.BasicBinder")) {
            String value = firstProperty(key);
            if (value != null && "trace".equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

record HibernateRuntimeVersion(String display, Integer major, Integer minor) {

    private static final Pattern VERSION_PREFIX = Pattern.compile("^(\\d++)(?:\\.(\\d++))?.*");

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
