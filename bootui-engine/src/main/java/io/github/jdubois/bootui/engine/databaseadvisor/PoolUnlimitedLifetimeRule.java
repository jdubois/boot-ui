package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Result;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.PoolSettings;

/**
 * A pool whose connections never reach a maximum age. It needs no server evidence, so it still runs when the server
 * could not be read, but {@link PoolIdleLimitRule} owns the datasource whenever it fires there: the same
 * {@code maxLifetime} of 0 is part of that finding, and reporting it twice would count one problem twice.
 */
final class PoolUnlimitedLifetimeRule extends AbstractDriverRule {

    static final String ID = "DB-POOL-003";

    PoolUnlimitedLifetimeRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "Connection pool has no maximum connection lifetime",
                        DatabaseAdvisorCategory.CONNECTION_POOL,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a HikariCP or Agroal pool on a PostgreSQL, MySQL or MariaDB datasource whose"
                                + " maxLifetime is 0, so a connection is never retired for age. HikariCP recommends a"
                                + " maximum lifetime a few seconds shorter than any limit the database or the"
                                + " infrastructure in between imposes (a firewall or load balancer idle timeout, a"
                                + " server-side connection limit), which BootUI cannot see from here. It stays quiet"
                                + " when DB-POOL-001 reports the datasource, because that finding already includes"
                                + " this setting. Agroal's maximum lifetime is 0 unless configured, and it has"
                                + " background validation that is not observable here, so on Agroal this is a review"
                                + " prompt.",
                        "Set a maximum lifetime well below the shortest connection limit on the path to the database"
                                + " (HikariCP's default is 30 minutes; in Quarkus,"
                                + " quarkus.datasource.jdbc.max-lifetime), or keep it unlimited deliberately and rely"
                                + " on keepalive or validation, then dismiss this finding.",
                        "https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby"),
                "No PostgreSQL, MySQL or MariaDB datasource was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        if (!PoolServerCompatibility.applies(schema)) {
            return Outcome.notApplicable();
        }
        Result<PoolSettings> pool = PoolServerCompatibility.pool(context, schema);
        if (pool.unresolved() != null) {
            return pool.unresolved();
        }
        Long lifetime = pool.value().maxLifetimeMs();
        if (lifetime == null || lifetime < 0) {
            return Outcome.gap("the pool's `maxLifetime` could not be read, so it is not evaluated.");
        }
        if (lifetime > 0) {
            return Outcome.assessed();
        }
        Outcome owner = PoolIdleLimitRule.unresolved(context, schema);
        if (owner != null && owner.kind() == Outcome.Kind.GAP) {
            // Whether DB-POOL-001 owns this datasource is not settled; reporting here could be retracted by the next
            // scan.
            return Outcome.gap("whether " + PoolIdleLimitRule.ID + " already reports this pool could not be settled ("
                    + owner.text() + ")");
        }
        if (owner == null && ownedByIdleLimit(context, schema)) {
            return Outcome.assessed();
        }
        String unit = PoolServerCompatibility.agroal(pool.value())
                ? "maxLifetime is 0 (unlimited, the Agroal default unless quarkus.datasource.jdbc.max-lifetime is set),"
                        + " so a connection is never retired for age."
                : "maxLifetime is 0 (unlimited), so a connection is never retired for age.";
        findings.add(
                "Datasource `" + schema.dataSourceName() + "`: " + unit + " Evidence: effective.",
                DatabaseIdentities.of(schema.dataSourceName(), "pool=max-lifetime"));
        return Outcome.assessed();
    }

    private static boolean ownedByIdleLimit(DatabaseAdvisorContext context, SchemaSnapshot schema) {
        PoolSettings pool = context.poolOf(schema.dataSourceName());
        PoolServerCompatibility.IdleLimit limit =
                PoolServerCompatibility.idleLimit(schema).value();
        return PoolServerCompatibility.exposedToIdleClose(pool, limit.millis()) == PoolServerCompatibility.Tri.YES;
    }
}
