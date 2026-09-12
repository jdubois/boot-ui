package io.github.jdubois.bootui.engine.postgres;

import java.lang.reflect.Method;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Connection-free detection of "is a PostgreSQL datasource configured?", used by the adapters to decide
 * whether the PostgreSQL panel is available at all.
 *
 * <p>Availability must never open a connection: the panel's whole contract is that rendering it contacts
 * nothing and only the explicit read action queries PostgreSQL. So the decision is made from declared
 * configuration only — a JDBC URL or a Quarkus {@code db-kind} — and it is deliberately inclusive rather
 * than exact. A URL is treated as PostgreSQL when any of its {@code jdbc:}-separated sub-protocol segments
 * names PostgreSQL, which keeps wrapping drivers such as {@code jdbc:aws-wrapper:postgresql://...},
 * {@code jdbc:p6spy:postgresql://...}, {@code jdbc:otel:postgresql://...} and Testcontainers'
 * {@code jdbc:tc:postgresql:...} visible instead of hiding the panel from a real PostgreSQL application.</p>
 *
 * <p>A datasource whose URL cannot be read without connecting reports {@code null} from
 * {@link #jdbcUrlOf(DataSource)} rather than a guess; the caller must treat that as "cannot rule PostgreSQL
 * out", never as "not PostgreSQL". Guessing either way would be a lie, and hiding the panel is the more
 * damaging of the two.</p>
 */
public final class PostgresDataSourceDetection {

    private PostgresDataSourceDetection() {}

    /**
     * True when a JDBC URL declares PostgreSQL, including through a wrapping driver.
     *
     * <p>{@code jdbc:postgres...} is accepted alongside {@code jdbc:postgresql...} because some wrappers and
     * test fixtures use the shorter spelling; no other database's sub-protocol starts with {@code postgres}.</p>
     */
    public static boolean isPostgresJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return false;
        }
        String url = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        if (!url.startsWith("jdbc:")) {
            return false;
        }
        for (String segment : url.substring("jdbc:".length()).split(":")) {
            if (segment.startsWith("postgres")) {
                return true;
            }
        }
        return false;
    }

    /** True when a declared database kind (Quarkus {@code db-kind}, Hibernate dialect name, ...) names PostgreSQL. */
    public static boolean isPostgresDbKind(String dbKind) {
        return dbKind != null && dbKind.toLowerCase(Locale.ROOT).contains("postgres");
    }

    /**
     * Returns the JDBC URL a datasource declares, or {@code null} when it declares none that can be read
     * without opening a connection.
     *
     * <p>Read reflectively so that the engine stays free of any connection-pool dependency: HikariCP exposes
     * {@code getJdbcUrl()}, Agroal, Tomcat JDBC, DBCP and the JDK's {@code DriverManager}-backed datasources
     * expose {@code getUrl()}. Any failure is reported as "unknown", never as "not PostgreSQL".</p>
     */
    public static String jdbcUrlOf(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        for (String accessor : new String[] {"getJdbcUrl", "getUrl"}) {
            String url = invokeString(dataSource, accessor);
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return null;
    }

    private static String invokeString(DataSource dataSource, String accessor) {
        try {
            Method method = dataSource.getClass().getMethod(accessor);
            if (method.getReturnType() != String.class) {
                return null;
            }
            try {
                method.setAccessible(true);
            } catch (RuntimeException ignored) {
                // A non-exported implementation class still answers through its public method handle.
            }
            Object value = method.invoke(dataSource);
            return value instanceof String url ? url : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
