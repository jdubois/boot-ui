package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Fit;
import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Result;
import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Share;

/**
 * A pool whose maximum size is larger than the connections the server lets an ordinary role hold. The pool can never
 * fill, so every borrower beyond the server's capacity waits for a connection that will not come or fails to connect.
 * {@link PoolServerShareRule} covers the weaker, heuristic case of a pool that fits but leaves no headroom; the two
 * never report the same datasource.
 */
final class PoolExceedsServerConnectionsRule extends AbstractDriverRule {

    static final String ID = "DB-POOL-002";

    PoolExceedsServerConnectionsRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "Connection pool is larger than the server's connection limit",
                        DatabaseAdvisorCategory.CONNECTION_POOL,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Detects a HikariCP or Agroal pool on a PostgreSQL, MySQL or MariaDB datasource whose"
                                + " maximum pool size is larger than the connections the server lets an ordinary role"
                                + " hold: max_connections, minus superuser_reserved_connections and, from PostgreSQL"
                                + " 16, reserved_connections. Such a pool cannot fill, and other clients of the same"
                                + " server count against the same limit. The limit is read from the server in the same"
                                + " bounded scan and is the budget of an ordinary role: a role granted"
                                + " pg_use_reserved_connections, or a superuser, may use the reserved slots. A proxy"
                                + " that multiplexes connections (PgBouncer, RDS Proxy, ProxySQL) is not visible from"
                                + " here and makes a larger pool deliberate.",
                        "Lower the pool's maximum size below the server's usable connections, leaving room for other"
                                + " clients, or raise max_connections if the server can afford it. A pool does not"
                                + " need many connections: HikariCP's pool-sizing guidance starts from a small"
                                + " multiple of the CPU cores. If a connection proxy sits between the application and"
                                + " the database, verify its own limits instead and dismiss this finding.",
                        "https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing"),
                "No PostgreSQL, MySQL or MariaDB datasource was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        Result<Fit> fit = PoolServerCompatibility.fit(context, schema);
        if (fit.unresolved() != null) {
            return fit.unresolved();
        }
        if (fit.value().share() == Share.EXCEEDS) {
            Fit value = fit.value();
            findings.add(
                    "Datasource `" + schema.dataSourceName() + "`: maximumPoolSize " + value.maximumPoolSize()
                            + " exceeds the " + value.capacity().usable() + " usable connections ("
                            + value.capacity().explanation()
                            + "): the pool cannot fill, unless a proxy multiplexes. Evidence: effective.",
                    DatabaseIdentities.of(schema.dataSourceName(), "pool=maximum-size"));
        }
        return Outcome.assessed();
    }
}
