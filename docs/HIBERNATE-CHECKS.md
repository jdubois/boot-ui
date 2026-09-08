# Hibernate checks

The Hibernate panel runs a fixed, on-demand ruleset against the host application's mapped JPA entities. It reads
the JPA `EntityManagerFactory` metamodel, selected persistence-unit observations, and verified Spring Data JPA repository metadata when
available; it does not intercept runtime queries, invoke repositories, execute SQL, or modify mappings.

The checks are heuristic review prompts. They highlight common Hibernate/JPA performance and maintainability risks, but
the right remediation still depends on the application's query patterns and data model.

`assessmentEvidence` records actual completed applicable persistence-unit checks or retained findings; the registry
size is not proof of a usable assessment. Partial scores follow the [shared policy](features/advisors.md#score-eligibility).

## Availability and bounds

The panel is available only when Hibernate ORM and an `EntityManagerFactory` bean are present. If either is missing, or if
the metamodel cannot be read, BootUI returns a stable empty report with an explanatory status.

The scan is bounded to mapped entities reported by the application's own JPA metamodel. This also covers entities added
through `@EntityScan` or custom persistence-unit configuration without scanning the entire classpath.

The active catalog contains **70 rules**. Five retired identifiers remain documented below so old links and persisted
dismissals retain their meaning; retired identifiers are never reused.

### Evidence and incomplete scans

Factory settings belong to their persistence unit, not to whichever factory was discovered first. The advisor reads an
allowlisted snapshot of effective Hibernate factory options where available and attributes findings to that unit.
Factory defaults do not establish per-session overrides, query-cache opt-in, entity-cache eligibility, or workload.
Missing or unreadable evidence is not an observed `false`, zero, or framework default.

Repository checks require JPA provenance and unambiguous unit/domain attribution. Native queries, composed query
annotations, projections and entity graphs affect applicability. Unsupported or ambiguous query shapes are not guessed;
Panache query methods remain outside the repository analysis.

Rule failures and unavailable required evidence produce an incomplete `PARTIAL` scan while retaining valid findings.
The report's `results` list still contains findings only; the scan message explains coverage and failures using bounded
identifiers and controlled reasons. Intentional platform inapplicability is distinct from a failed applicable check.
`rulesEvaluated` counts distinct active rule attempts, not successful verification of every mapping.

In this implementation, pool auto-commit guarantees (HIB-CONFIG-008) and effective cache access strategy
(HIB-CONFIG-011) are not fully observable. When applicable, these and unsupported query-plan evidence can make ordinary
scans `PARTIAL` even if entity discovery succeeds. This is deliberate coverage disclosure, not a claim that the
application is broken.

The declaration-based mapping checks are not a complete runtime mapping interpreter. XML overrides, auto-apply
converters, custom generators, physical naming, runtime query changes and some collection classifications cannot be
reconstructed from annotations alone. The individual limitations below matter when interpreting both findings and an
absence of findings.

### Version baseline

The audited dependency baselines are Spring Boot **4.1.1 / Hibernate 7.4.5.Final** and Quarkus
**3.33.3.1 / Hibernate 7.2.19.Final**, both using Jakarta Persistence **3.2.0**. Host applications may override these
versions; version-dependent conclusions use runtime evidence. Primary references and version caveats are listed at the
end of this catalog.

## Severity scale

- **CRITICAL** - a configuration choice that can immediately damage production data. Currently emitted by
  HIB-CONFIG-002 for destructive schema actions under a production-like profile. Quarkus/Jakarta `create` is
  create-only, unlike Hibernate `hbm2ddl.auto=create`.
- **HIGH** - a mapping choice that commonly causes large performance surprises.
- **MEDIUM** - a mapping or configuration issue that usually warrants review before production use.
- **LOW** - reserved for lower-impact hygiene findings.
- **INFO** - informational prompts where the fix depends heavily on project context.

The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id. Each rule
includes up to a handful of sample mapped members plus a remediation link.

The advisor score applies the shared severity penalty to every concrete finding, not just once per violated rule.
Dismissed rules remove all of their findings from the score.

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
- **Fires when**: an attributable, supported paged JPA query fetch-joins a mapped collection and the known runtime or
  declared query hint establishes an in-memory pagination risk. An unknown runtime is not treated as an older version.
- **Why it matters (Hibernate < 7.4)**: collection fetch joins duplicate root rows, and versions before 7.4 applied the
  `Pageable` limit in memory after loading the full, duplicated result set instead of at the SQL level.
- **Recommendation**: page root identifiers first, then fetch the required collection graph in a second query inside the
  same transaction.
- **Hibernate 7.4+**: the ["Limits and fetch joins"](https://github.com/hibernate/hibernate-orm/blob/7.4/migration-guide.adoc#limits-and-fetch-joins)
  migration-guide entry documents that the limit for a paged query with a collection `JOIN FETCH` is now applied in the
  generated SQL itself; the `org.hibernate.limitInMemory` query hint restores the pre-7.4 in-memory behavior. The check
  does not certify SQL pushdown merely from the version: dialect/query-plan fallback remains possible, and an explicit
  true hint can bypass the ordinary pagination guard. Unknown query/runtime evidence is reported as a coverage gap,
  not a blanket warning or proof of safety.

### HIB-FETCH-004 - Review entities with multiple bag collections

**Retired.** Multiple lazy bag declarations are valid; their existence does not establish simultaneous fetching.
HIB-QUERY-007 reviews supported query shapes instead. A Java `List` without `@OrderColumn` does not itself prove
effective BAG semantics because Hibernate can supply an implicit list index. Changing collections to sets also does not
eliminate Cartesian multiplication when multiple collections are fetched together.

### HIB-FETCH-002 - Batch fetching should cover lazy secondary-select associations

- **Severity**: INFO
- **Inspects**: lazy to-one and collection mappings, the owning factory's default batch-fetch size,
  association-level `@org.hibernate.annotations.BatchSize`,
  and target-entity `@BatchSize` for lazy to-one associations.
- **Fires when**: a lazy association can initialize through secondary selects and no global or applicable local batch
  fetch size is detected.
- **Why it matters**: lazy associations without batch fetching can produce N+1 select patterns when the same association
  is traversed across multiple owner rows.
- **Recommendation**: set a bounded global batch-fetch size or targeted `@BatchSize` for associations traversed across
  multiple owners; use explicit fetch plans or paged/filtered queries for a single oversized collection.
- **Quarkus**: effective factory options include Quarkus's integration default even when
  `quarkus.hibernate-orm.fetch.batch-size` is absent from raw configuration. Named units are inspected separately.

### HIB-FETCH-005 - Enhanced @Lob attributes should be loaded lazily

- **Severity**: MEDIUM
- **Inspects**: persistent attributes annotated with `@Lob` on bytecode-enhanced entities.
- **Fires when**: enhancement is available and a `@Lob` attribute does not declare
  `@Basic(fetch = FetchType.LAZY)`.
- **Why it matters**: infrequently used materialized CLOB/BLOB values can increase hydration cost. Locator-backed LOB
  behavior depends on the driver and transaction; `@Lob` does not prove that the full payload is read every time.
- **Recommendation**: on enhanced entities, annotate infrequently accessed `@Lob` fields with
  `@Basic(fetch = FetchType.LAZY)`.
- **Enhancement requirement**: Hibernate ORM 7 requires bytecode enhancement to honor lazy basic attributes. This rule
  deliberately does not recommend `@Basic(fetch = LAZY)` when enhancement is unavailable; HIB-FETCH-007 instead reports
  existing ineffective lazy-basic declarations. The advisor relies on transformed-class evidence or an adapter-verified
  platform capability; an ordinary Hibernate enhancement setting alone does not prove that application classes were
  transformed. Quarkus provides that verified capability at build time.

### HIB-FETCH-006 - Collection associations should not declare @Fetch(JOIN)

- **Severity**: MEDIUM
- **Inspects**: collection associations annotated with Hibernate's `@Fetch`.
- **Fires when**: a `@OneToMany` or `@ManyToMany` declares `@Fetch(FetchMode.JOIN)`.
- **Why it matters**: mapping-level joins can load more collection state than a use case needs. They do not force
  every JPQL query to join: query fetching directives determine that path. The declaration does not prove an actual
  Cartesian product or pagination failure.
- **Recommendation**: prefer `@Fetch(FetchMode.SELECT)`, an explicit entity query, or a DTO projection, and request
  `JOIN FETCH` only in the specific queries that need the graph.

### HIB-FETCH-007 - Lazy basic attributes require bytecode enhancement

- **Severity**: MEDIUM
- **Inspects**: attributes declaring `@Basic(fetch = FetchType.LAZY)` and the owning entity's enhancement state.
- **Fires when**: a lazy basic attribute belongs to an entity whose relevant enhancement is known to be absent.
  Unavailable enhancement evidence is not treated as verified absence.
- **Why it matters**: without enhancement, Hibernate cannot intercept access to a basic field and the requested lazy
  column loading is ineffective.
- **Recommendation**: enable Hibernate bytecode enhancement, or remove the misleading `LAZY` declaration.

### HIB-FETCH-008 - Subselect collection fetching should be reviewed

- **Severity**: INFO
- **Inspects**: collection associations declaring `@Fetch(FetchMode.SUBSELECT)`.
- **Fires when**: a mapped `@OneToMany` or `@ManyToMany` uses subselect fetching.
- **Why it matters**: initialization can load the same collection role for the originating registered query/load group,
  not necessarily every owner in the persistence context. A large owner group can fetch more data than the caller needs.
- **Recommendation**: keep `SUBSELECT` only for bounded owner sets. Prefer an explicit entity query or DTO projection when
  owner or collection cardinality can be large.

## Identifiers

### HIB-ID-001 - Review IDENTITY insert-generation trade-offs

- **Severity**: MEDIUM
- **Inspects**: mapped attributes annotated with `@GeneratedValue`.
- **Fires when**: the generator strategy is `GenerationType.IDENTITY`.
- **Why it matters**: identity columns require the insert to execute immediately so Hibernate can read the generated key,
  which disables JDBC batch inserts for those entities.
- **Recommendation**: prefer `SEQUENCE` with an allocation size and Hibernate pooled optimizer when the database supports
  sequences and insert throughput justifies it. IDENTITY is a supported choice, not a mapping defect. When HIB-ID-006
  provides the stronger batching-specific finding, this generic advice is not counted again.

### HIB-ID-002 - Review table-based identifier allocation

- **Severity**: MEDIUM
- **Inspects**: mapped attributes annotated with `@GeneratedValue`.
- **Fires when**: the generator strategy is `GenerationType.TABLE`.
- **Why it matters**: table generators emulate sequences through row updates, which can serialize identifier allocation
  under concurrent inserts.
- **Recommendation**: compare generator and pooling choices against the database and insert workload. The declaration
  does not demonstrate contention, and pooled table allocation may be intentional.

### HIB-ID-003 - Review sequence allocation declarations

- **Severity**: MEDIUM
- **Inspects**: field-level and class-level `@SequenceGenerator` declarations.
- **Fires when**: `allocationSize=1`.
- **Why it matters**: when this declaration selects the active generator, an allocation size of 1 cannot pool sequence
  values. An unused or overridden declaration does not establish runtime allocation behavior.
- **Recommendation**: measure allocation overhead before increasing the size; coordinate the optimizer, database
  sequence increment and other writers. The advisor does not resolve every named, package-level or custom generator.

### HIB-ID-004 - Review provider-selected identifier strategies

- **Severity**: INFO
- **Inspects**: identifier attributes annotated with `@GeneratedValue`, excluding `UUID`-typed identifiers.
- **Fires when**: `strategy` is omitted (or set to `AUTO`).
- **Why it matters**: `AUTO` deliberately delegates generator selection to the provider. Hibernate normally uses
  sequence-style generation for numeric identifiers, with a table fallback where sequences are unavailable, but
  named/custom generators can affect resolution. Explicit AUTO is legal and is not equivalent to IDENTITY.
- **Recommendation**: review the selected generator when portability or allocation throughput matters. Retain AUTO
  when its behavior fits the application; choosing an explicit strategy is not universally required.
- **UUID identifiers**: skipped entirely - for a `UUID`-typed identifier, Hibernate 6 interprets `AUTO` as the
  `UuidGenerator`, not a sequence, so this rule's "sequence-or-table fallback" rationale does not apply. HIB-ID-005 owns
  the UUID case exclusively, so a `UUID @Id @GeneratedValue` with no explicit strategy is flagged only once, by
  HIB-ID-005, with UUID-specific guidance instead of a misleading sequence/table warning.
- **Quarkus/Panache**: skipped for the `id` field inherited as-is from Panache's own `PanacheEntity` (ORM or Reactive),
  which declares `@Id @GeneratedValue public Long id;` with no explicit strategy and cannot be annotated by the
  application. A custom identifier the application declares itself (including on a `PanacheEntityBase` subclass) is
  still checked normally.

### HIB-ID-005 - Review generated UUID strategy when index locality matters

- **Severity**: LOW
- **Inspects**: `UUID` identifier attributes annotated with `@GeneratedValue`, and the runtime Hibernate ORM version.
- **Fires when**: the attribute is not also annotated with `@UuidGenerator`.
- **Why it matters**: default random UUID generation can affect index locality, depending on the database and workload.
  This declaration-level check does not establish an effective custom generator or measured index fragmentation.
- **Recommendation (Hibernate 7.0+)**: annotate the identifier with `@UuidGenerator(style = VERSION_7)` (`VERSION_6` is
  also acceptable). Both styles are monotonic, index-friendly UUID variants and only exist starting in Hibernate 7.0
  (both `@Incubating`) - confirmed by diffing `UuidGenerator.java` between the 6.6 branch (only `AUTO`/`RANDOM`/`TIME`)
  and the 7.0 branch (adds `VERSION_6`/`VERSION_7`).
- **Older-runtime caveat**: `style = TIME` produces an RFC 4122
  **version 1** UUID (per `@UuidGenerator`'s own Javadoc: "time-based generation strategy consistent with RFC 4122
  version 1, but with IP address instead of MAC address"), which places the fast-changing `time_low` field first and is
  **not materially more index-friendly than a random (v4) UUID** - it does not yield the monotonic ordering that makes
  `VERSION_6`/`VERSION_7` genuinely index-friendly. The recommendation is annotated with this caveat rather than
  presenting `TIME` as a real fix. An unknown runtime is not assumed to be an older release.
- **Generator caveat**: `@UuidGenerator` without a style still defaults to AUTO/RANDOM; its presence does not prove
  ordered UUIDs. String-based, named and custom generators are not fully resolved by this check.
- **Version detection**: the runtime Hibernate ORM version is read from `HibernateContext`/`HibernateEntityModel` the
  same way HIB-FETCH-003/HIB-CONFIG-016 gate their Hibernate-7.4 pagination behavior.

### HIB-ID-006 - GenerationType.IDENTITY disables JDBC batch inserts

- **Severity**: HIGH
- **Inspects**: the owning factory's effective JDBC batch size and identifier attributes
  annotated with `@GeneratedValue`.
- **Fires when**: the effective JDBC batch size is greater than 1 and an identifier uses `GenerationType.IDENTITY`.
- **Why it matters**: Hibernate must execute each identity insert immediately so it can read back the generated key,
  preventing JDBC insert batching for those entities despite the configured batch size.
- **Recommendation**: switch IDENTITY identifiers to `SEQUENCE` with a pooled `allocationSize` so Hibernate can batch
  inserts, or drop the JDBC batch-size expectation for these entities.

### HIB-ID-007 - Composite identifier classes must satisfy the JPA contract

- **Severity**: HIGH
- **Inspects**: `@EmbeddedId` attribute types and `@IdClass` values (including inherited from a superclass).
- **Fires when**: a non-record class has no public or protected
  no-arg constructor; or the class does not override both `equals` and `hashCode`.
- **Why it matters**: a composite identifier needs stable equality and a construction path that JPA can use. A record
  supplies its canonical constructor and generated equality methods, so it is exempt from the no-arg-constructor check.
- **Recommendation**: for a non-record class, declare a
  public or protected no-arg constructor and override both `equals` and `hashCode` over every identifier field.
- **Persistence 3.2**: Serializable is not a composite-key requirement. This check verifies detectable structure,
  not whether an equality implementation actually compares the correct identifier state.
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

### HIB-MAP-002 - Review many-to-many list semantics

- **Severity**: MEDIUM
- **Inspects**: `@ManyToMany` mappings and their Java collection type.
- **Fires when**: a many-to-many association is declared as `List` without an explicit persistent order column.
  An explicitly indexed ordered list is not reported merely for using List.
- **Why it matters**: collection semantics affect link-table mutation costs. Hibernate's effective default list semantics
  and XML mappings can also provide indexing; this declaration-only check does not resolve every case.
- **Recommendation**: preserve domain ordering when required. Benchmark mutation behavior; consider Set only when its
  uniqueness semantics fit, or a link entity when the relationship has its own attributes or lifecycle.

### HIB-MAP-003 - Enum attributes should declare an explicit storage strategy

- **Severity**: MEDIUM
- **Inspects**: enum-valued mapped attributes.
- **Fires when**: an enum attribute lacks an observed storage declaration after supported explicit conversion and
  `@EnumeratedValue` exclusions. Absence of a member annotation alone does not establish effective ordinal storage.
- **Why it matters**: positional ordinal persistence couples stored values to enum order. Persistence 3.2 can infer
  STRING storage from a String `@EnumeratedValue`, and auto-apply/class-level/XML converters can replace default storage.
  Full converter resolution remains outside this rule's evidence.
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

### HIB-MAP-007 - Review TABLE_PER_CLASS polymorphic queries

- **Severity**: INFO
- **Inspects**: class-level `@Inheritance`.
- **Fires when**: the strategy is `InheritanceType.TABLE_PER_CLASS`.
- **Why it matters**: polymorphic queries over the base type require a `UNION` across concrete subtype tables.
- **Recommendation**: evaluate the polymorphic query workload before changing inheritance strategy. Subtype-only access
  can make TABLE_PER_CLASS intentional; the declaration does not prove expensive queries are executed.

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
- **Why it matters**: Optional is not a standard portable persistent basic type. A custom type or converter can make
  a mapping valid; raw Java type alone does not prove a broken runtime mapping.
- **Recommendation**: map the underlying nullable type and expose `Optional` from a non-persistent getter if desired.

### HIB-MAP-010 - Review element-collection list ordering and mutation cost

- **Severity**: MEDIUM
- **Inspects**: `@ElementCollection` attributes typed as `List`.
- **Fires when**: the list does not declare `@OrderColumn` - regardless of whether it declares `@OrderBy`.
- **Why it matters**: unindexed bag-like collections can need broad delete/reinsert work for some mutations. The
  effective collection classification and mutation determine the cost; absent `@OrderColumn` does not prove every change
  rewrites every row, especially with Hibernate's implicit list-index support.
- **Recommendation**: use an order column when persistent positional order is required, or Set when its semantics fit.
  `@OrderBy` provides read-time sorting, not an index. Do not combine `@OrderBy` and `@OrderColumn` as a generic fix.

### HIB-MAP-011 - Entity classes should not be final

- **Severity**: INFO
- **Inspects**: `@Entity` classes for the `final` modifier and their bytecode-enhancement state.
- **Fires when**: an entity is declared `final` and is not proven bytecode-enhanced.
- **Why it matters**: final entities are not Jakarta-portable and cannot use subclass proxies for lazy to-one associations.
  Bytecode enhancement is a valid alternative and is not itself blocked by a final declaration.
- **Recommendation**: prefer non-final entities (and `open` Kotlin entities) when lazy subclass proxies are needed.
  Bytecode-enhanced entities are exempt.
- **Kotlin note**: Kotlin classes are final by default. Before Kotlin 2.3.20 the JPA plugin supplied no-arg constructors
  but did not itself enable all-open; since 2.3.20 JPA setup enables both. Boot's managed Kotlin 2.3.21 and Quarkus's
  2.3.10 therefore differ. The emitted class, not dependency or plugin-name presence, determines finality.

### HIB-MAP-012 - SINGLE_TABLE inheritance should declare @DiscriminatorColumn

**Retired.** Jakarta Persistence defines a valid implicit discriminator column. Requiring an explicit annotation solely
for visibility scores a supported default without evidence of an incorrect mapping.

### HIB-MAP-013 - String columns should declare explicit length

- **Severity**: INFO
- **Inspects**: persistent `String` attributes (excluding identifiers and `@Lob` fields).
- **Fires when**: there is no `@Column` mapping and no `columnDefinition`. Reflection cannot distinguish
  `@Column(length = 255)` from the annotation's default length, so an existing `@Column` is treated as an explicit
  mapping.
- **Why it matters**: default generated string DDL may not match domain expectations. Annotation absence does not prove
  the physical column length: migrations, validation integration, converters and XML overrides can determine it.
- **Recommendation**: set `@Column(length=...)` to match the domain, or use `@Lob`/`columnDefinition` for free-text
  payloads.

### HIB-MAP-014 - BigDecimal columns should declare precision and scale

- **Severity**: MEDIUM
- **Inspects**: persistent `BigDecimal` attributes.
- **Fires when**: `@Column(precision=..., scale=...)` is missing or precision is zero.
- **Why it matters**: generated numeric DDL varies by database. Missing declaration or zero precision is not evidence of
  actual rounding, and a column mapping is not input validation. Effective converters, column definitions and validation
  metadata are not fully resolved.
- **Recommendation**: review the actual numeric storage and required precision/scale; configure DDL and validation
  deliberately when needed rather than assuming every default is wrong.

### HIB-MAP-015 - Date/time attributes should use java.time

- **Severity**: LOW
- **Inspects**: persistent attributes typed as `java.util.Date`, `java.util.Calendar`, or `java.sql` temporal types.
- **Fires when**: any of those legacy types is detected on a mapped attribute.
- **Why it matters**: older temporal types have mutable or less expressive APIs. Persistence 3.2 deprecates `@Temporal`
  in favor of modern types, but the correct replacement must preserve date, wall-clock, instant and offset semantics.
- **Recommendation**: migrate to `java.time` (`Instant`, `LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`)
  where semantically equivalent. Not every java.time type carries a zone.

### HIB-MAP-016 - @ManyToOne should set optional=false when the join column is non-nullable

- **Severity**: LOW
- **Inspects**: `@ManyToOne` associations and their `@JoinColumn`.
- **Fires when**: `@JoinColumn(nullable=false)` is set but `optional=false` is not.
- **Why it matters**: the object-level optionality declaration and column-nullability declaration convey inconsistent
  intent. This does not prove a secondary SELECT occurs, and neither declaration alone verifies physical constraints.
- **Recommendation**: set `@ManyToOne(optional=false)` whenever the join column is non-nullable.

### HIB-MAP-018 - Review lazy inverse @OneToOne without enhancement

- **Severity**: MEDIUM
- **Inspects**: non-owning (`mappedBy`) `@OneToOne` associations and whether the declaring entity is bytecode enhanced.
- **Fires when**: relevant enhancement is known absent and an entity declares an explicitly lazy non-owning
  `@OneToOne`. Eager declarations and unknown enhancement do not establish a failed lazy-loading request.
- **Why it matters**: determining whether the inverse row exists can prevent the expected lazy behavior. Query plans,
  optionality, cache state and access patterns determine actual queries; this declaration does not prove N+1.
- **Recommendation**: enable bytecode enhancement, or replace the bidirectional `@OneToOne` with a shared primary key
  (`@MapsId`) and a unidirectional mapping.
- **Quarkus**: bytecode enhancement is verified by the adapter's unconditional build-time entity enhancement, so this
  check never fires there.

### HIB-MAP-019 - Missing foreign key indexes

**Retired.** Annotation declarations cannot verify physical index coverage, and explicit annotation names can still be
rewritten by a physical naming strategy. Database structural checks own index evidence; the Hibernate advisor does not
duplicate that work by assuming absent `@Index` means absent database index.

### HIB-MAP-020 - Review writable unidirectional one-to-many join-column DML

- **Severity**: MEDIUM
- **Inspects**: unidirectional `@OneToMany` associations and their join-column annotations.
- **Fires when**: a `@OneToMany` has no `mappedBy`, declares a join column, and that join column is not read-only
  (`insertable=false, updatable=false`). Composite `@JoinColumns` is exempt only when every join column is read-only.
- **Why it matters**: this ownership shape can require separate foreign-key UPDATE work depending on the effective
  mapping and mutation. The declaration alone does not prove an exact INSERT/UPDATE sequence.
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
- **Runtime scope**: this check is most useful while scanning an ORM 6.x application before migration. Removed
  annotation types cannot be inspected reliably through reflection on ORM 7. Their absence from a live scan is not
  evidence that the old restrictions were migrated or remain effective.

### HIB-MAP-022 - Explicit ordinal enum mappings should be reviewed

- **Severity**: INFO
- **Inspects**: enum attributes declaring `@Enumerated(EnumType.ORDINAL)`.
- **Fires when**: ordinal storage is explicit, so the mapping is intentional but still carries a schema-evolution risk.
- **Why it matters**: positional ordinal storage changes meaning when enum constants are inserted or reordered.
  Persistence 3.2 also permits stable numeric codes through `@EnumeratedValue`; numeric storage is not always position.
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

**Retired.** The existence of equality overrides and associations does not show that the methods traverse associations.
Safe ID-only implementations also matched. HIB-ENTITY-001 still checks an inconsistent equals/hashCode pair; it does not
claim to validate method bodies.

### HIB-ENTITY-004 - toString should not include lazy associations

**Retired.** A toString override plus association declarations does not establish traversal. No method-body analysis
proves lazy loading here; safe ID-only implementations must not receive this finding.

### HIB-ENTITY-005 - Persistent fields should not be public

- **Severity**: LOW
- **Inspects**: entity attributes backed by a `java.lang.reflect.Field` that is `public`.
- **Fires when**: a field-access persistent field is `public`. A field annotated `@Transient` is never flagged - it is
  not written to the database, so it carries none of the accessor-bypass risk this check targets (for example a public
  `@Transient` flag field used by a hand-written `isNew()`/`Persistable` implementation). Property-access (getter-mapped)
  attributes are never flagged either, even when the underlying getter method is `public` - the metamodel resolves those
  attributes from a `Method`, not a `Field`, and public getters are the normal, fully JPA/Hibernate-instrumented way to
  expose a property-access attribute.
- **Why it matters**: public fields can weaken encapsulation and proxy-based interception. Bytecode enhancement can
  instrument field access; a public field is not proof that Hibernate dirty checking is broken.
- **Recommendation**: keep persistent fields private (or package-private) and expose mutators when needed; this
  preserves application encapsulation without claiming that visibility alone establishes enhancer behavior.
- **Kotlin note**: a `lateinit var` is exempt. Kotlin has no public fields — the property is read and written through
  its generated `getX`/`setX` pair — but the compiler must leave the backing field public so the initialisation check
  can run from outside the class, and the language offers no way to change that. A property the author did expose,
  written `@JvmField var`, generates no accessors at all and is still reported: that is the same encapsulation break a
  Java public field is.
- **Quarkus/Panache**: skipped with verified adapter-provided Panache transformation capability, not merely an unrelated
  Panache class on the runtime classpath. Panache's active-record entities are meant to be
  used with public fields; once a Panache extension is present, its build-time bytecode transformation rewrites *every*
  public field access on *any* Hibernate-managed class (not just `PanacheEntityBase` subclasses) into the matching
  getter/setter call, so the accessor-bypass concern this check targets does not apply.

### HIB-ENTITY-006 - Review primitive-version newness semantics

- **Severity**: INFO
- **Inspects**: primitive-version declarations in the supported Spring Data JPA newness context.
- **Why it matters**: primitive identifiers and versions are legal. Spring Data recognizes zero-valued numeric primitive
  identifiers as new, but cannot use a primitive version as a nullable newness signal and falls back to identifier
  inspection. A wrapper alone does not prove correct custom newness or equality behavior.
- **Recommendation**: review the repository's actual save/persist/merge policy when nullable version detection is
  needed. Do not replace valid primitive IDs merely to satisfy a generic Hibernate warning.
- **Quarkus/Panache**: Spring Data newness advice is inapplicable without verified JPA repository metadata.

### HIB-ENTITY-007 - Review assigned-ID Spring Data newness

- **Severity**: MEDIUM
- **Inspects**: Spring Data repository domain entities with assigned identifiers (lacking `@GeneratedValue`) and no
  nullable `@Version` attribute.
- **Fires when**: a discovered Spring Data repository domain entity does not implement
  `org.springframework.data.domain.Persistable`, including through inherited subinterfaces, within the supported
  default newness policy. A primitive Version does not supply a nullable signal. Unresolved custom behavior is unknown.
- **Why it matters**: with populated assigned identifiers, the standard save policy can choose merge rather than persist.
  Actual SELECT/INSERT behavior depends on entity state and provider execution; a query before every insert is not
  guaranteed.
- **Recommendation**: consider a nullable version, a correctly managed `Persistable<ID>` lifecycle, or another deliberate
  persistence policy. Incorrect custom `isNew()` handling can itself cause errors; implementing Persistable is not
  universally required.
- **Quarkus**: skipped entirely because no Spring Data repository metadata is available. The concern is specific to
  Spring Data's repository `save()` merge-vs-persist decision; Panache's own `persist()`/`persistAndFlush()` always
  issues an `INSERT` and never probes existence first, so there is nothing to recommend on a Quarkus/Panache app.

### HIB-ENTITY-008 - Mutable entities should declare @Version for optimistic locking

- **Severity**: INFO
- **Inspects**: mapped entities that carry non-identifier persistent state (at least one attribute that is not an `@Id`,
  `@EmbeddedId`, `@Version`, association, or `@Transient`).
- **Fires when**: the entity declares no `@Version` attribute and has not opted into versionless optimistic locking
  (`@OptimisticLocking(DIRTY|ALL)`) or `@org.hibernate.annotations.Immutable`.
- **Why it matters**: without optimistic version checks, concurrent read/modify/write operations can overwrite changes.
  Pessimistic locking, isolation, append-only use and external coordination may already protect the workflow; their
  absence is not inferred from annotations.
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
  use Hibernate's natural-id resolution instead of a full query. Adding the annotation does not automatically reroute
  existing repository queries through natural-id lookup or cache APIs.
- **Quarkus/Panache**: applies identically - this check inspects only annotation metadata via reflection.

## Query

All HIB-QUERY rules inspect verified Spring Data JPA repository metadata. Quarkus/Panache does not expose equivalent
runtime repository/query metadata with enough fidelity to evaluate these rules reliably, so they remain unavailable on
Quarkus rather than guessing from Panache method names or generated bytecode. Mapping, identifier, fetching, configuration,
and caching rules still run on Quarkus against the live JPA metamodel.

The JPQL checks deliberately use bounded, conservative lexical analysis rather than a full JPQL parser. Comments and
quoted literals are not fetch paths. Queries with nested aliases, subqueries, multiple roots, a root inconsistent with
the repository domain, or unresolved rewriting/projection/graph effects may be skipped rather than inferred incorrectly.
Named and dynamic queries outside the observed metadata remain outside coverage.

### HIB-QUERY-001 - @Modifying bulk queries should clear stale persistence context

- **Severity**: INFO
- **Inspects**: Spring Data JPA `@Modifying` annotations on repository methods.
- **Fires when**: a supported declared bulk query's merged `@Modifying` annotation does not set `clearAutomatically`.
- **Why it matters**: the persistence context can hold stale entities after a bulk update or delete, leading to
  hard-to-diagnose data inconsistencies. `flushAutomatically` only synchronizes pending changes before the bulk query;
  it does not evict the stale managed entities afterward.
- **Recommendation**: set `@Modifying(clearAutomatically=true)` (and `flushAutomatically=true` when pending changes must
  be applied first), or manage affected state explicitly in a suitably isolated persistence context. Automatic clear
  detaches managed state and can discard unflushed changes; it is not an unconditional safe fix.

### HIB-QUERY-002 - Review streaming query resource lifetime

- **Severity**: INFO
- **Inspects**: Spring Data repository methods returning `java.util.stream.Stream`.
- **Fires when**: a verified query-backed repository method returns `Stream<>`.
- **Why it matters**: streaming methods keep the underlying JDBC cursor open and only behave correctly inside an open
  transaction with the caller closing the stream.
- **Recommendation**: consume and close the stream within the required transaction, normally using try-with-resources.
  Read-only transaction hints can help read-only workloads but are not a universal correctness requirement. The method
  signature does not prove that the caller has failed to do this.

### HIB-QUERY-003 - Native Page queries should review count derivation

- **Severity**: INFO
- **Inspects**: Spring Data `@Query(nativeQuery=true)`, `@NativeQuery`, and supported composed annotations returning
  `Page<>`.
- **Fires when**: `countQuery` is missing.
- **Why it matters**: Spring Data can derive a count query for some simple native SQL, but complex SQL can require an
  explicit count query or JSqlParser support.
- **Recommendation**: review the generated count query. Add `countQuery = "..."` when Spring Data cannot derive a correct
  count, especially for complex native SQL.

### HIB-QUERY-004 - Derived deleteBy methods load entities before deletion

- **Severity**: MEDIUM
- **Inspects**: Spring Data derived query methods named `deleteBy...` or `removeBy...`.
- **Fires when**: supported query metadata establishes derivation rather than merely a delete/remove method name.
  Known annotated, custom/default or unresolved named-query implementations are not assumed derived.
- **Why it matters**: Spring Data implements derived deletes by selecting matching entities first and deleting them one
  by one, which is expensive on large result sets.
- **Recommendation**: for bulk removals prefer an explicit `@Modifying @Query("delete from ... where ...")` with
  appropriate persistence-context handling; reserve derived `deleteBy` methods for small or cascading deletes.
  Bulk DML changes callbacks, cascades and optimistic-lock behavior, so it is not a drop-in replacement.

### HIB-QUERY-005 - Eager to-one associations should be JOIN FETCHed in entity-returning queries

- **Severity**: INFO
- **Inspects**: Spring Data JPQL `@Query` methods that return multiple whole entities (`List`/`Set`/`Collection`,
  `Stream`, `Page`, `Slice`, or arrays) on a repository whose domain entity declares an eager `@ManyToOne` / `@OneToOne`
  association (explicit `FetchType.EAGER` or the to-one default). Mapping-level `@Fetch(JOIN)` is not a JPQL exemption.
- **Fires when**: such a query selects the whole root entity (for example `select o from Entity o`) but does not
  `JOIN FETCH` the eager to-one association.
- **Why it matters**: JPQL can require secondary loads to satisfy eager mappings. Repeated targets, caches and actual
  fetch plans determine the query count; this does not prove a SELECT for every row.
- **Recommendation**: `JOIN FETCH` the eager association in the query, or map it `FetchType.LAZY` (see HIB-FETCH-001) and
  fetch it explicitly only where the use case needs it. Complements HIB-FETCH-001 by pinpointing the specific finders
  affected.

### HIB-QUERY-006 - Paged or streamed reads should prefer DTO projections over whole entities

- **Severity**: INFO
- **Inspects**: Spring Data JPQL `@Query` methods that are paged or streamed (a `Pageable` parameter, or a `Page`,
  `Slice`, or `Stream` return type).
- **Fires when**: the query selects the whole root entity (for example `select o from Entity o`) rather than a
  constructor expression (`select new ...(...)`) or an interface/DTO projection.
- **Why it matters**: whole managed entities can load and track more state than a read model needs. Enhanced lazy basic
  attributes and read-only settings change that cost; whole-entity reads can be intentional.
- **Recommendation**: return a DTO/interface projection so Hibernate selects only the columns the caller needs; reserve
  whole-entity reads for cases that mutate the loaded entities.

### HIB-QUERY-007 - Queries should not JOIN FETCH more than one collection

- **Severity**: MEDIUM
- **Inspects**: Spring Data repository `@Query` methods, resolvable JPQL `JOIN FETCH` paths, and collection/bag metadata
  on the repository domain entity.
- **Fires when**: a non-native JPQL query `JOIN FETCH`es two or more collection associations from the same root entity.
  Runtime bag classification is not established, so presumed bags do not receive a higher-severity exception prediction.
- **Why it matters**: simultaneous fetching of actual bags is unsupported, while parallel collections can multiply the
  row set. Java List declarations alone do not fully establish runtime BAG semantics, so the finding describes the
  parallel-fetch risk rather than predicting a guaranteed exception.
- **Recommendation**: fetch at most one collection per query. Initialize the remaining collections with separate queries,
  or suitably bounded batch fetching. An entity graph requesting the same parallel collections is not a universal fix,
  and Set does not eliminate row multiplication.

## Configuration

### HIB-CONFIG-001 - Open Session in View should be disabled

- **Severity**: MEDIUM
- **Inspects**: adapter-provided non-eager evidence of registered OSIV interceptors or filters.
- **Fires when**: OSIV is observed active in a Spring servlet application. A missing property or a servlet class on
  the classpath alone does not prove activation; unavailable evidence is skipped. Severity does not escalate solely
  because of a profile name. An interceptor bean or Boot MVC configurer alone is insufficient: an application extending
  `WebMvcConfigurationSupport` directly can ignore that configurer, and filter beans can be disabled or unregistered.
- **Why it matters**: lazy loading after the service transaction has completed can hide missing fetch plans and move data
  access into the web layer. OSIV does not by itself prove a JDBC connection is held for the entire request.
- **Recommendation**: set `spring.jpa.open-in-view=false` and fetch data inside transactional service boundaries.
- **Applicability**: the Spring adapter supplies the servlet-context signal to the framework-neutral engine. Reactive,
  non-web, and Quarkus applications skip this rule; Quarkus has no Open Session in View mechanism.

### HIB-CONFIG-003 - Lazy loading outside transactions should stay disabled

- **Severity**: HIGH
- **Inspects**: the owning persistence unit's known lazy-loading-outside-transactions setting.
- **Fires when**: that observed setting is `true`; unrelated or absent raw configuration does not establish runtime state.
- **Why it matters**: it hides missing fetch plans by opening temporary sessions outside the intended transaction boundary.
- **Recommendation**: remove the setting and fetch required data inside transactions with explicit fetch plans or DTO
  queries.

### HIB-CONFIG-004 - JDBC batching should be configured for writes

- **Severity**: INFO
- **Inspects**: the owning factory's effective JDBC batch size.
- **Fires when**: the observed size is less than 2. A batch size of 1 cannot combine multiple statements; an unreadable
  size is unknown, not disabled batching.
- **Why it matters**: write-heavy code otherwise sends insert/update/delete statements one at a time.
- **Recommendation**: benchmark a bounded batch size against representative writes. Read-mostly workloads may not
  benefit; no single range is optimal for every driver or statement shape.
- **Quarkus**: absence of `quarkus.hibernate-orm.jdbc.statement-batch-size` does not establish disabled batching.
  The observed Hibernate factory value is authoritative, including provider/dialect defaults.

### HIB-CONFIG-005 - JDBC batching should order inserts and updates

- **Severity**: INFO
- **Inspects**: effective factory insert/update ordering when JDBC batching is enabled.
- **Fires when**: an observed batch size greater than 1 is configured but a known ordering option is disabled.
- **Why it matters**: batches are grouped by SQL/table shape; interleaved entity types reduce batch efficiency.
- **Recommendation**: benchmark ordering when writes are interleaved across multiple entity types; sorting adds cost.
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
- **Fires when**: an observed threshold is not positive. Unavailable runtime evidence is not an unset effective default.
- **Why it matters**: a local slow-query threshold helps spot expensive SQL before it reaches shared environments.
- **Recommendation**: configure a bounded threshold in development and staging profiles.
- **Quarkus**: the neutral Hibernate threshold keys map to
  `quarkus.hibernate-orm.log.queries-slower-than-ms`, so a native Quarkus threshold is recognized.

### HIB-CONFIG-007 - Hibernate statistics should be enabled when tuning

- **Severity**: INFO
- **Inspects**: the live statistics-enabled state of the owning factory.
- **Fires when**: statistics are observed disabled.
- **Why it matters**: statistics expose query counts, fetch counts, and cache hit ratios useful during performance tuning.
- **Recommendation**: enable statistics in development or performance-test profiles when investigating data-access
  behavior. Leaving statistics disabled outside tuning sessions can intentionally avoid collection overhead.
- **Quarkus**: `hibernate.generate_statistics` maps to `quarkus.hibernate-orm.statistics` via
  `QuarkusHibernatePropertyLookup`, so this rule no longer false-positives when statistics are enabled with the
  native Quarkus property name.
- **UI consumer**: the standalone Hibernate Statistics panel (Database group) reads these same live counters once
  enabled — see `docs/features/` and `docs/SPECIFICATION.md` §5.17.1.1 for the panel this recommendation now
  unlocks.

### HIB-CONFIG-008 - Connection providers should disable auto-commit explicitly

- **Severity**: INFO
- **Inspects**: attributed connection-provider and transaction evidence, not a stale Hikari property in isolation.
- **Fires when**: supported observations establish a resource-local provider guarantee that makes the recommendation
  applicable. Otherwise the check is skipped; BootUI never acquires a JDBC connection to discover auto-commit.
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

### HIB-CONFIG-010 - Query caching requires effective region support

- **Severity**: HIGH
- **Inspects**: effective factory query-cache state and region-factory availability.
- **Fires when**: query caching is enabled and effective region infrastructure is known unusable. Missing explicit
  region-factory configuration is not a failure, and disabled entity second-level caching does not itself prohibit
  query caching.
- **Why it matters**: query-result and timestamp regions require a working provider; entity regions have separate
  eligibility and concurrency semantics.
- **Recommendation**: configure appropriate provider infrastructure or disable query caching. Entity-cache coverage
  is a separate workload choice; factory query-cache enablement does not prove that any query opts in.
- **Quarkus**: `hibernate.cache.use_query_cache` maps to `quarkus.hibernate-orm.second-level-caching-enabled` via
  `QuarkusHibernatePropertyLookup` — Quarkus exposes a single unified toggle for second-level/query caching, not a
  separate query-cache property, so both the query-cache and second-level-cache reads resolve to the same Quarkus
  setting. When enabled, Quarkus supplies its integrated second-level cache implementation even though it does not
  expose a `hibernate.cache.region.factory_class` property.

### HIB-CONFIG-011 - Review effective cache concurrency strategy

- **Severity**: MEDIUM
- **Inspects**: observed cache activation and available concurrency-strategy evidence.
- **Fires when**: supported evidence establishes a strategy problem, not merely absence of a Hibernate `@Cache`
  annotation. Providers may supply a valid default; unresolved effective strategy/eligibility is unknown.
- **Why it matters**: concurrency behavior must fit the data, but an explicit annotation is not mandatory.
- **Recommendation**: review the effective strategy and data mutability before adding annotations or changing cache use.
- **Quarkus**: `hibernate.cache.use_second_level_cache` maps to the same
  `quarkus.hibernate-orm.second-level-caching-enabled` toggle as HIB-CONFIG-010, so the precondition check for
  "second-level caching appears configured" is read correctly on Quarkus too.

### HIB-CONFIG-002 - Schema generation should not mutate non-test databases

- **Severity**: INFO (can emit MEDIUM, HIGH, or CRITICAL based on profile and value)
- **Inspects**: the owning unit's normalized schema database action and active profiles.
- **Fires when**: a mutating action is observed outside a test profile. Production-like profiles take precedence:
  destructive drop/drop-and-create actions emit CRITICAL, create-only/update emit HIGH. Dev/local profiles emit INFO;
  unpinned non-test profiles emit MEDIUM. A profile name does not prove the database is disposable.
- **Why it matters**: automatic schema mutation is convenient locally but risky against shared or persistent databases,
  and destructive actions can remove live schema data. Hibernate `hbm2ddl.auto=create` is destructive; Jakarta/Quarkus
  `create` is CREATE_ONLY and must not be described as guaranteed deletion. In Quarkus 3.33.3.1 an explicitly configured
  deprecated `database.generation` takes precedence over `schema-management.strategy`.
- **Recommendation**: use versioned migrations for shared databases and reserve mutating `ddl-auto` values for disposable
  test environments.

### HIB-CONFIG-012 - SQL logging should be off when a production profile is active

- **Severity**: MEDIUM
- **Inspects**: `spring.jpa.show-sql`, `hibernate.show_sql`, and DEBUG/TRACE log levels for `org.hibernate.SQL` /
  `org.hibernate.orm.jdbc.bind` / `org.hibernate.type.descriptor.sql.BasicBinder`.
- **Fires when**: any of those are enabled while a profile named `prod`, `production`, `staging`, or `*-prod` /
  `*-production` is active.
- **Why it matters**: statement logging adds workload-dependent overhead and can reveal sensitive SQL text. Parameter
  values have a separate binding logger checked by HIB-CONFIG-018; no measured throughput loss is inferred.
- **Recommendation**: keep SQL logging off in production-like environments and rely on structured slow-query logging or
  the database's statement audit.

### HIB-CONFIG-013 - Review applicable JDBC temporal binding

- **Severity**: LOW
- **Inspects**: `spring.jpa.properties.hibernate.jdbc.time_zone` and `hibernate.jdbc.time_zone`.
- **Fires when**: supported temporal mapping/binding evidence makes a JDBC-zone review applicable and no fixed zone is
  observed. Without temporal mappings this advice is inapplicable; unavailable binding evidence is not nondeterminism.
- **Why it matters**: some timestamp bindings depend on JVM or JDBC timezone behavior, while native/UTC Instant
  mappings and local wall-clock values have different semantics. Missing `hibernate.jdbc.time_zone` is not universally
  wrong.
- **Recommendation**: choose temporal types and binding semantics deliberately; configure a JDBC zone where needed,
  rather than treating UTC as a universal fix for every date/time mapping.
- **Quarkus**: `hibernate.jdbc.time_zone` maps to `quarkus.hibernate-orm.jdbc.timezone` via
  `QuarkusHibernatePropertyLookup`, so this rule no longer false-positives when the zone is pinned with the native
  Quarkus property name.

### HIB-CONFIG-014 - Hibernate's built-in connection pool should not be used

- **Severity**: HIGH
- **Inspects**: the actual selected Hibernate connection-provider classification.
- **Fires when**: the built-in test-oriented connection pool is observed selected. `pool_size` presence does not select
  it in preference to a managed DataSource or explicit provider; unknown provider evidence is skipped.
- **Why it matters**: the built-in pool is not intended for production use.
- **Recommendation**: use the framework's managed DataSource/provider or an appropriate production pool.

### HIB-CONFIG-015 - Deferred script initialization should have an intentional order

- **Severity**: INFO
- **Inspects**: `spring.jpa.defer-datasource-initialization` and `spring.jpa.hibernate.ddl-auto`.
- **Fires when**: supported Spring initializer evidence establishes deferred initialization. Ignored Spring property
  names in Quarkus cannot activate this rule.
- **Why it matters**: the property moves script-based datasource initialization until after JPA initialization. That can
  be intentional with schema validation or externally managed schemas, but the initialization owner and ordering should
  be explicit.
- **Recommendation**: verify that script-based initialization has the intended owner and order. Do not infer that scripts
  are ineffective solely because Hibernate validates or does not generate the schema.

### HIB-CONFIG-016 - Fail on pagination over collection fetch

- **Severity**: HIGH (INFO when only the safety-net setting is missing)
- **Inspects**: the `hibernate.query.fail_on_pagination_over_collection_fetch` property and paginated collection
  `JOIN FETCH` repository queries.
- **Fires when**: supported unit/runtime/query evidence establishes an applicable missing guard. Unknown runtime or
  guard state is not assumed to be an older unsafe configuration. Concrete query findings are counted once each;
  explanatory summary text does not add a fictitious finding.
- **Why it matters (Hibernate < 7.4)**: without this guard, affected runtimes can allow a paginated collection fetch join
  to fetch the whole result set into memory instead of failing fast.
- **Recommendation**: set `spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true` to throw
  an exception on covered in-memory pagination paths. The risk concerns an unbounded matching result, not necessarily
  an entire table.
- **Hibernate 7.4+**: collection-fetch pagination can be pushed into SQL, but dialect/query-plan fallback still matters.
  An explicit `org.hibernate.limitInMemory=true` hint opts back into in-memory limiting and can bypass the ordinary
  factory guard. The advisor does not certify SQL pushdown or claim the guard overrides that hint.
- **Quarkus**: `hibernate.query.fail_on_pagination_over_collection_fetch` maps to
  `quarkus.hibernate-orm.query.fail-on-pagination-over-collection-fetch` via `QuarkusHibernatePropertyLookup`, so this
  rule no longer false-positives when the safety net is enabled with the native Quarkus property name.

### HIB-CONFIG-017 - Disable SQL formatting in production

- **Severity**: LOW
- **Inspects**: the `hibernate.format_sql` property, SQL-logging state, and active Spring profiles.
- **Fires when**: a production profile is active, `hibernate.format_sql` is `true`, and statement logging is enabled.
  Binder-only logging does not establish that SQL statements are formatted.
- **Why it matters**: Hibernate formats a statement only when it logs that statement. Formatting every verbose SQL log
  line adds avoidable CPU and allocation work in production.
- **Recommendation**: disable `hibernate.format_sql` when verbose SQL logging is enabled in production.
- **Quarkus**: `quarkus.hibernate-orm.log.format-sql` defaults to `true`, but it is inert unless
  `quarkus.hibernate-orm.log.sql` or an equivalent Hibernate SQL logger is enabled.

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

### HIB-CONFIG-019 - SQL comments should be enabled intentionally

- **Severity**: INFO
- **Inspects**: `hibernate.use_sql_comments`.
- **Fires when**: generated SQL comments are enabled.
- **Why it matters**: comments add statement text. Varying text can affect statement-cache keys, but stable comments
  need not fragment caches, and the advisor does not measure either traffic or cache behavior.
- **Recommendation**: keep comments only when their measured observability benefit outweighs the statement-cache cost.

### HIB-CONFIG-020 - Oracle JDBC fetch size should exceed the driver default

- **Severity**: INFO
- **Inspects**: attributed database/driver classification and the owning unit's fetch-size evidence. Raw JDBC URLs and
  credentials are not report evidence.
- **Fires when**: Oracle is identifiable and the fetch size is absent or no greater than Oracle JDBC's default of 10.
- **Why it matters**: iterating result sets larger than ten rows can require avoidable database roundtrips. PostgreSQL and
  MySQL have different driver behavior, so this check deliberately does not prescribe a global fetch size.
- **Recommendation**: for Oracle queries that commonly return more than ten rows, benchmark a bounded fetch size above 10;
  retain the default when result sets are consistently small.
- **Quarkus**: reads `quarkus.hibernate-orm.jdbc.statement-fetch-size` through the native property lookup.

## Caching

### HIB-CACHE-001 - Cached entity association coverage should be reviewed

- **Severity**: INFO
- **Inspects**: entities annotated with `@Cacheable` or Hibernate `@Cache` and the entities they associate with.
- **Fires when**: a cache-enabled unit contains an observed cached-source declaration pointing to an apparently
  uncached target. A collection's `@Cache` stores membership, not the target entity state, and does not exempt its target.
- **Why it matters**: loading a cached aggregate can still query an uncached target, but caching target entities and
  caching collection roles have different semantics and costs. The right coverage depends on real hit rates, mutability,
  and access patterns.
- **Recommendation**: measure cache hit rates and access patterns before caching associated entities or collection roles.
  Leave mutable or low-hit targets uncached when that better fits the workload.
- **Limit**: annotations are not complete provider eligibility evidence; factory enablement does not prove an entity
  region exists or gets useful hits.

### HIB-CACHE-002 - READ_ONLY cache strategy on writable entities is unsafe

- **Severity**: MEDIUM
- **Inspects**: entities annotated with `@Cache(usage = READ_ONLY)`.
- **Fires when**: caching is active and a non-immutable entity has an observed update-oriented declaration.
  `@Immutable` with `@Version` alone is not writable, and disabled caches do not produce active-cache findings.
- **Why it matters**: READ_ONLY is unsuitable for managed state that is actually updated. No cache strategy is inferred
  to observe external writers automatically.
- **Recommendation**: switch to `READ_WRITE` or `NONSTRICT_READ_WRITE` for mutable entities.

### HIB-CACHE-003 - Immutable cached entities should use READ_ONLY

- **Severity**: INFO
- **Inspects**: entity-level Hibernate `@Immutable` and `@Cache(usage=...)`.
- **Fires when**: an immutable cached entity uses `READ_WRITE`, `NONSTRICT_READ_WRITE`, or `TRANSACTIONAL`.
- **Why it matters**: Hibernate documents `READ_ONLY` as the simplest, safest, and best-performing strategy for immutable
  entities; mutable strategies add coordination overhead for updates the mapping forbids.
- **Recommendation**: consider `@Cache(usage = READ_ONLY)` for genuinely immutable cached state; do not remove
  `@Immutable` merely to silence a cache-efficiency prompt. Disabled caching makes this check inapplicable.

## Primary research references

The audit covered every registered rule, including retained workload-dependent advice and the retired identifiers.
These are primary implementation/specification sources, not proof of a measured problem in the host application.

| Topic | Sources and version caveats |
| --- | --- |
| Exact managed dependencies | [Spring Boot 4.1.1 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom), [Quarkus 3.33.3.1 BOM](https://repo.maven.apache.org/maven2/io/quarkus/platform/quarkus-bom/3.33.3.1/quarkus-bom-3.33.3.1.pom). Application overrides remain possible. |
| Factory options and batching | [ORM 7.2.19 options](https://github.com/hibernate/hibernate-orm/blob/7.2.19/hibernate-core/src/main/java/org/hibernate/boot/spi/SessionFactoryOptions.java), [ORM 7.4.5 options](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-core/src/main/java/org/hibernate/boot/spi/SessionFactoryOptions.java), [batching guide](https://github.com/hibernate/hibernate-orm/blob/7.2.6/documentation/src/main/asciidoc/userguide/chapters/batch/Batching.adoc). Factory defaults differ from session overrides. |
| Fetching and entity graphs | [ORM 7.4.5 fetching](https://github.com/hibernate/hibernate-orm/blob/7.4.5/documentation/src/main/asciidoc/userguide/chapters/fetching/Fetching.adoc), [Persistence 3.2 entity operations](https://github.com/jakartaee/persistence/blob/3.2-3.2.0-RELEASE/spec/src/main/asciidoc/ch03-entity-operations.adoc). EAGER and graph requirements do not prove one SQL join. |
| Collection-fetch pagination | [ORM 7.4.5 hint contract](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-core/src/main/java/org/hibernate/jpa/HibernateHints.java#L245-L259), [execution path](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-core/src/main/java/org/hibernate/query/sqm/internal/SqmSelectionQueryImpl.java#L405-L456). Explicit in-memory hints and fallback prevent a blanket 7.4 safety claim. |
| Identifiers and records | [Persistence 3.2 IdClass](https://github.com/jakartaee/persistence/blob/3.2-3.2.0-RELEASE/api/src/main/java/jakarta/persistence/IdClass.java), [EmbeddedId](https://github.com/jakartaee/persistence/blob/3.2-3.2.0-RELEASE/api/src/main/java/jakarta/persistence/EmbeddedId.java), [Hibernate identifier guide](https://github.com/hibernate/hibernate-orm/blob/7.2.6/documentation/src/main/asciidoc/userguide/chapters/domain/identifiers.adoc), [ORM 7.2.19 UUID styles](https://github.com/hibernate/hibernate-orm/blob/7.2.19/hibernate-core/src/main/java/org/hibernate/annotations/UuidGenerator.java). Record support and Serializable guidance follow Persistence 3.2. |
| Mapping defaults and lists | [Persistence 3.2 discriminator](https://github.com/jakartaee/persistence/blob/3.2-3.2.0-RELEASE/api/src/main/java/jakarta/persistence/DiscriminatorColumn.java), [OrderColumn](https://github.com/jakartaee/persistence/blob/3.2-3.2.0-RELEASE/api/src/main/java/jakarta/persistence/OrderColumn.java), [ORM 7.4.5 collection semantics](https://github.com/hibernate/hibernate-orm/blob/7.4.5/documentation/src/main/asciidoc/userguide/chapters/domain/collections.adoc). List declarations are not complete runtime collection classification. |
| Enum/converter semantics | [Persistence 3.2 Enumerated](https://github.com/jakartaee/persistence/blob/3.2-3.2.0-RELEASE/api/src/main/java/jakarta/persistence/Enumerated.java), [Convert](https://github.com/jakartaee/persistence/blob/3.2-3.2.0-RELEASE/api/src/main/java/jakarta/persistence/Convert.java), [Converter](https://github.com/jakartaee/persistence/blob/3.2-3.2.0-RELEASE/api/src/main/java/jakarta/persistence/Converter.java). Auto-apply and XML resolution remain outside declaration-only checks. |
| Entity equality and locking | [Hibernate entity guide](https://github.com/hibernate/hibernate-orm/blob/7.2.6/documentation/src/main/asciidoc/userguide/chapters/domain/entity.adoc), [locking guide](https://github.com/hibernate/hibernate-orm/blob/7.2.6/documentation/src/main/asciidoc/userguide/chapters/locking/Locking.adoc). General guidance is pinned to 7.2.6; no method-body or workload inference is claimed. |
| Spring Data queries and newness | [Spring Data JPA query methods 4.1.0](https://github.com/spring-projects/spring-data-jpa/blob/4.1.0/src/main/antora/modules/ROOT/pages/jpa/query-methods.adoc), [primitive identifier handling 4.1.1](https://github.com/spring-projects/spring-data-commons/blob/4.1.1/src/main/java/org/springframework/data/repository/core/support/AbstractEntityInformation.java#L43-L56), [primitive version fallback 4.1.1](https://github.com/spring-projects/spring-data-jpa/blob/4.1.1/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/support/JpaMetamodelEntityInformation.java#L253-L264). Custom save/newness and runtime query rewriting need separate evidence. |
| Cache infrastructure and defaults | [ORM 7.4.5 cache option construction](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-core/src/main/java/org/hibernate/boot/internal/SessionFactoryOptionsBuilder.java#L444-L477), [provider strategy defaults](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-core/src/main/java/org/hibernate/boot/model/internal/EntityBinder.java#L1838-L1882), [Quarkus ORM guide 3.33.0](https://github.com/quarkusio/quarkus/blob/3.33.0/docs/src/main/asciidoc/hibernate-orm.adoc). Cache enablement, region support and actual use are distinct. |
| Pool selection and OSIV | [ORM 7.4.5 provider selection](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-core/src/main/java/org/hibernate/engine/jdbc/connections/internal/ConnectionProviderInitiator.java#L134-L159), [Boot 4.1.1 OSIV activation](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-jpa/src/main/java/org/springframework/boot/jpa/autoconfigure/JpaBaseConfiguration.java), [Spring 7.0.9 MVC delegation](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-webmvc/src/main/java/org/springframework/web/servlet/config/annotation/DelegatingWebMvcConfiguration.java). Property or configurer-bean presence alone is insufficient. |
| Quarkus schema actions | [3.33.3.1 provider precedence](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/hibernate-orm/runtime/src/main/java/io/quarkus/hibernate/orm/runtime/FastBootHibernatePersistenceProvider.java#L497-L499), [3.33.3.1 action configuration](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/hibernate-orm/runtime/src/main/java/io/quarkus/hibernate/orm/runtime/HibernateOrmRuntimeConfigPersistenceUnit.java), [ORM 7.2.19 Action](https://github.com/hibernate/hibernate-orm/blob/7.2.19/hibernate-core/src/main/java/org/hibernate/tool/schema/Action.java). Jakarta create-only and Hibernate destructive create differ. |
| Kotlin and Panache | [Kotlin 2.3.20 JPA change](https://github.com/JetBrains/kotlin-web-site/blob/master/docs/topics/whatsnew/whatsnew2320.md#L395-L421), [Kotlin 2.3.10 plugin](https://github.com/JetBrains/kotlin/blob/v2.3.10/libraries/tools/kotlin-noarg/src/common/kotlin/org/jetbrains/kotlin/noarg/gradle/KotlinJpaSubplugin.kt), [Kotlin 2.3.21 plugin](https://github.com/JetBrains/kotlin/blob/v2.3.21/libraries/tools/kotlin-noarg/src/common/kotlin/org/jetbrains/kotlin/noarg/gradle/KotlinJpaSubplugin.kt), [Panache guide 3.33.0](https://github.com/quarkusio/quarkus/blob/3.33.0/docs/src/main/asciidoc/hibernate-orm-panache.adoc). Compiler output and platform transformation, not classpath presence, establish capability. |
