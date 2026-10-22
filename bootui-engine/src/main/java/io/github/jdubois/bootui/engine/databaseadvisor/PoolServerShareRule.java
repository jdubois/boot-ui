package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Fit;
import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Result;
import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Share;

/**
 * A pool that fits in the server but takes more than {@value PoolServerCompatibility#POOL_SHARE_PERCENT} percent of
 * its usable connections, so a second instance of the application (a rolling deploy, a second replica) cannot reach
 * its own pool size. A heuristic, hence LOW and a rule of its own: the rule severity is what the CLI's gate and the
 * score count, and a heuristic must not weigh as much as a pool that cannot fill ({@link
 * PoolExceedsServerConnectionsRule}), which owns the datasource whenever it applies.
 */
final class PoolServerShareRule extends AbstractDriverRule {

    static final String ID = "DB-POOL-004";

    PoolServerShareRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "Connection pool leaves no headroom for a second instance",
                        DatabaseAdvisorCategory.CONNECTION_POOL,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a HikariCP or Agroal pool on a PostgreSQL, MySQL or MariaDB datasource whose maximum"
                                + " pool size is more than " + PoolServerCompatibility.POOL_SHARE_PERCENT
                                + " percent of the connections the server lets an ordinary role hold (see"
                                + " DB-POOL-002 for how they are counted), yet still fits. It is a heuristic that"
                                + " assumes a second instance of the application, as during a rolling deploy or when"
                                + " the service is scaled out, will open a pool of the same size on the same server;"
                                + " it knows nothing about other clients. It stays quiet when DB-POOL-002 reports the"
                                + " datasource. The Agroal default maximum size (50) is above the threshold for a"
                                + " stock PostgreSQL server.",
                        "Size the pool for the application's real concurrency rather than the server's capacity, so"
                                + " every instance and the other clients fit together: lower the maximum pool size,"
                                + " or raise max_connections if the server can afford it. Dismiss this finding when"
                                + " the application never runs more than one instance on this server, or when a"
                                + " connection proxy multiplexes the connections.",
                        "https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing"),
                "No PostgreSQL, MySQL or MariaDB datasource was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        Result<Fit> fit = PoolServerCompatibility.fit(context, schema);
        if (fit.unresolved() != null) {
            return fit.unresolved();
        }
        if (fit.value().share() == Share.HEAVY) {
            Fit value = fit.value();
            findings.add(
                    "Datasource `" + schema.dataSourceName() + "`: maximumPoolSize " + value.maximumPoolSize()
                            + " is over " + PoolServerCompatibility.POOL_SHARE_PERCENT + "% of the "
                            + value.capacity().usable() + " usable connections ("
                            + value.capacity().explanation()
                            + "); a second instance would not fit. Heuristic. Evidence: effective.",
                    DatabaseIdentities.of(schema.dataSourceName(), "pool=maximum-size"));
        }
        return Outcome.assessed();
    }
}
