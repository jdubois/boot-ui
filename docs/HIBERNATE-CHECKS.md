# Hibernate Advisor checks

The Hibernate Advisor panel runs a fixed, on-demand ruleset against the host application's mapped JPA entities. It reads
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

- **HIGH** - a mapping choice that commonly causes large performance surprises.
- **MEDIUM** - a mapping or configuration issue that usually warrants review before production use.
- **LOW** - reserved for lower-impact hygiene findings.
- **INFO** - informational prompts where the fix depends heavily on project context.

The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id. Each rule
includes up to a handful of sample mapped members plus a remediation link.

---

## Fetching

### HIB-FETCH-001 - Associations should avoid eager fetching by default

- **Severity**: HIGH
- **Inspects**: `@ManyToOne`, `@OneToOne`, `@OneToMany`, and `@ManyToMany` mappings on metamodel attributes.
- **Fires when**: an association resolves to `FetchType.EAGER`, including default-eager to-one associations where no
  `fetch` attribute is declared.
- **Why it matters**: eager associations are fetched whether or not the use case needs them, which can amplify query
  counts, payload size, and accidental object graph loading.
- **Recommendation**: prefer `LAZY` associations and fetch required data explicitly with joins, entity graphs, or DTO
  projections.

### HIB-FETCH-002 - Batch fetching should be configured for association-heavy models

- **Severity**: INFO
- **Inspects**: association mappings, `hibernate.default_batch_fetch_size` / Spring's
  `spring.jpa.properties.hibernate.default_batch_fetch_size`, and `@org.hibernate.annotations.BatchSize`.
- **Fires when**: associations are mapped but no global batch-fetch size or local `@BatchSize` annotation is detected.
- **Why it matters**: lazy associations without batch fetching can produce N+1 select patterns when iterated.
- **Recommendation**: set a bounded global batch-fetch size or apply `@BatchSize` to high-traffic associations.

### HIB-FETCH-003 - Collection fetch joins should not be paged directly

- **Severity**: HIGH
- **Inspects**: Spring Data JPA `@Query` methods with a `Pageable` parameter and resolvable JPQL `JOIN FETCH` paths.
- **Fires when**: a paged repository query fetch-joins a mapped collection on the repository domain entity.
- **Why it matters**: collection fetch joins duplicate root rows, so pagination may be applied in memory or require a
  more explicit two-step query plan.
- **Recommendation**: page root identifiers first, then fetch the required collection graph in a second query inside the
  same transaction.

### HIB-FETCH-004 - Entities should avoid multiple bag collections

- **Severity**: MEDIUM
- **Inspects**: `@OneToMany` and `@ManyToMany` collection attributes.
- **Fires when**: an entity declares two or more unordered `List` / `Collection` associations without `@OrderColumn`.
- **Why it matters**: fetching multiple bag collections together is fragile and can lead to cartesian products or
  `MultipleBagFetchException`.
- **Recommendation**: fetch at most one bag collection per query, persist list order with `@OrderColumn`, or split loading
  into targeted queries.

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

## Mapping

### HIB-MAP-001 - One-to-many associations should be bidirectional or join-column based

- **Severity**: MEDIUM
- **Inspects**: `@OneToMany` mappings.
- **Fires when**: a one-to-many association has neither `mappedBy` nor `@JoinColumn` / `@JoinColumns`.
- **Why it matters**: Hibernate models that shape through a join table by default, which often produces extra DML and a
  less obvious schema.
- **Recommendation**: use `mappedBy` for bidirectional ownership, or add `@JoinColumn` when a unidirectional one-to-many
  is intentional.

### HIB-MAP-002 - Many-to-many associations should use Set semantics

- **Severity**: MEDIUM
- **Inspects**: `@ManyToMany` mappings and their Java collection type.
- **Fires when**: a many-to-many association is declared as `List`.
- **Why it matters**: list-backed many-to-many mappings can force delete-and-reinsert behavior for join-table rows.
- **Recommendation**: use `Set`, or model the join table as an entity when it has attributes or business meaning.

### HIB-MAP-003 - Enum attributes should be stored as strings

- **Severity**: MEDIUM
- **Inspects**: enum-valued mapped attributes.
- **Fires when**: an enum attribute uses `@Enumerated(ORDINAL)` or omits `@Enumerated`, which defaults to ordinal storage.
- **Why it matters**: ordinal persistence is fragile because reordering enum constants changes the stored meaning.
- **Recommendation**: use `@Enumerated(EnumType.STRING)` or an explicit converter.

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

- **Severity**: MEDIUM
- **Inspects**: owning-side `@OneToOne` mappings.
- **Fires when**: the association has no `mappedBy`, no `@MapsId`, and the association itself is not the identifier.
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

## Configuration

### HIB-CONFIG-001 - Open Session in View should be disabled

- **Severity**: MEDIUM
- **Inspects**: `spring.jpa.open-in-view`.
- **Fires when**: the property is `true` or absent, matching Spring Boot's default for web applications.
- **Why it matters**: lazy loading after the service transaction has completed can hide missing fetch plans and move data
  access into the web layer.
- **Recommendation**: set `spring.jpa.open-in-view=false` and fetch data inside transactional service boundaries.

### HIB-CONFIG-002 - Schema generation should not mutate non-test databases

- **Severity**: INFO
- **Inspects**: `spring.jpa.hibernate.ddl-auto`, `spring.jpa.properties.hibernate.hbm2ddl.auto`, and
  `hibernate.hbm2ddl.auto`.
- **Fires when**: the configured value is `update`, `create`, or `create-drop` and no active profile is named `test`,
  starts with `test-`, or ends with `-test`.
- **Why it matters**: automatic schema mutation is convenient locally but risky against shared or persistent databases.
- **Recommendation**: use versioned migrations for shared databases and reserve mutating `ddl-auto` values for disposable
  test environments.

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

### HIB-CONFIG-008 - Connection providers should disable auto-commit explicitly

- **Severity**: INFO
- **Inspects**: `hibernate.connection.provider_disables_autocommit`, excluding JTA configurations.
- **Fires when**: the property is not `true`.
- **Why it matters**: when the pool already disables auto-commit, this setting lets Hibernate delay connection acquisition.
- **Recommendation**: configure the pool with auto-commit disabled and set
  `hibernate.connection.provider_disables_autocommit=true`.

### HIB-CONFIG-009 - Collection-parameter queries should use IN-clause padding

- **Severity**: INFO
- **Inspects**: Spring Data JPQL query methods with collection parameters and `IN` predicates.
- **Fires when**: such a query exists and `hibernate.query.in_clause_parameter_padding` is not enabled.
- **Why it matters**: variable-length `IN` predicates can produce many SQL shapes and reduce plan-cache reuse.
- **Recommendation**: enable IN-clause parameter padding when the database benefits from statement plan reuse.

### HIB-CONFIG-010 - Query cache requires a second-level cache provider

- **Severity**: HIGH
- **Inspects**: Hibernate query-cache, second-level-cache, and region-factory properties.
- **Fires when**: query cache is enabled without a configured second-level cache provider, or when second-level cache is
  explicitly disabled.
- **Why it matters**: query caching depends on the second-level cache infrastructure and proper entity caching.
- **Recommendation**: disable query caching or configure a region factory and cache entities returned by cacheable entity
  queries.

### HIB-CONFIG-011 - Cacheable entities should declare an explicit cache strategy

- **Severity**: MEDIUM
- **Inspects**: entity-level `@Cacheable` and Hibernate `@Cache` when second-level caching appears configured.
- **Fires when**: an entity is JPA-cacheable but does not declare an explicit Hibernate cache strategy.
- **Why it matters**: cache concurrency behavior should be explicit for cached entities.
- **Recommendation**: add a Hibernate cache concurrency strategy, or remove `@Cacheable` when the entity should not use the
  second-level cache.

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
