package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.AbstractDriverRule.Outcome;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.PoolSettings;
import java.util.Locale;
import java.util.Optional;

/**
 * The pure comparison of one datasource's connection pool with the limits of the database server behind it, shared by
 * the pool rules so that the rule that owns a situation and the rule that must stay quiet about it cannot drift apart.
 *
 * <p>It reads the sanitized {@link PoolSettings} (never a connection, never a URL or user name) and the server
 * settings the scan connection already read ({@link ServerSetting}). Both are <em>unknown</em> until proven: a pool
 * that could not be attributed, a setting the server did not report, one this server version lacks, or a value that
 * does not parse is reported through an {@link Outcome}, never as a guessed number.</p>
 */
final class PoolServerCompatibility {

    /**
     * HikariCP checks idle connections on a housekeeping timer, so a connection is retired up to this long <em>after</em>
     * {@code idleTimeout} (README: "a maximum variation of +30 seconds"). The configured value alone is therefore not
     * proof that the pool gets there first.
     */
    static final long IDLE_RETIREMENT_HEADROOM_MS = 30_000L;

    /** A pool taking more than this share of the server's usable connections leaves no room for a second instance. */
    static final int POOL_SHARE_PERCENT = 50;

    static final String MYSQL_IDLE_SETTING = "wait_timeout";
    static final String POSTGRES_IDLE_SETTING = "idle_session_timeout";

    private PoolServerCompatibility() {}

    /** Whether the pool rules judge this dialect at all; everything else is silently not applicable. */
    static boolean applies(SchemaSnapshot schema) {
        return schema.dialect() == Dialect.POSTGRESQL || schema.dialect().isMySqlFamily();
    }

    /** The datasource's pool, or why it cannot be judged. Only HikariCP and Agroal pools are attributed. */
    static Result<PoolSettings> pool(DatabaseAdvisorContext context, SchemaSnapshot schema) {
        PoolSettings pool = context.poolOf(schema.dataSourceName());
        if (pool == null) {
            return Result.unresolved(Outcome.skip("no HikariCP or Agroal connection pool could be attributed to this"
                    + " datasource, so its pool settings are not evaluated."));
        }
        if (!ConnectionPoolInfo.HIKARI.equals(pool.implementation())
                && !ConnectionPoolInfo.AGROAL.equals(pool.implementation())) {
            return Result.unresolved(Outcome.skip(
                    "its connection pool is not HikariCP or Agroal, so its pool settings" + " are not evaluated."));
        }
        return Result.of(pool);
    }

    static boolean agroal(PoolSettings pool) {
        return ConnectionPoolInfo.AGROAL.equals(pool.implementation());
    }

    // --- the server's idle-session limit (DB-POOL-001) ------------------------------------------------------------

    /** What the server does to a session that sits idle, as the scan connection's session sees it. */
    record IdleLimit(String setting, long millis) {}

    /**
     * The server's idle-session limit, or why there is none to judge: MySQL and MariaDB {@code wait_timeout} (seconds),
     * PostgreSQL {@code idle_session_timeout} (14+, disabled when {@code 0}). A disabled limit is a settled answer
     * ({@link Outcome#assessed()}), not a gap.
     */
    static Result<IdleLimit> idleLimit(SchemaSnapshot schema) {
        boolean postgres = schema.dialect() == Dialect.POSTGRESQL;
        VendorFindingKind<ServerSetting> kind =
                postgres ? VendorFindingKinds.POSTGRES_SERVER_SETTINGS : VendorFindingKinds.MYSQL_SERVER_SETTINGS;
        String name = postgres ? POSTGRES_IDLE_SETTING : MYSQL_IDLE_SETTING;
        Result<Settings> settings = settings(schema, kind);
        if (settings.unresolved() != null) {
            return Result.unresolved(settings.unresolved());
        }
        Optional<ServerSetting> setting = settings.value().get(name);
        if (setting.isEmpty()) {
            if (postgres && schema.version().known() && !schema.version().atLeast(14, 0)) {
                return Result.unresolved(Outcome.skip("this server version has no `" + name
                        + "` (PostgreSQL 14 or later), so it cannot close an idle session on its own."));
            }
            return Result.unresolved(Outcome.gap(
                    "the server did not report `" + name + "`, so its idle-session limit" + " is unknown."));
        }
        Long millis = postgres ? postgresMillis(setting.get()) : mysqlWaitTimeoutMillis(setting.get());
        if (millis == null || (!postgres && millis <= 0)) {
            return Result.unresolved(Outcome.gap("the server reported `" + name + "` as a value that could not be"
                    + " read, so its idle-session limit is unknown."));
        }
        if (millis == 0) {
            return Result.unresolved(Outcome.assessed());
        }
        return Result.of(new IdleLimit(name, millis));
    }

    private static Long mysqlWaitTimeoutMillis(ServerSetting setting) {
        Long seconds = parseLong(setting.value());
        if (seconds == null || seconds < 0) {
            return null;
        }
        try {
            return Math.multiplyExact(seconds, 1000L);
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    /** {@code pg_settings.setting} is a plain number in {@code unit}; a unit this class does not know is unknown. */
    private static Long postgresMillis(ServerSetting setting) {
        Long number = parseLong(setting.value());
        if (number == null || number < 0) {
            return null;
        }
        long factor;
        String unit = setting.unit() == null ? "" : setting.unit().toLowerCase(Locale.ROOT);
        switch (unit) {
            case "ms" -> factor = 1L;
            case "s" -> factor = 1_000L;
            case "min" -> factor = 60_000L;
            case "h" -> factor = 3_600_000L;
            case "d" -> factor = 86_400_000L;
            default -> {
                // A zero is "disabled" in every unit, so it does not need one.
                return number == 0 ? 0L : null;
            }
        }
        try {
            return Math.multiplyExact(number, factor);
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    /** Whether one pool setting settles a question, rules it out, or cannot be read. */
    enum Tri {
        YES,
        NO,
        UNKNOWN
    }

    /**
     * Whether the pool can hold an idle connection longer than the server allows: {@code true} only when nothing the
     * pool does gets there first. Each defence is three-valued, so an unknown one can neither cause nor hide a finding:
     * any defence that is known to cover settles the answer, and only when every defence is known not to cover is the
     * pool exposed. A defence that is not known while none covers leaves the answer unknown.
     *
     * <ol>
     *   <li>the connection is retired at {@code maxLifetime}, which must be positive and below the limit;</li>
     *   <li>it is pinged at {@code keepaliveTime}, which must be positive and below the limit;</li>
     *   <li>it is retired when idle: {@code minimumIdle} is 0 and {@code idleTimeout} is positive and, with the
     *       pool's housekeeping headroom, below the limit.</li>
     * </ol>
     */
    static Tri exposedToIdleClose(PoolSettings pool, long limitMillis) {
        Tri lifetime = belowLimit(pool.maxLifetimeMs(), limitMillis);
        Tri keepalive = belowLimit(pool.keepaliveTimeMs(), limitMillis);
        Tri idle = idleRetirementCovers(pool, limitMillis);
        if (lifetime == Tri.YES || keepalive == Tri.YES || idle == Tri.YES) {
            return Tri.NO;
        }
        if (lifetime == Tri.NO && keepalive == Tri.NO && idle == Tri.NO) {
            return Tri.YES;
        }
        return Tri.UNKNOWN;
    }

    private static Tri belowLimit(Long millis, long limitMillis) {
        if (millis == null || millis < 0) {
            return Tri.UNKNOWN;
        }
        return millis > 0 && millis < limitMillis ? Tri.YES : Tri.NO;
    }

    private static Tri idleRetirementCovers(PoolSettings pool, long limitMillis) {
        // HikariCP treats an unset minimumIdle (-1) as the maximum size, so idle retirement never applies to it.
        boolean keepsIdleConnections = pool.minimumIdle() != 0;
        Long idleTimeout = pool.idleTimeoutMs();
        if (keepsIdleConnections || (idleTimeout != null && idleTimeout == 0)) {
            return Tri.NO;
        }
        if (idleTimeout == null || idleTimeout < 0) {
            return Tri.UNKNOWN;
        }
        return idleTimeout + IDLE_RETIREMENT_HEADROOM_MS < limitMillis ? Tri.YES : Tri.NO;
    }

    // --- the server's usable connections (DB-POOL-002) -------------------------------------------------------------

    /**
     * The connections an ordinary role can hold at once, and how that was computed.
     *
     * @param usable {@code max_connections} minus the reserved ones
     * @param explanation the arithmetic, for the finding text
     */
    record Capacity(long usable, String explanation) {}

    /**
     * MySQL and MariaDB: {@code max_connections}. PostgreSQL: {@code max_connections} minus
     * {@code superuser_reserved_connections} and, from 16, {@code reserved_connections}. This is the budget of an
     * ordinary role: a role with {@code pg_use_reserved_connections} or a superuser may use the reserved slots.
     */
    static Result<Capacity> capacity(SchemaSnapshot schema) {
        boolean postgres = schema.dialect() == Dialect.POSTGRESQL;
        Result<Settings> settings = settings(
                schema,
                postgres ? VendorFindingKinds.POSTGRES_SERVER_SETTINGS : VendorFindingKinds.MYSQL_SERVER_SETTINGS);
        if (settings.unresolved() != null) {
            return Result.unresolved(settings.unresolved());
        }
        Long max = number(settings.value(), "max_connections");
        if (max == null || max <= 0) {
            return Result.unresolved(Outcome.gap("the server did not report a usable `max_connections`, so its"
                    + " connection capacity is unknown."));
        }
        if (!postgres) {
            return Result.of(new Capacity(max, "max_connections " + max));
        }
        Long superuser = number(settings.value(), "superuser_reserved_connections");
        if (superuser == null || superuser < 0) {
            return Result.unresolved(Outcome.gap("the server did not report `superuser_reserved_connections`, so its"
                    + " usable connections are unknown."));
        }
        long reserved = 0;
        if (settings.value().get("reserved_connections").isPresent()) {
            Long value = number(settings.value(), "reserved_connections");
            if (value == null || value < 0) {
                return Result.unresolved(Outcome.gap("the server reported `reserved_connections` as a value that could"
                        + " not be read, so its usable connections are unknown."));
            }
            reserved = value;
        } else if (!schema.version().known() || schema.version().atLeast(16, 0)) {
            return Result.unresolved(Outcome.gap(
                    "the server did not report `reserved_connections`, so its usable" + " connections are unknown."));
        }
        long usable = max - superuser - reserved;
        String explanation = "max_connections " + max + " - superuser_reserved_connections " + superuser
                + (reserved > 0 || schema.version().atLeast(16, 0) ? " - reserved_connections " + reserved : "");
        return Result.of(new Capacity(usable, explanation));
    }

    /** How the pool's maximum size stands against {@code capacity}. */
    enum Share {
        EXCEEDS,
        HEAVY,
        FITS
    }

    /** The pool's maximum size against the server's usable connections, with the numbers a finding quotes. */
    record Fit(Share share, int maximumPoolSize, Capacity capacity) {}

    /**
     * Where the pool's maximum size stands against the server's capacity, or why that cannot be said. Agroal reports
     * its sizes like HikariCP, so both are judged. A pool size that could not be read ({@code -1}) is unknown.
     */
    static Result<Fit> fit(DatabaseAdvisorContext context, SchemaSnapshot schema) {
        if (!applies(schema)) {
            return Result.unresolved(Outcome.notApplicable());
        }
        Result<PoolSettings> pool = pool(context, schema);
        if (pool.unresolved() != null) {
            return Result.unresolved(pool.unresolved());
        }
        int size = pool.value().maximumPoolSize();
        if (size <= 0) {
            return Result.unresolved(Outcome.gap("the pool's maximum size could not be read, so it is not evaluated."));
        }
        Result<Capacity> capacity = capacity(schema);
        if (capacity.unresolved() != null) {
            return Result.unresolved(capacity.unresolved());
        }
        return Result.of(new Fit(share(size, capacity.value().usable()), size, capacity.value()));
    }

    static Share share(int maximumPoolSize, long usable) {
        if (maximumPoolSize > usable) {
            return Share.EXCEEDS;
        }
        return (long) maximumPoolSize * 100L > usable * POOL_SHARE_PERCENT ? Share.HEAVY : Share.FITS;
    }

    // --- plumbing --------------------------------------------------------------------------------------------------

    private record Settings(VendorAugmentation<ServerSetting> augmentation) {

        Optional<ServerSetting> get(String name) {
            return augmentation.findings().stream()
                    .filter(setting -> name.equals(setting.name()))
                    .findFirst();
        }
    }

    private static Result<Settings> settings(SchemaSnapshot schema, VendorFindingKind<ServerSetting> kind) {
        VendorAugmentation<ServerSetting> augmentation = schema.vendorFindings().augmentation(kind);
        if (!augmentation.available()) {
            return Result.unresolved(Outcome.gap(augmentation.reason()));
        }
        if (augmentation.truncated()) {
            return Result.unresolved(
                    Outcome.gap("the server settings read was truncated, so a setting may be missing from it."));
        }
        return Result.of(new Settings(augmentation));
    }

    private static Long number(Settings settings, String name) {
        return settings.get(name).map(setting -> parseLong(setting.value())).orElse(null);
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** A duration as the findings quote it: the largest whole unit, with {@code 0} spelled out. */
    static String duration(long millis) {
        if (millis == 0) {
            return "0";
        }
        if (millis % 3_600_000L == 0) {
            return millis / 3_600_000L + " h";
        }
        if (millis % 60_000L == 0) {
            return millis / 60_000L + " min";
        }
        if (millis % 1_000L == 0) {
            return millis / 1_000L + " s";
        }
        return millis + " ms";
    }

    /** A value, or the {@link Outcome} that explains why there is none. */
    record Result<T>(T value, Outcome unresolved) {

        static <T> Result<T> of(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> unresolved(Outcome outcome) {
            return new Result<>(null, outcome);
        }
    }
}
