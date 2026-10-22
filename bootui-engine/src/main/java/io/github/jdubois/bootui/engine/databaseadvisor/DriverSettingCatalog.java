package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The JDBC driver settings the Database Advisor is allowed to retain, and what is known about each driver's own
 * rules for them.
 *
 * <p>This is an allow-list, not a filter: a JDBC URL or a pool property whose name is not listed here is never read
 * into a value, so credentials ({@code user}, {@code password}, a TLS key password, ...) cannot survive by accident.
 * Names are matched case-insensitively so that a differently-cased spelling is noticed, but both drivers read the
 * exact spelling (pgjdbc's {@code PGProperty} lookup, and Connector/J 9.7 declares these properties case-sensitive in
 * {@code PropertyKey}), so any other spelling is an unsettled setting, never the setting. Values are capped at
 * {@value #MAX_VALUE_LENGTH} characters and never trimmed.</p>
 *
 * <p>Verified primary sources (pgjdbc 42.7, Connector/J 9.7):</p>
 *
 * <ul>
 *   <li>pgjdbc: a property given both in the URL and in the {@code Properties} object takes its value from the URL
 *       ("the value from Properties object is ignored", pgjdbc <em>Connecting to the Database</em>; confirmed in
 *       {@code Driver.parseURL}, which ranks URL values first). Booleans are read with
 *       {@link Boolean#parseBoolean(String)}: only {@code true}, ignoring case, is true.</li>
 *   <li>Connector/J: <em>this advisor relies on no precedence</em>. The 9.7 source lets a {@code Properties} value
 *       override the URL, but an older line was not checked, so two differing values are reported as conflicting and
 *       every rule that needs that setting is skipped. Booleans accept {@code true}, {@code false}, {@code yes} and
 *       {@code no}; anything else makes the driver refuse the connection.</li>
 * </ul>
 */
public final class DriverSettingCatalog {

    /** pgjdbc: rewrite batched {@code INSERT}s into multi-row statements. Default {@code false}. */
    public static final String RE_WRITE_BATCHED_INSERTS = "reWriteBatchedInserts";

    /** Connector/J: rewrite batched prepared {@code INSERT}/{@code REPLACE} statements. Default {@code false}. */
    public static final String REWRITE_BATCHED_STATEMENTS = "rewriteBatchedStatements";

    /** Connector/J: report affected rows rather than found rows. Default {@code false}. */
    public static final String USE_AFFECTED_ROWS = "useAffectedRows";

    /**
     * pgjdbc: names a service in a {@code pg_service.conf} file, which can supply any setting the URL and the pool do
     * not. A marker only: its value is never judged.
     */
    public static final String SERVICE = "service";

    /**
     * Connector/J: names configuration files ({@code useConfigs=maxPerformance}) that can supply settings the URL and
     * the pool do not. A marker only.
     */
    public static final String USE_CONFIGS = "useConfigs";

    /** Connector/J: a class that may rewrite the connection properties. A marker only. */
    public static final String PROPERTIES_TRANSFORM = "propertiesTransform";

    /** Every driver setting that may be retained, under its canonical spelling. */
    public static final Set<String> NAMES = Set.of(
            RE_WRITE_BATCHED_INSERTS,
            REWRITE_BATCHED_STATEMENTS,
            USE_AFFECTED_ROWS,
            SERVICE,
            USE_CONFIGS,
            PROPERTIES_TRANSFORM);

    /** The longest value kept; a longer one is cut, which can only turn a boolean into an unrecognised value. */
    public static final int MAX_VALUE_LENGTH = 64;

    private static final Map<String, String> CANONICAL_BY_LOWER_CASE = canonicalByLowerCase();

    private DriverSettingCatalog() {}

    /** The JDBC driver families this advisor has verified rules for. */
    enum Driver {
        /** The PostgreSQL JDBC driver, exactly as {@code DatabaseMetaData.getDriverName()} names it. */
        PGJDBC,
        /**
         * MySQL Connector/J, which may be talking to a MySQL or a MariaDB server. Recognised by the "MySQL Connector"
         * name it reports ("MySQL Connector/J", formerly "MySQL Connector Java"), so a third-party driver that merely
         * mentions MySQL is not assumed to share its properties.
         */
        MYSQL_CONNECTOR_J,
        /** MariaDB Connector/J, whose bulk settings differ. */
        MARIADB_CONNECTOR_J,
        UNKNOWN;

        static Driver of(String driverName) {
            if (driverName == null) {
                return UNKNOWN;
            }
            String name = driverName.trim().toLowerCase(Locale.ROOT);
            if (name.equals("postgresql jdbc driver")) {
                return PGJDBC;
            }
            if (name.contains("mariadb")) {
                return MARIADB_CONNECTOR_J;
            }
            if (name.contains("mysql connector")) {
                return MYSQL_CONNECTOR_J;
            }
            return UNKNOWN;
        }
    }

    /** What a driver does when a setting is given both in the URL and in the pool's driver properties. */
    enum Precedence {
        /** The driver documents that the URL value is used. */
        URL_WINS,
        /** No winner is relied on, so differing values cannot be judged. */
        UNDEFINED
    }

    /**
     * The settings that make {@code driver} read more configuration from a source the advisor does not see (a service
     * file, configuration files, a properties transform). While one is set, an absent setting is not evidence.
     */
    static Set<String> expansionMarkers(Driver driver) {
        return switch (driver) {
            case PGJDBC -> Set.of(SERVICE);
            case MYSQL_CONNECTOR_J, MARIADB_CONNECTOR_J -> Set.of(USE_CONFIGS, PROPERTIES_TRANSFORM);
            case UNKNOWN -> Set.of();
        };
    }

    /**
     * Settings that can <em>replace</em> an explicitly configured value, unlike configuration files, which only fill
     * in what is absent. Connector/J applies a {@code ConnectionPropertiesTransform} after collecting every explicit
     * property, so while one is configured no value of either source can be trusted.
     */
    static Set<String> overrideMarkers(Driver driver) {
        return switch (driver) {
            case MYSQL_CONNECTOR_J, MARIADB_CONNECTOR_J -> Set.of(PROPERTIES_TRANSFORM);
            case PGJDBC, UNKNOWN -> Set.of();
        };
    }

    static Precedence precedence(Driver driver) {
        return driver == Driver.PGJDBC ? Precedence.URL_WINS : Precedence.UNDEFINED;
    }

    /** The canonical spelling of an allow-listed name, or {@code null} when {@code name} is not allow-listed. */
    static String canonical(String name) {
        return name == null ? null : CANONICAL_BY_LOWER_CASE.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The allow-listed entries of {@code properties}, matched case-insensitively but returned under the spelling the
     * pool used, with values capped (never trimmed: the drivers do not trim). The spelling matters because pgjdbc
     * ignores a differently-cased name; {@code DriverEvidenceReader} judges it. Every other entry, whatever its name
     * or value, is dropped without being read, so a pool adapter can pass a raw driver-properties map straight through
     * this method. Never throws.
     */
    public static Map<String, String> allowListed(Map<?, ?> properties) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        Map<String, String> retained = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            try {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                String canonical = canonical(key);
                if (canonical == null || entry.getValue() == null) {
                    continue;
                }
                retained.put(key, bounded(String.valueOf(entry.getValue())));
            } catch (RuntimeException ex) {
                // A property whose value cannot be rendered is not evidence of anything.
            }
        }
        return Map.copyOf(retained);
    }

    static String bounded(String value) {
        return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH);
    }

    /**
     * How {@code driver} reads {@code value} as a boolean, or {@code null} when it does not say. pgjdbc never fails
     * to read one: anything but {@code true} is false.
     */
    static Boolean asBoolean(Driver driver, String value) {
        if (value == null) {
            return null;
        }
        // Neither driver trims: pgjdbc's Boolean.parseBoolean and Connector/J's enum lookup both see the raw text.
        String normalized = value.toLowerCase(Locale.ROOT);
        if (driver == Driver.PGJDBC) {
            return normalized.equals("true");
        }
        boolean allowYesNo = driver == Driver.MYSQL_CONNECTOR_J;
        if (normalized.equals("true") || allowYesNo && normalized.equals("yes")) {
            return Boolean.TRUE;
        }
        if (normalized.equals("false") || allowYesNo && normalized.equals("no")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Map<String, String> canonicalByLowerCase() {
        Map<String, String> byLowerCase = new HashMap<>();
        for (String name : NAMES) {
            byLowerCase.put(name.toLowerCase(Locale.ROOT), name);
        }
        return Map.copyOf(byLowerCase);
    }
}
