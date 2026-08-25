# Database checks

The Database panel runs a fixed, on-demand ruleset against the physical schema of every discovered
application `DataSource` bean. It introspects tables, columns, primary keys, foreign keys, and indexes through plain
JDBC `DatabaseMetaData` — it never executes DDL and never queries application data.

One family, [Runtime SQL](#runtime-sql), is evaluated instead against the statements BootUI has *already* captured in
the SQL Trace retention window. It issues no query of its own, reads no application data, and reports only the shape of
a statement — never a captured literal value.

The checks are deterministic, low-false-positive structural checks, not query/workload-based tuning suggestions. See
[the Database advisor page](features/advisors.md#database) for scope, availability, and dialect-detection details.

## Availability and bounds

The panel is available whenever at least one application `DataSource` bean is discovered (reusing the same
proxy-aware datasource discovery as Database Connection Pools and SQL Trace, so a wrapped/routing `DataSource` is
never introspected twice). If no `DataSource` bean is present, BootUI returns a stable empty report with a `DISABLED`
status rather than failing.

Every scan runs under fixed bounds so an on-demand scan can never turn into unbounded work: at most 300 tables, 300
columns and 100 indexes per table, and 500 rows per catalog augmentation query, within an overall 20-second budget,
with a 5-second query timeout on every catalog statement (clamped to the remaining budget). Row bounds are enforced by
reading one row past the limit, so reaching a bound is detected rather than silently returning a full-looking result.
The connection's original read-only state is restored before it goes back to the pool.

## What "could not be checked" looks like

Nothing that failed is ever reported as a passing check:

- **Datasources** each carry a read status — `AVAILABLE`, `PARTIAL` (a bound was reached or some metadata could not be
  read) or `FAILED` — plus the detected product/dialect. Credentials in a JDBC URL or driver error message are always
  redacted, regardless of the value-exposure policy.
- **Diagnostics** list every problem next to the findings: an unreachable datasource, a table whose metadata could not
  be read, a catalog view a database role cannot see, a bound that truncated the scan, and every rule that was skipped
  or errored. They are never counted as violations and never affect the advisor score.
- **Scan status** is `SCANNED` only when every datasource was fully read, nothing was truncated, and no rule errored;
  otherwise it is `PARTIAL`. A scan where *every* datasource failed reports `ERROR` (not `DISABLED`, which means "there
  is nothing to inspect").
- **Rules** report `SKIPPED` with the real reason (no datasource of that dialect, an unsupported server version, a
  catalog permission error) instead of a clean `PASS` they did not earn, and a table whose metadata could not be read is
  skipped by the rules that would otherwise conclude something is absent.

## Severity scale

- **HIGH** - a structural issue with a well-understood, common performance or data-integrity impact (a missing index
  supporting a foreign key, an invalid PostgreSQL index, an unusable Oracle index, an unvalidated or disabled
  constraint, a sequence, `AUTO_INCREMENT` counter, or identity generator nearing exhaustion, a foreign key/referenced
  column type mismatch, a mapped column, foreign key, or unique constraint the database does not actually have, or a
  mapped sequence generator whose allocation size disagrees with the physical sequence).
- **MEDIUM** - a structural issue that usually warrants review before production use (a missing primary key, a mapped
  table or column that disagrees with the physical schema, a non-transactional MySQL/MariaDB storage engine, a legacy
  `utf8mb3` character set, a narrow auto-generated primary key, a composite foreign key or unique index with partially
  nullable columns, a PostgreSQL table published for logical replication with no usable replica identity), a statement shape that appears to embed dynamic literal values instead of bind parameters).
- **LOW** - reserved for lower-impact hygiene findings (redundant indexes, duplicate foreign key constraints).

The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id. Each
rule includes up to 10 sample findings plus a remediation link.

The advisor score applies the shared severity penalty to every concrete finding, not just once per violated rule. For
example, eight HIGH missing-index findings incur eight HIGH penalties. Dismissing that rule removes all eight findings
from the score.

---

## Schema

Generic checks that run against every JDBC-reachable `DataSource`, regardless of database vendor, using only
standard `DatabaseMetaData` calls (enriched, where the vendor catalog can answer, with index semantics JDBC cannot
express).

### DB-SCHEMA-001 - Tables without a primary key

- **Severity**: MEDIUM
- **Inspects**: `DatabaseMetaData.getPrimaryKeys()` for every application table in the physical schema.
- **Fires when**: a table reports no primary key columns.
- **Excludes**: system and temporary schemas, extension-owned tables (PostgreSQL `pg_depend.deptype = 'e'`, e.g. PostGIS
  or `pg_stat_statements` bookkeeping), migration bookkeeping tables (`flyway_schema_history`, `schema_version`,
  `DATABASECHANGELOG`, `DATABASECHANGELOGLOCK`), PostgreSQL child partitions (analyzed through their parent), and any
  table whose primary key metadata could not be read.
- **Why it matters**: without a primary key, ORMs cannot establish row identity, logical replication tools cannot
  target individual rows, and `UPDATE`/`DELETE` statements risk affecting more rows than intended.
- **Recommendation**: declare a primary key (a natural key or a surrogate id) on every table.

### DB-SCHEMA-002 - Foreign key columns without a supporting index

- **Severity**: HIGH
- **Inspects**: `DatabaseMetaData.getImportedKeys()` foreign keys against the table's own indexes.
- **Fires when**: the constraint's **complete ordered column list** is not the leading prefix of any usable index. A
  composite foreign key `(tenant_id, order_id)` is not supported by an index leading on `tenant_id` alone. On
  **Oracle**, the leading columns may appear in **any order** — a foreign key's real access pattern (a cascading
  delete/update check, a join, or the constraint's own validation) is a pure multi-column equality lookup that does
  not care which leading key part binds to which column, which is Oracle's own documented guidance; every other
  dialect keeps the stricter same-order check.
- **Does not count as support**: an invalid index, an invisible/ignored index, a partial index (it only covers the rows
  matching its predicate), an expression index, or a MySQL/MariaDB prefix index (`name(10)` indexes ten characters, not
  the value).
- **Why it matters**: MySQL/InnoDB creates a supporting index automatically for every foreign key column, but
  PostgreSQL, Oracle and SQL Server do not: joins against the referenced table and cascading deletes/updates on the
  parent row can force a full table scan on the child table.
- **Recommendation**: create an index whose leading columns are exactly the foreign key's columns, in the same order
  (any order on Oracle).

### DB-SCHEMA-003 - Duplicate/redundant indexes

- **Severity**: LOW
- **Inspects**: every pair of indexes on the same table.
- **Fires when**: a **non-unique** index's ordered key parts are a leading prefix of another index's, and both share the
  same semantics: same columns, direction, collation, prefix lengths and expressions, same access method, same partial
  predicate, same visibility.
- **Excludes**: unique indexes as the redundant side (a unique index enforces a constraint the covering index does
  not), the primary key's own backing index, partial/invalid indexes, and — on **Oracle** — an index automatically
  created to back a constraint, a partitioned index, or a specialized index type (function-based, domain, bitmap,
  LOB, or index-organized-table): dropping a constraint's own backing index is not the user's decision, and this
  rule's leading-prefix reasoning is written for ordinary B-tree indexes, not proven complete for those semantics.
- **Why it matters**: every additional index slows down `INSERT`/`UPDATE`/`DELETE` and consumes storage; when one
  index's key parts are a leading prefix of another's with identical semantics, the shorter one is usually redundant.
- **Recommendation**: review both index definitions — including any index hints or constraints relying on them — before
  dropping the redundant one.

### DB-SCHEMA-004 - Foreign key column type mismatch with the referenced column

- **Severity**: HIGH
- **Inspects**: each foreign key column against the column it **actually references**
  (`getImportedKeys().PKCOLUMN_NAME`, which may be an alternate unique key rather than the primary key), comparing type
  family, integer width and signedness, numeric precision/scale, and declared length.
- **Fires when**: the child column's type family differs, or it is a narrower integer, a different signedness, a smaller
  numeric precision/scale, or a shorter declared length than the referenced column.
- **Why it matters**: an `INT` foreign key referencing a `BIGINT` primary key works until the parent's ids pass 2^31; a
  coarse type-family mismatch can silently truncate values, defeat query planner join optimizations, or fail outright.
- **Recommendation**: align the foreign key column's type with the referenced column's type (e.g. both `BIGINT`).
- **Note**: classification is driven by the JDBC `DATA_TYPE` code and whole-token type names, so a PostgreSQL
  `interval` column is not treated as an integer (it contains "int") and unclassifiable vendor types never produce a
  finding.

### DB-SCHEMA-005 - Redundant unique index duplicating the primary key

- **Severity**: LOW
- **Inspects**: every unique index on a table against that table's primary key columns.
- **Fires when**: a unique index covers exactly the primary key's columns **in the same order** and is not the primary
  key's own backing index (identified by the driver-reported `PK_NAME`, falling back to the unique index matching the
  primary key columns in order). Partial and expression indexes are excluded.
- **Why it matters**: every additional unique index slows down `INSERT`/`UPDATE`/`DELETE` and consumes storage; an
  extra unique index matching the primary key's columns duplicates a guarantee the primary key already enforces.
- **Recommendation**: check that no foreign key or application code references the index by name, then drop it.

### DB-SCHEMA-006 - Duplicate foreign key constraints

- **Severity**: LOW
- **Inspects**: every pair of foreign key constraints on the same table.
- **Fires when**: two constraints reference the same parent table through the identical set of ordered
  child-column/parent-column pairs. Column order in the constraint's own DDL does not decide whether two constraints
  are duplicates — it is the pairing between a child column and the parent column it references that must match, so
  a constraint declared `(a, b) references parent(pa, pb)` and one declared `(b, a) references parent(pb, pa)` are the
  same relationship, while `(a, b) references parent(pa, pb)` and `(a, b) references parent(pb, pa)` are not.
- **Why it matters**: every insert, update, and delete on the child table pays the referential-integrity check twice
  for no additional guarantee, and cascading actions on the parent side run twice as well.
- **Recommendation**: drop the redundant constraint, after checking that no application code or tooling references it
  by name.

### DB-SCHEMA-007 - Narrow auto-generated primary key

- **Severity**: MEDIUM
- **Inspects**: single-column primary keys whose column reports `IS_AUTOINCREMENT = YES` (covers MySQL/MariaDB
  `AUTO_INCREMENT`, PostgreSQL `serial`/`GENERATED ... AS IDENTITY`, and equivalent driver-reported identity columns).
- **Fires when**: the column's declared type is `TINYINT` (max 127, or 255 unsigned) or `SMALLINT`/`INT2` (max 32,767,
  or 65,535 unsigned) — regardless of how many rows the table currently has.
- **Excludes**: a manually-assigned key of the same width (not auto-generated — a small, deliberately bounded reference
  table is not a defect), and composite primary keys, whose individual column widths are a different, less clear-cut
  question this rule does not guess at.
- **Why it matters**: this is a structural, design-time risk check, not a usage-based one — it fires the moment the
  schema is created, before the counter has consumed any meaningful fraction of its own tiny range, which is exactly
  when the type is cheapest to widen. `DB-PG-002`/`DB-MYSQL-003`/`DB-ORACLE-003` separately catch a counter that is
  already close to its column's actual maximum, at any width.
- **Recommendation**: widen the primary key column (and every foreign key referencing it) to `INTEGER` or `BIGINT`
  before the table grows, ideally in the same migration that creates it.

### DB-SCHEMA-008 - Composite foreign key with partially nullable columns

- **Severity**: MEDIUM
- **Inspects**: multi-column foreign keys against the nullability of their own child columns.
- **Fires when**: at least one column is definitely nullable and at least one other is definitely `NOT NULL`. Columns
  with unknown nullability are not counted either way, and single-column foreign keys are not evaluated.
- **Why it matters**: the SQL standard's default matching rule for a composite foreign key (`MATCH SIMPLE`, what every
  mainstream database uses unless `MATCH FULL` is explicitly requested) skips the referential check entirely whenever
  *any* one of the constraint's columns is `NULL` — even the columns that do have a value. A row like
  `(tenant_id = 5, order_id = NULL)` is therefore accepted unconditionally, which usually is not what a schema with a
  `NOT NULL` sibling column intended.
- **Recommendation**: either make every column in the composite foreign key `NOT NULL` (if the relationship is
  required) or make them all nullable (if it is genuinely optional).

### DB-SCHEMA-009 - Composite unique index with partially nullable columns

- **Severity**: MEDIUM
- **Inspects**: usable, multi-column unique indexes against the nullability of their own columns.
- **Fires when**: at least one column is definitely nullable and at least one other is definitely `NOT NULL`, and the
  index is not declared `NULLS NOT DISTINCT` (PostgreSQL 15+, the only dialect this checks). Columns with unknown
  nullability are not counted either way.
- **Why it matters**: standard SQL unique-index semantics compare a composite key as a whole: as soon as *any* one
  column in the row is `NULL`, the whole row is treated as distinct from every other row for that index — including
  ones whose `NOT NULL` columns hold the exact same values. A unique index on
  `(org_id NOT NULL, external_ref NULLABLE)` therefore does not limit an organization to one row with a given
  `external_ref`; it allows unlimited rows with the *same* `org_id` as long as `external_ref` is `NULL` each time,
  which usually defeats the "at most one" guarantee the constraint appears to make.
- **Recommendation**: make every column `NOT NULL`, declare the index `NULLS NOT DISTINCT` (PostgreSQL 15+), or, if
  application logic must tolerate several `NULL`s in that column, document that the constraint only applies when
  every column is present.

## Dialect detection and catalog augmentation

Dialect-specific catalog augmentation runs in addition to the generic checks above. The dialect is detected from
`DatabaseMetaData.getDatabaseProductName()`, the product version string and the JDBC URL — MariaDB is detected as its
own dialect even when reached through the MySQL driver, which reports the product name as "MySQL" and only reveals the
truth in the version string. A driver-reported "Oracle" product name is additionally confirmed by reading
`v$version`/`product_component_version` before any Oracle-specific catalog augmentation runs: some Oracle-compatible
databases (OceanBase's driver, in its 2.2.x default or with `useCompatibleMetadata=true`) report the same product
name for an Oracle-mode tenant, but their own version banner does not read as Oracle's; a server that cannot confirm
itself this way still gets the full generic ruleset, just not the Oracle-specific rules below. Every other database
(H2, SQL Server, Tibero, EDB Postgres Advanced Server, etc.) also runs the full generic ruleset through the standard
JDBC metadata fallback — it is never treated as unsupported.

Catalog queries are version-aware: MySQL 8.0's `IS_VISIBLE` versus MariaDB 10.6's `IGNORED` index-visibility column,
MySQL 8.0.13's `EXPRESSION` functional key parts, PostgreSQL 10's `pg_sequences` view and declarative partitioning,
PostgreSQL 11's `INCLUDE` columns, PostgreSQL 12's concurrent-index-build visibility, and PostgreSQL 15's
`NULLS NOT DISTINCT` are each selected from the reported server version; Oracle's `ALL_*`/`SYS_CONTEXT` augmentation
requires 19c or later. When a server is too old, or a role cannot read a catalog view, the matching rule reports
`SKIPPED` with that reason.

## PostgreSQL

### DB-PG-001 - Invalid PostgreSQL indexes

- **Severity**: HIGH
- **Inspects**: `pg_index.indisvalid`, `indisready`, `indislive` and `indisunique` on PostgreSQL datasources only.
- **Fires when**: an index is reported unusable — typically left behind by a failed `CREATE INDEX CONCURRENTLY`.
- **Excludes**: partitioned index parents (`relkind = 'I'`, legitimately invalid until every child index is attached),
  extension-owned indexes, and — on PostgreSQL 12+, where `pg_stat_progress_create_index` is available — an index
  currently being built `CONCURRENTLY`, which is transiently invalid by design until that build finishes or is
  abandoned. Findings are schema-qualified and name the failing flags.
- **Why it matters**: an invalid index is never used by the query planner but still pays the full write cost of index
  maintenance; if it is meant to be unique, it currently enforces no uniqueness at all.
- **Recommendation**: confirm no `CREATE INDEX CONCURRENTLY` is currently running against the table, then either
  rebuild it in place with `REINDEX INDEX CONCURRENTLY` (PostgreSQL 12+) or drop and recreate it (`DROP INDEX
  CONCURRENTLY` followed by `CREATE INDEX CONCURRENTLY`). A finding on a **unique** index calls this out explicitly,
  since the uniqueness gap is a more serious consequence than simply being unused by the planner.

### DB-PG-002 - PostgreSQL sequence nearing exhaustion

- **Severity**: HIGH
- **Inspects**: `pg_sequences.last_value` against **`min(the sequence's max_value, the owning column's capacity)`**,
  resolving the owning `table.column` and its type through `pg_depend`. Requires PostgreSQL 10 or later.
- **Fires when**: a non-cycling sequence has consumed at least 80% of that effective ceiling.
- **Why it matters**: the classic failure is a `bigint` sequence — 0% of its own range forever — feeding an `integer`
  column that stops accepting inserts at 2,147,483,647. Measuring against the sequence's own maximum, as BootUI
  previously did, reports 0% right up to the outage.
- **Recommendation**: widen the owning column (and the sequence maximum), or restart the sequence after archiving old
  rows. Cycling sequences wrap instead of failing and are never reported.
- **Note**: percentages are computed in arbitrary precision, so a `bigint` range cannot overflow the arithmetic.

### DB-PG-003 - PostgreSQL NOT VALID constraint never validated

- **Severity**: HIGH
- **Inspects**: `pg_constraint.convalidated` for foreign key and check constraints on PostgreSQL datasources only,
  excluding system and extension-owned objects.
- **Fires when**: a constraint was added `NOT VALID` and never validated.
- **Why it matters**: `ADD CONSTRAINT ... NOT VALID` is the standard way to avoid a long lock on a large table, with
  the intent of running `VALIDATE CONSTRAINT` afterwards. When that never happens the constraint is enforced for new
  rows only: existing rows may already violate it, and the planner cannot rely on it. `getImportedKeys()` reports the
  foreign key as if it were fully enforced, so nothing else in the panel can see this.
- **Recommendation**: run `ALTER TABLE ... VALIDATE CONSTRAINT ...` (a `SHARE UPDATE EXCLUSIVE` lock) after fixing any
  offending rows.

### DB-PG-004 - PostgreSQL table lacking usable replica identity

- **Severity**: MEDIUM
- **Inspects**: `pg_class.relreplident` for every table reachable through a publication — an explicit
  `pg_publication_rel` member, or implicitly included because some publication is declared `FOR ALL TABLES`.
- **Fires when**: `relreplident` is `n` (explicitly `NOTHING`), or `d` (the default, which uses the primary key) on a
  table with no primary key — both resolve to no usable replica identity. `f` (full row) and `i` (a specific unique
  index) are always usable and never flagged.
- **Excludes**: any table not reachable through a publication — flagging every primary-key-less table for a
  replication feature the database may not even have configured would be noise on the overwhelming majority of
  development databases, which is why this is a dedicated rule rather than a variant of `DB-SCHEMA-001`.
- **Why it matters**: `UPDATE`/`DELETE` against a published table with no usable replica identity fails outright once
  a logical replication subscriber attaches (`cannot update/delete from table ... because it does not have a replica
  identity and publishes updates or deletes`).
- **Recommendation**: add a primary key (restores the `DEFAULT` replica identity), or set one explicitly with
  `ALTER TABLE ... REPLICA IDENTITY FULL/USING INDEX ...`.

## MySQL and MariaDB

These checks share one section because they apply to both MySQL and MariaDB. Version-aware catalog queries account for
their differences where necessary.

### DB-MYSQL-001 - Tables on a non-transactional storage engine

- **Severity**: MEDIUM
- **Inspects**: `information_schema.tables.ENGINE` on MySQL **and MariaDB** datasources.
- **Fires when**: a table uses an engine whose defect is the absence of transactions: MyISAM, MERGE/MRG_MYISAM, MEMORY,
  CSV, ARCHIVE, BLACKHOLE, or MariaDB's Aria.
- **Excludes**: specialist engines a developer chooses deliberately (RocksDB/MyRocks, ColumnStore, NDB, FEDERATED,
  SPIDER, CONNECT, SEQUENCE, ...). "Not InnoDB" is not a finding when the engine was the point, which is why this is
  MEDIUM rather than HIGH.
- **Why it matters**: non-transactional engines do not enforce foreign keys, do not roll back, and use table-level
  locking, which surprises most JPA/Hibernate applications that assume ACID semantics.
- **Recommendation**: convert the table to InnoDB (`ALTER TABLE ... ENGINE=InnoDB`) during a maintenance window — the
  rewrite locks the table and changes its on-disk size.

### DB-MYSQL-002 - Tables/columns using the legacy utf8mb3 character set

- **Severity**: MEDIUM
- **Inspects**: `information_schema.tables.TABLE_COLLATION` (table defaults) and
  `information_schema.columns.CHARACTER_SET_NAME` (columns) on MySQL and MariaDB datasources.
- **Fires when**: a table default or column uses `utf8`/`utf8mb3`.
- **Excludes**: other legacy character sets (`latin1`, `ascii`, `binary`, ...), which are almost always a deliberate
  per-column choice; flagging each one made this rule pure noise on legacy schemas.
- **Why it matters**: MySQL's legacy `utf8` alias is a three-byte encoding that cannot store the full Unicode range
  (emoji, many CJK supplementary characters), so a developer who asked for Unicode did not get it — it surfaces as
  silent truncation or an insert failure.
- **Recommendation**: convert the column and the table default to `utf8mb4`. Re-check index key lengths first: utf8mb4
  needs 4 bytes per character, so an existing index on a long `VARCHAR` can exceed the maximum key length.
- **Note**: the suggested collation is dialect-appropriate and named in each finding rather than baked into one shared
  recommendation: MySQL 8.0's default, `utf8mb4_0900_ai_ci`, does not exist on MariaDB, which never shipped the
  Unicode 9.0 collations it is built on — MariaDB findings instead suggest `utf8mb4_uca1400_ai_ci` (10.10+) or
  `utf8mb4_general_ci` (older MariaDB).

### DB-MYSQL-003 - MySQL/MariaDB AUTO_INCREMENT nearing exhaustion

- **Severity**: HIGH
- **Inspects**: `information_schema.tables.AUTO_INCREMENT` against the signed/unsigned capacity of the table's
  `AUTO_INCREMENT` column type (`information_schema.columns.COLUMN_TYPE`).
- **Fires when**: the counter has consumed at least 80% of that capacity.
- **Why it matters**: when the counter reaches the column's maximum, every subsequent insert fails with a duplicate-key
  error — the MySQL equivalent of PostgreSQL sequence exhaustion, and just as common a cause of a sudden outage.
- **Recommendation**: widen the `AUTO_INCREMENT` column (and every foreign key column referencing it) in the same
  migration.
- **Note**: capacity is signedness-aware (`int` stops at 2,147,483,647, `int unsigned` at 4,294,967,295) and computed in
  arbitrary precision, because `bigint unsigned` exceeds `Long.MAX_VALUE`. A table whose `AUTO_INCREMENT` the server
  does not report is skipped, never treated as zero.
- **Known limitation**: `information_schema.tables.AUTO_INCREMENT` is itself only an estimate on InnoDB, not a
  transactionally exact reading. Before MySQL 8.0 (and still on MariaDB), the counter is kept purely in memory and is
  re-derived from `MAX(id) + 1` the first time the table is touched after a server restart — a scan that runs in that
  narrow window can under-report consumption. MySQL 8.0's persistent `AUTO_INCREMENT` counters close most of that gap,
  but this rule can still occasionally under-report a table's true usage; it is not known to over-report one, so every
  finding it does produce reflects real, already-consumed capacity.

## Oracle

Requires Oracle Database 19c or later, confirmed genuinely Oracle (not merely a driver-reported product name; see
above). Every query reads only `ALL_*` dictionary views and `SYS_CONTEXT('USERENV', ...)` — never `DBA_*`/`V$` views
that need an elevated role, never an application row, and never across a database link — scoped to the connected
session's `CURRENT_SCHEMA` with a bound `OWNER = ?` parameter, so a scan never silently widens to every schema the
connected user can merely see. There is no production `ojdbc` dependency anywhere in BootUI: the engine reads Oracle
through plain `java.sql.*` against whatever `Connection` the host application's own datasource supplies, using only
JDK JDBC APIs (`FETCH FIRST ? ROWS ONLY` for bounded row limiting, matching the same budgets/timeouts as every other
dialect).

Oracle is case-sensitive for genuinely distinct quoted identifiers (and folds unquoted ones to *uppercase*, unlike
PostgreSQL/MySQL's lowercase folding), so matching an object read through `DatabaseMetaData` against the same object
read through `ALL_*` never normalizes case — a normalized comparison could either merge two distinctly-quoted objects
or miss a real match.

### DB-ORACLE-001 - Unusable Oracle indexes

- **Severity**: HIGH
- **Inspects**: `all_indexes.status`, and — for a partitioned index, whose own `status` reads `N/A` — individual
  partitions/subpartitions in `all_ind_partitions`/`all_ind_subpartitions`.
- **Fires when**: an ordinary index reports `UNUSABLE`, or a partitioned index has at least one `UNUSABLE`
  partition/subpartition (named in the finding).
- **Excludes**: domain indexes (`index_type = 'DOMAIN'`, e.g. Oracle Text or Spatial) — their status semantics are
  governed by the domain index implementation's own auxiliary objects, which `all_indexes.status` alone cannot
  reliably describe. Every other index type — normal, function-based, bitmap, LOB, IOT-backing — uses the standard
  `status` semantics and is reported the same way, including one Oracle created automatically to back a primary key
  or unique constraint: an unusable constraint-backing index is, if anything, more consequential to miss.
- **Why it matters**: an unusable index is never used by the optimizer, and unless `SKIP_UNUSABLE_INDEXES` is enabled
  (Oracle's default since 10g), `INSERT`/`UPDATE` against the underlying table can fail outright until it is fixed.
- **Recommendation**: rebuild the index (`ALTER INDEX ... REBUILD`, or `ALTER INDEX ... REBUILD PARTITION/SUBPARTITION`
  for a single partition) during a maintenance window.

### DB-ORACLE-002 - Disabled or unvalidated Oracle constraints

- **Severity**: HIGH
- **Inspects**: `all_constraints.status` (`ENABLED`/`DISABLED`) and `all_constraints.validated`
  (`VALIDATED`/`NOT VALIDATED`) for primary key, unique, foreign key and check constraints — two independent flags,
  both worth knowing separately.
- **Fires when**: a constraint is `DISABLED`, or `NOT VALIDATED` (as `ALTER TABLE ... ENABLE NOVALIDATE` — the
  standard way to turn a constraint on for new rows without a full-table validation scan — leaves it).
- **Excludes**: Oracle's own system-generated column-level `NOT NULL` check constraint, recognized by its system-
  generated name *and* a search condition that is exactly the `IS NOT NULL` test Oracle synthesizes for it — reporting
  one of the dozens a typical schema has would be pure noise. A user-authored, merely-unnamed `CHECK (...)`
  constraint is not excluded, since its search condition is not that exact test.
- **Why it matters**: a disabled constraint enforces nothing at all, and `ENABLE NOVALIDATE`/`DISABLE NOVALIDATE`
  leaves existing rows unchecked — both are invisible to `DatabaseMetaData.getImportedKeys()`, which reports the
  foreign key as if it were fully enforced.
- **Recommendation**: `ALTER TABLE ... ENABLE CONSTRAINT ...` (after fixing any offending rows) to enable a disabled
  constraint, or `ALTER TABLE ... VALIDATE CONSTRAINT ...` (or `ENABLE VALIDATE`, which takes a stronger lock) to
  validate one already enabled without validation.

### DB-ORACLE-003 - Oracle sequence or identity generator nearing exhaustion

- **Severity**: HIGH
- **Inspects**: `all_sequences.last_number` against `max_value`/`min_value`, including a sequence created implicitly
  to back a `GENERATED ... AS IDENTITY` column (mapped back to its owning table/column via
  `all_tab_identity_cols` for a readable finding, instead of an opaque internal name like `ISEQ$$_74522`).
- **Fires when**: a non-cycling sequence (`cycle_flag = 'N'`) has consumed at least 80% of the range between
  `min_value` and `max_value`.
- **Excludes**: session, scalable, and sharded sequences (`session_flag`/`scale_flag`/`sharded_flag`) — each has range
  or reset semantics a plain percent-of-range reading would misrepresent.
- **Why it matters**: unlike PostgreSQL, there is no separate "owning column capacity" to cross-reference — Oracle's
  single generic `NUMBER` identifier type means the sequence's own `max_value` is always the real ceiling. A
  non-cycling sequence that reaches it causes every subsequent insert relying on it to fail.
- **Recommendation**: widen the sequence's `MAXVALUE` (`ALTER SEQUENCE ... MAXVALUE ...`), or the owning `IDENTITY`
  column's precision if the sequence backs one, or restart the sequence after archiving old rows.
- **Note**: `all_sequences.last_number` already reflects the cache's reserved high-water mark, not merely committed
  consumption, which makes this measurement conservative — it can flag a sequence slightly before it is truly that
  close, never after.

## Hibernate mapping

These checks run only when a Hibernate `EntityManagerFactory`/metamodel is also available for the same application,
cross-referencing the physical schema against the mapped JPA entities the shared Hibernate metamodel reader already
reads.

Only entities with an explicit `@Table(name = ...)` are cross-referenced; entities relying on the default naming
strategy are skipped rather than guessed. Matching honors an explicitly declared `catalog`/`schema`, and a mapped name
that matches tables in **more than one** readable datasource is treated as ambiguous and skipped rather than attributed
to an arbitrary database. Rules that conclude something is *absent* additionally skip any table whose metadata was
truncated or partly unreadable.

An entity that splits its columns across more than one physical table via `@SecondaryTable`/`@SecondaryTables` is
supported: a column, join column, or unique constraint explicitly pinned to a secondary table
(`@Column(table=...)`/`@JoinColumn(table=...)`/`@SecondaryTable(uniqueConstraints=...)`) is checked against that
secondary table, resolved the same ambiguity-averse way as the primary table, rather than against the primary table it
does not actually live in.

**Deliberately out of scope**: resolving a mapped table/column's *actual* Hibernate-computed physical name (after the
configured naming strategy runs) for an entity with no explicit `@Table`/`@Column` name. `JpaMetamodelReader` — the
sole engine class that reads the JPA metamodel — deliberately reads only the standard
`jakarta.persistence.metamodel.Metamodel` API to stay provider-version-agnostic. Recovering the naming-strategy output
would require Hibernate's internal `SessionFactoryImplementor`/`PhysicalNamingStrategy` SPI, which is not part of
Hibernate's guaranteed-stable public API and would need per-version-fragile reflection — undermining the "skip rather
than guess" contract these rules otherwise hold to. The conservative behavior (skip an implicitly-named entity/column
rather than guess its physical name) is kept instead.

### DB-HIB-001 - Mapped foreign key column has no physical index

- **Severity**: HIGH
- **Inspects**: `@ManyToOne`/`@OneToOne` `@JoinColumn` and composite `@JoinColumns` foreign keys against the physical
  schema's actual usable indexes.
- **Fires when**: the mapped join column(s) have no usable index leading on them, in declaration order (any order on
  Oracle) — using the same usability semantics as `DB-SCHEMA-002`.
- **Excludes**: an association whose join columns already match a physical foreign key constraint — every physical
  foreign key is independently evaluated by `DB-SCHEMA-002`, so reporting the same missing index again from the
  mapping side would double-count one problem as two findings. This rule keeps its distinct value for the case
  `DB-SCHEMA-002` cannot see at all: a mapped association with **no** physical foreign key constraint, unindexed or
  not.
- **Why it matters**: unlike the Hibernate Advisor's own `HIB-MAP-019` (which only sees JPA-declared
  `@Table(indexes=...)` metadata), this rule sees the database's actual indexes — including ones created by a
  Flyway/Liquibase migration. Hibernate loads the association's target through those columns on every traversal.
- **Recommendation**: add a database index (via a migration) leading on the mapped foreign key column(s).

### DB-HIB-002 - Mapped entity table not found in the physical schema

- **Severity**: MEDIUM
- **Inspects**: entities with an explicit `@Table(name = ...)` and every declared `@SecondaryTable`, honoring the
  declared catalog/schema, against the tables of every readable datasource.
- **Fires when**: a mapped table (primary or secondary) is absent from all of them. Skipped entirely when any
  datasource's table list was truncated, since an unread table cannot be told from a missing one.
- **Why it matters**: this usually points to a stale entity, a missing migration, or the wrong
  datasource/persistence-unit wiring.
- **Recommendation**: verify the entity is mapped to the correct persistence unit/datasource, that a pending
  migration creates the table, or that the entity is stale and should be removed.

### DB-HIB-003 - Mapped column type/nullability mismatch

- **Severity**: MEDIUM
- **Inspects**: `@Column(name = ...)` attributes against the physical column's JDBC type family and nullability.
- **Fires when**: a coarse type-family mismatch is detected (e.g. a `String` attribute mapped to a numeric column), or
  an **explicitly declared** `@Column(nullable = ...)` disagrees with the database.
- **Excludes**: attributes whose persisted shape is decided by an `@Convert`, an `@Enumerated` or an `@Lob` (a converter
  legitimately stores a `String` in an `int` column), physical types that cannot be classified confidently, and
  nullability that the mapping never declared — JPA defaults `nullable` to `true`, so comparing an undeclared default
  against a `NOT NULL` column produced advice the developer could not act on.
- **Why it matters**: these mismatches usually surface at runtime as a surprising constraint violation or
  conversion failure rather than at compile time.
- **Recommendation**: align the entity mapping with the physical column definition.

### DB-HIB-004 - Mapped column length longer than the physical column size

- **Severity**: MEDIUM
- **Inspects**: **explicitly declared** `@Column(length = ...)` attributes against the physical string/char column's
  reported size.
- **Fires when**: the declared length exceeds what the column can hold.
- **Excludes**: attributes with no explicit length (JPA's invisible default of 255 is not a statement of intent),
  `@Lob` attributes, and columns with no bounded physical size — PostgreSQL reports `2147483647` for unbounded `text`,
  which is not a width.
- **Why it matters**: a mapping that permits more characters than the database column can hold either silently
  truncates input or fails with a data-truncation error, depending on the database's strictness.
- **Recommendation**: align the entity's `@Column(length = ...)` with the physical column size, or widen the
  physical column via a migration.

### DB-HIB-005 - Mapped unique constraint has no backing physical unique index

- **Severity**: HIGH
- **Inspects**: single-column `@Column(unique = true)` attributes and multi-column
  `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {...}))`/`@SecondaryTable(uniqueConstraints = ...)`
  constraints against the uniqueness the database actually enforces.
- **Fires when**: no unique index (or primary key) genuinely covers the mapped columns. Column order is ignored —
  uniqueness over `(a, b)` and `(b, a)` is the same guarantee — but a MySQL/MariaDB **prefix** unique index
  (`unique key (email(20))`), a partial index, an expression index, and an invalid or invisible index are **not**
  coverage.
- **Why it matters**: without enforced uniqueness the database never rejects the duplicate the mapping assumes cannot
  exist, so concurrent inserts create rows the application logic never expected.
- **Recommendation**: add a unique index or constraint (via a migration) covering the same column(s) in full.

### DB-HIB-006 - Mapped column not found in the physical table

- **Severity**: HIGH
- **Inspects**: explicitly named `@Column(name = ...)` attributes and `@JoinColumn(s)` join columns against
  `DatabaseMetaData.getColumns()` for the resolved physical table (primary, or a named secondary table).
- **Fires when**: a mapped column does not exist physically.
- **Why it matters**: every query touching that attribute fails at runtime with "column does not exist", usually only
  on the code path that first selects it. It normally means a migration was never applied, was applied to a different
  schema, or the entity is ahead of the database. Hibernate's own `ddl-auto` validation covers the same ground at
  startup, but it is off in most applications and fails the boot instead of reporting.
- **Recommendation**: apply the missing migration, or correct the mapping.

### DB-HIB-007 - Mapped association has no physical foreign key constraint

- **Severity**: HIGH
- **Inspects**: mapped `@ManyToOne`/`@OneToOne` join column sets — including composite ones — against the foreign keys
  `DatabaseMetaData.getImportedKeys()` reports for the same table, verifying the child-to-parent column pairing and,
  when the association's target entity table is resolvable (an explicit `@Table` on the target), that the constraint
  actually references that table.
- **Fires when**: the entity model declares the association but the database enforces no matching constraint. A
  constraint's own DDL column order does not decide a match (`(a, b) references parent(pa, pb)` is the same
  constraint as one declared `(b, a) references parent(pb, pa)`) — only the child-to-parent pairing does, so
  reordering that pairing is a genuine mismatch, not a false positive, and a same-named-columns constraint pointing at
  an unrelated table is never mistaken for this association's.
- **Excludes**: an association explicitly declaring `@JoinColumn(foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))`
  — the absence of a physical constraint there is the developer's own choice, not a defect.
- **Why it matters**: without the constraint an orphaned child row is a normal insert as far as the database is
  concerned, so an association the entity model presents as guaranteed can resolve to a missing row at runtime, and
  cascading deletes are silently not enforced. It commonly happens when a schema was generated without constraints or a
  table was recreated without them.
- **Recommendation**: add the foreign key constraint via a migration.

### DB-HIB-008 - Hibernate sequence allocationSize does not match the physical sequence's INCREMENT BY

- **Severity**: HIGH
- **Inspects**: a `@GeneratedValue(strategy = GenerationType.SEQUENCE)` attribute's co-located, explicitly-named
  `@SequenceGenerator(sequenceName = ..., allocationSize = ...)` against the PostgreSQL/Oracle physical sequence's
  actual `INCREMENT BY`, when the sequence name resolves unambiguously to exactly one readable datasource.
- **Fires when**: `allocationSize` disagrees with the physical sequence's increment, in either direction.
- **Excludes**: a `@SequenceGenerator` that does not explicitly declare `sequenceName` (never guessed), or whose
  declared name does not resolve to exactly one sequence across every readable PostgreSQL/Oracle datasource.
- **Why it matters**: this is different from — and not a duplicate of — the Hibernate Advisor's own `HIB-ID-003`
  (which flags `allocationSize=1` purely from the mapping, never touching the database): this rule cross-references
  the declared `allocationSize` against the database's actual sequence definition. Hibernate's pooled/pooled-lo
  identifier optimizers call `nextval` once and hand out `allocationSize` consecutive identifiers from that single
  value in memory, assuming the sequence advances by exactly `allocationSize` on every call. If the physical
  sequence's `INCREMENT BY` is smaller, two JVMs (or two threads racing a fresh `nextval`) can compute overlapping
  identifier blocks — a real duplicate-key or silent-overwrite risk, not merely wasted numbers.
- **Recommendation**: set the physical sequence's `INCREMENT BY` to match `allocationSize` exactly
  (`ALTER SEQUENCE ... INCREMENT BY ...`), or change `allocationSize` to match the sequence.

## Runtime SQL

Checks evaluated against the statements already retained in the SQL Trace capture window rather than against the
physical schema. They read only what BootUI captured, run no query, and never reproduce a captured literal value.

Because the retained window is bounded and most-recent-first, these checks describe evidence in that window only. They
are `SKIPPED` with an explicit reason when SQL Trace is unavailable or the window holds nothing to evaluate.

### DB-RUNTIME-001 - Statement appears to embed literal values instead of bind parameters

- **Severity**: MEDIUM
- **Inspects**: retained SQL Trace executions, grouped by the same normalization the SQL Trace rankings use (literals
  and existing bind markers collapsed to `?`).
- **Fires when**: one normalized statement shape was executed with **two or more distinct raw texts** *and* the
  normalization replaced at least one literal in a filtering position (a `WHERE`/`AND`/`OR`/`HAVING`/`ON` comparison or
  `IN` list). Distinct texts are counted by hash only; the texts themselves are never retained or reported.
- **Excludes**: a shape executed with only one raw text (a genuinely constant statement, not a concatenated one), a
  shape whose literals appear only in a projection or `VALUES` clause, and anything beyond a bounded number of tracked
  shapes and variants per scan.
- **Confidence**: reported as **high** when three or more distinct texts were observed, otherwise **medium**. The
  finding also notes how many of the executions ran through a plain `Statement` rather than a `PreparedStatement`,
  which is corroborating — not conclusive — evidence.
- **Why it matters**: a statement whose filter values change from execution to execution is usually being built by
  string concatenation. That defeats the database's plan cache (every variant is a new plan), and it is the shape in
  which SQL injection defects normally appear.
- **Limitations**: this is a **heuristic over a bounded window, not proof**. A framework that legitimately emits
  literal SQL — a dynamic `IN` list expansion, a migration tool, a generated `LIMIT` — produces the same shape. The
  clearest known false positive is a framework-generated discriminator: Hibernate single-table inheritance emits
  `where dtype = 'EMPLOYEE'` as literal SQL, so an application that queries several subtypes shows one shape with
  several distinct texts and a literal in a filtering position, which is exactly this rule's signature even though no
  application value was concatenated. (An application that queries only one subtype does not fire the rule: the raw
  text never changes.) Treat a finding as a prompt to read the call site, not as a vulnerability report. BootUI makes no
  injection claim, and the evidence deliberately contains no captured literal values.
- **Recommendation**: bind the changing values as parameters (`PreparedStatement`, JPA/Hibernate query parameters, or
  the query DSL's own binding) instead of concatenating them into the statement text.

## Deliberately not checked

The panel stays a structural, deterministic advisor rather than a tuning engine, so it proposes no
workload/unused/missing-index heuristics, no bloat/vacuum/analyze advice, no query-plan or `pg_stat_statements`
analysis, and never scans application data. It also takes no position on unmanaged tables, timestamp time-zone style,
PostgreSQL ownership/superuser configuration, or pool-versus-`max_connections` sizing.

Oracle coverage is deliberately narrow. It does not add orphaned-entry, chained-row, unused-index, fragmentation, or
sequence-gap warnings, does not read AWR/ASH (both require a management pack license this advisor must never assume
is present), and never flags a bitmap index merely for existing (only where a specific, provably-safe condition holds,
such as an unusable status already covered by `DB-ORACLE-001`) — none of these can be evaluated from `ALL_*` views
alone without either an elevated role or workload assumptions this advisor does not make. It does not add SQL Server
vendor rules.
