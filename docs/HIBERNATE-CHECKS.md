# Hibernate Advisor checks

The Hibernate panel runs a fixed, on-demand ruleset against the host application's mapped JPA entities. It reads
the JPA `EntityManagerFactory` metamodel, selected persistence properties, and Spring Data repository metadata when
available; it does not intercept runtime queries, invoke repositories, execute SQL, or modify mappings.

The checks are heuristic review prompts. They highlight common Hibernate/JPA performance and maintainability risks, but
the right remediation still depends on the application's query patterns and data model.

## Availability and bounds

The panel is available only when Hibernate ORM and an `EntityManagerFactory` bean are present. If either is missing, or if
the metamodel cannot be read, BootUI returns a stable empty report with an explanatory status.

The scan is bounded to mapped entities reported by the application's own JPA metamodel. This also covers entities added
through `@EntityScan` or custom persistence-unit configuration without scanning the entire classpath.

## Severity scale

- **CRITICAL** - a configuration choice that can immediately damage production data. Currently emitted by
  HIB-CONFIG-002 for `ddl-auto=create` or `create-drop` under a production-like profile.
- **HIGH** - a mapping choice that commonly causes large performance surprises.
- **MEDIUM** - a mapping or configuration issue that usually warrants review before production use.
- **LOW** - reserved for lower-impact hygiene findings.
- **INFO** - informational prompts where the fix depends heavily on project context.

The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id. Each rule
includes up to a handful of sample mapped members plus a remediation link.

---

## Fetching

### HIB-FETCH-001 - Eager fetching should stay explicit and bounded

- **Severity**: HIGH
- **Inspects**: `@ManyToOne`, `@OneToOne`, `@OneToMany`, `@ManyToMany`, and `@ElementCollection` mappings on metamodel
  attributes.
- **Fires when**: a mapping resolves to `FetchType.EAGER`, including default-eager to-one associations where no `fetch`
  attribute is declared and element collections that explicitly opt into eager fetching.
- **Why it matters**: eager mappings are fetched whether or not the use case needs them, which can amplify query counts,
  payload size, and accidental object graph or collection-table loading.
- **Recommendation**: prefer `LAZY` mappings and fetch required data explicitly with joins, entity graphs, DTO
  projections, or targeted collection-value queries.

### HIB-FETCH-003 - Collection fetch joins should not be paged directly

- **Severity**: HIGH
- **Inspects**: Spring Data JPA `@Query` methods with a `Pageable` parameter and resolvable JPQL `JOIN FETCH` paths.
- **Fires when**: Hibernate is older than 7.4 (or the runtime version cannot be detected), Spring Data repository
  metadata is available, and a paged repository query fetch-joins a mapped collection on the repository domain entity.
- **Why it matters (Hibernate < 7.4)**: collection fetch joins duplicate root rows, and versions before 7.4 applied the
  `Pageable` limit in memory after loading the full, duplicated result set instead of at the SQL level.
- **Recommendation**: page root identifiers first, then fetch the required collection graph in a second query inside the
  same transaction.
- **Hibernate 7.4+**: the ["Limits and fetch joins"](https://github.com/hibernate/hibernate-orm/blob/7.4/migration-guide.adoc#limits-and-fetch-joins)
  migration-guide entry documents that the limit for a paged query with a collection `JOIN FETCH` is now applied in the
  generated SQL itself; the `org.hibernate.limitInMemory` query hint restores the pre-7.4 in-memory behavior. The check
  is skipped (not silently dropped) on 7.4+ runtimes, and the skip reason explains why.

### HIB-FETCH-004 - Review entities with multiple bag collections

- **Severity**: INFO
- **Inspects**: `@OneToMany` and `@ManyToMany` collection attributes.
- **Fires when**: an entity declares two or more unordered `List` / `Collection` associations (bags) without
  `@OrderColumn`.
- **Why it matters**: declaring multiple bags is common and safe on its own - the risk is only realized if two of them
  are ever join-fetched in the same query, which throws `MultipleBagFetchException`. HIB-QUERY-007 already flags that
  specific case (`JOIN FETCH` of 2+ collections in the same query); this rule is downgraded to an informational reminder
  to keep it that way rather than standing as a violation on ordinary, safe aggregate-root entities that never fetch
  both bags together.
- **Recommendation**: no action is required unless you plan to fetch these together: never `JOIN FETCH` more than one of
  these collections in the same query. Add `@OrderColumn` when list order is persistent, or use `Set<>` if you do need
  to fetch two of them eagerly in one query.

### HIB-FETCH-002 - Batch fetching should cover lazy secondary-select associations

- **Severity**: INFO
- **Inspects**: lazy to-one and collection mappings, `hibernate.default_batch_fetch_size` / Spring's
  `spring.jpa.properties.hibernate.default_batch_fetch_size`, association-level `@org.hibernate.annotations.BatchSize`,
  and target-entity `@BatchSize` for lazy to-one associations.
- **Fires when**: a lazy association can initialize through secondary selects and no global or applicable local batch
  fetch size is detected.
- **Why it matters**: lazy associations without batch fetching can produce N+1 select patterns when the same association
  is traversed across multiple owner rows.
- **Recommendation**: set a bounded global batch-fetch size or targeted `@BatchSize` for associations traversed across
  multiple owners; use explicit fetch plans or paged/filtered queries for a single oversized collection.
- **Quarkus**: `hibernate.default_batch_fetch_size` maps to `quarkus.hibernate-orm.fetch.batch-size` via
  `QuarkusHibernatePropertyLookup`, so a global batch-fetch size configured with the native Quarkus property name is
  read back correctly and this rule does not false-positive.

### HIB-FETCH-005 - Enhanced @Lob attributes should be loaded lazily

- **Severity**: MEDIUM
- **Inspects**: persistent attributes annotated with `@Lob` on bytecode-enhanced entities.
- **Fires when**: enhancement is available and a `@Lob` attribute does not declare
  `@Basic(fetch = FetchType.LAZY)`.
- **Why it matters**: large CLOB/BLOB payloads are read for every entity hydration unless lazy loading is requested.
- **Recommendation**: on enhanced entities, annotate infrequently accessed `@Lob` fields with
  `@Basic(fetch = FetchType.LAZY)`.
- **Enhancement requirement**: Hibernate ORM 7 requires bytecode enhancement to honor lazy basic attributes. This rule
  deliberately does not recommend `@Basic(fetch = LAZY)` when enhancement is unavailable; HIB-FETCH-007 instead reports
  existing ineffective lazy-basic declarations. Quarkus enhances entities at build time, while Spring applications must
  configure enhancement explicitly.

### HIB-FETCH-006 - Collection associations should not declare @Fetch(JOIN)

- **Severity**: MEDIUM
- **Inspects**: collection associations annotated with Hibernate's `@Fetch`.
- **Fires when**: a `@OneToMany` or `@ManyToMany` declares `@Fetch(FetchMode.JOIN)`.
- **Why it matters**: this forces every fetch path through a SQL JOIN, undermines pagination, and risks cartesian
  products.
- **Recommendation**: prefer `@Fetch(FetchMode.SELECT)` or `SUBSELECT` for collections, and request `JOIN FETCH` only in
  the specific queries that need the graph.

### HIB-FETCH-007 - Lazy basic attributes require bytecode enhancement

- **Severity**: MEDIUM
- **Inspects**: attributes declaring `@Basic(fetch = FetchType.LAZY)` and the owning entity's enhancement state.
- **Fires when**: a lazy basic attribute belongs to an entity that is not bytecode enhanced.
- **Why it matters**: without enhancement, Hibernate cannot intercept access to a basic field and the requested lazy
  column loading is ineffective.
- **Recommendation**: enable Hibernate bytecode enhancement, or remove the misleading `LAZY` declaration.

## Identifiers

### HIB-ID-001 - Generated identifiers should avoid GenerationType.IDENTITY

- **Severity**: MEDIUM
- **Inspects**: mapped attributes annotated with `@GeneratedValue`.
- **Fires when**: the generator strategy is `GenerationType.IDENTITY`.
- **Why it matters**: identity columns require the insert to execute immediately so Hibernate can read the generated key,
  which disables JDBC batch inserts for those entities.
- **Recommendation**: prefer `SEQUENCE` with an allocation size and Hibernate pooled optimizer when the database supports
  sequences.

### HIB-ID-002 - Generated identifiers should avoid GenerationType.TABLE

- **Severity**: HIGH
- **Inspects**: mapped attributes annotated with `@GeneratedValue`.
- **Fires when**: the generator strategy is `GenerationType.TABLE`.
- **Why it matters**: table generators emulate sequences through row updates, which can serialize identifier allocation
  under concurrent inserts.
- **Recommendation**: prefer `SEQUENCE` with pooled allocation, or `IDENTITY` only when the database has no sequence
  support.

### HIB-ID-003 - @SequenceGenerator should use pooled allocation

- **Severity**: MEDIUM
- **Inspects**: field-level and class-level `@SequenceGenerator` declarations.
- **Fires when**: `allocationSize=1`.
- **Why it matters**: allocation size 1 requires a sequence round-trip for every inserted row.
- **Recommendation**: use an allocation size greater than 1 and keep it aligned with the database sequence increment.

### HIB-ID-004 - @GeneratedValue should declare an explicit strategy

- **Severity**: MEDIUM
- **Inspects**: identifier attributes annotated with `@GeneratedValue`, excluding `UUID`-typed identifiers.
- **Fires when**: `strategy` is omitted (or set to `AUTO`).
- **Why it matters**: for numeric identifiers, `AUTO` never resolves to the database's native `IDENTITY`/auto-increment
  column. Hibernate's `GeneratorBinder` always maps the JPA `AUTO` strategy to its own `SequenceStyleGenerator`, which
  uses a real database `SEQUENCE` where the dialect supports one (PostgreSQL, Oracle, H2, SQL Server, ...) and falls back
  to a slower, row-locking table-based emulation otherwise (e.g. MySQL, which has no native sequence object) - verified
  directly against the current Hibernate ORM source (`correspondingGeneratorName(GenerationType)` in `GeneratorBinder`,
  whose `default` case - covering `AUTO` - always returns `SequenceStyleGenerator`, never `IDENTITY`) and the ORM user
  guide's "Interpreting AUTO" section. Leaving the strategy on `AUTO` means silently accepting whichever of those two
  Hibernate picks for the current database, instead of a strategy the team has actually reviewed.
- **Recommendation**: pick the strategy that fits the target database (for example `SEQUENCE` with `allocationSize` on
  Postgres/Oracle, `IDENTITY` only when truly required) and set it explicitly instead of relying on `AUTO`'s
  dialect-dependent sequence-or-table fallback.
- **UUID identifiers**: skipped entirely - for a `UUID`-typed identifier, Hibernate 6 interprets `AUTO` as the
  `UuidGenerator`, not a sequence, so this rule's "sequence-or-table fallback" rationale does not apply. HIB-ID-005 owns
  the UUID case exclusively, so a `UUID @Id @GeneratedValue` with no explicit strategy is flagged only once, by
  HIB-ID-005, with UUID-specific guidance instead of a misleading sequence/table warning.
- **Quarkus/Panache**: skipped for the `id` field inherited as-is from Panache's own `PanacheEntity` (ORM or Reactive),
  which declares `@Id @GeneratedValue public Long id;` with no explicit strategy and cannot be annotated by the
  application. A custom identifier the application declares itself (including on a `PanacheEntityBase` subclass) is
  still checked normally.

### HIB-ID-005 - UUID identifiers should use @UuidGenerator

- **Severity**: LOW
- **Inspects**: `UUID` identifier attributes annotated with `@GeneratedValue`, and the runtime Hibernate ORM version.
- **Fires when**: the attribute is not also annotated with `@UuidGenerator`.
- **Why it matters**: the JPA default generates random UUIDs (v4), which fragment B-tree indexes.
- **Recommendation (Hibernate 7.0+)**: annotate the identifier with `@UuidGenerator(style = VERSION_7)` (`VERSION_6` is
  also acceptable). Both styles are monotonic, index-friendly UUID variants and only exist starting in Hibernate 7.0
  (both `@Incubating`) - confirmed by diffing `UuidGenerator.java` between the 6.6 branch (only `AUTO`/`RANDOM`/`TIME`)
  and the 7.0 branch (adds `VERSION_6`/`VERSION_7`).
- **Recommendation (before Hibernate 7.0)**: `style = TIME` is the only style available. It produces an RFC 4122
  **version 1** UUID (per `@UuidGenerator`'s own Javadoc: "time-based generation strategy consistent with RFC 4122
  version 1, but with IP address instead of MAC address"), which places the fast-changing `time_low` field first and is
  **not materially more index-friendly than a random (v4) UUID** - it does not yield the monotonic ordering that makes
  `VERSION_6`/`VERSION_7` genuinely index-friendly. The recommendation is annotated with this caveat rather than
  presenting `TIME` as a real fix.
- **Version detection**: the runtime Hibernate ORM version is read from `HibernateContext`/`HibernateEntityModel` the
  same way HIB-FETCH-003/HIB-CONFIG-016 gate their Hibernate-7.4 pagination behavior.

### HIB-ID-006 - GenerationType.IDENTITY disables JDBC batch inserts

- **Severity**: HIGH
- **Inspects**: `spring.jpa.properties.hibernate.jdbc.batch_size`, `hibernate.jdbc.batch_size`, and identifier attributes
  annotated with `@GeneratedValue`.
- **Fires when**: a positive JDBC batch size is configured and an identifier uses `GenerationType.IDENTITY`.
- **Why it matters**: Hibernate must execute each identity insert immediately so it can read back the generated key,
  preventing JDBC insert batching for those entities despite the configured batch size.
- **Recommendation**: switch IDENTITY identifiers to `SEQUENCE` with a pooled `allocationSize` so Hibernate can batch
  inserts, or drop the JDBC batch-size expectation for these entities.

### HIB-ID-007 - Composite identifier classes must satisfy the JPA contract

- **Severity**: HIGH
- **Inspects**: `@EmbeddedId` attribute types and `@IdClass` values (including inherited from a superclass).
- **Fires when**: the composite identifier class is not `Serializable`, has no public no-arg constructor, or does not
  override both `equals` and `hashCode`.
- **Why it matters**: JPA and Hibernate require all four for a composite identifier class to work correctly - this is a
  pure, deterministic contract check with essentially zero false-positive risk. Violating any of these can silently
  break entity equality, second-level caching, and collection lookups.
- **Recommendation**: make the composite identifier class implement `Serializable`, declare a public no-arg constructor,
  and override both `equals` and `hashCode` over every identifier field.
- **Quarkus/Panache**: applies identically - this check inspects only the identifier class via reflection, with no
  framework-specific behavior.

## Mapping

### HIB-MAP-001 - One-to-many associations should be bidirectional or join-column based

- **Severity**: MEDIUM
- **Inspects**: `@OneToMany` mappings.
- **Fires when**: a one-to-many association has neither `mappedBy` nor `@JoinColumn` / `@JoinColumns`.
- **Why it matters**: Hibernate models that shape through a join table by default, which often produces extra DML and a
  less obvious schema.
- **Recommendation**: prefer a bidirectional association with `@ManyToOne` on the child and `@OneToMany(mappedBy=...)`
  on the parent so the child's foreign key owns the relationship. If a unidirectional mapping is intentional, add
  `@JoinColumn` to drop the join table and review the extra update statements flagged by HIB-MAP-020.

### HIB-MAP-002 - Many-to-many associations should use Set semantics

- **Severity**: MEDIUM
- **Inspects**: `@ManyToMany` mappings and their Java collection type.
- **Fires when**: a many-to-many association is declared as `List`.
- **Why it matters**: list-backed many-to-many mappings can force delete-and-reinsert behavior for join-table rows.
- **Recommendation**: use `Set`, or model the join table as an entity when it has attributes or business meaning.

### HIB-MAP-003 - Enum attributes should declare an explicit storage strategy

- **Severity**: MEDIUM
- **Inspects**: enum-valued mapped attributes.
- **Fires when**: an enum attribute omits `@Enumerated` and therefore relies on JPA's default ordinal storage.
- **Why it matters**: default ordinal persistence is easy to enable accidentally, and reordering enum constants changes the
  stored meaning unless the ordinal values are treated as a stable schema contract.
- **Recommendation**: use `@Enumerated(EnumType.STRING)`, a database-native enum type, or an explicit converter with
  stable database codes.

### HIB-MAP-004 - Many-to-many associations should not cascade remove

- **Severity**: HIGH
- **Inspects**: `@ManyToMany` cascade settings.
- **Fires when**: the cascade list contains `REMOVE` or `ALL`.
- **Why it matters**: many-to-many targets usually have independent lifecycles, so delete cascades can remove shared rows
  instead of only join-table links.
- **Recommendation**: remove `REMOVE` / `ALL`; use `PERSIST` / `MERGE` only when needed, or model the join table as an
  entity.

### HIB-MAP-005 - Many-to-one associations should not cascade remove

- **Severity**: HIGH
- **Inspects**: `@ManyToOne` cascade settings.
- **Fires when**: the cascade list contains `REMOVE` or `ALL`.
- **Why it matters**: child-to-parent delete cascade can remove a parent shared by other children.
- **Recommendation**: cascade lifecycle operations from aggregate roots to owned children, not from children to parents.

### HIB-MAP-006 - One-to-one associations should prefer shared primary keys

- **Severity**: MEDIUM (LOW when no lifecycle-dependency signal is detected)
- **Inspects**: owning-side `@OneToOne` mappings.
- **Fires when**: the association has no `mappedBy`, no `@MapsId`, and the association itself is not the identifier. The
  finding is MEDIUM when the mapping looks lifecycle-dependent (`optional=false` or cascade `REMOVE` / `ALL`) and LOW
  otherwise.
- **Why it matters**: dependent one-to-one rows often share the parent lifecycle and can avoid an extra foreign-key/index
  pair by sharing the primary key.
- **Recommendation**: use `@MapsId` when the child row is lifecycle-dependent on the parent; keep a separate foreign key
  only when the model requires independent identity.

### HIB-MAP-007 - Entity inheritance should avoid TABLE_PER_CLASS

- **Severity**: MEDIUM
- **Inspects**: class-level `@Inheritance`.
- **Fires when**: the strategy is `InheritanceType.TABLE_PER_CLASS`.
- **Why it matters**: polymorphic queries over the base type require a `UNION` across concrete subtype tables.
- **Recommendation**: prefer `SINGLE_TABLE` or `JOINED` unless each subtype is always queried independently.

### HIB-MAP-008 - @NotFound(IGNORE) should be reviewed

- **Severity**: MEDIUM
- **Inspects**: Hibernate `@NotFound` annotations.
- **Fires when**: `action=IGNORE`.
- **Why it matters**: missing target rows are silently treated as null and the association must be resolved eagerly to know
  whether the target exists.
- **Recommendation**: repair referential integrity or model optional data explicitly instead of suppressing missing
  targets.

### HIB-MAP-009 - Persistent attributes should not be Optional

- **Severity**: MEDIUM
- **Inspects**: mapped attributes whose raw Java type is `java.util.Optional`.
- **Fires when**: an `Optional` field or property is part of the mapped model.
- **Why it matters**: `Optional` is a return-type convenience, not a stable persistent attribute type.
- **Recommendation**: map the underlying nullable type and expose `Optional` from a non-persistent getter if desired.

### HIB-MAP-010 - @ElementCollection List should persist order with @OrderColumn

- **Severity**: MEDIUM
- **Inspects**: `@ElementCollection` attributes typed as `List`.
- **Fires when**: the list does not declare `@OrderColumn` - regardless of whether it declares `@OrderBy`.
- **Why it matters**: without `@OrderColumn`, Hibernate cannot compute a minimal diff on mutation and instead deletes and
  reinserts the entire collection table. `@OrderBy` is **not** an equivalent fix: it only adds a query-time SQL
  `ORDER BY` and is never persisted, so it does nothing to change this mutation behavior. The sibling rule HIB-MAP-004's
  `isBagAttribute()` helper already treats only `@OrderColumn` as removing bag semantics and deliberately ignores
  `@OrderBy`, so this rule now matches that same standard instead of presenting `@OrderBy` as an alternative.
- **Recommendation**: add `@OrderColumn` to persist the list's index so Hibernate can update rows in place. `@OrderBy`
  does not solve the mutation cost - keep it only if you also want read-time ordering, alongside `@OrderColumn`, not
  instead of it. Otherwise prefer `Set<>` if insertion order does not matter.

### HIB-MAP-011 - Entity classes should not be final

- **Severity**: HIGH
- **Inspects**: `@Entity` classes for the `final` modifier.
- **Fires when**: an entity is declared `final`.
- **Why it matters**: Hibernate cannot create runtime proxies for lazy associations or bytecode-enhanced state when the
  class is final.
- **Recommendation**: remove the `final` modifier from entities (and avoid Kotlin classes without `open`).

### HIB-MAP-012 - SINGLE_TABLE inheritance should declare @DiscriminatorColumn

- **Severity**: INFO
- **Inspects**: `@Inheritance(strategy = SINGLE_TABLE)` roots.
- **Fires when**: the root entity does not declare `@DiscriminatorColumn`.
- **Why it matters**: the default discriminator name, type, and length are implicit, which makes generated schemas and
  cross-team reviews harder to reason about.
- **Recommendation**: declare `@DiscriminatorColumn` explicitly so the chosen contract is visible.

### HIB-MAP-013 - String columns should declare explicit length

- **Severity**: INFO
- **Inspects**: persistent `String` attributes (excluding identifiers and `@Lob` fields).
- **Fires when**: there is no `@Column(length=...)` and no `columnDefinition`.
- **Why it matters**: generated DDL defaults to 255 characters, which can clash with domain expectations or database
  constraints.
- **Recommendation**: set `@Column(length=...)` to match the domain, or use `@Lob`/`columnDefinition` for free-text
  payloads.

### HIB-MAP-014 - BigDecimal columns should declare precision and scale

- **Severity**: MEDIUM
- **Inspects**: persistent `BigDecimal` attributes.
- **Fires when**: `@Column(precision=..., scale=...)` is missing or precision is zero.
- **Why it matters**: provider defaults vary by database, which can silently round monetary or scientific values.
- **Recommendation**: always set precision and scale on `BigDecimal` mappings so DDL and Bean Validation agree.

### HIB-MAP-015 - Date/time attributes should use java.time

- **Severity**: LOW
- **Inspects**: persistent attributes typed as `java.util.Date`, `java.util.Calendar`, or `java.sql` temporal types.
- **Fires when**: any of those legacy types is detected on a mapped attribute.
- **Why it matters**: legacy temporal types are mutable, not time-zone aware, and require `@Temporal` plumbing.
- **Recommendation**: migrate to `java.time` (`Instant`, `LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`)
  so JDBC binding is immutable and explicit about zones.

### HIB-MAP-016 - @ManyToOne should set optional=false when the join column is non-nullable

- **Severity**: LOW
- **Inspects**: `@ManyToOne` associations and their `@JoinColumn`.
- **Fires when**: `@JoinColumn(nullable=false)` is set but `optional=false` is not.
- **Why it matters**: when the foreign key is mandatory, Hibernate can skip the secondary SELECT it would otherwise
  issue to discriminate between null and a real proxy.
- **Recommendation**: set `@ManyToOne(optional=false)` whenever the join column is non-nullable.

### HIB-MAP-017 - Lazy owning @OneToOne requires bytecode enhancement

- **Severity**: INFO
- **Inspects**: optional owning `@OneToOne` associations.
- **Fires when**: the association is `FetchType.LAZY`, `optional` is not `false`, no `@MapsId` is declared, and Hibernate
  bytecode enhancement is not enabled.
- **Why it matters**: Hibernate cannot proxy the missing-or-present discriminator on the owning side without the
  enhancer, so it silently fetches the association eagerly.
- **Recommendation**: enable `hibernate.bytecode.enhancer.enableLazyInitialization` (and configure the enhancement
  plugin), or switch to `@MapsId` so the existing foreign key drives loading.
- **Quarkus**: bytecode enhancement is always considered enabled. Quarkus enhances every Hibernate ORM entity's
  bytecode unconditionally at build time (there is no `quarkus.hibernate-orm.enhancement.*` opt-out), so this check
  never fires there.

### HIB-MAP-018 - Non-owning @OneToOne triggers N+1 queries

- **Severity**: HIGH
- **Inspects**: non-owning (`mappedBy`) `@OneToOne` associations and whether Hibernate bytecode enhancement is enabled.
- **Fires when**: bytecode enhancement is disabled and an entity declares a non-owning `@OneToOne` association.
- **Why it matters**: without bytecode enhancement Hibernate cannot create a lazy proxy for a non-owning `@OneToOne`, so
  it loads the association eagerly with an extra query per parent row — the classic N+1 pattern.
- **Recommendation**: enable bytecode enhancement, or replace the bidirectional `@OneToOne` with a shared primary key
  (`@MapsId`) and a unidirectional mapping.
- **Quarkus**: bytecode enhancement is always considered enabled (see HIB-MAP-017), so this check never fires there.

### HIB-MAP-019 - Missing foreign key indexes

- **Severity**: INFO
- **Inspects**: `@ManyToOne` and owning `@OneToOne` join columns against the leading columns of `@Index` declarations in
  the entity's `@Table` mapping.
- **Fires when**: Hibernate/Jakarta schema management is set to a schema-creating mode (`create`, `create-only`,
  `create-drop`, `drop-and-create`, or `update`), an association declares an explicit join-column name, and that column
  is not the leading column of any JPA-declared `@Index`.
- **Why it matters**: databases do not always index foreign keys automatically. An unindexed foreign key forces full table
  scans when joining the association or when deleting parent rows for constraint and cascade checks, which can lead to
  lock contention and deadlocks.
- **Recommendation**: declare an `@Index` in `@Table` with the foreign-key column as the leading column. If you manage the
  schema with Flyway/Liquibase, ensure the index exists in your migrations.
- **Noise controls**: inferred join-column names are skipped because a physical naming strategy can rewrite them, and
  migration-managed schemas are skipped because JPA annotations cannot prove whether the migration created an index.

### HIB-MAP-020 - Unidirectional @OneToMany with @JoinColumn issues extra UPDATE statements

- **Severity**: MEDIUM
- **Inspects**: unidirectional `@OneToMany` associations and their join-column annotations.
- **Fires when**: a `@OneToMany` has no `mappedBy`, declares a join column, and that join column is not read-only
  (`insertable=false, updatable=false`).
- **Why it matters**: Hibernate inserts the child rows first and then issues separate `UPDATE` statements to set the
  foreign key.
- **Recommendation**: make the association bidirectional with `@ManyToOne` on the child and `@OneToMany(mappedBy=...)` on
  the parent so the child's foreign key is written in the `INSERT`. A read-only `@JoinColumn(insertable=false,
  updatable=false)` is exempt.

### HIB-MAP-021 - Legacy @Where restrictions should be migrated

- **Severity**: MEDIUM
- **Inspects**: entity and persistent-attribute annotations by name, without linking the optional Hibernate 6 type.
- **Fires when**: `org.hibernate.annotations.Where` or `WhereJoinTable` is present.
- **Why it matters**: Hibernate deprecated these annotations in ORM 6.3 and removed them in ORM 7, so mappings must be
  migrated before or during an ORM 7 upgrade.
- **Recommendation**: use `@SQLRestriction` / `@SQLJoinTableRestriction`, or Hibernate's `@SoftDelete` for supported
  soft-delete mappings.

### HIB-MAP-022 - Explicit ordinal enum mappings should be reviewed

- **Severity**: INFO
- **Inspects**: enum attributes declaring `@Enumerated(EnumType.ORDINAL)`.
- **Fires when**: ordinal storage is explicit, so the mapping is intentional but still carries a schema-evolution risk.
- **Why it matters**: inserting or reordering enum constants changes the meaning of existing numeric values.
- **Recommendation**: prefer `STRING`, a database-native enum, or a stable-code converter. Keep `ORDINAL` only when
  append-only ordering is an explicit schema contract.

## Entity design

### HIB-ENTITY-001 - Entities should override equals and hashCode consistently

- **Severity**: INFO
- **Inspects**: entity classes for detectable `equals(Object)` and `hashCode()` overrides.
- **Fires when**: an entity overrides one method but not the other.
- **Why it matters**: inconsistent equality contracts break sets, maps, and Hibernate collection semantics.
- **Recommendation**: implement `equals` and `hashCode` as a pair, and review generated identifier semantics before using
  entities in hash-based collections.

### HIB-ENTITY-002 - Versionless optimistic locking should use dynamic updates

- **Severity**: MEDIUM
- **Inspects**: Hibernate `@OptimisticLocking`.
- **Fires when**: `type=DIRTY` or `type=ALL` is used without `@DynamicUpdate`.
- **Why it matters**: versionless optimistic locking relies on update predicates that match the chosen locking strategy.
- **Recommendation**: add `@DynamicUpdate` when using versionless optimistic locking, or use a regular `@Version` column
  for simpler optimistic locking.

### HIB-ENTITY-003 - equals/hashCode should not include lazy associations

- **Severity**: INFO
- **Inspects**: entities that override both `equals` and `hashCode` and declare JPA associations.
- **Fires when**: such an entity is detected. Generated implementations (Lombok `@Data` / `@EqualsAndHashCode` without
  exclusions, IDE templates) typically include those associations.
- **Why it matters**: comparing entities that participate in collections then triggers lazy loading or proxy/initialized
  mismatches.
- **Recommendation**: base `equals` and `hashCode` on a stable business key or natural id only; exclude lazy associations
  explicitly when generated tooling is used.

### HIB-ENTITY-004 - toString should not include lazy associations

- **Severity**: INFO
- **Inspects**: entities that override `toString` and declare JPA associations.
- **Fires when**: such an entity is detected. Generated implementations (Lombok `@Data` / `@ToString` without
  exclusions, IDE templates) typically traverse associations.
- **Why it matters**: logging or debugging the entity then pulls the object graph, triggering N+1 lazy loads or
  `LazyInitializationException` outside an open session.
- **Recommendation**: base `toString` on the identifier and a few stable scalar fields; exclude associations explicitly
  (for example with `@ToString(exclude = ...)`).

### HIB-ENTITY-005 - Persistent fields should not be public

- **Severity**: LOW
- **Inspects**: entity attributes backed by a `java.lang.reflect.Field` that is `public`.
- **Fires when**: a field-access persistent field is `public`. A field annotated `@Transient` is never flagged - it is
  not written to the database, so it carries none of the accessor-bypass risk this check targets (for example a public
  `@Transient` flag field used by a hand-written `isNew()`/`Persistable` implementation). Property-access (getter-mapped)
  attributes are never flagged either, even when the underlying getter method is `public` - the metamodel resolves those
  attributes from a `Method`, not a `Field`, and public getters are the normal, fully JPA/Hibernate-instrumented way to
  expose a property-access attribute.
- **Why it matters**: public fields let callers bypass Hibernate's instrumentation for lazy loading and dirty tracking.
- **Recommendation**: keep persistent fields private (or package-private) and expose mutators when needed; this
  preserves proxy substitution and bytecode-enhancer guarantees.
- **Quarkus/Panache**: skipped app-wide when a Panache extension (`quarkus-hibernate-orm-panache` or
  `quarkus-hibernate-reactive-panache`) is on the runtime classpath. Panache's active-record entities are meant to be
  used with public fields; once a Panache extension is present, its build-time bytecode transformation rewrites *every*
  public field access on *any* Hibernate-managed class (not just `PanacheEntityBase` subclasses) into the matching
  getter/setter call, so the accessor-bypass concern this check targets does not apply.

### HIB-ENTITY-006 - Avoid primitive @Id or @Version types

- **Severity**: HIGH
- **Inspects**: the Java type of attributes annotated with `@Id`, `@EmbeddedId`, or `@Version`.
- **Fires when**: an identifier or version attribute uses a primitive type.
- **Why it matters**: primitives have default values (for example `0` for `long`), so Hibernate/JPA can no longer treat
  `null` as the signal for "not yet persisted." Frameworks and code paths that rely on that signal - Spring Data's
  default `isNew()` strategy, Hibernate's own transient/detached detection for cascading and merging - can then
  misidentify a genuinely new entity as already existing, triggering an unnecessary pre-`INSERT` `SELECT` or a wrong
  merge decision.
- **Recommendation**: use wrapper classes (`Long`, `Integer`) so the default value is `null`, enabling both Hibernate and
  Spring Data to cleanly detect new entities and skip the pre-insert `SELECT`.
- **Quarkus/Panache**: applies identically. This check inspects only the mapped Java type, so it fires the same way on
  a Panache active-record entity's `id`/`version` fields as on a plain getter/setter entity.

### HIB-ENTITY-007 - Assigned IDs should implement Persistable

- **Severity**: MEDIUM
- **Inspects**: entities with assigned identifiers (lacking `@GeneratedValue`) and no `@Version` attribute.
- **Fires when**: the entity does not implement `org.springframework.data.domain.Persistable`.
- **Why it matters**: when using assigned identifiers such as a natural key or application-created UUID, Spring Data cannot
  determine whether the entity is new or detached because the ID is already populated. It assumes the entity might exist
  and issues a `SELECT` before `INSERT`.
- **Recommendation**: implement `Persistable<ID>` and manage the `isNew()` flag manually, for example via a `@Transient`
  flag set after loading or defaulting to true, so Spring Data avoids the unnecessary `SELECT` before every insert.
- **Quarkus**: skipped entirely (whole-rule) when `spring-data-commons` is not on the classpath. The concern is specific
  to Spring Data's repository `save()` merge-vs-persist decision; Panache's own `persist()`/`persistAndFlush()` always
  issues an `INSERT` and never probes existence first, so there is nothing to recommend on a Quarkus/Panache app.

### HIB-ENTITY-008 - Mutable entities should declare @Version for optimistic locking

- **Severity**: INFO
- **Inspects**: mapped entities that carry non-identifier persistent state (at least one attribute that is not an `@Id`,
  `@EmbeddedId`, `@Version`, association, or `@Transient`).
- **Fires when**: the entity declares no `@Version` attribute and has not opted into versionless optimistic locking
  (`@OptimisticLocking(DIRTY|ALL)`) or `@org.hibernate.annotations.Immutable`.
- **Why it matters**: without a version column, two concurrent transactions that read and then update the same row will
  silently overwrite each other's changes (a lost update) because nothing detects the stale snapshot.
- **Recommendation**: add a `@Version` attribute (for example a `Long` or `Instant`) so concurrent updates fail fast with
  an optimistic-lock exception; skip this only for append-only, read-only, or reference data where lost updates cannot
  occur.

### HIB-ENTITY-009 - Unique business-key columns should consider @NaturalId

- **Severity**: INFO
- **Inspects**: `@Column(unique = true)` attributes and entity-level `@Table(uniqueConstraints = @UniqueConstraint(...))`
  declarations.
- **Fires when**: a unique column exists and no attribute on the entity is annotated
  `org.hibernate.annotations.NaturalId`.
- **Why it matters**: a unique business key (email, ISBN, order number) is frequently queried by value.
  `@NaturalId` lets Hibernate resolve the entity from the natural-id cache/lookup without needing a full JPQL query - a
  well-documented performance pattern. This is advisory, not a defect: a unique column is not always a natural lookup
  key, so the finding is INFO severity.
- **Recommendation**: consider annotating the business-key attribute(s) with `@NaturalId` so lookups by that value can
  use Hibernate's natural-id resolution instead of a full query.
- **Quarkus/Panache**: applies identically - this check inspects only annotation metadata via reflection.

## Query

All HIB-QUERY rules currently inspect Spring Data repository metadata. Quarkus/Panache does not expose equivalent
runtime repository/query metadata with enough fidelity to evaluate these rules reliably, so they remain unavailable on
Quarkus rather than guessing from Panache method names or generated bytecode. Mapping, identifier, fetching, configuration,
and caching rules still run on Quarkus against the live JPA metamodel.

### HIB-QUERY-001 - @Modifying queries should clear or flush the persistence context

- **Severity**: HIGH
- **Inspects**: Spring Data JPA `@Modifying` annotations on repository methods.
- **Fires when**: a `@Modifying` method does not set `clearAutomatically` or `flushAutomatically`.
- **Why it matters**: the persistence context can hold stale entities after a bulk update or delete, leading to
  hard-to-diagnose data inconsistencies.
- **Recommendation**: set `@Modifying(clearAutomatically=true)` (and `flushAutomatically=true` when pending changes must
  be applied first), or evict affected entities explicitly before issuing the bulk statement.

### HIB-QUERY-002 - Streaming repository methods need a transactional, read-only scope

- **Severity**: MEDIUM
- **Inspects**: Spring Data repository methods returning `java.util.stream.Stream`.
- **Fires when**: a repository method returns `Stream<>`.
- **Why it matters**: streaming methods keep the underlying JDBC cursor open and only behave correctly inside an open
  transaction with the caller closing the stream.
- **Recommendation**: annotate the caller with `@Transactional(readOnly = true)`, consume the stream inside a
  try-with-resources block, and close it before the transaction ends; otherwise prefer `Page<>` or a bounded `List<>`.

### HIB-QUERY-003 - Native paged @Query must declare countQuery

- **Severity**: HIGH
- **Inspects**: Spring Data `@Query(nativeQuery=true)` methods with `Pageable` or returning `Page<>`.
- **Fires when**: `countQuery` is missing.
- **Why it matters**: Spring Data cannot derive a correct COUNT statement from a native query, so paging either fails to
  start or executes the wrong COUNT.
- **Recommendation**: add `countQuery = "..."` to the `@Query` so paging can compute totals reliably.

### HIB-QUERY-004 - Derived deleteBy methods load entities before deletion

- **Severity**: MEDIUM
- **Inspects**: Spring Data derived query methods named `deleteBy...` or `removeBy...`.
- **Fires when**: a derived delete method has no explicit `@Query`.
- **Why it matters**: Spring Data implements derived deletes by selecting matching entities first and deleting them one
  by one, which is expensive on large result sets.
- **Recommendation**: for bulk removals prefer an explicit `@Modifying @Query("delete from ... where ...")` with
  `clearAutomatically=true`; reserve derived `deleteBy` methods for small or cascading deletes.

### HIB-QUERY-005 - Eager to-one associations should be JOIN FETCHed in entity-returning queries

- **Severity**: INFO
- **Inspects**: Spring Data JPQL `@Query` methods that return multiple whole entities (`List`/`Set`/`Collection`,
  `Stream`, `Page`, `Slice`, or arrays) on a repository whose domain entity declares an eager `@ManyToOne` / `@OneToOne`
  association (explicit `FetchType.EAGER` or the to-one default) without `@Fetch(JOIN)` / `@Fetch(SUBSELECT)`.
- **Fires when**: such a query selects the whole root entity (for example `select o from Entity o`) but does not
  `JOIN FETCH` the eager to-one association.
- **Why it matters**: JPQL does not automatically add joins for eager to-one mappings, so Hibernate issues an extra
  secondary `SELECT` for the association on every returned row, producing an N+1 query pattern.
- **Recommendation**: `JOIN FETCH` the eager association in the query, or map it `FetchType.LAZY` (see HIB-FETCH-001) and
  fetch it explicitly only where the use case needs it. Complements HIB-FETCH-001 by pinpointing the specific finders
  affected.

### HIB-QUERY-006 - Paged or streamed reads should prefer DTO projections over whole entities

- **Severity**: INFO
- **Inspects**: Spring Data JPQL `@Query` methods that are paged or streamed (a `Pageable` parameter, or a `Page`,
  `Slice`, or `Stream` return type).
- **Fires when**: the query selects the whole root entity (for example `select o from Entity o`) rather than a
  constructor expression (`select new ...(...)`) or an interface/DTO projection.
- **Why it matters**: hydrating whole managed entities for read-mostly, paged, or streamed endpoints loads every mapped
  column and tracks each row in the persistence context, which is wasteful when the caller only needs a few fields.
- **Recommendation**: return a DTO/interface projection so Hibernate selects only the columns the caller needs; reserve
  whole-entity reads for cases that mutate the loaded entities.

### HIB-QUERY-007 - Queries should not JOIN FETCH more than one collection

- **Severity**: HIGH (MEDIUM for multi-collection fetches without multiple bags)
- **Inspects**: Spring Data repository `@Query` methods, resolvable JPQL `JOIN FETCH` paths, and collection/bag metadata
  on the repository domain entity.
- **Fires when**: a non-native JPQL query `JOIN FETCH`es two or more collection associations from the same root entity.
  Two or more bag collections are reported as HIGH; other multi-collection fetch joins are reported as MEDIUM.
- **Why it matters**: fetching multiple bags throws `MultipleBagFetchException`, and fetching multiple collections
  multiplies the result set into a cartesian product.
- **Recommendation**: fetch at most one collection per query. Initialize the remaining collections with separate queries,
  `@EntityGraph` attribute nodes, or `@BatchSize` / `default_batch_fetch_size`, and use `Set` collections to avoid
  `MultipleBagFetchException`.

## Configuration

### HIB-CONFIG-001 - Open Session in View should be disabled

- **Severity**: INFO when inferred from Spring Boot's absent-property default; MEDIUM for explicit `true`; HIGH for
  explicit `true` under a production-like profile
- **Inspects**: adapter-provided servlet applicability, `spring.jpa.open-in-view`, and active Spring profiles.
- **Fires when**: the application is a Spring servlet web application and the property is `true` or absent, matching
  Spring Boot's servlet default.
- **Why it matters**: lazy loading after the service transaction has completed can hide missing fetch plans and move data
  access into the web layer.
- **Recommendation**: set `spring.jpa.open-in-view=false` and fetch data inside transactional service boundaries.
- **Applicability**: the Spring adapter supplies the servlet-context signal to the framework-neutral engine. Reactive,
  non-web, and Quarkus applications skip this rule; Quarkus has no Open Session in View mechanism.

### HIB-CONFIG-003 - Lazy loading outside transactions should stay disabled

- **Severity**: HIGH
- **Inspects**: `hibernate.enable_lazy_load_no_trans` and Spring's `spring.jpa.properties.*` variant.
- **Fires when**: the property is `true`.
- **Why it matters**: it hides missing fetch plans by opening temporary sessions outside the intended transaction boundary.
- **Recommendation**: remove the setting and fetch required data inside transactions with explicit fetch plans or DTO
  queries.

### HIB-CONFIG-004 - JDBC batching should be configured for writes

- **Severity**: INFO
- **Inspects**: `hibernate.jdbc.batch_size` and Spring's `spring.jpa.properties.*` variant.
- **Fires when**: the property is absent or non-positive.
- **Why it matters**: write-heavy code otherwise sends insert/update/delete statements one at a time.
- **Recommendation**: set a bounded batch size, such as 25, and tune it with representative workloads.

### HIB-CONFIG-005 - JDBC batching should order inserts and updates

- **Severity**: INFO
- **Inspects**: `hibernate.order_inserts` and `hibernate.order_updates` when JDBC batching is enabled.
- **Fires when**: a positive batch size is configured but either ordering property is not `true`.
- **Why it matters**: batches are grouped by SQL/table shape; interleaved entity types reduce batch efficiency.
- **Recommendation**: enable both ordering properties when batching writes across multiple entity types.
- **Quarkus**: `hibernate.order_inserts`/`hibernate.order_updates` have no first-class `quarkus.hibernate-orm.*`
  equivalent, but `QuarkusHibernatePropertyLookup` falls back to Quarkus' generic
  `quarkus.hibernate-orm.unsupported-properties."hibernate.order_inserts"` (and `"hibernate.order_updates"`) escape
  hatch. A live-boot spike against `bootui-quarkus-integration-tests` confirmed this end to end: with
  `quarkus.hibernate-orm.jdbc.statement-batch-size` and both `unsupported-properties` entries set, Hibernate's own
  `SessionFactoryOptions.isOrderInsertsEnabled()`/`isOrderUpdatesEnabled()` both report `true` at runtime, and this
  rule correctly does not fire.

### HIB-CONFIG-006 - Slow query logging should be available in development

- **Severity**: INFO
- **Inspects**: Hibernate slow-query threshold properties.
- **Fires when**: no positive threshold is configured.
- **Why it matters**: a local slow-query threshold helps spot expensive SQL before it reaches shared environments.
- **Recommendation**: configure a bounded threshold in development and staging profiles.

### HIB-CONFIG-007 - Hibernate statistics should be enabled when tuning

- **Severity**: INFO
- **Inspects**: `hibernate.generate_statistics` and Spring's `spring.jpa.properties.*` variant.
- **Fires when**: the property is not `true`.
- **Why it matters**: statistics expose query counts, fetch counts, and cache hit ratios useful during performance tuning.
- **Recommendation**: enable statistics in development or performance-test profiles when investigating data-access
  behavior.
- **Quarkus**: `hibernate.generate_statistics` maps to `quarkus.hibernate-orm.statistics` via
  `QuarkusHibernatePropertyLookup`, so this rule no longer false-positives when statistics are enabled with the
  native Quarkus property name.

### HIB-CONFIG-008 - Connection providers should disable auto-commit explicitly

- **Severity**: INFO
- **Inspects**: `hibernate.connection.provider_disables_autocommit`, JTA transaction settings, and
  `spring.datasource.hikari.auto-commit`.
- **Fires when**: JTA is not detected, `hibernate.connection.provider_disables_autocommit` is not enabled, and Hikari is
  explicitly configured with `spring.datasource.hikari.auto-commit=false`. If the pool's auto-commit behavior cannot be
  confirmed, the check is skipped with guidance.
- **Why it matters**: when the pool already disables auto-commit, this setting lets Hibernate delay connection acquisition.
- **Recommendation**: configure the pool with auto-commit disabled and set
  `hibernate.connection.provider_disables_autocommit=true`.

### HIB-CONFIG-009 - Collection-parameter queries should use IN-clause padding

- **Severity**: INFO
- **Inspects**: Spring Data JPQL query methods with collection parameters and `IN` predicates.
- **Fires when**: such a query exists and `hibernate.query.in_clause_parameter_padding` is not enabled.
- **Why it matters**: variable-length `IN` predicates can produce many SQL shapes and reduce plan-cache reuse.
- **Recommendation**: enable IN-clause parameter padding when the database benefits from statement plan reuse.
- **Quarkus**: `hibernate.query.in_clause_parameter_padding` maps to
  `quarkus.hibernate-orm.query.in-clause-parameter-padding` via `QuarkusHibernatePropertyLookup`, so the property is
  read correctly. The repository-scanning half of this rule only inspects Spring Data JPQL query methods, so it has
  nothing to flag on a Panache-based Quarkus app regardless of the property value.

### HIB-CONFIG-010 - Query cache requires a second-level cache provider

- **Severity**: HIGH
- **Inspects**: Hibernate query-cache, second-level-cache, and region-factory properties.
- **Fires when**: query cache is enabled without a configured second-level cache provider, or when second-level cache is
  explicitly disabled.
- **Why it matters**: query caching depends on the second-level cache infrastructure and proper entity caching.
- **Recommendation**: disable query caching or configure a region factory and cache entities returned by cacheable entity
  queries.
- **Quarkus**: `hibernate.cache.use_query_cache` maps to `quarkus.hibernate-orm.second-level-caching-enabled` via
  `QuarkusHibernatePropertyLookup` — Quarkus exposes a single unified toggle for second-level/query caching, not a
  separate query-cache property, so both the query-cache and second-level-cache reads resolve to the same Quarkus
  setting. `hibernate.cache.region.factory_class` has no first-class Quarkus equivalent but is still reachable via
  the `unsupported-properties` fallback if explicitly configured.

### HIB-CONFIG-011 - Cacheable entities should declare an explicit cache strategy

- **Severity**: MEDIUM
- **Inspects**: entity-level `@Cacheable` and Hibernate `@Cache` when second-level caching appears configured.
- **Fires when**: an entity is JPA-cacheable but does not declare an explicit Hibernate cache strategy.
- **Why it matters**: cache concurrency behavior should be explicit for cached entities.
- **Recommendation**: add a Hibernate cache concurrency strategy, or remove `@Cacheable` when the entity should not use the
  second-level cache.
- **Quarkus**: `hibernate.cache.use_second_level_cache` maps to the same
  `quarkus.hibernate-orm.second-level-caching-enabled` toggle as HIB-CONFIG-010, so the precondition check for
  "second-level caching appears configured" is read correctly on Quarkus too.

### HIB-CONFIG-002 - Schema generation should not mutate non-test databases

- **Severity**: INFO (can emit MEDIUM, HIGH, or CRITICAL based on profile and value)
- **Inspects**: `spring.jpa.hibernate.ddl-auto`, `spring.jpa.properties.hibernate.hbm2ddl.auto`,
  `hibernate.hbm2ddl.auto`, and active Spring profiles.
- **Fires when**: the configured value is `update`, `create`, or `create-drop` outside a test profile. A production-like
  profile wins over any other active profile: `create` / `create-drop` emits CRITICAL, and `update` emits HIGH. Dev/local
  disposable profiles emit INFO; unpinned non-test profiles emit MEDIUM.
- **Why it matters**: automatic schema mutation is convenient locally but risky against shared or persistent databases,
  and `create` / `create-drop` can drop and recreate live schemas at application startup.
- **Recommendation**: use versioned migrations for shared databases and reserve mutating `ddl-auto` values for disposable
  test environments.

### HIB-CONFIG-012 - SQL logging should be off when a production profile is active

- **Severity**: MEDIUM
- **Inspects**: `spring.jpa.show-sql`, `hibernate.show_sql`, and DEBUG/TRACE log levels for `org.hibernate.SQL` /
  `org.hibernate.orm.jdbc.bind` / `org.hibernate.type.descriptor.sql.BasicBinder`.
- **Fires when**: any of those are enabled while a profile named `prod`, `production`, `staging`, or `*-prod` /
  `*-production` is active.
- **Why it matters**: logging every statement degrades throughput dramatically and can leak parameter values in
  application logs.
- **Recommendation**: keep SQL logging off in production-like environments and rely on structured slow-query logging or
  the database's statement audit.

### HIB-CONFIG-013 - Configure hibernate.jdbc.time_zone

- **Severity**: LOW
- **Inspects**: `spring.jpa.properties.hibernate.jdbc.time_zone` and `hibernate.jdbc.time_zone`.
- **Fires when**: neither property is set.
- **Why it matters**: without a fixed zone, JDBC binds and reads use the JVM default zone, so results vary across hosts.
- **Recommendation**: pin `hibernate.jdbc.time_zone=UTC` (or another fixed zone) for deterministic timestamp handling.
- **Quarkus**: `hibernate.jdbc.time_zone` maps to `quarkus.hibernate-orm.jdbc.timezone` via
  `QuarkusHibernatePropertyLookup`, so this rule no longer false-positives when the zone is pinned with the native
  Quarkus property name.

### HIB-CONFIG-014 - Hibernate's built-in connection pool should not be used

- **Severity**: HIGH
- **Inspects**: `hibernate.connection.pool_size`.
- **Fires when**: the property is set.
- **Why it matters**: the property activates Hibernate's internal connection pool, which is intended for testing only
  and lacks the resilience and monitoring of a production pool.
- **Recommendation**: remove the property and rely on Spring Boot's managed `DataSource` (HikariCP by default).

### HIB-CONFIG-015 - spring.jpa.defer-datasource-initialization is only safe with embedded DDL flows

- **Severity**: MEDIUM
- **Inspects**: `spring.jpa.defer-datasource-initialization` and `spring.jpa.hibernate.ddl-auto`.
- **Fires when**: deferred initialization is enabled while `ddl-auto` is explicitly set to a value other than `create`,
  `create-drop`, or `update`. If `ddl-auto` is unset, the check is skipped because embedded-database defaults can still
  make deferred initialization meaningful.
- **Why it matters**: the property is meaningful only when Hibernate creates or updates the schema; otherwise `data.sql`
  is never executed by that flow and the configuration is misleading.
- **Recommendation**: combine deferred initialization with `ddl-auto=create`/`create-drop`, or remove it and load seed
  data through your migration tool (Flyway, Liquibase).

### HIB-CONFIG-016 - Fail on pagination over collection fetch

- **Severity**: HIGH (INFO when only the safety-net setting is missing)
- **Inspects**: the `hibernate.query.fail_on_pagination_over_collection_fetch` property and paginated collection
  `JOIN FETCH` repository queries.
- **Fires when**: Hibernate is older than 7.4 (or the runtime version cannot be detected) and the property is absent,
  false, or unparseable. If matching paginated collection fetch queries exist, the finding is HIGH; otherwise it is INFO
  hardening guidance for future queries.
- **Why it matters (Hibernate < 7.4)**: without this guard, affected runtimes can allow a paginated collection fetch join
  to fetch the whole result set into memory instead of failing fast.
- **Recommendation**: set `spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true` to throw
  an exception instead of risking a full-table fetch and memory exhaustion.
- **Hibernate 7.4+**: as described under HIB-FETCH-003, the limit for a paged collection fetch join is now applied in
  the generated SQL by default, so the in-memory blowup this safety net guards against no longer happens without opting
  back in via `org.hibernate.limitInMemory`. The check is skipped (not silently dropped) on 7.4+ runtimes, and the skip
  reason explains why.
- **Quarkus**: `hibernate.query.fail_on_pagination_over_collection_fetch` maps to
  `quarkus.hibernate-orm.query.fail-on-pagination-over-collection-fetch` via `QuarkusHibernatePropertyLookup`, so this
  rule no longer false-positives when the safety net is enabled with the native Quarkus property name.

### HIB-CONFIG-017 - Disable SQL formatting in production

- **Severity**: LOW
- **Inspects**: the `hibernate.format_sql` property and the active Spring profiles.
- **Fires when**: a production profile is active and `hibernate.format_sql` is `true`.
- **Why it matters**: pretty-printing SQL adds CPU and memory overhead, and Hibernate formats the statements even when SQL
  logging is disabled, so it is wasted work in production.
- **Recommendation**: disable `hibernate.format_sql` in production profiles and keep it enabled only for local development
  where readable SQL helps.

### HIB-CONFIG-018 - Bind-parameter logging should be off in production

- **Severity**: HIGH
- **Inspects**: TRACE-level logging for `org.hibernate.orm.jdbc.bind` (and the legacy
  `org.hibernate.type.descriptor.sql.BasicBinder` binder logger), plus the active Spring/Quarkus profile.
- **Fires when**: a production-like profile is active and bind-parameter logging is enabled at TRACE.
- **Why it matters**: at TRACE, Hibernate logs every bound parameter value - this can leak PII, credentials, or tokens
  passed as query parameters into application logs.
- **Recommendation**: keep bind-parameter logging off in production; only enable it temporarily, in a non-production
  environment, while diagnosing a specific issue.
- **Quarkus**: also detects the Quarkus-native `quarkus.hibernate-orm.log.bind-parameters` convenience flag (and its
  deprecated `.bind-param` alias), which `QuarkusHibernatePropertyLookup` reports as the neutral TRACE logger state -
  Quarkus's own guide explicitly warns against enabling this in production.

## Caching

### HIB-CACHE-001 - Cached entities should also cache their associations

- **Severity**: MEDIUM
- **Inspects**: entities annotated with `@Cacheable` or Hibernate `@Cache` and the entities they associate with.
- **Fires when**: an association on a cached entity targets another entity that is itself uncached.
- **Why it matters**: loading the aggregate from the cache still hits the database for every uncached association,
  defeating much of the cache's value.
- **Recommendation**: annotate the associated entities (or the association attributes) with
  `@org.hibernate.annotations.Cache` so the second-level cache covers the whole graph.

### HIB-CACHE-002 - READ_ONLY cache strategy on writable entities is unsafe

- **Severity**: MEDIUM
- **Inspects**: entities annotated with `@Cache(usage = READ_ONLY)`.
- **Fires when**: the entity also has `@Version` or `@DynamicUpdate`, both signals that the entity is mutable.
- **Why it matters**: `READ_ONLY` throws when Hibernate detects state changes and silently misses updates from other
  transactions on the same entity.
- **Recommendation**: switch to `READ_WRITE` or `NONSTRICT_READ_WRITE` for mutable entities.

### HIB-CACHE-003 - Immutable cached entities should use READ_ONLY

- **Severity**: INFO
- **Inspects**: entity-level Hibernate `@Immutable` and `@Cache(usage=...)`.
- **Fires when**: an immutable cached entity uses `READ_WRITE`, `NONSTRICT_READ_WRITE`, or `TRANSACTIONAL`.
- **Why it matters**: Hibernate documents `READ_ONLY` as the simplest, safest, and best-performing strategy for immutable
  entities; mutable strategies add coordination overhead for updates the mapping forbids.
- **Recommendation**: use `@Cache(usage = READ_ONLY)`, or remove `@Immutable` if the entity is expected to change.
