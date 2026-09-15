package io.github.jdubois.bootui.engine.mysql;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;

/** Declaration-only detection. Unknown declarations are candidates, never proof of a supported server. */
public final class MySqlDataSourceDetection {

    /**
     * Drivers that wrap another driver and therefore prefix, rather than replace, the real sub-protocol:
     * {@code jdbc:p6spy:mysql://...}, {@code jdbc:aws-wrapper:mysql://...}, {@code jdbc:otel:mysql://...} and
     * Testcontainers' {@code jdbc:tc:mysql:8.4.6:///db}. An unrecognised prefix does not establish MySQL:
     * its address or database-name tokens must not be mistaken for a known sub-protocol.
     */
    private static final Set<String> WRAPPING_DRIVERS = Set.of("aws-wrapper", "p6spy", "otel", "tc", "log4jdbc");

    private MySqlDataSourceDetection() {}

    /**
     * True only when the URL <em>declares</em> the MySQL driver, never when it merely mentions the word.
     *
     * <p>Only the sub-protocol can name a driver, so parsing stops at the first vendor token: everything after
     * it is that vendor's own address, path, database name or parameter syntax. {@code jdbc:h2:mem:mysql} is an
     * H2 database that happens to be called {@code mysql}, and {@code jdbc:sqlserver://host;database=mysql} is
     * SQL Server; treating either as MySQL would offer a panel whose every query is invalid there. MariaDB is
     * rejected for the same reason — it is a separate integration, not a MySQL dialect BootUI can read.</p>
     */
    public static boolean isMySqlJdbcUrl(String value) {
        if (value == null) {
            return false;
        }
        String url = value.strip().toLowerCase(Locale.ROOT);
        if (!url.startsWith("jdbc:")) {
            return false;
        }
        // Stop at the address: a host, database name or URL parameter cannot declare the driver.
        String declaration = url.split("//", 2)[0];
        String[] segments = declaration.split(":", -1);
        // segments[0] is the "jdbc" scheme; the vendor is the first segment that is not a wrapping driver.
        for (int index = 1; index < segments.length; index++) {
            if (!WRAPPING_DRIVERS.contains(segments[index])) {
                return segments[index].equals("mysql") || segments[index].equals("mysql+srv");
            }
        }
        return false;
    }

    public static boolean isMySqlDbKind(String value) {
        return value != null && value.strip().equalsIgnoreCase("mysql");
    }

    public static String jdbcUrlOf(DataSource source) {
        if (source == null) {
            return null;
        }
        for (String name : new String[] {"getJdbcUrl", "getUrl"}) {
            try {
                Method method = source.getClass().getMethod(name);
                if (method.getReturnType() != String.class) {
                    continue;
                }
                method.trySetAccessible();
                Object value = method.invoke(source);
                if (value instanceof String url && !url.isBlank()) {
                    return url;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Unknown is deliberately not a negative declaration.
            }
        }
        return null;
    }
}
