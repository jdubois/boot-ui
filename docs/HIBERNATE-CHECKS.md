# Hibernate Advisor checks

The Hibernate Advisor panel runs a fixed, on-demand ruleset against the host application's mapped JPA entities. It reads
the JPA `EntityManagerFactory` metamodel and selected persistence properties; it does not intercept runtime queries,
invoke repositories, execute SQL, or modify mappings.

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

## Identifiers

### HIB-ID-001 - Generated identifiers should avoid GenerationType.IDENTITY

- **Severity**: MEDIUM
- **Inspects**: mapped attributes annotated with `@GeneratedValue`.
- **Fires when**: the generator strategy is `GenerationType.IDENTITY`.
- **Why it matters**: identity columns require the insert to execute immediately so Hibernate can read the generated key,
  which disables JDBC batch inserts for those entities.
- **Recommendation**: prefer `SEQUENCE` with an allocation size and Hibernate pooled optimizer when the database supports
  sequences.

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

## Entity design

### HIB-ENTITY-001 - Entities should override equals and hashCode consistently

- **Severity**: INFO
- **Inspects**: entity classes for detectable `equals(Object)` and `hashCode()` overrides.
- **Fires when**: an entity overrides one method but not the other.
- **Why it matters**: inconsistent equality contracts break sets, maps, and Hibernate collection semantics.
- **Recommendation**: implement `equals` and `hashCode` as a pair, and review generated identifier semantics before using
  entities in hash-based collections.
