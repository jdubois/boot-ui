package io.github.jdubois.bootui.quarkus.quarkusapp;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.spi.QuarkusAppEvidenceProblem;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot.Setting;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshotProvider;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.Expressions;
import io.smallrye.config.NameIterator;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;

/** Reads bounded declarations and effective native configuration without constructing application beans or clients. */
public class QuarkusAppSnapshotProviderImpl implements QuarkusAppSnapshotProvider {

    static final int MAX_PROPERTY_NAMES = 4096;
    static final int MAX_CONFIG_SOURCES = 128;
    private static final int MAX_CLIENTS = 256;
    private static final Set<String> CONFIG_RULES = Set.of(
            "QA-CDI-007",
            "QA-CFG-002",
            "QA-CFG-003",
            "QA-CFG-004",
            "QA-CFG-005",
            "QA-CFG-006",
            "QA-CFG-007",
            "QA-PROD-002",
            "QA-PROD-003",
            "QA-DB-002",
            "QA-WEB-001",
            "QA-WEB-003",
            "QA-WEB-004",
            "QA-WEB-005",
            "QA-PERF-005");
    private static final Set<String> PRODUCTION_RULES =
            Set.of("QA-CFG-002", "QA-CFG-003", "QA-CFG-005", "QA-PROD-002", "QA-PROD-003");
    private static final String PRODUCTION_UNOBSERVED =
            "Only loaded production declarations were inspected; effective production configuration, "
                    + "external overrides and unloaded profile-aware files are unavailable.";
    private static final String BIND_PARAMETERS = "quarkus.hibernate-orm.log.bind-parameters";
    private static final String LEGACY_BIND_PARAMETERS = "quarkus.hibernate-orm.log.bind-param";
    private static final String ACTIVE = "active configuration";
    private static final String DECLARED_PROD = "visible production declaration";
    private static final String ORM = "quarkus.hibernate-orm.";
    private static final String DATASOURCE = "quarkus.datasource.";
    private static final SecretMasker MASKER = new SecretMasker();

    private final Config config;
    private final Supplier<QuarkusAppMetadata> metadata;

    public QuarkusAppSnapshotProviderImpl(Config config) {
        this(config, QuarkusAppMetadataStore::load);
    }

    QuarkusAppSnapshotProviderImpl(Config config, Supplier<QuarkusAppMetadata> metadata) {
        this.config = config;
        this.metadata = metadata;
    }

    @Override
    public QuarkusAppSnapshot snapshot() {
        return new Collection(config, metadata.get()).collect();
    }

    private static final class Collection {
        private final Config config;
        private final QuarkusAppMetadata metadata;
        private final List<Setting> settings = new ArrayList<>();
        private final List<QuarkusAppEvidenceProblem> problems = new ArrayList<>();
        private final Set<String> completed = new HashSet<>(CONFIG_RULES);
        private final Set<String> names = new TreeSet<>();
        private final List<ConfigSource> sources = new ArrayList<>();
        private List<String> profiles = List.of();
        private boolean activeProd;
        private Config productionDeclarations;

        private Collection(Config config, QuarkusAppMetadata metadata) {
            this.config = config;
            this.metadata = metadata;
        }

        private QuarkusAppSnapshot collect() {
            inspect(CONFIG_RULES, () -> {
                profiles = List.copyOf(config.unwrap(SmallRyeConfig.class).getProfiles());
                activeProd = profiles.equals(List.of("prod"));
                int count = 0;
                for (ConfigSource source : config.getConfigSources()) {
                    if (isDefaultConfigSource(source)) {
                        continue;
                    }
                    if (sources.size() >= MAX_CONFIG_SOURCES) {
                        unavailable(CONFIG_RULES, "Configuration name discovery reached its inspection limit.");
                        break;
                    }
                    sources.add(source);
                    // Aggregated native names strip active profile prefixes; raw sources retain declarations.
                    for (String name : source.getPropertyNames()) {
                        if (++count > MAX_PROPERTY_NAMES) {
                            unavailable(CONFIG_RULES, "Configuration name discovery reached its inspection limit.");
                            return;
                        }
                        if (name != null && name.length() <= 1024) {
                            names.add(name);
                        } else {
                            unavailable(
                                    CONFIG_RULES, "A configuration name could not be inspected within the name limit.");
                        }
                    }
                }
            });
            inspect(Set.of("QA-CDI-007"), this::arcValidation);
            inspect(Set.of("QA-CFG-004"), this::legacyProperties);
            inspect(Set.of("QA-CFG-006"), this::deprecatedProperties);
            inspect(Set.of("QA-CFG-007"), this::configHygiene);
            inspect(productionRules(), this::production);
            inspect(Set.of("QA-DB-002"), this::agroalPool);
            inspect(Set.of("QA-WEB-001"), this::compression);
            inspect(Set.of("QA-WEB-004"), this::shutdown);
            inspect(Set.of("QA-WEB-003"), this::clientTimeouts);
            inspect(Set.of("QA-WEB-005"), this::httpLimits);
            inspect(Set.of("QA-PERF-005"), this::workerThreadPool);
            return new QuarkusAppSnapshot(
                    metadata,
                    profiles.stream()
                            .map(QuarkusAppSnapshotProviderImpl::display)
                            .toList(),
                    Runtime.version().feature(),
                    settings,
                    completed,
                    problems);
        }

        private void production() {
            Set<String> applicable = productionRules();
            if (!metadata.available()) {
                unavailable(
                        Set.of("QA-CFG-002", "QA-CFG-005", "QA-PROD-002", "QA-PROD-003"),
                        "ORM and JDBC capability evidence is unavailable.");
            }
            Config source = activeProd ? config : productionDeclarations();
            String provenance = activeProd ? ACTIVE : DECLARED_PROD;
            if (!activeProd) {
                unavailable(applicable, PRODUCTION_UNOBSERVED);
            }
            if (applicable.contains("QA-CFG-005") && !activeProd) {
                // An active production profile is HIB-CONFIG-018's effective-logger evidence; do not charge it twice.
                inspect(Set.of("QA-CFG-005"), () -> {
                    Config build = buildTimeBindDeclarations();
                    String current = raw(build, BIND_PARAMETERS, true);
                    String legacy = raw(build, LEGACY_BIND_PARAMETERS, true);
                    if (current != null || legacy != null) {
                        // Quarkus ORs both global build-time flags.
                        boolean enabled = (current != null
                                        && booleanValue(current).equals("true"))
                                || (legacy != null && booleanValue(legacy).equals("true"));
                        add(
                                "QA-CFG-005",
                                "Hibernate ORM bind-parameter logging",
                                enabled ? "true" : "false",
                                "visible build-time declaration for production");
                    }
                });
            }

            Set<String> units = new TreeSet<>();
            units.add(ORM);
            Set<String> datasources = new TreeSet<>();
            datasources.add(DATASOURCE);
            for (String name : names) {
                String bare = unprofiled(name);
                String unit = namespace(bare, "quarkus", "hibernate-orm");
                if (unit != null) {
                    units.add(unit);
                }
                String datasource = namespace(bare, "quarkus", "datasource");
                if (datasource != null) {
                    datasources.add(datasource);
                }
            }
            if (!activeProd) {
                // An active production profile is HIB-CONFIG-002 and HIB-CONFIG-012's effective-SessionFactory
                // evidence; do not charge them twice.
                for (String unit : units) {
                    if (applicable.contains("QA-PROD-002")) {
                        inspect(Set.of("QA-PROD-002"), () -> {
                            String legacy = raw(source, unit + "database.generation", true);
                            String strategy =
                                    legacy != null ? legacy : raw(source, unit + "schema-management.strategy", true);
                            if (strategy != null) {
                                String normalized = strategy.trim().toLowerCase(Locale.ROOT);
                                if (!Set.of("none", "validate", "create", "update", "drop", "drop-and-create")
                                        .contains(normalized)) {
                                    unavailable(
                                            Set.of("QA-PROD-002"), "A schema action could not be classified safely.");
                                } else {
                                    add("QA-PROD-002", unit + "schema action", normalized, provenance);
                                }
                            }
                        });
                    }
                    if (applicable.contains("QA-CFG-002")) {
                        inspect(Set.of("QA-CFG-002"), () -> {
                            String value = raw(source, unit + "log.sql", true);
                            if (value != null) {
                                add("QA-CFG-002", unit + "log.sql", booleanValue(value), provenance);
                            }
                        });
                    }
                }
            }
            inspect(Set.of("QA-CFG-003"), () -> {
                String level = raw(source, "quarkus.log.level", !activeProd);
                if (level != null) {
                    String normalized = level.trim().toUpperCase(Locale.ROOT);
                    if (!Set.of(
                                    "ALL", "FINEST", "FINER", "TRACE", "DEBUG", "FINE", "CONFIG", "INFO", "WARN",
                                    "WARNING", "ERROR", "SEVERE", "FATAL", "OFF")
                            .contains(normalized)) {
                        unavailable(Set.of("QA-CFG-003"), "The logging level could not be classified safely.");
                    } else {
                        add(
                                "QA-CFG-003",
                                "root logger",
                                Set.of("ALL", "FINEST", "FINER", "TRACE", "DEBUG", "FINE")
                                                .contains(normalized)
                                        ? "verbose"
                                        : "normal",
                                provenance);
                    }
                }
            });
            for (String datasource : datasources) {
                if (!applicable.contains("QA-PROD-003")) {
                    break;
                }
                inspect(Set.of("QA-PROD-003"), () -> {
                    String url = raw(source, datasource + "jdbc.url", !activeProd);
                    if (url != null) {
                        add(
                                "QA-PROD-003",
                                datasource + "JDBC storage",
                                inMemory(url) ? "in-memory" : "persistent-or-unclassified",
                                provenance);
                    }
                });
            }
        }

        private Set<String> productionRules() {
            Set<String> applicable = new HashSet<>(PRODUCTION_RULES);
            if (metadata.available() && !metadata.hibernateOrmSupported()) {
                applicable.remove("QA-CFG-002");
                applicable.remove("QA-CFG-005");
                applicable.remove("QA-PROD-002");
            }
            if (metadata.available() && !metadata.jdbcDatasourceSupported()) {
                applicable.remove("QA-PROD-003");
            }
            return applicable;
        }

        /**
         * A private, literal-only projection of already-loaded declarations: no source discovery, profile
         * switching on the live config, base/dev-file guessing, or production expression expansion.
         */
        private Config productionDeclarations() {
            if (productionDeclarations == null) {
                productionDeclarations = projectProductionDeclarations();
            }
            return productionDeclarations;
        }

        /**
         * Development mode never drains requests and builds with the development profile, so a visible literal
         * {@code %prod.} declaration describes the production intent better than the active value.
         */
        private String productionIntent(Set<String> rules, String key) {
            if (activeProd) {
                return null;
            }
            unavailable(rules, PRODUCTION_UNOBSERVED);
            return raw(productionDeclarations(), key, true);
        }

        private Config projectProductionDeclarations() {
            return project((source, name) -> {
                if (!isProductionSetting(unprofiled(name))) {
                    return false;
                }
                if (isProductionKey(name)) {
                    return true;
                }
                if (!name.startsWith("%") && isBaseApplicationFile(source)) {
                    String value = source.getValue(name);
                    return value != null && !value.contains("${");
                }
                return false;
            });
        }

        /**
         * Bind-parameter logging is fixed at build time, so an unqualified declaration in a base application file is
         * packaged into a production build unless a {@code %prod.} declaration overrides it. Profile-aware files,
         * system properties and environment variables of this development run are not production evidence.
         */
        private Config buildTimeBindDeclarations() {
            return project((source, name) -> {
                String bare = unprofiled(name);
                if (!bare.equals(BIND_PARAMETERS) && !bare.equals(LEGACY_BIND_PARAMETERS)) {
                    return false;
                }
                if (isProductionKey(name)) {
                    return true;
                }
                if (!name.startsWith("%") && isBaseApplicationFile(source)) {
                    String value = source.getValue(name);
                    return value != null && !value.contains("${");
                }
                return false;
            });
        }

        private Config project(BiPredicate<ConfigSource, String> include) {
            List<ConfigSource> projectedSources = new ArrayList<>();
            int count = 0;
            int declarationsRead = 0;
            for (ConfigSource source : sources) {
                count++;
                Map<String, String> declarations = new LinkedHashMap<>();
                for (String name : names) {
                    if (include.test(source, name)) {
                        String value = source.getValue(name);
                        if (value != null) {
                            if (++declarationsRead > MAX_PROPERTY_NAMES) {
                                throw new IllegalStateException("Production declaration limit reached");
                            }
                            declarations.put(name, value.length() <= 8192 ? value : "${bootui.unavailable}");
                        }
                    }
                }
                if (!declarations.isEmpty()) {
                    projectedSources.add(
                            new PropertiesConfigSource(declarations, "observed-source-" + count, source.getOrdinal()));
                }
            }
            return new SmallRyeConfigBuilder()
                    .withSources(projectedSources.toArray(ConfigSource[]::new))
                    .addDefaultInterceptors()
                    .withProfile("prod")
                    .build();
        }

        private void legacyProperties() {
            Set<String> groups = new TreeSet<>();
            for (String name : names) {
                if (!isLegacyGeneration(unprofiled(name))) {
                    continue;
                }
                inspect(Set.of("QA-CFG-004"), () -> {
                    ConfigValue value = Expressions.withoutExpansion(() -> config.getConfigValue(name));
                    if (value.getRawValue() != null
                            && !value.getRawValue().isBlank()
                            && value.getSourceOrdinal() > Integer.MIN_VALUE) {
                        // One migration per persistence unit and profile, however many legacy keys it declares.
                        groups.add(name.replaceFirst("\\.(create-schemas|halt-on-error)$", ""));
                    }
                });
            }
            groups.forEach(group -> add("QA-CFG-004", group, "legacy", "configured property declaration"));
        }

        private void compression() {
            String declared = productionIntent(Set.of("QA-WEB-001"), "quarkus.http.enable-compression");
            if (declared != null) {
                add(
                        "QA-WEB-001",
                        "application HTTP server",
                        booleanValue(declared).equals("true") ? "enabled" : "disabled",
                        DECLARED_PROD);
                return;
            }
            boolean enabled = config.getOptionalValue("quarkus.http.enable-compression", Boolean.class)
                    .orElse(false);
            boolean frameworkDefault = !isExplicitlyConfigured("quarkus.http.enable-compression");
            add(
                    "QA-WEB-001",
                    "application HTTP server",
                    enabled ? "enabled" : frameworkDefault ? "default-disabled" : "disabled",
                    ACTIVE);
        }

        private boolean isExplicitlyConfigured(String key) {
            ConfigValue value = config.getConfigValue(key);
            if (value.getRawValue() != null
                    && !value.getRawValue().isBlank()
                    && value.getSourceOrdinal() > Integer.MIN_VALUE
                    && !isDefaultConfigSourceName(value.getSourceName())) {
                return true;
            }
            for (ConfigSource source : sources) {
                String direct = source.getValue(key);
                if (direct != null && !direct.isBlank()) {
                    return true;
                }
                for (String name : names) {
                    if (unprofiled(name).equals(key) && isActiveProfileKey(name, profiles)) {
                        String profiled = source.getValue(name);
                        if (profiled != null && !profiled.isBlank()) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private void shutdown() {
            String declared = productionIntent(Set.of("QA-WEB-004"), "quarkus.shutdown.timeout");
            Duration timeout;
            String provenance = ACTIVE;
            if (declared != null) {
                timeout = config.getConverter(Duration.class)
                        .orElseThrow(() -> new IllegalStateException("No duration converter"))
                        .convert(declared.trim());
                provenance = DECLARED_PROD;
            } else {
                timeout = config.getOptionalValue("quarkus.shutdown.timeout", Duration.class)
                        .orElse(null);
            }
            if (timeout != null && timeout.isNegative()) {
                unavailable(Set.of("QA-WEB-004"), "The shutdown duration is invalid.");
                return;
            }
            String value;
            if (timeout == null) {
                value = "absent";
            } else {
                long millis;
                try {
                    millis = timeout.toMillis();
                } catch (ArithmeticException ex) {
                    unavailable(Set.of("QA-WEB-004"), "The shutdown duration is invalid.");
                    return;
                }
                value = millis <= 0L ? "zero" : "positive";
            }
            add("QA-WEB-004", "HTTP request draining", value, provenance);
        }

        private void clientTimeouts() {
            if (!metadata.available()) {
                unavailable(Set.of("QA-WEB-003"), "REST client registration evidence is unavailable.");
                return;
            }
            if (!metadata.restClientSupported()) {
                return;
            }
            int count = 0;
            for (QuarkusAppMetadata.RestClient client : metadata.restClients()) {
                if (++count > MAX_CLIENTS) {
                    unavailable(Set.of("QA-WEB-003"), "REST client discovery reached its inspection limit.");
                    break;
                }
                String prefix = "quarkus.rest-client.\"" + client.className() + "\".";
                inspect(Set.of("QA-WEB-003"), () -> clientTimeout(client, prefix, "connect-timeout", 15_000L));
                inspect(Set.of("QA-WEB-003"), () -> clientTimeout(client, prefix, "read-timeout", 30_000L));
            }
        }

        private void clientTimeout(QuarkusAppMetadata.RestClient client, String prefix, String timer, long fallback) {
            // Quarkus installs configKey/FQCN/MP alias and source-priority interceptors for this canonical key.
            long timeout = config.getOptionalValue(prefix + timer, Long.class)
                    .orElseGet(() -> config.getOptionalValue("quarkus.rest-client." + timer, Long.class)
                            .orElse(fallback));
            if (timeout < 0) {
                unavailable(Set.of("QA-WEB-003"), "A managed REST client timer could not be classified safely.");
            } else if (timeout == 0) {
                add(
                        "QA-WEB-003",
                        client.className(),
                        timer.equals("connect-timeout") ? "connect-zero" : "read-zero",
                        "managed client configuration");
            }
        }

        private void arcValidation() {
            for (String key :
                    List.of("quarkus.arc.fail-on-intercepted-private-method", "quarkus.arc.detect-wrong-annotations")) {
                inspect(Set.of("QA-CDI-007"), () -> {
                    boolean reportedDisabled = false;
                    if (isExplicitlyConfigured(key)) {
                        String value = raw(config, key, false);
                        if (value != null) {
                            String classified = classifyBoolean(
                                    "QA-CDI-007", value, "An ArC validation setting could not be classified safely.");
                            if (classified != null) {
                                boolean disabled = classified.equals("false");
                                add("QA-CDI-007", key, disabled ? "disabled" : "enabled", ACTIVE);
                                reportedDisabled = disabled;
                            }
                        }
                    }
                    if (!activeProd && !reportedDisabled && hasProductionDeclaration(key)) {
                        String prodValue = raw(productionDeclarations(), key, true);
                        if (prodValue != null) {
                            String classified = classifyBoolean(
                                    "QA-CDI-007",
                                    prodValue,
                                    "An ArC validation setting could not be classified safely.");
                            if (classified != null) {
                                add(
                                        "QA-CDI-007",
                                        key,
                                        classified.equals("false") ? "disabled" : "enabled",
                                        DECLARED_PROD);
                            }
                        }
                    }
                });
            }
        }

        private void deprecatedProperties() {
            Set<String> found = new TreeSet<>();
            for (String name : names) {
                if (!isDeprecatedProperty(unprofiled(name))) {
                    continue;
                }
                inspect(Set.of("QA-CFG-006"), () -> {
                    if (hasNonBlankUserDeclaration(name)) {
                        found.add(name);
                    }
                });
            }
            found.forEach(key -> add("QA-CFG-006", key, "deprecated", "configured property declaration"));
        }

        private boolean hasNonBlankUserDeclaration(String name) {
            ConfigValue value = Expressions.withoutExpansion(() -> config.getConfigValue(name));
            if (value.getRawValue() != null
                    && !value.getRawValue().isBlank()
                    && value.getSourceOrdinal() > Integer.MIN_VALUE
                    && !isDefaultConfigSourceName(value.getSourceName())) {
                return true;
            }
            for (ConfigSource source : sources) {
                String rawValue = source.getValue(name);
                if (rawValue != null && !rawValue.isBlank()) {
                    return true;
                }
            }
            return false;
        }

        private void configHygiene() {
            for (String key :
                    List.of("quarkus.config.mapping.validate-unknown", "smallrye.config.mapping.validate-unknown")) {
                inspect(Set.of("QA-CFG-007"), () -> {
                    boolean reportedDisabled = false;
                    if (isExplicitlyConfigured(key)) {
                        String value = raw(config, key, false);
                        if (value != null) {
                            String classified = classifyBoolean(
                                    "QA-CFG-007",
                                    value,
                                    "A configuration validation or logging setting could not be classified safely.");
                            if (classified != null) {
                                boolean disabled = classified.equals("false");
                                add(
                                        "QA-CFG-007",
                                        key,
                                        disabled ? "validate-unknown-disabled" : "validate-unknown-enabled",
                                        ACTIVE);
                                reportedDisabled = disabled;
                            }
                        }
                    }
                    if (!activeProd && !reportedDisabled && hasProductionDeclaration(key)) {
                        String prodValue = raw(productionDeclarations(), key, true);
                        if (prodValue != null) {
                            String classified = classifyBoolean(
                                    "QA-CFG-007",
                                    prodValue,
                                    "A configuration validation or logging setting could not be classified safely.");
                            if (classified != null) {
                                add(
                                        "QA-CFG-007",
                                        key,
                                        classified.equals("false")
                                                ? "validate-unknown-disabled"
                                                : "validate-unknown-enabled",
                                        DECLARED_PROD);
                            }
                        }
                    }
                });
            }
            inspect(Set.of("QA-CFG-007"), () -> {
                String key = "quarkus.config.log.values";
                if (activeProd) {
                    if (isExplicitlyConfigured(key)) {
                        String value = raw(config, key, false);
                        if (value != null) {
                            String classified = classifyBoolean(
                                    "QA-CFG-007",
                                    value,
                                    "A configuration validation or logging setting could not be classified safely.");
                            if (classified != null) {
                                add(
                                        "QA-CFG-007",
                                        key,
                                        classified.equals("true") ? "log-values-enabled" : "log-values-disabled",
                                        ACTIVE);
                            }
                        }
                    }
                } else if (hasProductionDeclaration(key)) {
                    String prodValue = raw(productionDeclarations(), key, true);
                    if (prodValue != null) {
                        String classified = classifyBoolean(
                                "QA-CFG-007",
                                prodValue,
                                "A configuration validation or logging setting could not be classified safely.");
                        if (classified != null) {
                            add(
                                    "QA-CFG-007",
                                    key,
                                    classified.equals("true") ? "log-values-enabled" : "log-values-disabled",
                                    DECLARED_PROD);
                        }
                    }
                }
            });
        }

        private void agroalPool() {
            if (!metadata.available()) {
                unavailable(Set.of("QA-DB-002"), "ORM and JDBC capability evidence is unavailable.");
                return;
            }
            if (!metadata.jdbcDatasourceSupported()) {
                return;
            }
            Set<String> datasources = new TreeSet<>();
            datasources.add(DATASOURCE);
            for (String name : names) {
                String ds = namespace(unprofiled(name), "quarkus", "datasource");
                if (ds != null) {
                    datasources.add(ds);
                }
            }
            for (String datasource : datasources) {
                inspectAgroalDatasource(datasource, config, false, ACTIVE);
                if (!activeProd) {
                    inspectAgroalDatasource(datasource, productionDeclarations(), true, DECLARED_PROD);
                }
            }
        }

        private void inspectAgroalDatasource(
                String datasource, Config source, boolean declarationOnly, String provenance) {
            inspect(Set.of("QA-DB-002"), () -> {
                String jdbcFlag = raw(source, datasource + "jdbc", declarationOnly);
                if (jdbcFlag != null) {
                    String classifiedJdbc = classifyBoolean(
                            "QA-DB-002", jdbcFlag, "An Agroal pool setting could not be classified safely.");
                    if ("false".equals(classifiedJdbc)) {
                        return;
                    }
                }
                String poolingKey = datasource + "jdbc.pooling-enabled";
                if (declarationOnly ? hasProductionDeclaration(poolingKey) : isExplicitlyConfigured(poolingKey)) {
                    String poolingRaw = raw(source, poolingKey, declarationOnly);
                    if (poolingRaw != null) {
                        String classified = classifyBoolean(
                                "QA-DB-002", poolingRaw, "An Agroal pool setting could not be classified safely.");
                        if (classified != null) {
                            add(
                                    "QA-DB-002",
                                    poolingKey,
                                    classified.equals("false") ? "pooling-disabled" : "enabled",
                                    provenance);
                        }
                    }
                }
                String bgKey = datasource + "jdbc.background-validation-interval";
                if (declarationOnly ? hasProductionDeclaration(bgKey) : isExplicitlyConfigured(bgKey)) {
                    String bgRaw = raw(source, bgKey, declarationOnly);
                    if (bgRaw != null) {
                        Duration bg = parseDuration(
                                "QA-DB-002", bgRaw, "An Agroal pool setting could not be classified safely.");
                        if (bg == null) {
                            return;
                        }
                        if (bg.toMillis() <= 0L) {
                            boolean alternativeValidation = false;
                            String borrowKey = datasource + "jdbc.validate-on-borrow";
                            String borrowRaw = raw(source, borrowKey, declarationOnly);
                            if (borrowRaw != null) {
                                String borrowClassified = classifyBoolean(
                                        "QA-DB-002",
                                        borrowRaw,
                                        "An Agroal pool setting could not be classified safely.");
                                if (borrowClassified == null) {
                                    return;
                                }
                                alternativeValidation |= borrowClassified.equals("true");
                            }
                            String fgKey = datasource + "jdbc.foreground-validation-interval";
                            String fgRaw = raw(source, fgKey, declarationOnly);
                            if (fgRaw != null) {
                                Duration fg = parseDuration(
                                        "QA-DB-002", fgRaw, "An Agroal pool setting could not be classified safely.");
                                if (fg == null) {
                                    return;
                                }
                                alternativeValidation |= fg.toMillis() > 0L;
                            }
                            add(
                                    "QA-DB-002",
                                    bgKey,
                                    alternativeValidation ? "enabled" : "validation-disabled",
                                    provenance);
                        } else {
                            add("QA-DB-002", bgKey, "enabled", provenance);
                        }
                    }
                }
            });
        }

        private void httpLimits() {
            inspectHttpLimits(config, false, ACTIVE);
            if (!activeProd) {
                inspectHttpLimits(productionDeclarations(), true, DECLARED_PROD);
            }
        }

        private void inspectHttpLimits(Config source, boolean declarationOnly, String provenance) {
            String bodySizeKey = "quarkus.http.limits.max-body-size";
            inspect(Set.of("QA-WEB-005"), () -> {
                if (isExplicitlyEmpty(bodySizeKey, declarationOnly)) {
                    add("QA-WEB-005", bodySizeKey, "unlimited-body-size", provenance);
                } else if (declarationOnly
                        ? hasProductionDeclaration(bodySizeKey)
                        : isExplicitlyConfigured(bodySizeKey)) {
                    String rawVal = raw(source, bodySizeKey, declarationOnly);
                    if (rawVal != null) {
                        MemorySize size = parseMemorySize(
                                "QA-WEB-005", rawVal, "An HTTP limit setting could not be classified safely.");
                        if (size != null) {
                            add(
                                    "QA-WEB-005",
                                    bodySizeKey,
                                    size.isNegative() ? "unlimited-body-size" : "bounded",
                                    provenance);
                        }
                    }
                }
            });
            String formBytesKey = "quarkus.http.limits.max-form-buffered-bytes";
            inspect(Set.of("QA-WEB-005"), () -> {
                if (declarationOnly ? hasProductionDeclaration(formBytesKey) : isExplicitlyConfigured(formBytesKey)) {
                    String rawVal = raw(source, formBytesKey, declarationOnly);
                    if (rawVal != null) {
                        if (rawVal.trim().equals("-1")) {
                            add("QA-WEB-005", formBytesKey, "unlimited-form-buffered-bytes", provenance);
                        } else {
                            MemorySize size = parseMemorySize(
                                    "QA-WEB-005", rawVal, "An HTTP limit setting could not be classified safely.");
                            if (size != null) {
                                add(
                                        "QA-WEB-005",
                                        formBytesKey,
                                        size.isNegative() ? "unlimited-form-buffered-bytes" : "bounded",
                                        provenance);
                            }
                        }
                    }
                }
            });
            String formFieldsKey = "quarkus.http.limits.max-form-fields";
            inspect(Set.of("QA-WEB-005"), () -> {
                if (declarationOnly ? hasProductionDeclaration(formFieldsKey) : isExplicitlyConfigured(formFieldsKey)) {
                    String rawVal = raw(source, formFieldsKey, declarationOnly);
                    if (rawVal != null) {
                        Integer fields =
                                parseInt("QA-WEB-005", rawVal, "An HTTP limit setting could not be classified safely.");
                        if (fields != null) {
                            add(
                                    "QA-WEB-005",
                                    formFieldsKey,
                                    fields < 0 ? "unlimited-form-fields" : "bounded",
                                    provenance);
                        }
                    }
                }
            });
            String deleteUploadsKey = "quarkus.http.body.delete-uploaded-files-on-end";
            inspect(Set.of("QA-WEB-005"), () -> {
                if (declarationOnly
                        ? hasProductionDeclaration(deleteUploadsKey)
                        : isExplicitlyConfigured(deleteUploadsKey)) {
                    String rawVal = raw(source, deleteUploadsKey, declarationOnly);
                    if (rawVal != null) {
                        String classified = classifyBoolean(
                                "QA-WEB-005", rawVal, "An HTTP limit setting could not be classified safely.");
                        if (classified == null) {
                            return;
                        }
                        boolean deleteOnEnd = classified.equals("true");
                        boolean uploadsEnabled = true;
                        String handleRaw = raw(source, "quarkus.http.body.handle-file-uploads", declarationOnly);
                        if (handleRaw != null) {
                            String handleClassified = classifyBoolean(
                                    "QA-WEB-005", handleRaw, "An HTTP limit setting could not be classified safely.");
                            if (handleClassified == null) {
                                return;
                            }
                            uploadsEnabled = handleClassified.equals("true");
                        }
                        add(
                                "QA-WEB-005",
                                deleteUploadsKey,
                                (!deleteOnEnd && uploadsEnabled) ? "retained-uploads" : "bounded",
                                provenance);
                    }
                }
            });
        }

        private void workerThreadPool() {
            inspectWorkerThreadPool(config, false, ACTIVE);
            if (!activeProd) {
                inspectWorkerThreadPool(productionDeclarations(), true, DECLARED_PROD);
            }
        }

        private void inspectWorkerThreadPool(Config source, boolean declarationOnly, String provenance) {
            String coreKey = "quarkus.thread-pool.core-threads";
            String maxKey = "quarkus.thread-pool.max-threads";
            inspect(Set.of("QA-PERF-005"), () -> {
                boolean hasCore = declarationOnly ? hasProductionDeclaration(coreKey) : isExplicitlyConfigured(coreKey);
                boolean hasMax = declarationOnly ? hasProductionDeclaration(maxKey) : isExplicitlyConfigured(maxKey);
                if (!hasCore && !hasMax) {
                    return;
                }
                String coreRaw = hasCore ? raw(source, coreKey, declarationOnly) : null;
                String maxRaw = hasMax ? raw(source, maxKey, declarationOnly) : null;
                Integer core = coreRaw != null
                        ? parseInt(
                                "QA-PERF-005", coreRaw, "A worker thread pool setting could not be classified safely.")
                        : null;
                if (coreRaw != null && core == null) {
                    return;
                }
                Integer max = maxRaw != null
                        ? parseInt(
                                "QA-PERF-005", maxRaw, "A worker thread pool setting could not be classified safely.")
                        : null;
                if (maxRaw != null && max == null) {
                    return;
                }
                if (core != null && core < 0) {
                    unavailable(Set.of("QA-PERF-005"), "A worker thread pool setting could not be classified safely.");
                    return;
                }
                if (max != null) {
                    if (max <= 0) {
                        add("QA-PERF-005", maxKey, "max-non-positive", provenance);
                    } else if ((core != null ? core : 1) > max) {
                        add("QA-PERF-005", coreKey, "core-exceeds-max", provenance);
                    } else {
                        add("QA-PERF-005", maxKey, "valid", provenance);
                    }
                } else if (core != null) {
                    add("QA-PERF-005", coreKey, "valid", provenance);
                }
            });
            String interruptKey = "quarkus.thread-pool.shutdown-interrupt";
            String timeoutKey = "quarkus.thread-pool.shutdown-timeout";
            inspect(Set.of("QA-PERF-005"), () -> {
                boolean hasInterrupt =
                        declarationOnly ? hasProductionDeclaration(interruptKey) : isExplicitlyConfigured(interruptKey);
                boolean hasTimeout =
                        declarationOnly ? hasProductionDeclaration(timeoutKey) : isExplicitlyConfigured(timeoutKey);
                if (!hasInterrupt && !hasTimeout) {
                    return;
                }
                String interruptRaw = hasInterrupt ? raw(source, interruptKey, declarationOnly) : null;
                String timeoutRaw = hasTimeout ? raw(source, timeoutKey, declarationOnly) : null;
                Duration interrupt = interruptRaw != null
                        ? parseDuration(
                                "QA-PERF-005",
                                interruptRaw,
                                "A worker thread pool setting could not be classified safely.")
                        : null;
                if (interruptRaw != null && interrupt == null) {
                    return;
                }
                Duration timeout = timeoutRaw != null
                        ? parseDuration(
                                "QA-PERF-005",
                                timeoutRaw,
                                "A worker thread pool setting could not be classified safely.")
                        : null;
                if (timeoutRaw != null && timeout == null) {
                    return;
                }
                if (interrupt != null || timeout != null) {
                    Duration effectiveInterrupt = interrupt != null ? interrupt : Duration.ofSeconds(10);
                    Duration effectiveTimeout = timeout != null ? timeout : Duration.ofMinutes(1);
                    if (effectiveInterrupt.compareTo(effectiveTimeout) >= 0) {
                        add("QA-PERF-005", interruptKey, "interrupt-not-before-timeout", provenance);
                    } else {
                        add("QA-PERF-005", interruptKey, "valid", provenance);
                    }
                }
            });
        }

        private boolean hasProductionDeclaration(String key) {
            for (ConfigSource source : sources) {
                for (String name : names) {
                    if (!unprofiled(name).equals(key)) {
                        continue;
                    }
                    if (isProductionKey(name)) {
                        String val = source.getValue(name);
                        if (val != null && !val.isBlank()) {
                            return true;
                        }
                    } else if (!name.startsWith("%") && isBaseApplicationFile(source)) {
                        String val = source.getValue(name);
                        if (val != null && !val.isBlank() && !val.contains("${")) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean isExplicitlyEmpty(String key, boolean declarationOnly) {
            if (!declarationOnly) {
                ConfigValue value = Expressions.withoutExpansion(() -> config.getConfigValue(key));
                if (value.getRawValue() != null
                        && value.getRawValue().isBlank()
                        && value.getSourceOrdinal() > Integer.MIN_VALUE
                        && !isDefaultConfigSourceName(value.getSourceName())) {
                    return true;
                }
                for (ConfigSource source : sources) {
                    String direct = source.getValue(key);
                    if (direct != null && direct.isBlank() && names.contains(key)) {
                        return true;
                    }
                    for (String name : names) {
                        if (unprofiled(name).equals(key) && isActiveProfileKey(name, profiles)) {
                            String profiled = source.getValue(name);
                            if (profiled != null && profiled.isBlank()) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
            for (ConfigSource source : sources) {
                for (String name : names) {
                    if (unprofiled(name).equals(key) && isProductionKey(name)) {
                        String val = source.getValue(name);
                        if (val != null && val.isBlank()) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private String classifyBoolean(String ruleId, String value, String errorMessage) {
            try {
                return booleanValue(value);
            } catch (IllegalArgumentException ex) {
                unavailable(Set.of(ruleId), errorMessage);
                return null;
            }
        }

        private Integer parseInt(String ruleId, String value, String errorMessage) {
            try {
                return Integer.valueOf(value.trim());
            } catch (NumberFormatException ex) {
                unavailable(Set.of(ruleId), errorMessage);
                return null;
            }
        }

        private MemorySize parseMemorySize(String ruleId, String value, String errorMessage) {
            try {
                MemorySize size = MemorySize.of(value.trim());
                size.asLongValue();
                return size;
            } catch (IllegalArgumentException | ArithmeticException ex) {
                unavailable(Set.of(ruleId), errorMessage);
                return null;
            }
        }

        private Duration parseDuration(String ruleId, String value, String errorMessage) {
            try {
                Duration duration = config.getConverter(Duration.class)
                        .orElseThrow(() -> new IllegalStateException("No duration converter"))
                        .convert(value.trim());
                if (duration == null || duration.isNegative()) {
                    unavailable(Set.of(ruleId), errorMessage);
                    return null;
                }
                duration.toMillis();
                return duration;
            } catch (IllegalArgumentException | ArithmeticException ex) {
                unavailable(Set.of(ruleId), errorMessage);
                return null;
            }
        }

        private void add(String rule, String target, String value, String provenance) {
            settings.add(new Setting(rule, display(target), value, provenance));
        }

        private void inspect(Set<String> rules, Runnable operation) {
            try {
                operation.run();
            } catch (ArithmeticException
                    | IllegalArgumentException
                    | IllegalStateException
                    | NoSuchElementException
                    | UnsupportedOperationException ex) {
                unavailable(rules, "Required configuration evidence could not be read or converted.");
            }
        }

        private void unavailable(Set<String> rules, String message) {
            for (String rule : new TreeSet<>(rules)) {
                completed.remove(rule);
                QuarkusAppEvidenceProblem problem = new QuarkusAppEvidenceProblem(rule, message);
                if (!problems.contains(problem)) {
                    problems.add(problem);
                }
            }
        }
    }

    private static String raw(Config config, String key, boolean declarationOnly) {
        ConfigValue value = declarationOnly
                ? Expressions.withoutExpansion(() -> config.getConfigValue(key))
                : config.getConfigValue(key);
        String text = declarationOnly ? value.getRawValue() : value.getValue();
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > 8192 || (declarationOnly && text.contains("${"))) {
            throw new IllegalArgumentException("Unresolved or oversized declaration");
        }
        return text;
    }

    private static String booleanValue(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> "true";
            case "false", "0", "no", "n", "off" -> "false";
            default -> throw new IllegalArgumentException("Unclassified boolean");
        };
    }

    private static boolean isDefaultConfigSource(ConfigSource source) {
        return source.getOrdinal() <= Integer.MIN_VALUE || isDefaultConfigSourceName(source.getName());
    }

    private static boolean isDefaultConfigSourceName(String name) {
        if (name == null) {
            return false;
        }
        return name.equals("BuildTime RunTime Fixed")
                || name.contains("BuildTime RunTime Fixed")
                || name.startsWith("DefaultValuesConfigSource");
    }

    private static boolean isActiveProfileKey(String name, List<String> activeProfiles) {
        if (!name.startsWith("%")) {
            return false;
        }
        int separator = name.indexOf('.');
        if (separator <= 1) {
            return false;
        }
        for (String profile : name.substring(1, separator).split(",")) {
            if (activeProfiles.contains(profile.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBaseApplicationFile(ConfigSource source) {
        String name = source.getName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("application.properties")
                || lower.contains("application.yaml")
                || lower.contains("application.yml");
    }

    private static boolean isProductionKey(String name) {
        if (!name.startsWith("%")) {
            return false;
        }
        int separator = name.indexOf('.');
        if (separator <= 1) {
            return false;
        }
        for (String profile : name.substring(1, separator).split(",")) {
            if (profile.trim().equals("prod")) {
                return true;
            }
        }
        return false;
    }

    private static String unprofiled(String name) {
        int separator = name.startsWith("%") ? name.indexOf('.') : -1;
        return separator > 0 ? name.substring(separator + 1) : name;
    }

    private static boolean isProductionSetting(String name) {
        if (name.equals("quarkus.log.level")
                || name.equals("quarkus.http.enable-compression")
                || name.equals("quarkus.shutdown.timeout")
                || name.equals("quarkus.arc.fail-on-intercepted-private-method")
                || name.equals("quarkus.arc.detect-wrong-annotations")
                || name.equals("quarkus.config.mapping.validate-unknown")
                || name.equals("smallrye.config.mapping.validate-unknown")
                || name.equals("quarkus.config.log.values")
                || name.equals("quarkus.http.limits.max-body-size")
                || name.equals("quarkus.http.limits.max-form-buffered-bytes")
                || name.equals("quarkus.http.limits.max-form-fields")
                || name.equals("quarkus.http.body.delete-uploaded-files-on-end")
                || name.equals("quarkus.http.body.handle-file-uploads")
                || name.equals("quarkus.thread-pool.core-threads")
                || name.equals("quarkus.thread-pool.max-threads")
                || name.equals("quarkus.thread-pool.shutdown-interrupt")
                || name.equals("quarkus.thread-pool.shutdown-timeout")) {
            return true;
        }
        String unit = namespace(name, "quarkus", "hibernate-orm");
        if (unit != null) {
            return name.equals(unit + "database.generation")
                    || name.equals(unit + "schema-management.strategy")
                    || name.equals(unit + "log.sql");
        }
        String datasource = namespace(name, "quarkus", "datasource");
        return datasource != null
                && (name.equals(datasource + "jdbc.url")
                        || name.equals(datasource + "jdbc")
                        || name.equals(datasource + "jdbc.pooling-enabled")
                        || name.equals(datasource + "jdbc.background-validation-interval")
                        || name.equals(datasource + "jdbc.foreground-validation-interval")
                        || name.equals(datasource + "jdbc.validate-on-borrow"));
    }

    private static final Set<String> LOG_HANDLERS = Set.of("console", "file", "syslog", "socket");
    private static final Set<String> OIDC_PROXY_KEYS = Set.of("host", "port", "username", "password");
    private static final Set<String> REST_CLIENT_PROXY_KEYS =
            Set.of("proxy-address", "proxy-user", "proxy-password", "proxy-connect-timeout", "non-proxy-hosts");

    static boolean isDeprecatedProperty(String name) {
        if (name.equals("quarkus.vertx.worker-pool-size")
                || name.equals("quarkus.smallrye-health.ui.enable")
                || name.equals("quarkus.smallrye-openapi.enable")
                || name.equals("quarkus.swagger-ui.enable")) {
            return true;
        }
        List<String> segments = segments(name, 7);
        if (segments == null || segments.size() < 3 || !segments.get(0).equals("quarkus")) {
            return false;
        }
        String group = segments.get(1);
        List<String> rest = segments.subList(2, segments.size());
        return switch (group) {
            case "hibernate-orm" -> isDeprecatedHibernateProperty(rest);
            case "log" -> isDeprecatedLogProperty(rest);
            case "oidc" -> isDeprecatedOidcProxyProperty(rest);
            case "rest-client" -> isDeprecatedRestClientProxyProperty(rest);
            default -> false;
        };
    }

    private static boolean isDeprecatedHibernateProperty(List<String> rest) {
        if (rest.equals(List.of("blocking"))) {
            return true;
        }
        for (int unit = 0; unit <= 1 && unit < rest.size(); unit++) {
            if (unit == 1 && RESERVED_SUBGROUPS.contains(rest.get(0))) {
                continue;
            }
            List<String> tail = rest.subList(unit, rest.size());
            if (tail.equals(List.of("batch-fetch-size"))
                    || tail.equals(List.of("max-fetch-depth"))
                    || tail.equals(List.of("validation", "enabled"))
                    || tail.equals(List.of("database", "globally-quoted-identifiers"))
                    || tail.equals(List.of("log", "bind-param"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDeprecatedLogProperty(List<String> rest) {
        if (rest.size() >= 2 && LOG_HANDLERS.contains(rest.get(0))) {
            List<String> tail = rest.subList(1, rest.size());
            return tail.equals(List.of("enable"))
                    || tail.equals(List.of("async"))
                    || tail.equals(List.of("async", "enable"));
        }
        if (rest.size() >= 4 && rest.get(0).equals("handler") && LOG_HANDLERS.contains(rest.get(1))) {
            List<String> tail = rest.subList(3, rest.size());
            return tail.equals(List.of("enable"))
                    || tail.equals(List.of("async"))
                    || tail.equals(List.of("async", "enable"));
        }
        return false;
    }

    private static boolean isDeprecatedOidcProxyProperty(List<String> rest) {
        if (rest.size() == 2 && rest.get(0).equals("proxy")) {
            return OIDC_PROXY_KEYS.contains(rest.get(1));
        }
        return rest.size() == 3
                && !rest.get(0).equals("proxy")
                && rest.get(1).equals("proxy")
                && OIDC_PROXY_KEYS.contains(rest.get(2));
    }

    private static boolean isDeprecatedRestClientProxyProperty(List<String> rest) {
        if (rest.size() == 1) {
            return REST_CLIENT_PROXY_KEYS.contains(rest.get(0));
        }
        return rest.size() == 2 && REST_CLIENT_PROXY_KEYS.contains(rest.get(1));
    }

    private static List<String> segments(String name, int maxSegments) {
        NameIterator iterator = new NameIterator(name);
        List<String> segments = new ArrayList<>();
        while (iterator.hasNext()) {
            segments.add(iterator.getNextSegment());
            iterator.next();
            if (segments.size() > maxSegments) {
                return null;
            }
        }
        return segments;
    }

    /** The deprecated (since 3.22) database.generation group of the default or one named persistence unit. */
    static boolean isLegacyGeneration(String name) {
        if (!name.startsWith("quarkus.hibernate-orm.")) {
            return false;
        }
        NameIterator iterator = new NameIterator(name);
        List<String> segments = new ArrayList<>();
        while (iterator.hasNext()) {
            segments.add(iterator.getNextSegment());
            iterator.next();
            if (segments.size() > 6) {
                return false;
            }
        }
        if (segments.size() < 4 || !segments.get(1).equals("hibernate-orm")) {
            return false;
        }
        for (int unit = 0; unit <= 1 && 2 + unit < segments.size(); unit++) {
            List<String> rest = segments.subList(2 + unit, segments.size());
            if (rest.equals(List.of("database", "generation"))
                    || rest.equals(List.of("database", "generation", "create-schemas"))
                    || rest.equals(List.of("database", "generation", "halt-on-error"))) {
                return true;
            }
        }
        return false;
    }

    private static final List<List<String>> ORM_SUFFIXES = List.of(
            List.of("database", "generation"),
            List.of("schema-management", "strategy"),
            List.of("log", "sql"),
            List.of("packages"),
            List.of("datasource"));
    private static final List<List<String>> DATASOURCE_SUFFIXES = List.of(
            List.of("jdbc", "url"),
            List.of("jdbc", "pooling-enabled"),
            List.of("jdbc", "background-validation-interval"),
            List.of("jdbc", "foreground-validation-interval"),
            List.of("jdbc", "validate-on-borrow"),
            List.of("db-kind"));
    private static final Set<String> RESERVED_SUBGROUPS = Set.of(
            "database",
            "schema-management",
            "log",
            "scripts",
            "mapping",
            "fetch",
            "jdbc",
            "cache",
            "multitenant",
            "validation",
            "discriminator");

    static String namespace(String name, String root, String group) {
        if (!name.startsWith(root + "." + group + ".")) {
            return null;
        }
        NameIterator iterator = new NameIterator(name);
        List<String> segments = new ArrayList<>();
        while (iterator.hasNext()) {
            segments.add(iterator.getNextSegment());
            iterator.next();
            if (segments.size() > 6) {
                return null;
            }
        }
        if (segments.size() < 3
                || !segments.get(0).equals(root)
                || !segments.get(1).equals(group)) {
            return null;
        }
        List<String> rest = segments.subList(2, segments.size());
        List<List<String>> suffixes = group.equals("hibernate-orm")
                ? ORM_SUFFIXES
                : group.equals("datasource") ? DATASOURCE_SUFFIXES : List.of();
        for (List<String> suffix : suffixes) {
            if (rest.equals(suffix)) {
                return root + "." + group + ".";
            }
        }
        for (List<String> suffix : suffixes) {
            if (rest.size() == suffix.size() + 1
                    && rest.subList(1, rest.size()).equals(suffix)
                    && (suffix.size() > 1 || !RESERVED_SUBGROUPS.contains(rest.get(0)))) {
                String suffixText = String.join(".", suffix);
                if (name.endsWith("." + suffixText)) {
                    return name.substring(0, name.length() - suffixText.length());
                }
            }
        }
        return null;
    }

    static boolean inMemory(String jdbcUrl) {
        String url = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        if (url.equals("jdbc:h2:mem")
                || url.startsWith("jdbc:h2:mem:")
                || url.startsWith("jdbc:h2:mem;")
                || url.startsWith("jdbc:hsqldb:mem:")
                || url.startsWith("jdbc:derby:memory:")
                || url.startsWith("jdbc:sqlite::memory:")) {
            return true;
        }
        if (url.startsWith("jdbc:h2:tcp://") || url.startsWith("jdbc:h2:ssl://") || url.startsWith("jdbc:derby://")) {
            int start = url.indexOf("://") + 3;
            int path = url.indexOf('/', start);
            if (path < 0) {
                return false;
            }
            String database = url.substring(path + 1);
            return url.startsWith("jdbc:h2:") ? database.startsWith("mem:") : database.startsWith("memory:");
        }
        return false;
    }

    private static final Set<String> SAFE_TARGET_KEYS = Set.of(
            "quarkus.arc.fail-on-intercepted-private-method",
            "quarkus.arc.detect-wrong-annotations",
            "quarkus.config.mapping.validate-unknown",
            "smallrye.config.mapping.validate-unknown",
            "quarkus.config.log.values");

    private static String display(String name) {
        if (name.length() > 256) {
            return SecretMasker.MASKED_VALUE;
        }
        String bare = unprofiled(name);
        if (SAFE_TARGET_KEYS.contains(bare) || isDeprecatedProperty(bare)) {
            if (name.startsWith("%")) {
                int separator = name.indexOf('.');
                String profilePart = name.substring(1, separator);
                if (MASKER.isSecret(profilePart) || MASKER.shouldMask("configuration.name", profilePart)) {
                    return SecretMasker.MASKED_VALUE;
                }
            }
            return hasSecretNamedSegment(bare) ? SecretMasker.MASKED_VALUE : name;
        }
        if (MASKER.isSecret(name) || MASKER.shouldMask("configuration.name", name)) {
            return SecretMasker.MASKED_VALUE;
        }
        return name;
    }

    private static boolean hasSecretNamedSegment(String bare) {
        List<String> segments = segments(bare, 7);
        if (segments == null || segments.size() < 3) {
            return false;
        }
        String group = segments.get(1);
        String candidate = null;
        if (group.equals("hibernate-orm") && segments.size() >= 4 && !RESERVED_SUBGROUPS.contains(segments.get(2))) {
            candidate = segments.get(2);
        } else if (group.equals("oidc")
                && segments.size() == 5
                && segments.get(3).equals("proxy")) {
            candidate = segments.get(2);
        } else if (group.equals("rest-client") && segments.size() == 4) {
            candidate = segments.get(2);
        } else if (group.equals("log")
                && segments.size() >= 6
                && segments.get(2).equals("handler")) {
            candidate = segments.get(4);
        }
        return candidate != null && (MASKER.isSecret(candidate) || MASKER.shouldMask("configuration.name", candidate));
    }
}
