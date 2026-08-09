# Database Advisor checks

The Database Advisor panel runs a fixed, on-demand ruleset against the physical schema of every discovered
application `DataSource` bean. It introspects tables, columns, primary keys, foreign keys, and indexes through plain
JDBC `DatabaseMetaData` — it never executes DDL and never queries application data.

The checks are deterministic, low-false-positive structural checks, not query/workload-based tuning suggestions. See
[FEATURES.md](FEATURES.md#database-advisor) for scope, availability, and dialect-detection details.

## Availability and bounds

The panel is available whenever at least one application `DataSource` bean is discovered (reusing the same
proxy-aware datasource discovery as Database Connection Pools and SQL Trace, so a wrapped/routing `DataSource` is
never introspected twice). If no `DataSource` bean is present, or introspection fails, BootUI returns a stable empty
report with an explanatory status rather than failing.

The PostgreSQL and MySQL dialect-specific rules are skipped (not silently dropped) when no datasource of that dialect
is detected, and both dialect-specific queries fail soft on restricted privileges rather than propagating. The
Hibernate mapping cross-reference rules are skipped when either a `DataSource` or a Hibernate metamodel is
unavailable, and only cross-reference entities with an explicit `@Table(name = ...)`.

## Severity scale

- **HIGH** - a structural issue with a well-understood, common performance or data-integrity impact (a missing index
  supporting a foreign key, an invalid PostgreSQL index, a non-InnoDB MySQL table).
- **MEDIUM** - a structural issue that usually warrants review before production use (a missing primary key, a mapped
  table or column that disagrees with the physical schema).
- **LOW** - reserved for lower-impact hygiene findings (redundant indexes).

The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id. Each
rule includes up to 10 sample findings plus a remediation link.

---

## Schema

Generic checks that run against every JDBC-reachable `DataSource`, regardless of database vendor, using only
standard `DatabaseMetaData` calls.

### DB-SCHEMA-001 - Tables without a primary key

- **Severity**: MEDIUM
- **Inspects**: `DatabaseMetaData.getPrimaryKeys()` for every table in the physical schema.
- **Fires when**: a table reports no primary key columns.
- **Why it matters**: without a primary key, ORMs cannot establish row identity, logical replication tools cannot
  target individual rows, and `UPDATE`/`DELETE` statements risk affecting more rows than intended.
- **Recommendation**: declare a primary key (a natural key or a surrogate id) on every table.

### DB-SCHEMA-002 - Foreign key columns without a supporting index

- **Severity**: HIGH
- **Inspects**: `DatabaseMetaData.getImportedKeys()` foreign key columns against the table's own indexes.
- **Fires when**: a foreign key column is not the leading column of any index on the same table.
- **Why it matters**: most databases do not automatically index foreign keys, so joins against the referenced table
  and cascading deletes/updates on the parent row can force a full table scan on the child table.
- **Recommendation**: create an index leading on the foreign key column(s).

### DB-SCHEMA-003 - Duplicate/redundant indexes

- **Severity**: LOW
- **Inspects**: every pair of indexes on the same table.
- **Fires when**: two indexes on the same table share the same leading column, and one column list is a strict
  prefix of the other (or an exact duplicate).
- **Why it matters**: every additional index slows down `INSERT`/`UPDATE`/`DELETE` and consumes storage; when one
  index's column list is a prefix of another's, the shorter one is usually redundant.
- **Recommendation**: review both index definitions before dropping the redundant one.

## Dialect-specific (PostgreSQL and MySQL)

A small amount of dialect-specific catalog augmentation runs in addition to the generic checks above, for the two
most widely used relational databases among Java developers. The dialect is detected from
`DatabaseMetaData.getDatabaseProductName()` and the JDBC URL; every other database (H2, SQL Server, Oracle, MariaDB,
etc.) still runs the full generic ruleset above through the standard JDBC metadata fallback — MariaDB is not detected
as the MySQL dialect, so `DB-MYSQL-001` does not run against it.

### DB-PG-001 - Invalid PostgreSQL indexes

- **Severity**: HIGH
- **Inspects**: `pg_index.indisvalid` on PostgreSQL datasources only; skipped when no PostgreSQL datasource is
  detected.
- **Fires when**: an index is reported invalid, typically left behind by a failed `CREATE INDEX CONCURRENTLY`.
- **Why it matters**: an invalid index is never used by the query planner but still pays the full write cost of
  index maintenance.
- **Recommendation**: drop and recreate the index (`DROP INDEX CONCURRENTLY` followed by `CREATE INDEX
  CONCURRENTLY`).

### DB-MYSQL-001 - Tables not using the InnoDB storage engine

- **Severity**: HIGH
- **Inspects**: `information_schema.tables.ENGINE` on MySQL datasources only; skipped when no MySQL datasource is
  detected (MariaDB is not detected as the MySQL dialect and runs the generic Schema checks instead — see above).
- **Fires when**: a table's storage engine is not `InnoDB`.
- **Why it matters**: non-InnoDB engines such as MyISAM do not enforce foreign keys, do not support
  transactions/MVCC, and use table-level locking, which surprises most JPA/Hibernate applications that assume ACID
  semantics.
- **Recommendation**: convert the table to InnoDB (`ALTER TABLE ... ENGINE=InnoDB`).

## Hibernate mapping

These checks run only when a Hibernate `EntityManagerFactory`/metamodel is also available for the same application,
cross-referencing the physical schema against the mapped JPA entities the shared Hibernate metamodel reader already
reads. Only entities with an explicit `@Table(name = ...)` are cross-referenced; entities relying on the default
naming strategy are skipped rather than guessed, keeping the false-positive rate low.

### DB-HIB-001 - Mapped foreign key column has no physical index

- **Severity**: HIGH
- **Inspects**: `@ManyToOne`/`@OneToOne` `@JoinColumn` foreign keys against the physical schema's actual indexes.
- **Fires when**: a mapped foreign key column has no leading index in the physical schema.
- **Why it matters**: unlike the Hibernate Advisor's own `HIB-MAP-019` (which only sees JPA-declared
  `@Table(indexes=...)` metadata), this rule sees the database's actual indexes — including ones created by a
  Flyway/Liquibase migration — so it only fires when the physical schema genuinely has no supporting index, a
  high-confidence, low-noise signal. Hibernate loads the association's target through this column on every
  traversal, and cascading deletes/updates on the parent row scan the child table without it.
- **Recommendation**: add a database index (via a migration) leading on the foreign key column.

### DB-HIB-002 - Mapped entity table not found in the physical schema

- **Severity**: MEDIUM
- **Inspects**: entities with an explicit `@Table(name = ...)` against the physical schema's table names.
- **Fires when**: a mapped table is simply absent from the database.
- **Why it matters**: this usually points to a stale entity, a missing migration, or the wrong
  datasource/persistence-unit wiring.
- **Recommendation**: verify the entity is mapped to the correct persistence unit/datasource, that a pending
  migration creates the table, or that the entity is stale and should be removed.

### DB-HIB-003 - Mapped column type/nullability mismatch

- **Severity**: MEDIUM
- **Inspects**: `@Column(name = ...)` attributes against the physical column's reported JDBC type family and
  nullability.
- **Fires when**: a coarse type-family mismatch (e.g. a `String` attribute mapped to a numeric column) or a
  nullability mismatch where the database is stricter than the mapping (a `NOT NULL` column mapped to a nullable
  attribute, or vice versa) is detected.
- **Why it matters**: these mismatches usually surface at runtime as a surprising constraint violation or
  class-cast/conversion failure rather than at compile time.
- **Recommendation**: align the entity mapping with the physical column definition.
