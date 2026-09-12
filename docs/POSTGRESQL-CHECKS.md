# PostgreSQL checks

The PostgreSQL panel performs a bounded, user-triggered read of PostgreSQL's own `pg_stat_*` and `pg_catalog` views for
the application's discovered PostgreSQL datasource(s). It is a point-in-time developer diagnostic, not a monitoring
platform, capacity planner, or automatic tuning system. BootUI is local-only and must never be enabled in production.

These checks are fixed review prompts from the framework-neutral engine. They describe evidence from PostgreSQL's
cumulative statistics and current catalog state; they do not prove root cause or that a change is safe.

## Availability and bounds

- Opening the panel and `GET /bootui/api/postgresql` return the cached report only. Nothing touches the database on page
  load. The explicit `POST /bootui/api/postgresql/read` action, MCP `postgresql_read` tool, or CLI command starts a read.
- Each datasource read runs in one read-only transaction. BootUI sets the JDBC connection read-only and pins
  `set transaction read only`, `statement_timeout = 5000ms`, `lock_timeout = 2000ms`, and
  `idle_in_transaction_session_timeout = 15000ms` for the session.
- Every statistics query runs inside its own savepoint. PostgreSQL aborts the whole transaction on any statement error,
  so without that isolation one permission error would make every later section report "current transaction is aborted"
  instead of its own content.
- Column and view availability is read from the catalog, never inferred from the server version: the
  `pg_stat_statements` timing columns follow the extension version, and `pg_stat_checkpointer` may be absent on a server
  new enough to have it only in theory.
- The cooperative read budget is 15 seconds. It is checked between collectors; JDBC connection acquisition and driver
  work may still take as long as the driver/server allow. A datasource the budget never reached is reported as an
  explicit limitation, not silently omitted, so an exhausted budget cannot produce a clean-looking report. A datasource
  that could not be discovered at all is reported the same way: the read is then `PARTIAL`, never `READ`.
- Row and text bounds are fixed: 25 statements, 50 indexes, 25 largest relations, 25 autovacuum rows, 10 replicas,
  40 notable settings, and 400 characters per statement text. List reads fetch one row past the limit so truncation is
  visible instead of silently treated as complete.
- Each section reports `AVAILABLE`, `SKIPPED`, or `FAILED`. A skipped or failed section is a limitation, not a pass, and
  does not produce findings for rules that depend on that section. A section that was read but could not be read in full
  stays `AVAILABLE` and carries a reason; the UI shows it as partially read rather than clean.
- Session pinning is itself savepoint-isolated. A role that may not set one of these parameters would otherwise abort the
  transaction before any savepoint existed, and an aborted PostgreSQL transaction refuses even `SAVEPOINT`; each failed
  pin is rolled back and reported as a warning instead. Because the statement, lock and idle bounds are what make this
  read safe against a live server, a database whose session could not be pinned in full is reported as `PARTIAL`, never
  as a clean `SCANNED` read.
- Values follow the global exposure policy. Under `MASKED` or `METADATA_ONLY`, statement text has its string literals and
  dollar-quoted bodies (`$$ ... $$` and `$tag$ ... $tag$`, how `CREATE FUNCTION` and `DO` blocks reach
  `pg_stat_statements`) replaced, and `METADATA_ONLY` also masks replica `client_addr` and every `pg_settings` value —
  the setting names, units and sources are still shown, and the raw values are still used internally to judge autovacuum
  against the server's real configuration. Error text is redacted before it becomes a diagnostic.
- Use a read-only application role with PostgreSQL's built-in `pg_monitor` role when possible. Without it, some
  replication and statistics views fail outright, and `pg_stat_activity` silently nulls the state of backends the role
  does not own. BootUI detects that case and reports the whole session breakdown as unknown rather than counting hidden
  backends as idle and clean; the section is then `AVAILABLE` with a reason explaining what is missing.
- The statement-ranking section requires `pg_stat_statements` to be loaded in `shared_preload_libraries` and installed
  with `CREATE EXTENSION pg_stat_statements;`. The view is located through `pg_extension`, so an extension installed
  into a schema outside `search_path` is still read.
- PostgreSQL statistics are cumulative since the last statistics reset and cover every client of the database, not only
  this application or this JVM.
- Unused-index scan counts are per node. A primary can show zero scans for an index a replica still uses, and statistics
  resets or recently-created indexes make "never scanned" weak evidence.
- BootUI keeps only the previous read in memory to show simple deltas. It writes no PostgreSQL baseline to disk.

## Vital signs

### PG-VITALS-001 — Low buffer cache hit ratio

- **Severity:** MEDIUM
- **What it measures:** `pg_stat_database.blks_hit` versus `blks_read` for the current database. Below the threshold
  means an unusually large share of reads went to disk, not that most reads did.
- **Threshold:** `LOW_CACHE_HIT_RATIO = 0.90`; findings appear below a 90% hit ratio, and only once the database has
  completed at least `MIN_COMPLETED_TRANSACTIONS = 1000` transactions.
- **What to do:** Check `shared_buffers` against the working set, and confirm the host has enough free memory for the OS
  page cache before changing settings.
- **Caveat:** Cumulative statistics are counted since the last statistics reset and cover every client, not only this
  application. A recent restart or a working set genuinely larger than memory can read low without being misconfigured.
- **Learn more:** <https://www.postgresql.org/docs/current/runtime-config-resource.html>

### PG-VITALS-002 — High transaction rollback ratio

- **Severity:** MEDIUM
- **What it measures:** rolled-back transactions versus committed plus rolled-back transactions in `pg_stat_database`.
- **Threshold:** `HIGH_ROLLBACK_RATIO = 0.05`; findings appear above 5% rollbacks, and only once the database has
  completed at least `MIN_COMPLETED_TRANSACTIONS = 1000` transactions.
- **What to do:** Correlate with the Exceptions and SQL Trace panels; rollbacks usually mean failing statements,
  constraint violations, or retry loops rather than deliberate aborts.
- **Caveat:** Cumulative statistics cover every client. Test suites and dry-run workflows may deliberately roll back.
- **Learn more:** <https://www.postgresql.org/docs/current/monitoring-stats.html>

### PG-VITALS-003 — Connection usage close to max_connections

- **Severity:** HIGH
- **What it measures:** client backends in `pg_stat_activity` across the whole server versus `max_connections`. The
  count is server-wide on purpose, because `max_connections` is a cluster-wide ceiling shared by every database.
- **Threshold:** `HIGH_CONNECTION_USAGE = 0.80`; findings appear at or above 80% of `max_connections`.
- **What to do:** Reduce pool sizes across all clients or use a connection pooler. Raising `max_connections` trades one
  limit for memory pressure.
- **Caveat:** Client backends are counted across the whole server, so another database, another application or a
  leftover local session can be the cause.
- **Learn more:** <https://www.postgresql.org/docs/current/runtime-config-connection.html>

### PG-VITALS-004 — Sessions idle in transaction

- **Severity:** MEDIUM
- **What it measures:** client backends whose `pg_stat_activity.state` is `idle in transaction`.
- **Threshold:** any idle-in-transaction session (`> 0`).
- **What to do:** Find the owning code path and commit or roll back before doing non-database work. Configure
  `idle_in_transaction_session_timeout` to bound the damage.
- **Caveat:** This is a point-in-time sample. A short-lived idle transaction can be missed, and a debugger session
  counts. The check is skipped entirely unless the BootUI role can see the state of other backends, which requires
  `pg_monitor`.
- **Learn more:** <https://www.postgresql.org/docs/current/monitoring-stats.html>

### PG-VITALS-005 — Sessions waiting on locks

- **Severity:** HIGH
- **What it measures:** sessions in `pg_stat_activity` with `wait_event_type = 'Lock'`.
- **Threshold:** any blocked session (`> 0`).
- **What to do:** Identify the blocking transaction and shorten it. Long transactions and idle-in-transaction sessions
  are the usual cause.
- **Caveat:** This is a point-in-time sample; a lock wait that resolves between reads is not reported, and a brief wait
  is normal under write contention. The check is skipped entirely unless the BootUI role can see the state of other
  backends, which requires `pg_monitor`.
- **Learn more:** <https://www.postgresql.org/docs/current/explicit-locking.html>

### PG-VITALS-006 — Transaction id age approaching wraparound

- **Severity:** CRITICAL
- **What it measures:** `age(datfrozenxid)` for the current database versus `autovacuum_freeze_max_age`.
- **Threshold:** `WRAPAROUND_WARNING_RATIO = 0.50`; findings appear at or above 50% of the forced autovacuum age.
- **What to do:** Let autovacuum finish, or run `VACUUM (FREEZE)` on the oldest relations. Check long-running
  transactions, abandoned replication slots, and stale prepared transactions.
- **Caveat:** The ratio is against `autovacuum_freeze_max_age`, where anti-wraparound vacuum is forced, not the later
  two-billion transaction shutdown limit.
- **Learn more:** <https://www.postgresql.org/docs/current/routine-vacuuming.html>

### PG-VITALS-007 — Deadlocks recorded

- **Severity:** MEDIUM
- **What it measures:** `pg_stat_database.deadlocks` for the current database.
- **Threshold:** any recorded deadlock (`> 0`).
- **What to do:** Make concurrent transactions take locks in the same order and keep write transactions short.
- **Caveat:** Cumulative statistics cover every client. A single deadlock from an old load test still counts until
  statistics are reset.
- **Learn more:** <https://www.postgresql.org/docs/current/explicit-locking.html>

### PG-VITALS-008 — Queries writing temporary files

- **Severity:** LOW
- **What it measures:** `pg_stat_database.temp_files` and `temp_bytes`.
- **Threshold:** any temporary file (`> 0`).
- **What to do:** Look at the largest statements first. Raising `work_mem` globally multiplies per operation, so a
  targeted query fix is usually cheaper.
- **Caveat:** Cumulative statistics cover every client. The counter does not say which operation wrote the files — it
  counts every query temporary file, not only a sort or hash spilling past `work_mem` — and a one-off maintenance query
  can account for the whole total.
- **Learn more:** <https://www.postgresql.org/docs/current/runtime-config-resource.html>

## Statements

### PG-STATEMENTS-001 — Statements with a high mean execution time

- **Severity:** MEDIUM
- **What it measures:** ranked `pg_stat_statements` rows for the current database.
- **Threshold:** `SLOW_STATEMENT_MEAN_MILLIS = 100` and `SLOW_STATEMENT_MIN_CALLS = 10`; findings appear when a retained
  statement averages at least 100 ms over at least 10 calls.
- **What to do:** Run `EXPLAIN (ANALYZE, BUFFERS)` before changing anything. The fix is often an index or narrower result,
  not a server setting.
- **Caveat:** Statement text is normalized by PostgreSQL and timings are averages; a bimodal cached-versus-cold statement
  hides behind its mean.
- **Learn more:** <https://www.postgresql.org/docs/current/pgstatstatements.html>

### PG-STATEMENTS-002 — One statement dominates total execution time

- **Severity:** LOW
- **What it measures:** each retained `pg_stat_statements` row's total execution time as a share of the retained ranked
  total.
- **Threshold:** `DOMINANT_STATEMENT_SHARE = 0.50`; findings appear when one retained statement accounts for at least half
  of retained statement time, at least `MIN_RANKED_STATEMENTS = 5` statements carried a timing, and they total at least
  `MIN_RANKED_TOTAL_MILLIS = 1000` ms. A row without a timing contributes nothing to the share and is not counted
  towards the minimum. With fewer statements a share above 50% is arithmetic rather than evidence.
- **What to do:** Start tuning here: the dominant statement is where a fix has the most effect, even when its mean time
  looks acceptable.
- **Caveat:** The share is computed over the statements BootUI retained, not the server's entire workload.
- **Learn more:** <https://www.postgresql.org/docs/current/pgstatstatements.html>

## Indexes

### PG-INDEX-001 — Indexes never scanned on this node

- **Severity:** LOW
- **What it measures:** `pg_stat_user_indexes.idx_scan`, index size, and catalog flags for non-primary, non-unique,
  non-constraint-backed indexes.
- **Threshold:** `UNUSED_INDEX_MIN_BYTES = 1048576`; findings appear for eligible indexes at least 1 MB with zero scans.
- **What to do:** Confirm on every node before dropping anything, then drop the index concurrently if it is genuinely
  unused.
- **Caveat:** Scan counts are per node and reset with statistics. A primary can show zero scans for an index a read
  replica depends on, and a new index may not have had a chance to be used.
- **Learn more:** <https://www.postgresql.org/docs/current/monitoring-stats.html>

## Tables

### PG-TABLE-001 — Large relations scanned mostly sequentially

- **Severity:** MEDIUM
- **What it measures:** relation size and the share of scan invocations that were sequential (`seq_scan` against
  `idx_scan`) from `pg_stat_user_tables`.
- **Threshold:** `SEQUENTIAL_SCAN_MIN_BYTES = 52428800`, `SEQUENTIAL_SCAN_RATIO = 0.90`, and
  `SEQUENTIAL_SCAN_MIN_SCANS = 50`; findings appear for relations at least 50 MB where at least 90% of 50 or more scans
  are sequential.
- **What to do:** Check the predicates the application uses against the table and index the selective ones. Confirm with
  `EXPLAIN` before adding an index.
- **Caveat:** The ratio counts scan invocations, not rows or blocks read, so one enormous sequential scan and one
  trivial one count the same. Sequential scanning is correct for small or fully cached tables and for queries that genuinely read most
  rows; the counters cannot tell those cases apart.
- **Learn more:** <https://www.postgresql.org/docs/current/indexes.html>

## Vacuum

### PG-VACUUM-001 — Tables past their autovacuum threshold

- **Severity:** MEDIUM
- **What it measures:** estimated dead tuples from `pg_stat_user_tables` versus the autovacuum threshold computed from
  the cluster-wide `autovacuum_vacuum_threshold`, `autovacuum_vacuum_scale_factor` and, on PostgreSQL 18 and later,
  `autovacuum_vacuum_max_threshold`.
- **Threshold:** `DEAD_TUPLE_MIN_ROWS = 1000`; findings appear for vacuum-due relations with at least 1,000 dead tuples.
  "Due" floors `autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor * live tuples` so that the integer
  comparison matches PostgreSQL's own fractional one exactly, and caps it with `autovacuum_vacuum_max_threshold` when
  the server has that setting and it is not negative. A server without the setting, or with it disabled, has no cap.
- **What to do:** Check whether autovacuum is keeping up; a long-running transaction or abandoned replication slot can
  hold the cleanup horizon back.
- **Caveat:** Per-table `reloptions` overrides are not read, so "due" uses cluster-wide settings, and the threshold is
  computed from the `pg_stat_user_tables` live-tuple estimate while autovacuum itself uses `pg_class.reltuples`.
  Autovacuum may also be running right now, making the backlog transient.
- **Learn more:** <https://www.postgresql.org/docs/current/routine-vacuuming.html>

### PG-VACUUM-002 — High dead-tuple ratio

- **Severity:** MEDIUM
- **What it measures:** estimated live and dead tuples from `pg_stat_user_tables`.
- **Threshold:** `DEAD_TUPLE_MIN_ROWS = 1000` and `DEAD_TUPLE_RATIO = 0.20`; findings appear for relations with at least
  1,000 dead tuples and at least 20% dead rows.
- **What to do:** Let autovacuum catch up, then consider `VACUUM (FULL)` or `pg_repack` during a maintenance window if
  bloat persists.
- **Caveat:** Tuple counts in `pg_stat_user_tables` are estimates maintained by the statistics collector, not exact row
  counts.
- **Learn more:** <https://www.postgresql.org/docs/current/routine-vacuuming.html>

## Replication

### PG-REPLICATION-001 — Requested checkpoints outnumber timed checkpoints

- **Severity:** MEDIUM
- **What it measures:** requested checkpoints versus time-triggered checkpoints from `pg_stat_bgwriter` or, on
  PostgreSQL 17+, `pg_stat_checkpointer`.
- **Threshold:** `FORCED_CHECKPOINT_MIN = 5`; findings appear when at least five checkpoints were requested and
  requested checkpoints outnumber timed checkpoints.
- **What to do:** The usual cause is WAL reaching `max_wal_size`, which raising `max_wal_size` fixes. Rule out an
  explicit `CHECKPOINT`, a base backup and a clean shutdown first: PostgreSQL counts all of those as requested.
- **Caveat:** Cumulative statistics cover every client. A bulk load or restore can account for the whole imbalance.
  PostgreSQL counts requested checkpoints without recording why each was requested, so this reports the imbalance
  and not its cause.
- **Learn more:** <https://www.postgresql.org/docs/current/wal-configuration.html>

### PG-REPLICATION-002 — Inactive replication slot

- **Severity:** HIGH
- **What it measures:** inactive rows in `pg_replication_slots`.
- **Threshold:** any inactive replication slot (`> 0`).
- **What to do:** Reattach the consumer or drop the slot. An abandoned slot can fill disk and hold cleanup back.
- **Caveat:** A slot can be legitimately inactive for a moment while its consumer reconnects. Only the active flag is
  read: physical and logical slots are not distinguished, and a slot whose WAL was already dropped under
  `max_slot_wal_keep_size` no longer retains anything. Check `pg_replication_slots.wal_status` and `slot_type` before
  concluding how much is at risk.
- **Learn more:** <https://www.postgresql.org/docs/current/warm-standby.html>

## Settings

### PG-SETTINGS-001 — Autovacuum is disabled

- **Severity:** HIGH
- **What it measures:** the curated `pg_settings` row for `autovacuum`.
- **Threshold:** `autovacuum = off`.
- **What to do:** Turn autovacuum back on. Tuning its thresholds is almost always better than disabling it.
- **Caveat:** A cluster genuinely maintained by an external scheduled `VACUUM` is not broken, only unusual.
  PostgreSQL still starts anti-wraparound vacuum workers with `autovacuum = off`, so this reports the loss of
  routine maintenance, not an imminent wraparound.
- **Learn more:** <https://www.postgresql.org/docs/current/routine-vacuuming.html>

### PG-SETTINGS-002 — Crash-safety setting disabled

- **Severity:** HIGH
- **What it measures:** the curated `pg_settings` rows for `fsync` and `full_page_writes`.
- **Threshold:** either `fsync = off` or `full_page_writes = off`.
- **What to do:** This is acceptable for a throwaway local database and for nothing else. Turn both back on anywhere the
  data matters.
- **Caveat:** Development containers frequently ship with these disabled on purpose.
- **Learn more:** <https://www.postgresql.org/docs/current/runtime-config-wal.html>

### PG-SETTINGS-003 — Statement I/O timing is not measured

- **Severity:** INFO
- **What it measures:** the curated `pg_settings` row for `track_io_timing`.
- **Threshold:** `track_io_timing = off`.
- **What to do:** Turn `track_io_timing` on when investigating I/O-bound statements; measure its overhead on the host
  first.
- **Caveat:** This reports missing measurement, not a performance problem.
- **Learn more:** <https://www.postgresql.org/docs/current/runtime-config-statistics.html>
