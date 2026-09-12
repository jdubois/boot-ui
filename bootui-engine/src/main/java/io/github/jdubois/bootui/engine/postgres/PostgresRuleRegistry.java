package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresIndexDto;
import io.github.jdubois.bootui.core.dto.PostgresReplicationDto;
import io.github.jdubois.bootui.core.dto.PostgresStatementDto;
import io.github.jdubois.bootui.core.dto.PostgresTableDto;
import io.github.jdubois.bootui.core.dto.PostgresVacuumDto;
import io.github.jdubois.bootui.core.dto.PostgresVitalSignsDto;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The fixed catalogue of PostgreSQL checks, in report order.
 *
 * <p>Every rule is deterministic: the same collected rows always produce the same findings, with no
 * generated prose and no scoring gimmick. Thresholds are constants here rather than configuration, so a
 * finding always means the same thing across applications and across releases of BootUI.</p>
 */
final class PostgresRuleRegistry {

    static final double LOW_CACHE_HIT_RATIO = 0.90;
    /**
     * The completed transactions a database must have before its cumulative ratios mean anything. A
     * development database that has just started can read a 40% rollback ratio from two failed statements,
     * or a 50% cache hit ratio from a handful of cold reads, and reporting that as a finding would train the
     * reader to ignore the panel.
     */
    static final long MIN_COMPLETED_TRANSACTIONS = 1_000;

    static final double HIGH_ROLLBACK_RATIO = 0.05;
    static final double HIGH_CONNECTION_USAGE = 0.80;
    static final double WRAPAROUND_WARNING_RATIO = 0.50;
    static final double SLOW_STATEMENT_MEAN_MILLIS = 100;
    static final long SLOW_STATEMENT_MIN_CALLS = 10;
    static final double DOMINANT_STATEMENT_SHARE = 0.50;
    static final int MIN_RANKED_STATEMENTS = 5;
    static final double MIN_RANKED_TOTAL_MILLIS = 1_000;
    static final long UNUSED_INDEX_MIN_BYTES = 1024L * 1024L;
    static final long SEQUENTIAL_SCAN_MIN_BYTES = 50L * 1024L * 1024L;
    static final double SEQUENTIAL_SCAN_RATIO = 0.90;
    static final long SEQUENTIAL_SCAN_MIN_SCANS = 50;
    static final long DEAD_TUPLE_MIN_ROWS = 1000;
    static final double DEAD_TUPLE_RATIO = 0.20;
    static final long FORCED_CHECKPOINT_MIN = 5;
    static final int MAX_SAMPLES = 5;

    private static final String STATS_CAVEAT =
            "Cumulative statistics are counted since the last statistics reset and cover every client, not only "
                    + "this application.";

    private static final List<PostgresRule> RULES = List.of(
            rule(
                    "PG-VITALS-001",
                    PostgresSectionIds.VITAL_SIGNS,
                    "Low buffer cache hit ratio",
                    "PERFORMANCE",
                    "MEDIUM",
                    "An unusually large share of reads had to go to disk rather than being served from PostgreSQL's "
                            + "own buffer cache.",
                    "Check shared_buffers against the working set, and confirm the host has enough free memory "
                            + "for the operating system page cache before changing anything.",
                    STATS_CAVEAT + " A database that was recently restarted, or one whose working set is "
                            + "genuinely larger than memory, reads a low ratio without being misconfigured. "
                            + "Nothing is reported below " + MIN_COMPLETED_TRANSACTIONS + " completed "
                            + "transactions, because a ratio over a handful of them means nothing.",
                    "https://www.postgresql.org/docs/current/runtime-config-resource.html",
                    data -> {
                        PostgresVitalSignsDto vitals = data.vitalSigns();
                        if (vitals == null || vitals.cacheHitRatio() == null) {
                            return null;
                        }
                        Long completed = sum(vitals.transactionsCommitted(), vitals.transactionsRolledBack());
                        if (completed == null || completed < MIN_COMPLETED_TRANSACTIONS) {
                            return null;
                        }
                        if (vitals.cacheHitRatio() >= LOW_CACHE_HIT_RATIO) {
                            return null;
                        }
                        return PostgresRuleMatch.of("Cache hit ratio is "
                                + PostgresFormat.percent(vitals.cacheHitRatio()) + " over "
                                + PostgresFormat.count(completed) + " completed transactions, below the "
                                + PostgresFormat.percent(LOW_CACHE_HIT_RATIO) + " review threshold.");
                    }),
            rule(
                    "PG-VITALS-002",
                    PostgresSectionIds.VITAL_SIGNS,
                    "High transaction rollback ratio",
                    "CORRECTNESS",
                    "MEDIUM",
                    "A large share of transactions ends in a rollback rather than a commit.",
                    "Correlate with the Exceptions and SQL Trace panels: rollbacks usually mean failing "
                            + "statements, constraint violations, or a retry loop rather than deliberate aborts.",
                    STATS_CAVEAT + " Applications that use rollback deliberately (tests, dry runs) are expected "
                            + "to read high here. Nothing is reported below " + MIN_COMPLETED_TRANSACTIONS
                            + " completed transactions, because a ratio over a handful of them means nothing.",
                    "https://www.postgresql.org/docs/current/monitoring-stats.html",
                    data -> {
                        PostgresVitalSignsDto vitals = data.vitalSigns();
                        if (vitals == null || vitals.rollbackRatio() == null) {
                            return null;
                        }
                        Long completed = sum(vitals.transactionsCommitted(), vitals.transactionsRolledBack());
                        if (completed == null || completed < MIN_COMPLETED_TRANSACTIONS) {
                            return null;
                        }
                        if (vitals.rollbackRatio() <= HIGH_ROLLBACK_RATIO) {
                            return null;
                        }
                        return PostgresRuleMatch.of(PostgresFormat.percent(vitals.rollbackRatio())
                                + " of completed transactions rolled back ("
                                + PostgresFormat.count(vitals.transactionsRolledBack()) + " of "
                                + PostgresFormat.count(
                                        sum(vitals.transactionsCommitted(), vitals.transactionsRolledBack()))
                                + ").");
                    }),
            rule(
                    "PG-VITALS-003",
                    PostgresSectionIds.VITAL_SIGNS,
                    "Connection usage close to max_connections",
                    "CAPACITY",
                    "HIGH",
                    "The server is running close to its hard ceiling on concurrent backends.",
                    "Reduce pool sizes across all clients, or put a connection pooler in front of the server. "
                            + "Raising max_connections trades one limit for memory pressure.",
                    "Client backends are counted across the whole server, because max_connections is a "
                            + "cluster-wide ceiling; another database, another application or a leftover session "
                            + "can be the cause.",
                    "https://www.postgresql.org/docs/current/runtime-config-connection.html",
                    data -> {
                        PostgresVitalSignsDto vitals = data.vitalSigns();
                        if (vitals == null || vitals.connectionUsageRatio() == null) {
                            return null;
                        }
                        if (vitals.connectionUsageRatio() < HIGH_CONNECTION_USAGE) {
                            return null;
                        }
                        return PostgresRuleMatch.of(vitals.connections() + " of " + vitals.maxConnections()
                                + " connections are in use (" + PostgresFormat.percent(vitals.connectionUsageRatio())
                                + ").");
                    }),
            rule(
                    "PG-VITALS-004",
                    PostgresSectionIds.VITAL_SIGNS,
                    "Sessions idle in transaction",
                    "CONCURRENCY",
                    "MEDIUM",
                    "A backend is holding an open transaction while doing nothing, which keeps its locks and "
                            + "blocks vacuum from reclaiming rows newer than its snapshot.",
                    "Find the owning code path and commit or roll back before doing non-database work. "
                            + "idle_in_transaction_session_timeout bounds the damage.",
                    "This is a point-in-time sample taken during the read; a short-lived idle transaction can be "
                            + "missed, and a debugger session counts too. The check is skipped entirely unless the "
                            + "BootUI role can see the state of other backends, which requires pg_monitor.",
                    "https://www.postgresql.org/docs/current/monitoring-stats.html",
                    data -> {
                        PostgresVitalSignsDto vitals = data.vitalSigns();
                        if (vitals == null || vitals.idleInTransactionSessions() == null) {
                            return null;
                        }
                        if (vitals.idleInTransactionSessions() <= 0) {
                            return null;
                        }
                        return PostgresRuleMatch.of(vitals.idleInTransactionSessions()
                                + " session(s) are idle in transaction; the oldest running transaction is "
                                + PostgresFormat.seconds(vitals.longestTransactionSeconds()) + " old.");
                    }),
            rule(
                    "PG-VITALS-005",
                    PostgresSectionIds.VITAL_SIGNS,
                    "Sessions waiting on locks",
                    "CONCURRENCY",
                    "HIGH",
                    "At least one backend is blocked waiting for a lock another backend holds.",
                    "Identify the blocking transaction and shorten it. Long transactions and idle-in-transaction "
                            + "sessions are the usual cause.",
                    "This is a point-in-time sample; a lock wait that resolves between reads is not reported, and "
                            + "a brief wait is normal under write contention. The check is skipped entirely unless "
                            + "the BootUI role can see the state of other backends, which requires pg_monitor.",
                    "https://www.postgresql.org/docs/current/explicit-locking.html",
                    data -> {
                        PostgresVitalSignsDto vitals = data.vitalSigns();
                        if (vitals == null || vitals.blockedSessions() == null || vitals.blockedSessions() <= 0) {
                            return null;
                        }
                        return PostgresRuleMatch.of(vitals.blockedSessions() + " session(s) are waiting on a lock.");
                    }),
            rule(
                    "PG-VITALS-006",
                    PostgresSectionIds.VITAL_SIGNS,
                    "Transaction id age approaching wraparound",
                    "AVAILABILITY",
                    "CRITICAL",
                    "The oldest unfrozen transaction id in this database is a significant share of the age at "
                            + "which PostgreSQL forces an anti-wraparound vacuum, and eventually refuses writes.",
                    "Let autovacuum finish, or run VACUUM (FREEZE) on the oldest relations. Check for a "
                            + "long-running transaction, an abandoned replication slot, or a stale prepared "
                            + "transaction holding the horizon back.",
                    "The ratio is measured against autovacuum_freeze_max_age, the point at which an "
                            + "anti-wraparound vacuum is forced — not against the absolute two-billion shutdown "
                            + "limit, which is much further away.",
                    "https://www.postgresql.org/docs/current/routine-vacuuming.html",
                    data -> {
                        PostgresVitalSignsDto vitals = data.vitalSigns();
                        if (vitals == null || vitals.wraparoundUsageRatio() == null) {
                            return null;
                        }
                        if (vitals.wraparoundUsageRatio() < WRAPAROUND_WARNING_RATIO) {
                            return null;
                        }
                        return PostgresRuleMatch.of("Transaction id age is "
                                + PostgresFormat.count(vitals.transactionIdAge())
                                + " of autovacuum_freeze_max_age " + PostgresFormat.count(vitals.wraparoundLimit())
                                + " (" + PostgresFormat.percent(vitals.wraparoundUsageRatio()) + ").");
                    }),
            rule(
                    "PG-VITALS-007",
                    PostgresSectionIds.VITAL_SIGNS,
                    "Deadlocks recorded",
                    "CONCURRENCY",
                    "MEDIUM",
                    "PostgreSQL has had to break at least one deadlock in this database by aborting a transaction.",
                    "Make concurrent transactions take the same locks in the same order, and keep write "
                            + "transactions short.",
                    STATS_CAVEAT + " A single deadlock from a load test months ago still counts until the "
                            + "statistics are reset.",
                    "https://www.postgresql.org/docs/current/explicit-locking.html",
                    data -> {
                        PostgresVitalSignsDto vitals = data.vitalSigns();
                        if (vitals == null || vitals.deadlocks() == null || vitals.deadlocks() <= 0) {
                            return null;
                        }
                        return PostgresRuleMatch.of(
                                vitals.deadlocks() + " deadlock(s) recorded since the last " + "statistics reset.");
                    }),
            rule(
                    "PG-VITALS-008",
                    PostgresSectionIds.VITAL_SIGNS,
                    "Queries spilling to temporary files",
                    "PERFORMANCE",
                    "LOW",
                    "Sorts or hashes have exceeded work_mem and been written to disk.",
                    "Look at the largest statements first; raising work_mem globally multiplies per-operation, "
                            + "so a targeted query fix is usually cheaper.",
                    STATS_CAVEAT + " A one-off maintenance query can account for the whole total.",
                    "https://www.postgresql.org/docs/current/runtime-config-resource.html",
                    data -> {
                        PostgresVitalSignsDto vitals = data.vitalSigns();
                        if (vitals == null || vitals.temporaryFiles() == null || vitals.temporaryFiles() <= 0) {
                            return null;
                        }
                        return PostgresRuleMatch.of(vitals.temporaryFiles() + " temporary file(s) totalling "
                                + PostgresFormat.bytes(vitals.temporaryBytes()) + " were written.");
                    }),
            rule(
                    "PG-STATEMENTS-001",
                    PostgresSectionIds.STATEMENTS,
                    "Statements with a high mean execution time",
                    "PERFORMANCE",
                    "MEDIUM",
                    "At least one frequently executed statement averages more than " + (long) SLOW_STATEMENT_MEAN_MILLIS
                            + " ms per call.",
                    "Run EXPLAIN (ANALYZE, BUFFERS) on the statement before changing anything; the fix is often "
                            + "an index or a narrower result, not a server setting.",
                    "Statement text is normalized by PostgreSQL and the timings are averages: a bimodal "
                            + "statement (cached versus cold) hides behind its mean.",
                    "https://www.postgresql.org/docs/current/pgstatstatements.html",
                    data -> {
                        List<String> samples = new ArrayList<>();
                        for (PostgresStatementDto statement : data.statements()) {
                            if (statement.meanTimeMs() == null || statement.calls() == null) {
                                continue;
                            }
                            if (statement.meanTimeMs() >= SLOW_STATEMENT_MEAN_MILLIS
                                    && statement.calls() >= SLOW_STATEMENT_MIN_CALLS) {
                                samples.add(PostgresFormat.millis(statement.meanTimeMs()) + " mean over "
                                        + statement.calls() + " calls: " + statement.query());
                            }
                        }
                        if (samples.isEmpty()) {
                            return null;
                        }
                        return PostgresRuleMatch.of(
                                samples.size() + " ranked statement(s) average at least "
                                        + (long) SLOW_STATEMENT_MEAN_MILLIS + " ms per call.",
                                samples.subList(0, Math.min(MAX_SAMPLES, samples.size())));
                    }),
            rule(
                    "PG-STATEMENTS-002",
                    PostgresSectionIds.STATEMENTS,
                    "One statement dominates total execution time",
                    "PERFORMANCE",
                    "LOW",
                    "A single normalized statement accounts for most of the execution time across the ranked "
                            + "statements.",
                    "Start tuning here: the dominant statement is where a fix has the most effect, even when its "
                            + "mean time looks acceptable.",
                    "The share is computed over the statements BootUI retained, not over the server's entire "
                            + "workload. It is reported only once at least " + MIN_RANKED_STATEMENTS
                            + " statements were ranked and they account for at least "
                            + (long) MIN_RANKED_TOTAL_MILLIS + " ms in total, because with two near-idle "
                            + "statements one of them necessarily dominates.",
                    "https://www.postgresql.org/docs/current/pgstatstatements.html",
                    data -> {
                        double total = 0;
                        PostgresStatementDto top = null;
                        for (PostgresStatementDto statement : data.statements()) {
                            if (statement.totalTimeMs() == null) {
                                continue;
                            }
                            total += statement.totalTimeMs();
                            if (top == null || statement.totalTimeMs() > top.totalTimeMs()) {
                                top = statement;
                            }
                        }
                        if (top == null
                                || total < MIN_RANKED_TOTAL_MILLIS
                                || data.statements().size() < MIN_RANKED_STATEMENTS) {
                            return null;
                        }
                        double share = top.totalTimeMs() / total;
                        if (share < DOMINANT_STATEMENT_SHARE) {
                            return null;
                        }
                        return PostgresRuleMatch.of(
                                "One statement accounts for " + PostgresFormat.percent(share)
                                        + " of the ranked total execution time.",
                                List.of(PostgresFormat.millis(top.totalTimeMs()) + " total over " + top.calls()
                                        + " calls: " + top.query()));
                    }),
            rule(
                    "PG-INDEX-001",
                    PostgresSectionIds.INDEXES,
                    "Indexes never scanned on this node",
                    "MAINTENANCE",
                    "LOW",
                    "An index larger than " + PostgresFormat.bytes(UNUSED_INDEX_MIN_BYTES)
                            + " has never been used by a scan on this server, while still costing write time "
                            + "and disk space.",
                    "Confirm on every node before dropping anything, then drop the index concurrently if it is "
                            + "genuinely unused.",
                    "Scan counts are per node and reset with the statistics. A primary can show zero scans for an "
                            + "index a read replica depends on, and an index created since the last reset has had "
                            + "no chance to be used. Primary-key, constraint-backed and unique indexes are all "
                            + "excluded, because they enforce correctness whether or not a query ever scans them.",
                    "https://www.postgresql.org/docs/current/monitoring-stats.html",
                    data -> {
                        List<String> samples = new ArrayList<>();
                        int matched = 0;
                        for (PostgresIndexDto index : data.indexes()) {
                            if (index.primaryKey() || index.constraintBacked() || index.unique()) {
                                continue;
                            }
                            if (index.scans() == null || index.scans() > 0) {
                                continue;
                            }
                            if (index.sizeBytes() == null || index.sizeBytes() < UNUSED_INDEX_MIN_BYTES) {
                                continue;
                            }
                            matched++;
                            if (samples.size() < MAX_SAMPLES) {
                                samples.add(PostgresFormat.relation(index.schema(), index.table()) + " index "
                                        + index.index() + " (" + PostgresFormat.bytes(index.sizeBytes())
                                        + ", 0 scans)");
                            }
                        }
                        if (matched == 0) {
                            return null;
                        }
                        return PostgresRuleMatch.of(
                                matched + " index(es) larger than " + PostgresFormat.bytes(UNUSED_INDEX_MIN_BYTES)
                                        + " have never been scanned on this node.",
                                samples);
                    }),
            rule(
                    "PG-TABLE-001",
                    PostgresSectionIds.TABLES,
                    "Large relations scanned mostly sequentially",
                    "PERFORMANCE",
                    "MEDIUM",
                    "Most scans started on a relation larger than " + PostgresFormat.bytes(SEQUENTIAL_SCAN_MIN_BYTES)
                            + " were sequential rather than index scans.",
                    "Check the predicates the application uses against this table and index the selective ones. "
                            + "Confirm with EXPLAIN before adding an index.",
                    "The ratio counts scan invocations (seq_scan against idx_scan), not rows or blocks read, so "
                            + "one enormous sequential scan and one trivial one count the same. Sequential "
                            + "scanning is also correct for a small or fully cached table, and for a query that "
                            + "genuinely reads most rows; the counters cannot tell the two apart.",
                    "https://www.postgresql.org/docs/current/indexes.html",
                    data -> {
                        List<String> samples = new ArrayList<>();
                        int matched = 0;
                        for (PostgresTableDto table : data.tables()) {
                            if (table.totalSizeBytes() == null || table.totalSizeBytes() < SEQUENTIAL_SCAN_MIN_BYTES) {
                                continue;
                            }
                            if (table.sequentialScanRatio() == null
                                    || table.sequentialScanRatio() < SEQUENTIAL_SCAN_RATIO) {
                                continue;
                            }
                            if (table.sequentialScans() == null
                                    || table.sequentialScans() < SEQUENTIAL_SCAN_MIN_SCANS) {
                                continue;
                            }
                            matched++;
                            if (samples.size() < MAX_SAMPLES) {
                                samples.add(PostgresFormat.relation(table.schema(), table.table()) + " ("
                                        + PostgresFormat.bytes(table.totalSizeBytes()) + ", "
                                        + PostgresFormat.percent(table.sequentialScanRatio())
                                        + " of scan invocations sequential, over " + table.sequentialScans()
                                        + " sequential scans)");
                            }
                        }
                        if (matched == 0) {
                            return null;
                        }
                        return PostgresRuleMatch.of(
                                matched + " large relation(s) are scanned mostly sequentially.", samples);
                    }),
            rule(
                    "PG-VACUUM-001",
                    PostgresSectionIds.VACUUM,
                    "Tables past their autovacuum threshold",
                    "MAINTENANCE",
                    "MEDIUM",
                    "A relation holds more dead tuples than the server's own autovacuum threshold, so autovacuum "
                            + "is due and has not caught up.",
                    "Check whether autovacuum is keeping up at all; a long-running transaction or an abandoned "
                            + "replication slot can hold the cleanup horizon back.",
                    PostgresVacuumCollector.RELOPTIONS_LIMITATION
                            + " Autovacuum may also be running right now, in which case the backlog is transient.",
                    "https://www.postgresql.org/docs/current/routine-vacuuming.html",
                    data -> {
                        List<String> samples = new ArrayList<>();
                        int matched = 0;
                        for (PostgresVacuumDto table : data.vacuum()) {
                            if (!table.vacuumDue()
                                    || table.deadTuples() == null
                                    || table.deadTuples() < DEAD_TUPLE_MIN_ROWS) {
                                continue;
                            }
                            matched++;
                            if (samples.size() < MAX_SAMPLES) {
                                samples.add(PostgresFormat.relation(table.schema(), table.table()) + " has "
                                        + table.deadTuples() + " dead tuples against a threshold of "
                                        + table.vacuumThreshold());
                            }
                        }
                        if (matched == 0) {
                            return null;
                        }
                        return PostgresRuleMatch.of(
                                matched + " relation(s) are past their autovacuum threshold.", samples);
                    }),
            rule(
                    "PG-VACUUM-002",
                    PostgresSectionIds.VACUUM,
                    "High dead-tuple ratio",
                    "MAINTENANCE",
                    "MEDIUM",
                    "A significant share of a relation's rows are dead, which inflates its size and slows every "
                            + "scan over it.",
                    "Let autovacuum catch up, then consider VACUUM (FULL) or pg_repack during a maintenance "
                            + "window if the bloat persists.",
                    "Tuple counts in pg_stat_user_tables are estimates maintained by the statistics collector, "
                            + "not exact row counts.",
                    "https://www.postgresql.org/docs/current/routine-vacuuming.html",
                    data -> {
                        List<String> samples = new ArrayList<>();
                        int matched = 0;
                        for (PostgresVacuumDto table : data.vacuum()) {
                            if (table.deadTupleRatio() == null
                                    || table.deadTuples() == null
                                    || table.deadTuples() < DEAD_TUPLE_MIN_ROWS
                                    || table.deadTupleRatio() < DEAD_TUPLE_RATIO) {
                                continue;
                            }
                            matched++;
                            if (samples.size() < MAX_SAMPLES) {
                                samples.add(PostgresFormat.relation(table.schema(), table.table()) + " is "
                                        + PostgresFormat.percent(table.deadTupleRatio()) + " dead ("
                                        + table.deadTuples() + " dead, " + table.liveTuples() + " live)");
                            }
                        }
                        if (matched == 0) {
                            return null;
                        }
                        return PostgresRuleMatch.of(
                                matched + " relation(s) are at least " + PostgresFormat.percent(DEAD_TUPLE_RATIO)
                                        + " dead tuples.",
                                samples);
                    }),
            rule(
                    "PG-REPLICATION-001",
                    PostgresSectionIds.REPLICATION,
                    "Requested checkpoints outnumber timed checkpoints",
                    "PERFORMANCE",
                    "MEDIUM",
                    "More checkpoints were requested than were triggered by checkpoint_timeout. A requested "
                            + "checkpoint spreads far less of the write cost over time than a timed one.",
                    "The usual cause is WAL reaching max_wal_size under the normal write rate, which raising "
                            + "max_wal_size fixes. Rule out the other causes first: an explicit CHECKPOINT, a "
                            + "base backup, and a clean shutdown are all counted here too.",
                    STATS_CAVEAT + " A bulk load or a restore can account for the whole imbalance. PostgreSQL "
                            + "counts requested checkpoints without recording why each was requested, so this "
                            + "reports the imbalance and not its cause.",
                    "https://www.postgresql.org/docs/current/wal-configuration.html",
                    data -> {
                        PostgresReplicationDto replication = data.replication();
                        if (replication == null
                                || replication.checkpointsRequested() == null
                                || replication.checkpointsTimed() == null) {
                            return null;
                        }
                        if (replication.checkpointsRequested() < FORCED_CHECKPOINT_MIN
                                || replication.checkpointsRequested() <= replication.checkpointsTimed()) {
                            return null;
                        }
                        return PostgresRuleMatch.of(replication.checkpointsRequested()
                                + " requested checkpoint(s) against " + replication.checkpointsTimed()
                                + " triggered by checkpoint_timeout.");
                    }),
            rule(
                    "PG-REPLICATION-002",
                    PostgresSectionIds.REPLICATION,
                    "Inactive replication slot",
                    "AVAILABILITY",
                    "HIGH",
                    "A replication slot exists with no client attached. PostgreSQL retains the WAL an inactive "
                            + "slot has not consumed, so the disk can fill; a logical slot additionally holds the "
                            + "catalog cleanup horizon back.",
                    "Reattach the consumer or drop the slot. An abandoned slot is one of the few local "
                            + "misconfigurations that can take a server down outright.",
                    "A slot can be legitimately inactive for a moment while its consumer reconnects. This check "
                            + "reads only the active flag: it does not distinguish physical from logical slots, "
                            + "and a slot whose WAL was already dropped under max_slot_wal_keep_size no longer "
                            + "retains anything. Check pg_replication_slots.wal_status and slot_type before "
                            + "concluding how much is at risk.",
                    "https://www.postgresql.org/docs/current/warm-standby.html",
                    data -> {
                        PostgresReplicationDto replication = data.replication();
                        if (replication == null
                                || replication.inactiveReplicationSlots() == null
                                || replication.inactiveReplicationSlots() <= 0) {
                            return null;
                        }
                        return PostgresRuleMatch.of(replication.inactiveReplicationSlots() + " of "
                                + replication.replicationSlots() + " replication slot(s) have no active consumer.");
                    }),
            rule(
                    "PG-SETTINGS-001",
                    PostgresSectionIds.SETTINGS,
                    "Autovacuum is disabled",
                    "MAINTENANCE",
                    "HIGH",
                    "Autovacuum is off cluster-wide, so routine vacuum and analyze never run: dead tuples are "
                            + "not reclaimed and planner statistics go stale.",
                    "Turn autovacuum back on. Tuning its thresholds is almost always better than disabling it.",
                    "A cluster that is genuinely maintained by an external scheduled VACUUM is not broken, only "
                            + "unusual. PostgreSQL still starts anti-wraparound vacuum workers even with autovacuum "
                            + "off, so this is not an imminent wraparound, only the loss of routine maintenance.",
                    "https://www.postgresql.org/docs/current/routine-vacuuming.html",
                    data -> {
                        if (!"off".equalsIgnoreCase(String.valueOf(data.setting("autovacuum")))) {
                            return null;
                        }
                        return PostgresRuleMatch.of("autovacuum is set to off.");
                    }),
            rule(
                    "PG-SETTINGS-002",
                    PostgresSectionIds.SETTINGS,
                    "Crash-safety setting disabled",
                    "DURABILITY",
                    "HIGH",
                    "fsync or full_page_writes is off. The server is faster and will not survive an operating "
                            + "system crash or power loss with its data intact.",
                    "This is acceptable for a throwaway local database and for nothing else. Turn both back on "
                            + "anywhere the data matters.",
                    "Development containers frequently ship with these disabled on purpose.",
                    "https://www.postgresql.org/docs/current/runtime-config-wal.html",
                    data -> {
                        List<String> disabled = new ArrayList<>();
                        if ("off".equalsIgnoreCase(String.valueOf(data.setting("fsync")))) {
                            disabled.add("fsync = off");
                        }
                        if ("off".equalsIgnoreCase(String.valueOf(data.setting("full_page_writes")))) {
                            disabled.add("full_page_writes = off");
                        }
                        if (disabled.isEmpty()) {
                            return null;
                        }
                        return PostgresRuleMatch.of(String.join(", ", disabled) + ".", disabled);
                    }),
            rule(
                    "PG-SETTINGS-003",
                    PostgresSectionIds.SETTINGS,
                    "Statement I/O timing is not measured",
                    "OBSERVABILITY",
                    "INFO",
                    "track_io_timing is off, so PostgreSQL records no per-statement read and write time at all.",
                    "Turn track_io_timing on when investigating I/O-bound statements; measure its overhead on "
                            + "the host first.",
                    "This reports missing measurement, not a performance problem.",
                    "https://www.postgresql.org/docs/current/runtime-config-statistics.html",
                    data -> {
                        if (!"off".equalsIgnoreCase(String.valueOf(data.setting("track_io_timing")))) {
                            return null;
                        }
                        return PostgresRuleMatch.of("track_io_timing is set to off.");
                    }));

    private PostgresRuleRegistry() {}

    static List<PostgresRule> rules() {
        return RULES;
    }

    private static Long sum(Long first, Long second) {
        if (first == null && second == null) {
            return null;
        }
        return (first == null ? 0L : first) + (second == null ? 0L : second);
    }

    private static PostgresRule rule(
            String id,
            String sectionId,
            String title,
            String category,
            String severity,
            String description,
            String recommendation,
            String caveat,
            String learnMoreUrl,
            Function<PostgresDatabaseData, PostgresRuleMatch> evaluator) {
        PostgresRuleDefinition definition = new PostgresRuleDefinition(
                id, sectionId, title, category, severity, description, recommendation, caveat, learnMoreUrl);
        return new PostgresRule() {
            @Override
            public PostgresRuleDefinition definition() {
                return definition;
            }

            @Override
            public PostgresRuleMatch evaluate(PostgresDatabaseData data) {
                return evaluator.apply(data);
            }
        };
    }
}
