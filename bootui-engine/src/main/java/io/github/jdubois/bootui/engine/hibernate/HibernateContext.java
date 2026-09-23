package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
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
        HibernateRuntimeVersion hibernateVersion,
        HibernateFactorySettings factorySettings,
        HibernateApplicationFacts applicationFacts,
        Boolean enhancementVerified,
        HibernateEvaluationEvidence evidence,
        AdvisorViolationCollector violationCollector,
        String unitLabel) {

    HibernateContext(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Function<String, String> propertyLookup,
            List<String> activeProfiles,
            HibernateRuntimeVersion hibernateVersion,
            HibernateFactorySettings factorySettings,
            HibernateApplicationFacts applicationFacts,
            Boolean enhancementVerified,
            HibernateEvaluationEvidence evidence) {
        this(
                entities,
                repositories,
                propertyLookup,
                activeProfiles,
                hibernateVersion,
                factorySettings,
                applicationFacts,
                enhancementVerified,
                evidence,
                new AdvisorViolationCollector(10000),
                null);
    }

    HibernateContext withViolationCollector(AdvisorViolationCollector collector, String label) {
        return new HibernateContext(
                entities,
                repositories,
                propertyLookup,
                activeProfiles,
                hibernateVersion,
                factorySettings,
                applicationFacts,
                enhancementVerified,
                evidence,
                collector,
                label);
    }

    void retainViolations(String ruleId, List<String> details) {
        violationCollector.record(ruleId, details.size(), details, value -> {
            String detail = HibernateRuleSupport.detail(value);
            return unitLabel == null ? detail : HibernateRuleSupport.detail("[" + unitLabel + "] " + detail);
        });
    }

    HibernateContext(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Function<String, String> propertyLookup,
            List<String> activeProfiles,
            HibernateRuntimeVersion hibernateVersion) {
        this(
                entities,
                repositories,
                propertyLookup,
                activeProfiles,
                hibernateVersion,
                null,
                null,
                null,
                new HibernateEvaluationEvidence());
    }

    static HibernateContext observed(HibernatePersistenceUnitObservation unit, HibernateApplicationFacts application) {
        return new HibernateContext(
                unit.entities(),
                unit.repositories(),
                key -> null,
                application.activeProfiles(),
                HibernateRuntimeVersion.parse(unit.hibernateVersion()),
                unit.settings(),
                application,
                unit.enhancementVerified(),
                new HibernateEvaluationEvidence());
    }

    boolean observed() {
        return factorySettings != null;
    }

    <T> T required(T value) {
        return required(value, HibernateEvidenceGap.OTHER);
    }

    <T> T required(T value, HibernateEvidenceGap gap) {
        evidence.markApplicableIf(!entities.isEmpty());
        if (value == null) {
            evidence.markRequiredUnknown(gap);
            throw new HibernateRequiredObservationException();
        }
        return value;
    }

    void missingEvidence() {
        missingEvidence(HibernateEvidenceGap.OTHER);
    }

    void missingEvidence(HibernateEvidenceGap gap) {
        evidence.markRequiredUnknown(gap);
    }

    /** Records a gap together with a bounded example subject such as {@code Repository#method} or an entity name. */
    void missingEvidence(HibernateEvidenceGap gap, String subject) {
        evidence.markRequiredUnknown(gap, subject, subject);
    }

    /** Records a repository-method gap, counting overloads separately while showing {@code Repository#method}. */
    void missingEvidence(HibernateEvidenceGap gap, HibernateRepositoryMethodModel method) {
        evidence.markRequiredUnknown(
                gap,
                method.description(),
                method.description() + "("
                        + method.parameterTypes().stream()
                                .map(Class::getName)
                                .collect(java.util.stream.Collectors.joining(","))
                        + ")");
    }

    <T> List<T> targets(List<T> values) {
        return targets(values, value -> true);
    }

    <T> List<T> targets(List<T> values, java.util.function.Predicate<T> applicable) {
        List<T> selected = values.stream().filter(applicable).toList();
        evidence.markApplicableIf(!selected.isEmpty());
        return selected;
    }

    private String property(String key) {
        evidence.markApplicableIf(!entities.isEmpty());
        if (!observed()) return propertyLookup.apply(key);
        String nativeKey =
                key.startsWith("spring.jpa.properties.") ? key.substring("spring.jpa.properties.".length()) : key;
        if ("spring.jpa.show-sql".equals(nativeKey)) nativeKey = "hibernate.show_sql";
        if (nativeKey.startsWith("hibernate."))
            return required(factorySettings.property(nativeKey), HibernateEvidenceGap.FACTORY_SETTING);
        return switch (key) {
            case "spring.jpa.open-in-view" ->
                switch (applicationFacts.openInView()) {
                    case ENABLED -> "true";
                    case DISABLED, NOT_APPLICABLE -> "false";
                    case UNKNOWN -> required(null, HibernateEvidenceGap.APPLICATION_SETTING);
                };
            case HibernateScanner.OPEN_IN_VIEW_APPLICABLE_PROPERTY ->
                Boolean.toString(applicationFacts.openInView() != HibernateApplicationFacts.OpenInView.NOT_APPLICABLE);
            case HibernateScanner.BYTECODE_ENHANCEMENT_VERIFIED_PROPERTY ->
                Boolean.toString(Boolean.TRUE.equals(enhancementVerified));
            case "spring.jpa.defer-datasource-initialization" ->
                required(applicationFacts.deferredDatasourceInitialization(), HibernateEvidenceGap.APPLICATION_SETTING)
                        .toString();
            case "logging.level.org.hibernate.SQL" ->
                required(applicationFacts.sqlLoggerEnabled(), HibernateEvidenceGap.APPLICATION_SETTING)
                        ? "debug"
                        : "off";
            case "logging.level.org.hibernate.orm.jdbc.bind",
                    "logging.level.org.hibernate.type.descriptor.sql.BasicBinder" ->
                required(applicationFacts.bindLoggerEnabled(), HibernateEvidenceGap.APPLICATION_SETTING)
                        ? "trace"
                        : "off";
            default -> required(null, HibernateEvidenceGap.APPLICATION_SETTING);
        };
    }

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
            String value = property(key);
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
        String value = property(key);
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
        String value = property(key);
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

    boolean isHibernateEnhancementEnabled(HibernateEntityModel entity) {
        if (observed()) {
            if (Boolean.TRUE.equals(enhancementVerified) || entity.isBytecodeEnhanced()) return true;
            if (Boolean.FALSE.equals(enhancementVerified)) return false;
            if (entity.javaType() == null) return required(null, HibernateEvidenceGap.ENTITY_METADATA);
            try {
                Class.forName(
                        "org.hibernate.engine.spi.PersistentAttributeInterceptable",
                        false,
                        entity.javaType().getClassLoader());
                return false;
            } catch (ClassNotFoundException | LinkageError ex) {
                return required(null, HibernateEvidenceGap.ENTITY_METADATA);
            }
        }
        return isPropertyTrue(HibernateScanner.BYTECODE_ENHANCEMENT_VERIFIED_PROPERTY) || entity.isBytecodeEnhanced();
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

    boolean isStatementLoggingEnabled() {
        if (observed()) {
            if (Boolean.TRUE.equals(applicationFacts.sqlLoggerEnabled())) return true;
            return required(factorySettings.showSql(), HibernateEvidenceGap.FACTORY_SETTING);
        }
        return isPropertyTrue("spring.jpa.show-sql", "hibernate.show_sql")
                || "debug".equalsIgnoreCase(firstProperty("logging.level.org.hibernate.SQL"))
                || "trace".equalsIgnoreCase(firstProperty("logging.level.org.hibernate.SQL"));
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

/**
 * Scan-local applicability and completion bookkeeping for one Hibernate rule evaluation.
 *
 * <p>State is deliberately private. A rule marks what it actually looked at through
 * {@link #markApplicable(boolean)} (usually via {@link HibernateContext#targets}), and records a missing
 * required observation through {@link #markRequiredUnknown()}. Only {@link #complete} derives usability,
 * so "the rule reached a conclusion about something it could see" is decided in one place rather than by
 * whichever caller last wrote a field.</p>
 */
final class HibernateEvaluationEvidence {
    static final int MAX_SUBJECTS_PER_GAP = 3;
    private final java.util.EnumMap<HibernateEvidenceGap, Integer> gaps =
            new java.util.EnumMap<>(HibernateEvidenceGap.class);
    private final java.util.EnumMap<HibernateEvidenceGap, java.util.Set<String>> subjects =
            new java.util.EnumMap<>(HibernateEvidenceGap.class);
    private final java.util.Map<HibernateEvidenceGap, java.util.Set<String>> identities =
            new java.util.EnumMap<>(HibernateEvidenceGap.class);
    private boolean requiredUnknown;
    private boolean applicable;
    private boolean usable;
    private boolean evaluated;

    boolean applicable() {
        return applicable;
    }

    boolean requiredUnknown() {
        return requiredUnknown;
    }

    boolean usable() {
        return usable;
    }

    boolean evaluated() {
        return evaluated;
    }

    void markApplicable(boolean value) {
        applicable = value;
    }

    void markApplicableIf(boolean value) {
        applicable |= value;
    }

    void markRequiredUnknown() {
        markRequiredUnknown(HibernateEvidenceGap.OTHER);
    }

    void markRequiredUnknown(HibernateEvidenceGap gap) {
        markRequiredUnknown(gap, null, null);
    }

    /**
     * Records a gap; {@code identity} deduplicates the count (distinct identities count once, anonymous gaps per
     * occurrence) and {@code subject} is the sanitized example shown to the user.
     */
    void markRequiredUnknown(HibernateEvidenceGap gap, String subject, String identity) {
        requiredUnknown = true;
        HibernateEvidenceGap kind = gap == null ? HibernateEvidenceGap.OTHER : gap;
        if (identity != null && !identity.isBlank()) {
            if (!identities
                    .computeIfAbsent(kind, key -> new java.util.HashSet<>())
                    .add(identity)) return;
        }
        gaps.merge(kind, 1, Integer::sum);
        if (subject != null && !subject.isBlank()) {
            java.util.Set<String> examples = subjects.computeIfAbsent(kind, key -> new java.util.LinkedHashSet<>());
            // One extra example is kept only to know whether more distinct subjects exist than are shown.
            if (examples.size() <= MAX_SUBJECTS_PER_GAP) examples.add(HibernateRuleSupport.detail(subject));
        }
    }

    /** Up to {@link #MAX_SUBJECTS_PER_GAP} sanitized example subjects per gap kind. */
    java.util.List<String> subjects(HibernateEvidenceGap gap) {
        java.util.Set<String> known = subjects.get(gap);
        return known == null
                ? java.util.List.of()
                : known.stream().limit(MAX_SUBJECTS_PER_GAP).toList();
    }

    /** True when more distinct example subjects were recorded for the gap than {@link #subjects} returns. */
    boolean hasMoreSubjects(HibernateEvidenceGap gap) {
        java.util.Set<String> known = subjects.get(gap);
        return known != null && known.size() > MAX_SUBJECTS_PER_GAP;
    }

    /** Occurrences of each missing-evidence kind recorded during the current evaluation, in declaration order. */
    java.util.Map<HibernateEvidenceGap, Integer> gaps() {
        return java.util.Collections.unmodifiableMap(new java.util.EnumMap<>(gaps));
    }

    void complete(io.github.jdubois.bootui.core.dto.HibernateRuleResultDto result) {
        evaluated = true;
        boolean conclusive = HibernateRuleSupport.PASS.equals(result.status())
                || HibernateRuleSupport.VIOLATION.equals(result.status());
        usable = applicable && !requiredUnknown && conclusive
                || HibernateRuleSupport.VIOLATION.equals(result.status()) && result.violationCount() > 0;
    }

    void reset() {
        requiredUnknown = false;
        gaps.clear();
        subjects.clear();
        identities.clear();
        applicable = false;
        usable = false;
        evaluated = false;
    }
}

final class HibernateRequiredObservationException extends RuntimeException {}

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
