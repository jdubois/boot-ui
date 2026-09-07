package io.github.jdubois.bootui.quarkus.quarkusapp;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.spi.QuarkusAppEvidenceProblem;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot.Setting;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshotProvider;
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
            "QA-CFG-002",
            "QA-CFG-003",
            "QA-CFG-004",
            "QA-PROD-002",
            "QA-PROD-003",
            "QA-WEB-001",
            "QA-WEB-002",
            "QA-WEB-003",
            "QA-WEB-004");
    private static final Set<String> PRODUCTION_RULES =
            Set.of("QA-CFG-002", "QA-CFG-003", "QA-PROD-002", "QA-PROD-003");
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

        private Collection(Config config, QuarkusAppMetadata metadata) {
            this.config = config;
            this.metadata = metadata;
        }

        private QuarkusAppSnapshot collect() {
            inspect(CONFIG_RULES, () -> {
                profiles = List.copyOf(config.unwrap(SmallRyeConfig.class).getProfiles());
                int count = 0;
                for (ConfigSource source : config.getConfigSources()) {
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
            inspect(Set.of("QA-CFG-004"), this::legacyProperties);
            inspect(productionRules(), this::production);
            inspect(Set.of("QA-WEB-001"), this::compression);
            inspect(Set.of("QA-WEB-002", "QA-WEB-004"), this::shutdown);
            inspect(Set.of("QA-WEB-003"), this::clientTimeouts);
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
            boolean activeProd = profiles.equals(List.of("prod"));
            Set<String> applicable = productionRules();
            if (!metadata.available()) {
                unavailable(
                        Set.of("QA-CFG-002", "QA-PROD-002", "QA-PROD-003"),
                        "ORM and JDBC capability evidence is unavailable.");
            }
            Config source = activeProd ? config : productionDeclarations();
            String provenance = activeProd ? ACTIVE : DECLARED_PROD;
            if (!activeProd) {
                unavailable(
                        applicable,
                        "Only loaded production declarations were inspected; effective production configuration, "
                                + "external overrides and unloaded profile-aware files are unavailable.");
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
            for (String unit : units) {
                if (!applicable.contains("QA-PROD-002")) {
                    break;
                }
                inspect(Set.of("QA-PROD-002"), () -> {
                    String legacy = raw(source, unit + "database.generation", !activeProd);
                    String strategy =
                            legacy != null ? legacy : raw(source, unit + "schema-management.strategy", !activeProd);
                    if (strategy != null) {
                        String normalized = strategy.trim().toLowerCase(Locale.ROOT);
                        if (!Set.of("none", "validate", "create", "update", "drop", "drop-and-create")
                                .contains(normalized)) {
                            unavailable(Set.of("QA-PROD-002"), "A schema action could not be classified safely.");
                        } else {
                            add("QA-PROD-002", unit + "schema action", normalized, provenance);
                        }
                    }
                });
                inspect(Set.of("QA-CFG-002"), () -> {
                    String value = raw(source, unit + "log.sql", !activeProd);
                    if (value != null) {
                        add("QA-CFG-002", unit + "log.sql", booleanValue(value), provenance);
                    }
                });
            }
            inspect(Set.of("QA-CFG-003"), () -> {
                String level = raw(source, "quarkus.log.level", !activeProd);
                if (level != null) {
                    String normalized = level.trim().toUpperCase(Locale.ROOT);
                    if (!Set.of("ALL", "TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "OFF")
                            .contains(normalized)) {
                        unavailable(Set.of("QA-CFG-003"), "The logging level could not be classified safely.");
                    } else {
                        add(
                                "QA-CFG-003",
                                "root logger",
                                Set.of("ALL", "TRACE", "DEBUG").contains(normalized) ? "verbose" : "normal",
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
            List<ConfigSource> projectedSources = new ArrayList<>();
            int count = 0;
            int declarationsRead = 0;
            for (ConfigSource source : sources) {
                count++;
                Map<String, String> declarations = new LinkedHashMap<>();
                for (String name : names) {
                    if (isProductionKey(name) && isProductionSetting(unprofiled(name))) {
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
            for (String name : names) {
                String bare = unprofiled(name);
                String namespace = namespace(bare, "quarkus", "hibernate-orm");
                if (namespace == null || !bare.equals(namespace + "database.generation")) {
                    continue;
                }
                inspect(Set.of("QA-CFG-004"), () -> {
                    ConfigValue value = Expressions.withoutExpansion(() -> config.getConfigValue(name));
                    if (value.getRawValue() != null
                            && !value.getRawValue().isBlank()
                            && value.getSourceOrdinal() > Integer.MIN_VALUE) {
                        add("QA-CFG-004", name, "legacy", "configured property declaration");
                    }
                });
            }
        }

        private void compression() {
            ConfigValue value = config.getConfigValue("quarkus.http.enable-compression");
            boolean enabled = config.getOptionalValue("quarkus.http.enable-compression", Boolean.class)
                    .orElse(false);
            boolean frameworkDefault = value.getRawValue() == null || value.getSourceOrdinal() == Integer.MIN_VALUE;
            add(
                    "QA-WEB-001",
                    "application HTTP server",
                    enabled ? "enabled" : frameworkDefault ? "default-disabled" : "disabled",
                    ACTIVE);
        }

        private void shutdown() {
            Duration timeout = config.getOptionalValue("quarkus.shutdown.timeout", Duration.class)
                    .orElse(null);
            if (timeout != null && timeout.isNegative()) {
                unavailable(Set.of("QA-WEB-002", "QA-WEB-004"), "The shutdown duration is invalid.");
                return;
            }
            String value = timeout == null ? "absent" : timeout.isZero() ? "zero" : "positive";
            add("QA-WEB-002", "HTTP request draining", value, ACTIVE);
            add("QA-WEB-004", "HTTP request draining", value, ACTIVE);
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

        private void add(String rule, String target, String value, String provenance) {
            settings.add(new Setting(rule, display(target), value, provenance));
        }

        private void inspect(Set<String> rules, Runnable operation) {
            try {
                operation.run();
            } catch (IllegalArgumentException
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
        if (name.equals("quarkus.log.level")) {
            return true;
        }
        String unit = namespace(name, "quarkus", "hibernate-orm");
        if (unit != null) {
            return name.equals(unit + "database.generation")
                    || name.equals(unit + "schema-management.strategy")
                    || name.equals(unit + "log.sql");
        }
        String datasource = namespace(name, "quarkus", "datasource");
        return datasource != null && name.equals(datasource + "jdbc.url");
    }

    private static String namespace(String name, String root, String group) {
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
        if (segments.size() < 4
                || !segments.get(0).equals(root)
                || !segments.get(1).equals(group)) {
            return null;
        }
        if (segments.size() == 4 || segments.size() == 5) {
            String suffix = segments.get(segments.size() - 2) + "." + segments.get(segments.size() - 1);
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return null;
    }

    static boolean inMemory(String jdbcUrl) {
        String url = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:h2:mem:")
                || url.startsWith("jdbc:hsqldb:mem:")
                || url.startsWith("jdbc:derby:memory:")) {
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

    private static String display(String name) {
        if (name.length() > 256 || MASKER.isSecret(name) || MASKER.shouldMask("configuration.name", name)) {
            return SecretMasker.MASKED_VALUE;
        }
        return name;
    }
}
