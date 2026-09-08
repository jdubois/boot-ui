# Database checks

The Database advisor runs **24 fixed, on-demand checks** over the physical schema reported by the application's
JDBC datasources, supplemented by vendor catalogs, available JPA declarations and already-retained SQL Trace
observations. It never executes DDL, advances a sequence, queries application rows or starts work on page load.

These are structural observations and review prompts, not workload forecasts, business-model validation or
automatic migration instructions. A finding describes the available evidence, not everything the database
could contain. See [the advisor page](features/advisors.md#database) for availability.

## Availability and bounds

A known-findings score can remain usable with unread schemas or missing metadata, without treating those gaps as
passes. See the shared [score eligibility policy](features/advisors.md#score-eligibility).

Spring MVC, Spring WebFlux and Quarkus use the same engine and report contract. Native adapters discover and
de-duplicate datasource beans, including supported routing, delegating and SQL Trace wrappers. A successfully
empty inventory returns `DISABLED`; failed discovery returns `ERROR`, not a claim that no datasource exists.
Individual bean failures remain visible alongside successfully discovered pools.
If a vendor query fails mid-stream, that query's rows are discarded and its failure is diagnosed; successful
JDBC metadata and independent catalog families remain available. A normal row/deadline bound instead retains
the observed prefix with incomplete coverage.

The limits are 300 tables, 300 columns and 100 indexes per table, 500 rows per vendor query, a **cooperative**
20-second scan budget, and a 5-second catalog statement timeout clamped to the remaining budget. Bounded reads
detect truncation rather than treating the retained prefix as a complete inventory. Metadata row processing
also has finite bounds. JDBC timeouts have second-level granularity.
The table-inventory bound counts raw rows, including filtered relations and advertised views; a very small
bound can retain zero application tables without establishing that none exist.

**The scan budget is not a hard wall-clock guarantee.** Connection acquisition and JDBC-driver metadata calls
cannot necessarily be interrupted by BootUI. No background JDBC workers or pool-wide timeout changes are
introduced to simulate cancellation. The connection's original read-only state is restored when known;
unsupported hints and failed restoration are reported rather than silently ignored.

## What "could not be checked" looks like

- Datasources report `AVAILABLE`, `PARTIAL` or `FAILED`, with product, dialect and diagnostics.
- Unknown, failed, unsupported and truncated metadata are distinct from a confirmed absence. A completed JDBC
  method is not a universal guarantee that the driver/role reports every object.
- Rule results contain findings only. A rule with no eligible evidence is `SKIPPED`, not a fabricated `PASS`;
  missing coverage can be reported separately while retaining confirmed findings.
- The scan is `PARTIAL` when metadata or required evidence is incomplete, and `ERROR` when no discovered schema
  could be read. Normal wrong-dialect or absent optional-feature skips are informational.
- A PostgreSQL-only inventory makes MySQL/MariaDB and Oracle rules **not applicable**, not incomplete: their
  `SKIPPED` counters and `INFO` diagnostics remain, but they earn no completed-check credit and add no assessment
  limitations. An applicable vendor's unavailable/version-unsupported catalog is different: it records missing
  coverage even when the rule returns early, including mixed readable/unsupported datasource inventories.
  A feature known not to exist is still inapplicable (for example, publications before PostgreSQL 10);
  an unknown server version cannot establish that absence.
  Failed product identification also leaves applicability unknown. Assessment limitations summarize warning/error
  diagnostics, never these neutral wrong-vendor notes.
- Diagnostics are bounded and credential-redacted. They do not count as violations. Report status remains
  available to the shared scoring policy independently of retained findings and dismissals.

Qualified catalog identities preserve case and component boundaries: quoted names and dots inside names must
not collide. Composite keys preserve child-to-parent pairing and validated sequence order. An ambiguous
anonymous composite FK cannot safely be reconstructed from adjacent JDBC rows.

## Severity scale

**HIGH** denotes a concrete integrity/availability concern such as an explicitly invalid index, a known
generator frontier near its effective bound, or missing declared uniqueness. **MEDIUM** denotes a structural
discrepancy requiring contextual review. **LOW** denotes limited-evidence or lower-impact review prompts,
including exact index-definition overlap and observed SQL text variation.

Severity is not a prediction that the application is broken. The report sorts findings by severity, count
and stable rule ID and shows up to ten sample details per rule. Dismissals keep their existing stable IDs;
retired IDs are never reassigned.

## Schema

Generic checks consume JDBC metadata enriched by supported catalogs. System/temporary schemas, migration
bookkeeping, extension-owned objects and inherited child partitions are excluded where identified.

### DB-SCHEMA-001 - Tables without a primary key

**MEDIUM.** Reports an application table with no primary-key columns in readable, complete metadata.
A declared PK is useful row-identity documentation, but neither an ORM nor every replication arrangement
universally requires a database PK. Review whether a natural key, surrogate key or intentional keyless
relation is appropriate; absence alone does not prove unsafe updates or duplicate data.

### DB-SCHEMA-002 - Foreign key columns without a supporting index

**MEDIUM.** Reviews physical FKs without a known ordinary leading index access path over the complete child
column set. An equality lookup can use those leading columns in a different order; indexing just one column
of a composite FK is not equivalent. Known trailing expressions must not erase a usable leading key.

Partial, value-prefix and specialized definitions may require evidence this check does not have.
Incomplete index inventories cannot prove absence. MySQL/MariaDB engines that require FK support normally
create a suitable index automatically, so contradictory metadata warrants investigation rather than blind DDL.
Review parent-key changes and actual query plans before adding an index; an unindexed FK is not universally
invalid or slow. See [PostgreSQL FK constraints](https://www.postgresql.org/docs/17/ddl-constraints.html#DDL-CONSTRAINTS-FK),
[MySQL FK restrictions](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/create-table-foreign-keys.html) and
[Oracle concurrency](https://docs.oracle.com/en/database/oracle/oracle-database/19/cncpt/data-concurrency-and-consistency.html).

### DB-SCHEMA-003 - Duplicate/redundant indexes

**LOW.** Reviews exact ordinary-index definition overlap only when the relevant semantics are known.
A shorter leading prefix of a longer index is **not** sufficient evidence of redundancy. Included payload,
key order/direction, expressions, predicates, access method, collation/operator class, state and constraint
ownership can make superficially similar indexes different. Unknown definitions do not prove equality.
Review dependencies, hints and measured usage; BootUI does not assert that dropping an index is safe.
Generic JDBC and vendor catalogs lacking the complete comparison evidence can therefore leave this check
unevaluated. A readable datasource can still produce a `PARTIAL` report when an applicable comparison is unknown.

### DB-SCHEMA-004 - Foreign key column type mismatch with the referenced column

**MEDIUM.** Compares each child column with the column actually named by the FK, including alternate
referenced keys. Reports a known representational-domain discrepancy, not merely unequal type names.
Decimal containment considers both integral and fractional capacity; unknown scale is not zero.
Review intended value domains and vendor compatibility before aligning definitions. JDBC type-family
classification alone cannot establish coercion behavior or query-plan quality.

### DB-SCHEMA-005 - Redundant unique index duplicating the primary key

**LOW.** Reviews an additional exact unique-index definition only when the actual PK backing identity and
relevant index semantics are established. The first unique index with matching columns is not assumed to
be the backing index. Different included columns, access semantics or ownership prevent an equivalence
conclusion. Oracle may use a **nonunique** index to enforce a PK/unique constraint.
Review full definitions and dependencies, never drop a guessed constraint backing index.

### DB-SCHEMA-006 - Duplicate foreign key constraints

**LOW.** Reviews relationships with identical qualified parent identities and child-to-parent pairs,
including known update/delete actions and deferrability. Reordering the same pairs does not change the
relationship; swapping which parent column each child references does. Different actions or timing are
not redundant. Unmodeled enforcement/match semantics require checking full constraint definitions, not
unconditional removal.

### DB-SCHEMA-007 - Narrow auto-generated primary key

**LOW.** Reviews positively identified generated single-column `TINYINT`/`SMALLINT` keys.
Their finite representable domain may be intentional. Type capacity is not a lifetime row count, a count
of committed inserts or an exhaustion forecast. Review the intended domain; vendor generator checks
separately inspect an observed frontier. No automatic widening is recommended.

## Dialect detection and catalog augmentation

The product/version/JDBC metadata distinguishes PostgreSQL, MySQL and MariaDB. Oracle-specific augmentation
requires a genuine Oracle banner and Oracle 19c or later; unknown compatible products retain generic
metadata support. Oracle schema-resolution failure must not silently widen the scan to all visible schemas.
The confirmation read is separate from ordinary scoped `ALL_*` augmentation.

Queries gate features by the detected version: PostgreSQL sequence views from 10, INCLUDE key/payload
distinction from 11, index-build progress from 12 and NULLS NOT DISTINCT/schema publications from 15;
PostgreSQL 18 enforcement semantics are not assumed on older servers. MySQL visibility, functional keys
(8.0.13+) and MariaDB ignored indexes (10.6+) have separate capabilities. A denied or unsupported catalog
is not an empty successful one. Bounded detail rows must not replace a complete composite index with
only its first catalog key parts.

Primary contracts:
[Java 17 DatabaseMetaData](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/DatabaseMetaData.html),
[PostgreSQL pg_index](https://www.postgresql.org/docs/17/catalog-pg-index.html),
[MySQL STATISTICS](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/information-schema-statistics-table.html),
[MariaDB STATISTICS](https://mariadb.com/docs/server/reference/system-tables/information-schema/information-schema-tables/information-schema-statistics-table),
[Oracle ALL_INDEXES](https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_INDEXES.html).

## PostgreSQL

### DB-PG-001 - Invalid PostgreSQL indexes

**HIGH.** Reports known `indisvalid`/`indisready`/`indislive` problems, preserving transient-build and
partition-parent exclusions where supported. Planner validity, write maintenance and uniqueness are
different facts. An invalid UNIQUE index left by a failed concurrent build **may continue rejecting
duplicates**; invalid does not mean no enforcement or a guaranteed complete uniqueness guarantee.
Confirm build state and dependencies before choosing a version-supported repair.
See [CREATE INDEX CONCURRENTLY](https://www.postgresql.org/docs/17/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY).

### DB-PG-002 - PostgreSQL sequence nearing exhaustion

**HIGH.** Reviews the observed sequence frontier against direction-aware sequence bounds and a known
owning-column domain. Positive and negative increments and nondefault ranges matter.
`pg_sequences.last_value` may be null because of permissions, lack of use or standby state; that is unknown
consumption, not zero, and must not erase the sequence definition.

The 80% threshold describes a bounded-range snapshot, not remaining time. Cached reservations are not
committed identifiers. Cycling can still exceed a narrower owning column before wrapping.
Review sequence and column bounds together; restarting after deleting/archiving rows is not established safe.
See [CREATE SEQUENCE](https://www.postgresql.org/docs/17/sql-createsequence.html) and
[pg_sequences](https://www.postgresql.org/docs/17/view-pg-sequences.html).

### DB-PG-003 - PostgreSQL NOT VALID constraint never validated

**MEDIUM.** The retained ID reports a constraint **currently not validated**; the historical heading is
retained for existing links, not as a claim that it was never validated or that a migration was forgotten.
NOT VALID normally checks new/updated rows while leaving existing rows unverified. PostgreSQL 18 also
distinguishes enforcement state. Review the intended migration stage and version-supported validation;
the snapshot does not prove bad rows or how long the state has existed.
See [ALTER TABLE](https://www.postgresql.org/docs/17/sql-altertable.html) and
[PostgreSQL 18 pg_constraint](https://www.postgresql.org/docs/18/catalog-pg-constraint.html).

### DB-PG-004 - PostgreSQL table lacking usable replica identity

**MEDIUM.** Reviews a table in an applicable publication that publishes **UPDATE or DELETE**, using
expanded membership and partition-root semantics. INSERT-only publications are excluded.
Default identity without a PK and explicit NOTHING require review; unknown selected-index state is not
assumed usable. Relevant writes can fail without waiting for a subscriber to attach.
Review publication actions and choose an appropriate PK, supported identity index or FULL identity.
See [publications](https://www.postgresql.org/docs/17/logical-replication-publication.html) and
[pg_publication_tables](https://www.postgresql.org/docs/17/view-pg-publication-tables.html).

## MySQL and MariaDB

### DB-MYSQL-001 - Tables on a non-transactional storage engine

**MEDIUM.** Reviews known nontransactional engines while preserving intentional specialist exclusions.
Rollback support, FK enforcement, crash safety and locking are different capabilities: MariaDB Aria can
be crash-safe without being transactional. Review application transaction requirements and migration costs,
not merely whether the engine is named InnoDB.
See [MariaDB Aria](https://mariadb.com/docs/server/server-usage/storage-engines/aria).

### DB-MYSQL-002 - Tables/columns using the legacy utf8mb3 character set

**MEDIUM.** Reviews observed `utf8`/`utf8mb3` defaults or column encodings, not every non-utf8mb4 choice.
Three-byte encoding cannot represent the full Unicode range. Changing a table default is distinct from
converting existing columns. Review supported character sets, index lengths and required comparison
semantics before migration; a different collation can change uniqueness and ordering.

MariaDB **11.4.5+** supports MySQL-compatible `0900` names as aliases to UCA1400 collations.
This does not mean older MariaDB supports them or that the implementation is identical to MySQL's.
See [MariaDB 11.4.5](https://mariadb.com/docs/release-notes/community-server/11.4/11.4.5).

### DB-MYSQL-003 - MySQL/MariaDB AUTO_INCREMENT nearing exhaustion

**HIGH.** Reviews a reported next/reserved counter at or beyond the 80% threshold of the known
signed/unsigned column capacity. Arithmetic accommodates `BIGINT UNSIGNED`; missing counter or column
evidence is unknown, not zero or a clean pass.

InnoDB counter metadata can be cached/reserved/stale and is not a committed-row count. MariaDB has
persistent InnoDB counters from **10.2.4**, not only an in-memory counter on modern versions. Persistence
does not make allocation transactional or gapless. Review column bounds and referencing columns without
querying application `MAX(id)` or resetting counters.
See [MariaDB InnoDB counter handling](https://mariadb.com/docs/server/server-usage/storage-engines/innodb/auto_increment-handling-in-innodb).

## Oracle

Augmentation uses scoped `ALL_*` dictionary reads with bound owner parameters on confirmed Oracle 19c+,
without a production Oracle-driver dependency. Exact owner/object identity and independent partition
metadata are necessary; an inaccessible partition catalog does not establish that all partitions are usable.

### DB-ORACLE-001 - Unusable Oracle indexes

**HIGH.** Reports explicit `UNUSABLE` ordinary/partition/subpartition state. Null, unknown and `N/A`
are not synonyms for UNUSABLE. Domain-index special semantics are excluded consistently.
`GENERATED='Y'` describes an index's generated **name**, not constraint ownership.
An unusable unique enforcement index can block DML even with `SKIP_UNUSABLE_INDEXES` enabled.
Review exact index/partition type and dependencies before a suitable maintenance operation.
See [ALL_INDEXES](https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_INDEXES.html).

### DB-ORACLE-002 - Disabled or unvalidated Oracle constraints

**HIGH.** Interprets known STATUS and VALIDATED together, with enforcement, RELY and deferral kept
distinct. ENABLE NOVALIDATE checks new changes without proving old rows valid; DISABLE VALIDATE has
different restrictions from DISABLE NOVALIDATE. An automatically named NOT NULL check is not excluded
when its actual state is problematic. Unknown strings do not establish a disabled constraint.
Review the intended state and Oracle's `ENABLE VALIDATE CONSTRAINT` syntax and locking implications.
See [ALL_CONSTRAINTS](https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_CONSTRAINTS.html) and
[data integrity](https://docs.oracle.com/en/database/oracle/oracle-database/19/adfns/data-integrity.html).

### DB-ORACLE-003 - Oracle sequence or identity generator nearing exhaustion

**HIGH.** Reviews positive/negative sequence bounds and known linked identity-column precision.
`NUMBER(p,0)` can be much narrower than the underlying sequence; not every identifier is an unconstrained
NUMBER. Identity ownership is obtained from dictionary linkage, not guessed from an `ISEQ` name.

`LAST_NUMBER` includes cache reservation, not committed consumption. The 80% snapshot threshold is not a
time estimate. Session/scalable/sharded definitions remain excluded from ordinary range inference, and
cycling does not automatically protect a narrower column.
Oracle does not expose the original starting value through `ALL_SEQUENCES`: the denominator is the configured
directional min/max range, with both endpoints clamped to a known identity-column domain.
Use identity-aware column guidance for internal identity sequences; do not blindly alter/reset their
sequence. Review bounds and precision without querying application rows.
See [ALL_SEQUENCES](https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_SEQUENCES.html),
[ALL_TAB_IDENTITY_COLS](https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_TAB_IDENTITY_COLS.html)
and [NUMBER types](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Data-Types.html).

## Hibernate mapping

These checks compare available declarations with observed metadata, **not Hibernate's effective runtime
mapping**. Even explicit annotation/XML names are logical names subject to a physical naming strategy.
The pinned Spring Boot 4.1.1 BOM selects Hibernate 7.4.5.Final; Quarkus 3.33.3.1 selects 7.2.19.Final;
both use Persistence 3.2.0. The matching
[7.4](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/boot/model/naming/PhysicalNamingStrategy.html) and
[7.2](https://docs.hibernate.org/orm/7.2/javadocs/org/hibernate/boot/model/naming/PhysicalNamingStrategy.html)
contracts explicitly distinguish logical names from names used in generated DML/DDL.

No persistence-unit-to-datasource or effective optimizer contract is guessed. Failed or ambiguous source
inventories, unresolved inheritance/overrides, incomplete columns and unsupported association placement
cannot prove missing schema objects. Relation-name resolution must not confuse a mapped view with a
missing base table. Ordinary reflection also cannot distinguish an omitted annotation default from that
same default written explicitly.
The bridge recognizes annotation-visible converters and placement restrictions, but does not resolve
auto-applied converters, XML overrides or provider-specific effective JDBC mappings.

### DB-HIB-002 - Mapped entity table not found in the physical schema

**MEDIUM.** Reviews an explicit declared table name not observed in sufficiently complete scoped relation
metadata. This is not proof that the effective Hibernate table is missing. Review naming strategy,
relation type, privileges, migration and persistence-unit/datasource assignment before changing anything.

### DB-HIB-003 - Mapped column type/nullability mismatch

**MEDIUM.** Retains supported nondefault `nullable=false` comparison with known physical nullability.
Default-valued true does not establish explicit intent. Raw Java type family no longer proves an effective
JDBC type mismatch: converters, Boolean/UUID emulation and custom types are valid.
Review declarations and actual column constraints, not a guessed Java-to-SQL representation.

### DB-HIB-004 - Mapped column length longer than the physical column size

**MEDIUM.** Compares positive **nondefault** declared lengths with a positively bounded physical string
column. LOB, conversion, native-definition and unresolved placement ambiguity are excluded.
An arbitrary large length is not synonymous with an unbounded SQL type.
`@Column(length=...)` describes schema generation, not runtime input validation; review declaration versus
database definition rather than assuming the mapping accepts or validates every string of that length.

### DB-HIB-005 - Mapped unique constraint has no backing physical unique index

**HIGH.** Reviews declared uniqueness against known physical guarantees, separately from optimizer
visibility. Invisible/ignored UNIQUE indexes still enforce uniqueness. A value-prefix key may reject
*more* values without permitting duplicate full keys. Subset coverage also depends on null semantics:
Oracle `UNIQUE(a)` with nullable `a` can allow repeated `(NULL, 1)` rows that `UNIQUE(a, b)` rejects.
Such Oracle subset coverage needs known NOT NULL keys or equivalent evidence; it cannot be assumed.
INCLUDE payload is not a unique key. Oracle may use nonunique backing indexes for unique constraints.
Partial, invalid and unknown definitions require precise evidence. Review full constraint semantics
before adding a new guarantee.

### DB-HIB-006 - Mapped column not found in the physical table

**MEDIUM.** Reviews an explicit declared column name not observed in a resolved relation with complete
column metadata. Physical naming, inherited/secondary placement and source ambiguity must be considered.
Do not infer inevitable runtime SQL failure or prescribe applying a migration solely from annotation names.

### DB-HIB-007 - Mapped association has no physical foreign key constraint

**MEDIUM.** Reviews a complete explicit association declaration against actual qualified child-to-parent
pairs. Respects `NO_CONSTRAINT`, supported join placement and explicit target information.
JPA cascade does **not** imply database ON DELETE CASCADE; FK-generation annotations are not a proof of
the live database's intended cascade policy. Review whether a database constraint is intended before adding one.
See [Jakarta Persistence 3.2](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html).

## Runtime SQL

### DB-RUNTIME-001 - SQL text variations with predicate literals

**LOW.** Describes distinct retained SQL texts sharing a normalized shape that contains predicate literals,
within a bounded SQL Trace observation window. It issues no new query and reports bounded counts and an
opaque shape identifier, not captured literal values or allegedly guaranteed literal-free SQL text.

Changed comments, whitespace, projection constants or legitimate framework discriminator literals can
produce variation. This does **not** establish that predicate values changed, that code concatenated input,
that a plan cache is inefficient or that an injection vulnerability exists. A larger variant count is not
“high confidence” in any of those claims. Inspect the existing SQL Trace evidence and call site before
deciding whether parameterization is relevant. An absent capture window is SKIPPED.

## Retired rules

These IDs remain reserved so old dismissals stay harmless and are never applied to unrelated future checks.

### DB-SCHEMA-008 - Composite foreign key with partially nullable columns

Retired: mixed nullability can correctly model a required tenant and an optional relationship. MATCH SIMPLE
and MATCH FULL differ; uniform nullability does not establish business intent. Making every column nullable
can weaken integrity. See [PostgreSQL foreign keys](https://www.postgresql.org/docs/17/ddl-constraints.html#DDL-CONSTRAINTS-FK).

### DB-SCHEMA-009 - Composite unique index with partially nullable columns

Retired: NULLS DISTINCT can be intentional, and Oracle rejects equal non-null portions of partially null
composite unique keys, contrary to the previous generalized explanation.
See [Oracle integrity semantics](https://docs.oracle.com/en/database/oracle/oracle-database/19/cncpt/data-integrity.html).

### DB-HIB-001 - Mapped foreign key column has no physical index

Retired: without a physical FK, the residual mapped association does not establish a child-side index need.
Traversing an owning to-one association loads through the parent's referenced key. Physical FK access-path
review remains `DB-SCHEMA-002`; workload-specific child lookup tuning is not inferred.

### DB-HIB-008 - Hibernate sequence allocationSize does not match the physical sequence's INCREMENT BY

Retired: annotation allocation size alone lacks effective optimizer, mismatch strategy, qualified sequence
and persistence-unit provenance. Hibernate's documented FIX strategy can override the mapping from the
database. Compare effective generator contracts when diagnosing a real mismatch, not coincidentally equal
bare names. See matching
[Hibernate 7.4](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/id/SequenceMismatchStrategy.html) and
[7.2](https://docs.hibernate.org/orm/7.2/javadocs/org/hibernate/id/SequenceMismatchStrategy.html) APIs.

## Deliberately not checked

No application row counts, index cardinality/usage, bloat, cache-size tuning, sequence gaps, blanket
NOT NULL policy, automatic sequence restart, arbitrary query plans or production workload predictions.
Catalog snapshots can change concurrently and depend on driver coverage and role visibility.
Live MariaDB documentation is version-sensitive; Oracle 19c documentation can include later patch syntax.
An unsupported or unverified capability stays unknown rather than being silently assumed absent.
