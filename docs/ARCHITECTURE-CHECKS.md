# Architecture checks

The Architecture panel runs a fixed, zero-config [ArchUnit](https://www.archunit.org/) ruleset against your
application's own classes. This page lists every rule that ships today, what it inspects, when it fires, and what to do
about it.

Each rule is a small class registered in
[`ArchitectureRuleRegistry`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/architecture/ArchitectureRuleRegistry.java)
and implemented in
[`ArchitectureRules.java`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/architecture/ArchitectureRules.java).
Adding a rule means adding one focused class plus a registry entry, which keeps the list reviewable.

The rules, the scanner, and the base-package-discovery seam all live in the framework-neutral `bootui-engine` module,
so the same ruleset runs unmodified on Spring and Quarkus.

::: tip Reading more than the preview
`sampleViolations` is a ten-entry preview, not the full `violationCount`. **View violations**, or
`GET <api>/architecture/rules/{id}/violations?scanId=...&offset=0&limit=100`, reads bounded sanitized details from the
same completed scan without rerunning ArchUnit. Check the retained counts and `truncated` separately from evidence
coverage. See [snapshot, retention, and MCP/CLI retrieval](features/advisors.md#reading-every-retained-violation).
:::

## What BootUI does

The scanner detects your application's base packages, imports the compiled `.class` files from them with ArchUnit's
`ClassFileImporter`, and evaluates every registered rule against the imported classes. When several base packages are
detected, all of them are imported and analyzed together.

Discovery differs per adapter. The Spring adapter reads the `@SpringBootApplication` configuration through
`AutoConfigurationPackages`. Quarkus uses a build-time `BasePackageProvider` seam that reduces the Jandex application
index to a package root antichain. See [Quarkus design notes](QUARKUS-SUPPORT.md).

Importing is bounded to your own base packages, never the whole classpath, and runs only when you invoke the scan. The
controller caches the last report.

ArchUnit still resolves the external types those classes reference, such as super-classes and interfaces, so
hierarchy-aware checks work. BootUI keeps that resolution enabled but skips any referenced class whose resource
location uses a URL scheme the JVM cannot open, such as the Quarkus runtime classloader's `quarkus:` scheme, so a scan
never floods the console with per-class resolution warnings.

ArchUnit comes in transitively with `bootui-spring-boot-starter`, and the Quarkus adapter bundles it. On Spring, the
panel is available when ArchUnit is on the classpath and a base package is resolvable. Quarkus uses its own panel
availability and build-time discovery, so availability there does not prove that usable package roots or importable
classes were found.

### How a scan reports itself

| Outcome | Status |
| ------- | ------ |
| Known package-discovery or import failure | `ERROR`, never a successful empty result |
| A rule fails, others evaluate | `PARTIAL`, with valid findings and per-rule `analysisErrors` |
| No rule could be evaluated | `ERROR` |
| Successful import under known base packages, no classes found | `SCANNED` with `usable: false`, `coverageComplete: true`, and no limitations |

Failure details identify the error type without exposing arbitrary exception messages.

A complete-empty result stays unscored and reads **Not applicable**, rather than incomplete or a fabricated 100. No
detectable base packages still means unknown coverage even when the status is `SCANNED`: the explanatory message and a
zero rule count do not establish absence. Import and evaluation gaps qualify usable known-findings scores and never
become passing checks. See the shared
[score eligibility policy](features/advisors.md#score-eligibility). These distinctions apply on all three stacks.

### Rules on Quarkus and CDI

The same rules, including the `SPRING_STEREOTYPES` category, run unmodified against Quarkus and CDI applications.
Rules keyed on Spring-only annotations such as `@Autowired`, `@Component`, and `@Service` match zero classes and
degrade to a no-op pass rather than a false positive.

A handful of rules are dual-framework by design, because they also key on the shared `jakarta.*` annotations that both
Spring and CDI containers recognize, such as `jakarta.transaction.Transactional` and
`jakarta.annotation.PostConstruct`, or on legacy `javax.*` annotations that neither container reads. Each rule entry
below says which case it falls into.

Two coding-practice rules adapt to Quarkus: ARCH-CODE-003 is not evaluated there, because `java.util.logging` is a
built-in Quarkus logging API, and ARCH-CODE-016 reports standard-annotation field injection at LOW rather than MEDIUM,
because `@Inject` field injection is the idiom of the Quarkus guides.
`ArchitectureCdiNeutralityTests` pins this property across every `SPRING_STEREOTYPES` rule against a pure-CDI fixture
set.

## Generated application code

The coding-practice rules **ARCH-CODE-001** through **ARCH-CODE-018** exclude classes BootUI can positively identify as
generated, so OpenAPI Generator's `ApiUtil` helpers do not contribute generic-exception findings while a handwritten
generic throw still does.

This is a class-level exemption. It is not a rule dismissal, and it does not exclude every class named `ApiUtil` or
every `api` package. Package-cycle, module-boundary, and Spring or CDI checks keep the full class graph, including
generated types, and handwritten callers and subclasses stay eligible for coding checks.

Identification happens only during an explicit scan. BootUI matches imported classes to their local source ownership
using their module, package, recorded source filename, and enclosing type:

- Maven `target/classes` uses that module's `target/generated-sources`, and `target/test-classes` uses
  `target/generated-test-sources`.
- Gradle `build/classes/java/main` and `build/classes/kotlin/main` use the module's `build/generated` tree, including
  generator-specific subdirectories and OpenAPI Generator's default `build/generate-resources/main` output. The
  corresponding `test` output uses test-source ownership. Recognized source-layout prefixes distinguish `main` from
  `test`, so a package directory with either name does not change the source set.
- Java and Kotlin generated sources are recognized even when their directories do not mirror their package names.

A bounded module-local source census then checks both conventional and custom handwritten directories for conflicting
declarations, excluding generated trees, compiled output, the opposite source set, and `.git`, `.gradle`, `.m2`, and
`node_modules`. Duplicate generated candidates, conflicting handwritten declarations, and uncertain ownership all
prevent an exemption, as do Maven compiler-input lists that identify sources outside the module, which are rejected
without opening those external files.

Classes read from inside an archive, such as an executable jar, an extracted `BOOT-INF/lib` image layout, or a Quarkus
`lib` directory, can never have local source ownership. For those classes only, BootUI recognizes one bytecode
fingerprint: the OpenAPI Generator Spring servlet `ApiUtil` template, as a Java class (`JavaSpring`) or a Kotlin
`object` (`kotlin-spring`), with either `jakarta.servlet` or `javax.servlet`. The class must be a top-level `ApiUtil`
compiled from `ApiUtil.java` or `ApiUtil.kt`, with no members beyond the template's. The complete instruction stream of
`setExampleResponse(NativeWebRequest, String, String)` must equal the verified output of a known compiler: javac
(`--release` 8 through 26), or kotlinc 1.3, 1.5, 1.6 through 1.9, or 2.0 through 2.4. Constants, call descriptors,
argument wiring, branches, and the exception table are all compared, so a changed header, an extra or conditional throw,
an added call, or a broader handler keeps the class eligible, as does output from any other compiler.

Unresolved local classes, including unsupported output layouts and classes without `SourceFile` metadata, never use
the fingerprint, so a template-shaped class compiled from `src/main/java` is still reported. An exact handwritten copy
of the template inside a jar cannot be told apart from generated output and is excluded too. The scan message says how
many classes were excluded this way. The class file is read only during the explicit scan, is limited to 64 KiB, and
types are compared by name, so Spring and the servlet API are never loaded. An unreadable class file keeps its
findings.

::: details Why source lookup is needed at all
The standard `jakarta.annotation.Generated`, `javax.annotation.Generated`, and `javax.annotation.processing.Generated`
annotations have SOURCE retention, so they normally disappear from compiled bytecode. A same-named class-level marker
is recognized when it is actually present, but normal generator output needs local source provenance, and the Kotlin
OpenAPI `ApiUtil` template carries no such marker at all.

BootUI still evaluates bytecode, not source-level coding rules. The source lookup only establishes ownership.
:::

::: details Lookup budgets
A scan is limited to 64 module and source-set groups, 50,000 directory entries, depth 32 beneath each inspected root,
256 KiB per file, and 16 MiB of source and metadata bytes in total. It never follows source-tree symlinks, searches
arbitrary ancestors or the process working directory, downloads sources, or runs a build. Cached reports and
violation-detail reads reuse the completed scan without reading sources again.
:::

The policy is conservative. Apart from the packaged `ApiUtil` template fingerprint, classes in packaged jars,
unsupported or custom output layouts, classes with missing sources or `SourceFile` metadata, and ambiguous matches all
retain their findings, and a SOURCE-retained annotation in a non-generated source layout does not by itself exempt a
class.

Ownership recognition handles multiline declarations, Java Unicode escapes, and Kotlin string templates, but it is not
a full Java or Kotlin parser. Kotlin file facades, including `@file:JvmName` facades, are not treated as explicit
declarations and stay eligible for coding checks, because their function bodies may be handwritten. Source inputs
outside the module, or inside excluded dependency and cache trees, are not supported.

Lookup failures, symlinked source trees, and exhausted budgets produce a sanitized limitation and a `PARTIAL` scan
while retaining uncertain classes and known findings. These limitations carry no source contents or local paths.

This policy is shared by all three stacks. `classesAnalyzed` still counts the full imported application graph, while
coding-rule counts, previews, retained details, and score penalties exclude only established generated findings. An
empty eligible coding-rule target set does not establish usable evidence on its own.

### Violation locations

The same module and source-set lookup, with the same budgets, also resolves where each finding is. After every rule
has run, the scan maps each class named by a [violation location](features/advisors.md#violation-locations) to exactly
one `.java` or `.kt` file under its module's `src/main` or `src/test` tree or generated-source roots, reading only
same-named candidate files to learn their package and length. The location itself comes from ArchUnit's violating
objects, never from the report text:

- a field access, method call, or other dependency points at the calling method or constructor and the line of the
  access;
- a method, constructor, or field finding points at that member, with the method's first recorded line;
- a class finding points at the class, at `CLASS` precision;
- a static initializer, lambda body, or other compiler-generated member keeps its class and line but not its name.

ARCH-PKG-001 cycles span several packages and never carry a location. Classes from archives, other layouts, ambiguous
matches, and exhausted budgets keep a `null` path, with the reason in `violationDetails.locationNotes`; a location
failure never changes a finding, its text, or the scan status. A Kotlin line is kept only when the class's source map
maps it one-to-one onto the class's own file; inlined code, whether its inline function lives in another file or the
same one, and lines past the end of the resolved file drop to `MEMBER` precision. At most 1,024 Kotlin class files are
read for their source map per scan, only from local output directories; beyond that, lines are dropped and a location
note says so. Symbolic links under `src/*/resources`, `src/*/webapp`, or `src/*/frontend` are ignored, while any other
link in a source tree leaves that module's classes without a path, with its own note. A class file reached through a
symbolic link, or missing, leaves only that class without a path.
Unlike the limitations above, resolved locations deliberately include the local source path, so the panel and agents
can open the file.

## Kotlin applications

The rules read compiled bytecode, so they run unchanged on Kotlin classes. The engine recognizes Kotlin constructs by
bytecode name only, and BootUI never adds a `kotlin-stdlib` dependency to your application.

**Compiler-generated shapes are filtered where recognized.** Synthetic and bridge members, `$suspendImpl`, `$default`,
and `$annotations` helpers, `componentN` and `copy` accessors on data classes, `Companion` and `DefaultImpls` holders,
`WhenMappings` tables, and top-level `FooKt` file facades all receive rule-specific filtering. This matters in
practice: an `open suspend fun` compiles into the declared function plus a static synthetic `$suspendImpl` carrying a
copy of the original annotations, which would otherwise produce duplicate and outright false findings.

**Scheduled suspend functions do not require a Unit result.** BootUI excludes the compiler-added `Continuation`
parameter from that check, so both Unit and value-returning functions are supported and only real source arguments
fail.

**Final-by-default is respected in the advice, not the detection.** Kotlin classes and members are final unless marked
`open`, but the `kotlin-spring` and `no-arg` compiler plugins change the emitted bytecode, so proxyability and entity
rules stay accurate. Where a recommendation would otherwise say "remove `final`", it offers the Kotlin equivalent.

## What BootUI does not do

- It does not run project-specific layered-architecture rules. BootUI cannot know your intended layering, so it ships
  general conventions that may not fit every application.
- It does not modify, compile, or instrument application code. It reads already-compiled bytecode.
- It does not replace a project-authored ArchUnit test suite. Generic rules are necessarily weaker than rules written
  with knowledge of the application's design, so treat the panel as a starting point and write your own ArchUnit tests
  for project-specific invariants.

### Static-analysis limits

An error-free scan means the imported classes were evaluated. It does not prove runtime behavior or import
completeness. ArchUnit can resolve external references to stubs without full annotation or hierarchy information, and
those omissions need not throw.

Imports are restricted by package roots rather than a class-count or execution-time budget, so runtime test output
under the same roots is not automatically excluded. Package cycles are evaluated per root and top-level slice, not
across every possible module boundary.

Most Spring rules recognize direct annotations rather than Spring's full merged and aliased metadata model, though the
scheduling check also recognizes repeatable and composed presence. Proxy checks do not observe per-bean JDK, CGLIB, or
AspectJ configuration. The same declaring class does not prove a `this` receiver, and filtering Kotlin dispatch helpers
can hide authored calls inside `$suspendImpl` bodies or mangled internal members.

These remain known limitations rather than problems solved by the logger, scheduling, and `ThreadFactory`
improvements. For background, see the
[ArchUnit import model](https://github.com/TNG/ArchUnit/blob/v1.5.0/docs/userguide/006_The_Core_API.adoc),
[Spring proxy semantics](https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/core/aop/proxying.adoc),
and [Kotlin 2.2.20 suspend lowering](https://github.com/JetBrains/kotlin/blob/v2.2.20/compiler/ir/backend.jvm/lower/src/org/jetbrains/kotlin/backend/jvm/lower/AddContinuationLowering.kt).

## Severity scale

Severity reflects the worst plausible impact if the finding is real, not how likely it is:

| Severity | Meaning |
| -------- | ------- |
| **CRITICAL** | Supported for the most severe correctness or safety problems. No active check emits it. |
| **HIGH** | A serious structural problem with clear maintenance impact, such as package cycles or forcibly terminating the JVM. |
| **MEDIUM** | Weakens maintainability or layering and usually warrants a fix, such as field injection or layering inversions. |
| **LOW** | A defense-in-depth or hygiene gap, such as standard-stream use, generic exceptions, or `java.util.logging`. |
| **INFO** | An informational convention prompt, such as legacy library use or deprecated APIs. |

The scan evaluates every registered rule, but the results panel lists only rules that found violations. They are
ordered by severity, then by the number of violating instances, and include a few sample detail lines from ArchUnit.

The advisor score applies the shared severity penalty to every concrete violating instance, not once per rule.
Dismissing a rule removes all of its instances from the score.

---

## Package structure

### ARCH-PKG-001 - Packages should be free of cycles

- **Severity**: HIGH
- **Inspects**: cyclic dependencies between the top-level package slices under the application base package
  (`<basePackage>.(*)..`).
- **Fires when**: two or more slices depend on each other directly or transitively, forming a cycle. Evaluated per
  detected base package and aggregated. The violation count is the number of cycles ArchUnit reports, not the number of
  dependency edges shown inside those cycle reports.
- **Why it matters**: package cycles make code hard to understand, test, and modularize, and they block clean extraction
  of modules.
- **Recommendation**: break the dependency cycle by extracting shared types or inverting one of the dependencies so
  packages form a directed acyclic graph.

### ARCH-MOD-001 - Internal packages should not be accessed from other modules

- **Severity**: HIGH
- **Inspects**: direct dependencies from application classes to packages under a literal `internal` segment within the
  detected application base packages.
- **Fires when**: a class outside the owning module prefix accesses a type in another module's `internal` package (for
  example, `base.order` accessing `base.inventory.internal`). Each dependency is reported once with ArchUnit's own
  description, so a call or constructor invocation points at its source line; a field or signature type points at the
  class.
- **Why it matters**: `internal` marks an encapsulation boundary; crossing it couples modules to each other's
  implementation details.
- **Recommendation**: depend only on a module's public API (the packages outside its `internal` subpackage), or move the
  shared type into a published package.

## Coding practices

### ARCH-CODE-001 - Classes should not access standard streams

- **Severity**: LOW
- **Inspects**: direct use of `System.out` or `System.err` (via ArchUnit's `GeneralCodingRules`).
- **Fires when**: any class reads `System.out` or `System.err`, including `e.printStackTrace(System.err)`, or calls the
  no-arg `Throwable.printStackTrace()`, instead of using a logging framework. Capturing a stack trace through an
  explicit writer, such as `e.printStackTrace(new PrintWriter(stringWriter))`, is not reported.
- **Recommendation**: replace `System.out` / `System.err` calls with a logger (e.g. SLF4J) so output is structured and
  configurable.

### ARCH-CODE-002 - Classes should not throw generic exceptions

- **Severity**: LOW
- **Inspects**: throwing of generic exception types such as `Exception`, `RuntimeException`, or `Throwable`.
- **Fires when**: a class throws one of the generic types instead of a specific exception.
- **Generated code**: verified generated classes are exempt under the
  [shared generated-code policy](#generated-application-code); handwritten throws remain findings.
- **Recommendation**: throw specific, meaningful exception types so callers can handle failures precisely.

### ARCH-CODE-003 - Classes should not use java.util.logging

- **Severity**: LOW
- **Inspects**: assignments to fields of a `java.util.logging` type (via ArchUnit's `GeneralCodingRules`), typically a
  `java.util.logging.Logger` field.
- **Fires when**: application code stores a `java.util.logging` logger or handler in a field instead of using the
  project logging facade. Tuning a JUL-based library's logger without storing it, such as
  `Logger.getLogger("org.example").setLevel(Level.WARNING)`, is not reported.
- **Quarkus**: not evaluated (`SKIPPED`). Quarkus lists `java.util.logging` as a
  [built-in logging API](https://quarkus.io/guides/logging) backed by JBoss LogManager, and its `@LoggingFilter`
  extension point implements `java.util.logging.Filter`.
- **Recommendation**: use the project logging facade (SLF4J over Logback by default in Spring Boot) for consistent
  logging.

### ARCH-CODE-004 - Classes should not use Joda-Time

- **Severity**: INFO
- **Inspects**: use of the legacy Joda-Time library.
- **Fires when**: a class references Joda-Time types instead of `java.time`.
- **Recommendation**: migrate Joda-Time usage to the standard `java.time` API.

### ARCH-CODE-006 - Classes should not forcibly terminate the JVM

- **Severity**: HIGH
- **Inspects**: calls to `System.exit(int)`, `Runtime.exit(int)`, or `Runtime.halt(int)`.
- **Fires when**: a class abruptly terminates the JVM instead of letting the framework manage shutdown.
  `System.exit(int)` is exempt when called directly from a canonical `public static void main(String[])` entry point:
  this is Spring Boot's own officially
  documented pattern for propagating an `ExitCodeGenerator` result from CLI/batch applications,
  `System.exit(SpringApplication.exit(context, ...))` — see the Spring Boot reference docs,
  ["Application Exit"](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.application-exit).
  A `System.exit` call from anywhere else — a service, controller, or other business-logic class — is still flagged, as
  are all `Runtime.exit`/`Runtime.halt` calls regardless of origin.
- **Recommendation**: let the container or application framework manage the lifecycle instead of calling
  `System.exit()`, `Runtime.exit()`, or `Runtime.halt()`. If you do need to propagate a process exit code from a
  CLI/batch application, call `System.exit(SpringApplication.exit(context, ...))` from the static `main` method only.

### ARCH-CODE-007 - Classes should not access JDK-internal APIs

- **Severity**: MEDIUM
- **Inspects**: dependencies on unsupported JDK-internal packages such as `sun..`, `jdk.internal..`, or
  `com.sun..internal..` subtrees.
- **Fires when**: a class depends on a non-public JDK-internal type.
- **Why it matters**: since [JEP 403](https://openjdk.org/jeps/403) (JDK 17), most internals are strongly
  encapsulated and need `--add-exports` or `--add-opens`, so a JDK upgrade can break the application. Critical internal
  APIs such as `sun.misc.Unsafe` stay accessible, but its memory-access methods are deprecated for removal
  ([JEP 471](https://openjdk.org/jeps/471)) and warn at run time since JDK 24 ([JEP 498](https://openjdk.org/jeps/498)).
- **Recommendation**: depend only on public, supported APIs so the code stays portable across JDK versions, for example
  `VarHandle` or the Foreign Function & Memory API instead of `sun.misc.Unsafe` memory access.

### ARCH-CODE-008 - Classes should not use legacy date and time classes

- **Severity**: INFO
- **Inspects**: dependencies on `java.util.Date`, `java.util.Calendar`, `java.sql.Date`, `java.sql.Time`, and
  `java.sql.Timestamp`.
- **Fires when**: a class declares legacy field, parameter, or return types, constructs legacy values (including
  `Calendar.getInstance()`), or uses legacy operations. Generic and array dependencies are also checked.
  Calls and method references to standard `java.time` conversion bridges are exempt:

  | Legacy type | Exempt bridges |
  | --- | --- |
  | `java.util.Date` | `toInstant()`, `from(Instant)` |
  | `java.util.Calendar` | `toInstant()` |
  | `java.sql.Date` | `toLocalDate()`, `valueOf(LocalDate)` |
  | `java.sql.Time` | `toLocalTime()`, `valueOf(LocalTime)` |
  | `java.sql.Timestamp` | `toInstant()`, `toLocalDateTime()`, `from(Instant)`, `valueOf(LocalDateTime)` |

  For example, a Java or Kotlin mapper that receives a third-party ticket and immediately calls
  `ticket.createdAt.toInstant()` is not flagged for that conversion. A legacy field or method parameter in the same
  mapper remains a finding: the exemption applies to the bridge access, not the whole class. Supported inherited
  bridges are recognized when their JDK declaration can be resolved.
  The string-taking `valueOf` overloads and the unsupported `java.sql.Date.toInstant()` /
  `java.sql.Time.toInstant()` methods remain findings.
  `TimeZone` and `GregorianCalendar` are outside this rule's checked type set, so their bridges, including
  `TimeZone.toZoneId()`, do not introduce findings.
- **Recommendation**: prefer the `java.time` API (`LocalDate`, `Instant`, `ZonedDateTime`, ...) for clearer, immutable
  date/time handling. Use the standard bridges at legacy API boundaries rather than retaining legacy types in
  application fields and signatures.

### ARCH-CODE-009 - Classes should not use deprecated APIs

- **Severity**: INFO
- **Inspects**: access to members or types annotated with `@Deprecated` (via ArchUnit's `GeneralCodingRules`).
- **Fires when**: a class references a deprecated API.
- **Recommendation**: migrate to the recommended replacement API; deprecated members may be removed in future releases.

### ARCH-CODE-010 - Exceptions should be named ending with Exception

- **Severity**: INFO (a naming convention with no runtime effect)
- **Inspects**: classes that extend `Exception` or `RuntimeException`.
- **Fires when**: an exception type's simple class name does not end with `Exception`, and no enclosing class does
  either.
- **Recommendation**: rename exception classes to end with `Exception` so their purpose is immediately clear, or nest
  them inside the exception type they specialise. See
  [Creating Exception Classes](https://docs.oracle.com/javase/tutorial/essential/exceptions/creating.html).
- **Kotlin note**: the variants of a `sealed class` hierarchy are nested inside their parent so the compiler can close
  the hierarchy, which leaves them with names like `ClaimException.AlreadyAssigned`. Those are exempt: the enclosing
  name already says what the type is at every call site, and adding the suffix would only make it stutter. The same
  applies to a nested Java exception hierarchy.

### ARCH-CODE-012 - Loggers should be private final or container-managed

- **Severity**: LOW
- **Inspects**: logger fields whose raw type is SLF4J, Log4j2, Commons Logging, JBoss Logging, `java.util.logging`, or
  Logback.
- **Fires when**: a logger field is not `private` and `final`, with supported injection and abstract-base exceptions.
  Both static and instance loggers are valid; SLF4J does not prefer one over the other.
  Container-managed injection points (`@Inject`, `@Autowired`, or `jakarta.annotation.Resource`, e.g. Quarkus's idiomatic
  `@Inject Logger log;`) are exempt entirely, since a field wired by the container is non-static by construction — see the
  [Quarkus Logging guide](https://quarkus.io/guides/logging#injection-of-a-configured-logger).
  On Quarkus, a non-static, non-final `org.jboss.logging.Logger` field with `io.quarkus.logging.LoggerName`
  is also exempt without `@Inject`, matching the documented default auto-injection behavior in
  [Quarkus 3.33.3.1](https://github.com/quarkusio/quarkus/blob/3.33.3.1/docs/src/main/asciidoc/logging.adoc).
  This exemption does not apply on Spring, to producer fields, to other field types, or when only an unrelated same-name annotation is
  present. The scanner does not observe overrides of Quarkus's default auto-injection configuration.
  Legacy `javax.annotation.Resource` is deliberately not exempt: Spring Framework 7 removed support for
  `javax.annotation` annotations, and Quarkus 3 uses the Jakarta namespace, so it is not a container-managed injection
  point on either supported baseline.
  A `protected`, non-static, `final` logger declared in an abstract base class and initialized via
  `LoggerFactory.getLogger(getClass())` is also accepted: subclasses inherit the field and each logs under its own
  runtime class name, which requires the field to be an instance member; the SLF4J FAQ explicitly declines to
  recommend static over instance loggers ("we no longer recommend one approach over the other") and documents instance
  loggers as IOC-friendly — see the [SLF4J FAQ](https://www.slf4j.org/faq.html#declared_static). A plain non-final,
  non-static, non-injected, non-abstract-base-class logger field (e.g. a mutable public field) still fails.
- **Recommendation**: make logger fields `private final`, optionally `static`, to prevent reassignment and external
  access. Instance fields do not necessarily allocate a new underlying logger. For a logger shared with subclasses,
  declare it `protected`, non-static, and `final` in an
  abstract base class, initialized with `LoggerFactory.getLogger(getClass())`. Container-managed logger injection
  points are exempt because the container wires them, not the class itself. The independent field-injection convention
  in ARCH-CODE-016 is unchanged.

### ARCH-CODE-013 - Application classes should not depend on test frameworks

- **Severity**: MEDIUM
- **Inspects**: dependencies on common test-only APIs such as JUnit, Mockito, AssertJ, Hamcrest, Spring Test, Spring Boot
  Test, Testcontainers, Quarkus's `@QuarkusTest` (`io.quarkus.test..`), or RestAssured (`io.restassured..`).
- **Fires when**: an application class references a test framework type. Classes whose class file sits in a local test
  output directory (`target/test-classes`, `build/classes/java/test`, or `build/classes/kotlin/test`) are not judged:
  they are on the classpath when the application runs from its tests, as with `spring-boot:test-run` or Gradle's
  `bootTestRun`, and may use test APIs by definition. Classes from archives and other layouts stay judged.
- **Why it matters**: production code that depends on test frameworks is usually an accidental source-set leak and can
  pull unnecessary or unavailable test libraries into runtime code.
- **Recommendation**: move assertions, fixtures, containers, and test helpers to test sources; keep production classes
  independent of test APIs.

### ARCH-CODE-014 - Classes should not have public mutable static fields

- **Severity**: MEDIUM
- **Inspects**: `public static` fields that are not `final`.
- **Fires when**: a class exposes a public static field that can be reassigned, creating shared, globally reachable
  mutable state.
- **Why it matters**: public mutable static state is hard to reason about, is not thread-safe by default, and couples
  unrelated code through a hidden global.
- **Recommendation**: make the field `final` so it cannot be reassigned, reduce its visibility, or move the mutable state
  into a managed bean.

### ARCH-CODE-015 - Utility classes should be final with a private constructor

- **Severity**: LOW
- **Inspects**: classes that expose only static members (at least one static method, no instance methods, and no instance
  fields), excluding interfaces, enums, records, abstract classes, and container-managed classes: Spring stereotypes,
  including composed ones such as `@AutoConfiguration` and `@SpringBootConfiguration`, CDI bean-defining annotations,
  JAX-RS resources, and classes declaring `@Bean` or CDI `@Produces` methods. The container instantiates those, and a
  full `@Configuration` class must stay subclassable.
- **Fires when**: such a utility class is not `final`, or it can be instantiated through a non-private constructor.
- **Recommendation**: make utility classes `final` and give them a single private constructor so they cannot be
  instantiated or subclassed.
- **Kotlin note**: compiler-generated holders — top-level `FooKt` file facades, `Companion`, `DefaultImpls` and
  `WhenMappings` classes — are skipped, since their shape is not under the author's control. Idiomatic Kotlin `object`
  declarations are not utility classes (their members are instance members on `INSTANCE`) and never fire.

### ARCH-CODE-016 - Classes should not use standard-annotation field injection

- **Severity**: MEDIUM on Spring, LOW on Quarkus
- **Inspects**: `jakarta.inject.Inject`, `javax.inject.Inject`, `jakarta.annotation.Resource`,
  `javax.annotation.Resource`, or `com.google.inject.Inject` annotations on non-static fields — the standard JSR-330 /
  Jakarta / Guice injection annotations a CDI container such as Quarkus' Arc (or plain Guice) uses. A static
  `jakarta.inject.Inject` field is reported by ARCH-SPRING-023, and a legacy `javax` annotation on a Spring or CDI bean,
  which the container ignores altogether, by ARCH-SPRING-024, so one field is never reported under two IDs.
- **Why the severity differs**: on Spring, `@Inject` field injection is the same pattern as `@Autowired` field injection,
  which the Spring team advises against. On Quarkus, `@Inject` field injection is the idiom of the official guides, which
  only advise against `private` injected fields, so it is a testability preference rather than a defect.
- **Fires when**: a dependency is injected directly into a field via one of these standard annotations instead of
  through a constructor.
- **Why it matters**: field injection hides required dependencies, prevents `final` fields, and makes classes harder to
  instantiate in tests — the same rationale as ARCH-SPRING-001, just for the framework-neutral annotation set. Kept as a
  separate rule (rather than folded into ARCH-SPRING-001) so it fires correctly on a Quarkus/CDI application that has no
  Spring annotations anywhere on its classpath.
- **Recommendation**: prefer constructor injection so dependencies are explicit, final, and easy to test; CDI containers
  such as Quarkus' Arc inject constructor parameters just as readily as fields.
- **Kotlin note**: an injected `lateinit var` is a true positive; take the dependency as a constructor `val` instead.

### ARCH-CODE-017 - Classes should not directly instantiate Thread

- **Severity**: MEDIUM
- **Inspects**: `new Thread(...)` constructor calls, including instantiating a class that extends `Thread`.
- **Fires when**: application code directly constructs a `Thread` (or a subclass), except inside an actual public,
  non-static `ThreadFactory.newThread(Runnable)` implementation with a Thread-compatible return type, or a verified
  Java/Kotlin ThreadFactory lambda body.
  The [JDK 17 ThreadFactory example](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadFactory.html)
  explicitly constructs a thread there. An unrelated `newThread` method, an overload, or another method in the factory
  class is not exempt. Named and anonymous implementations, covariant returns, and captured lambda arguments are
  recognized, including ThreadFactory subinterfaces used only as local lambda targets and intersection types.
  Lambda exemptions use the compiled functional-interface contract, not just a generated method name,
  a `Runnable -> Thread` signature, or the presence of an executor call. Unrelated construction on the same source
  line and non-factory lambdas nested inside a factory remain findings.
- **Why it matters**: an unmanaged thread bypasses pool sizing, naming, and uncaught-exception handling, and sits
  outside both frameworks' managed-concurrency story — Spring's `TaskExecutor` / `@Async` (and
  `spring.threads.virtual.enabled` on Java 21+), or Quarkus's `ManagedExecutor` / `@RunOnVirtualThread`. This mirrors
  [Effective Java Item 80](https://www.oreilly.com/library/view/effective-java-3rd/9780134686097/), "Prefer executors,
  tasks, and streams to threads", and the JDK `java.util.concurrent.Executor` Javadoc. See the
  [Quarkus context-propagation guide](https://quarkus.io/guides/context-propagation).
- **Recommendation**: prefer Spring's `TaskExecutor`/`@Async` or Quarkus's `ManagedExecutor`, or use an
  application-owned `ExecutorService` with explicit shutdown. Plain `Executors` factories are not automatically
  container-managed.
- **Limitations**: construction does not prove that a thread starts. Arbitrary Thread-returning methods, delegated
  factory helpers and shutdown-hook patterns are not exempted through object-flow analysis. Constructor references
  such as `Thread::new` are not reported, whether used as a factory or another functional interface.
  Class-based factory implementations and Java/Kotlin
  LambdaMetafactory bodies are supported; other compiler lowering patterns are not inferred from a signature alone.
  Required bytecode that cannot be read, or constructor observations that cannot be reconciled with it, produce an
  analysis error rather than a clean result. This remains bounded by ArchUnit's imported model: accesses omitted by
  that importer (for example, orphaned synthetic bodies after bytecode rewriting) are not independently recovered.
- **CRaC distinction**: `CRAC-THREAD-001` separately checks thread starts and executor ownership. A factory such as
  `Executors.newSingleThreadScheduledExecutor(r -> new Thread(r))` no longer triggers ARCH-CODE-017 for its lambda,
  but the executor construction can still need CRaC lifecycle review. A factory exemption does not establish that
  its threads remain unstarted or that its executor is lifecycle-managed.

### ARCH-CODE-018 - Assertions should have a detail message

- **Severity**: INFO
- **Inspects**: `assert` statements, which compile to a no-arg `new AssertionError()` when they have no detail message
  (via ArchUnit's built-in `GeneralCodingRules.ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE`).
- **Fires when**: a class contains an `assert` statement with no detail message (`assert x > 0;`), which produces a
  near-useless failure diagnostic. An `assert` with a message (`assert x > 0 : "x must be positive";`) compiles to the
  message-taking overload and is not matched.
- **Recommendation**: add a detail message, e.g. `assert x > 0 : "x must be positive";`, so a failure explains what was
  expected.

## Spring stereotypes

### ARCH-SPRING-001 - Classes should not use field injection

- **Severity**: MEDIUM
- **Inspects**: `@Autowired` or `@Value` (Spring's own field-injection annotations) on non-static fields. Static fields
  are never injected and are reported by ARCH-SPRING-023 instead.
- **Fires when**: a dependency is injected directly into a field instead of through a constructor.
- **Why it matters**: field injection hides required dependencies, prevents `final` fields, and makes classes harder to
  instantiate in tests.
- **Recommendation**: prefer constructor injection so dependencies are explicit, final, and easy to test.
- **Kotlin note**: an `@Autowired lateinit var` is a true positive; take the dependency as a constructor `val` instead.
- **Quarkus/CDI note**: deliberately scoped to Spring's own annotations only, so it never fires on plain
  `jakarta.inject.Inject` / `@Resource` field injection — the idiomatic style on a CDI/Quarkus application. See
  ARCH-CODE-016 for the framework-neutral equivalent that covers those standard annotations instead.

### ARCH-SPRING-002 - Controllers should not depend on repositories

- **Severity**: LOW
- **Inspects**: `@Controller` / `@RestController` classes that depend directly on `@Repository` beans. Spring Data
  repository interfaces without an explicit `@Repository` annotation are not recognized.
- **Fires when**: a controller references a repository, bypassing a service layer.
- **Why LOW**: this is a layering convention, not a defect. Simple CRUD and vertical-slice designs, including Spring's own
  [PetClinic](https://github.com/spring-projects/spring-petclinic), call repositories from controllers. The inverted
  directions (ARCH-SPRING-003, ARCH-SPRING-006, ARCH-SPRING-007) stay MEDIUM.
- **Recommendation**: when business rules, transactions, or reuse across entry points are involved, introduce a service
  layer between controllers and repositories. For thin CRUD endpoints, dismiss the rule if direct repository access is
  the intended design.

### ARCH-SPRING-003 - Repositories should not depend on controllers

- **Severity**: MEDIUM
- **Inspects**: `@Repository` beans that depend on `@Controller` / `@RestController` classes.
- **Fires when**: persistence code references web-layer classes, inverting the expected layering.
- **Recommendation**: keep persistence code free of web concerns; dependencies should flow from controllers toward
  repositories, not back.

### ARCH-SPRING-007 - Repositories should not depend on services

- **Severity**: MEDIUM
- **Inspects**: `@Repository` beans that depend directly on `@Service` beans.
- **Fires when**: persistence code references business services, inverting the usual service-to-repository dependency
  direction.
- **Recommendation**: keep repository beans focused on persistence concerns; dependencies should flow from services
  toward repositories, not back.

### ARCH-SPRING-006 - Services should not depend on controllers

- **Severity**: MEDIUM
- **Inspects**: `@Service` beans that depend directly on `@Controller` / `@RestController` classes.
- **Fires when**: service-layer code references web-layer classes, coupling business logic back to HTTP concerns.
- **Recommendation**: keep service beans free of controller dependencies; web dependencies should flow from controllers
  toward services, not back.

### ARCH-SPRING-004 - Beans should not self-invoke their own proxied methods

- **Severity**: HIGH
- **Inspects**: direct self-invocation (`this.method()`) of methods proxied through `@Transactional` (Spring's own or the
  portable `jakarta.transaction.Transactional`), `@Async`, any Spring cache operation (`@Cacheable`, `@CachePut`,
  `@CacheEvict`, or `@Caching`), Spring Framework 7's `@Retryable` or `@ConcurrencyLimit`, Spring Retry's `@Retryable`,
  or method security (`@PreAuthorize`, `@PostAuthorize`, `@PreFilter`, `@PostFilter`, `@Secured`, or
  `jakarta.annotation.security.RolesAllowed`) on the method, or `@Async` / a cache operation on the declaring class.
- **Fires when**: a bean calls one of its own proxied methods directly, bypassing the Spring proxy.
- **Why it matters**: the transaction, async execution, caching, retry, concurrency limit, or authorization check is
  silently lost because the call never passes through the proxy — a real correctness bug, and for method security an
  authorization bypass. Whether method security is enabled at all is the Security advisor's SEC-METHOD-001.
- **Class-level declarations**: class-level `@Transactional`, `@Retryable`, `@ConcurrencyLimit`, and method security are
  not treated as making every self-call a finding: the calling method already runs inside the same transaction, retry,
  permit, or authorization decision, and routing a nested call through the proxy could even deadlock a
  `@ConcurrencyLimit(1)`.
- **Recommendation**: refactor so the call goes through the Spring proxy: move the proxied method to a separate bean, or,
  only if necessary, inject a `@Lazy` self-reference and call through it.
- **Kotlin note**: Kotlin behaves identically — marking a function `open` does not make a `this`-call go through the
  proxy. Two compiler-generated shapes are read as what the developer wrote rather than as extra self-invocations.
  Calls a function makes into its own helpers (such as the `$suspendImpl` of an `open suspend fun`, or the `$default`
  bridge that fills in default arguments) are not reported, because nobody can refactor a call the compiler makes.
  Conversely, a call that omits a default argument is compiled into a call to that `$default` bridge, and it is
  followed through to the function it dispatches to — so a genuine self-invocation stays reported, named after the
  function you can actually change, instead of disappearing the moment a proxied function gains a default parameter
  value. A self-invocation written inside a lambda is still reported: the compiler puts the body in a synthetic
  method, but the code is yours and the behaviour really is lost.
- **Quarkus/CDI note**: this rule is skipped on Quarkus. Arc deliberately supports intercepted self-invocation, unlike
  standard proxy-based Spring AOP, so reporting the Spring limitation there would be a false positive. See the
  [Quarkus CDI reference](https://quarkus.io/version/3.33/guides/cdi-reference#intercepted-self-invocation).

### ARCH-SPRING-008 - Services and repositories should not depend on web request types

- **Severity**: MEDIUM
- **Inspects**: `@Service` and `@Repository` beans that depend on `jakarta.servlet`, `javax.servlet`, or Spring web
  request types, including WebFlux functional request/response types (`ServerRequest` / `ServerResponse`), reactive
  server exchange/session types (`ServerWebExchange`, `WebSession`, and their families), and low-level reactive HTTP
  server types.
- **Fires when**: business or persistence code accepts, stores, or otherwise references servlet or reactive web
  infrastructure. Exception types in those packages, such as `org.springframework.web.server.ResponseStatusException`,
  are not request state and are not reported.
- **Why it matters**: service and repository code should be transport-agnostic so it can be reused from HTTP
  controllers, CLI runners, scheduled jobs, tests, and message consumers.
- **Recommendation**: extract request data in the controller and pass plain application values into services and
  repositories.

### ARCH-SPRING-009 - Transactional annotations should not be declared on interfaces

- **Severity**: MEDIUM
- **Inspects**: Spring or Jakarta `@Transactional` annotations on interfaces and interface methods.
- **Fires when**: a non-exempt interface or one of its methods declares transaction metadata.
- **Spring Data exception**: on Spring, interfaces extending `org.springframework.data.repository.Repository`
  (directly or through interfaces such as `CrudRepository`, `JpaRepository`, or application-owned base repositories)
  and interfaces carrying `@RepositoryDefinition` (directly, through a composed annotation, or via a superinterface)
  are exempt. Spring Data's repository proxies read transaction declarations from these interfaces, including
  interface-level defaults and method-level overrides such as
  `@Transactional(propagation = REQUIRES_NEW)`. The exemption does not require `@Query` or `@Modifying`; it applies to
  supported repository declarations generally. Custom fragment interfaces inherited by a recognized repository in the
  scanned classes are also exempt, including indirect fragment inheritance. This uses the declared interface hierarchy,
  not fragment implementation naming conventions. A repository-like name or the `@Repository` stereotype alone does not
  establish a Spring Data repository; an unmarked fragment with no recognized repository in the scanned scope remains
  subject to the ordinary-interface guidance.
- **Why it matters**: Spring recommends annotating concrete classes or methods because interface-declared annotations can
  behave differently across proxy modes and may be silently ignored with AspectJ weaving. Spring Data repositories are a
  documented exception, not an instruction to move annotations to an application implementation that may not exist.
- **Recommendation**: move transaction annotations on ordinary interfaces to concrete implementation classes or methods.
  Keep supported Spring Data repository declarations on the interface; see
  [Transactional query methods](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html#transactional-query-methods).

### ARCH-SPRING-010 - Proxy-driven methods should be interceptable

- **Severity**: MEDIUM
- **Inspects**: methods annotated with `@Transactional` (Spring's own or the portable
  `jakarta.transaction.Transactional`), `@Async`, a Spring cache operation (`@Cacheable`, `@CachePut`, `@CacheEvict`,
  or `@Caching`), `@Retryable`, `@ConcurrencyLimit`, or a method security annotation.
- **Fires on Spring when**: a proxy-driven annotation is applied to a private, static, or final method. Spring Framework
  6+ supports protected and package-private transactional and cache methods on class-based proxies, which Spring Boot
  uses by default. Applications that explicitly select interface-based JDK proxies should keep annotated methods public.
- **Fires on Quarkus when**: `jakarta.transaction.Transactional` or `jakarta.annotation.security.RolesAllowed` is
  applied to a private method. Arc supports intercepted
  static methods and transforms final intercepted methods by default, so applying Spring's modifier bar would create
  false positives.
- **Why it matters**: an annotation on a method the active runtime cannot intercept silently loses its transaction,
  asynchronous, or caching behavior.
- **Why MEDIUM rather than HIGH**: a `final` implementation method is still intercepted when the application selects
  interface-based JDK proxies, so the modifier alone does not prove the loss.
- **Recommendation**: for portable Spring proxy behavior, use a public, non-static, non-final method. On Quarkus, avoid
  private interceptor-bound methods.
- **Kotlin note**: classes and members are final by default — mark them `open`, or apply the `kotlin-spring` compiler
  plugin, which opens Spring-annotated classes for you.

### ARCH-SPRING-011 - Async methods should return void or Future

- **Severity**: HIGH
- **Inspects**: methods annotated with `@Async`, and methods declared on `@Async` classes, that a class-based proxy can
  intercept. Private, static, and final methods never reach the interceptor; ARCH-SPRING-010 reports their ignored
  annotation instead.
- **Fires when**: an async method returns a value type that is neither `void`, `kotlin.Unit`, nor assignable to
  `java.util.concurrent.Future`, or a Kotlin suspending function is annotated with `@Async`.
- **Why it matters**: Spring Framework 7's
  [`AsyncExecutionAspectSupport.doSubmit`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-aop/src/main/java/org/springframework/aop/interceptor/AsyncExecutionAspectSupport.java)
  accepts only `Future` variants, `void`, and `kotlin.Unit`, and throws `IllegalArgumentException` ("Invalid return type
  for async method") on every proxied call of anything else. A suspending function's raw return type is `Object`, so it
  fails the same way.
- **Recommendation**: use `void` for fire-and-forget async work, or return `Future` / `CompletableFuture` when callers
  need a result.
- **Kotlin note**: launch the work in a coroutine (for example `withContext(Dispatchers.IO)`) rather than annotating a
  suspending function with `@Async`.

### ARCH-SPRING-012 - Scheduled methods should have supported signatures

- **Severity**: MEDIUM
- **Inspects**: direct `@Scheduled`, nonempty repeatable `@Schedules`, and composed annotations, including transitive
  composition. Repeated schedules do not multiply the same signature finding; empty containers do not count.
- **Fires when**: a scheduled method declares source parameters, returns a non-deferred `CompletionStage`/
  `CompletableFuture`, or returns another non-void type not recognized as a standard reactive type. An ordinary
  synchronous return is a review prompt about discarded values, not an invalid method declaration.
- **Supported types**: Reactive Streams `Publisher` (including Reactor `Mono`/`Flux`), JDK `Flow.Publisher`, Kotlin
  `Flow`/`Deferred`, RxJava 3 `Flowable`/`Observable`/`Single`/`Maybe`/`Completable`, and Mutiny `Uni`/`Multi`, including
  resolvable subtypes. Merely sharing their package is insufficient. RxJava 2 is not a standard Spring 7 adapter family;
  a type implementing Reactive Streams Publisher can still qualify through that interface.
- **Non-deferred distinction**: Spring 7.0.9's standard Reactor registrar supplies a non-deferred CompletionStage
  adapter. Scheduling rejects that adapter when active; without it, the ordinary return value is ignored. See
  [ScheduledAnnotationReactiveSupport](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/scheduling/annotation/ScheduledAnnotationReactiveSupport.java)
  and [ReactiveAdapterRegistry](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-core/src/main/java/org/springframework/core/ReactiveAdapterRegistry.java).
- **Recommendation**: declare no source parameters; use void/Unit, a supported deferred reactive type, or a Kotlin
  suspend function. Custom adapter registrations must be checked separately.
- **Kotlin note**: both Unit and value-returning suspend functions are supported. The Continuation is not a source
  parameter, and emitted results are ignored just like publisher emissions. The runtime requires
  `kotlinx-coroutines-reactor`; the bytecode check does not verify that bridge is available.
- **Limits**: this is signature analysis, not proof the bean/scheduler is active, configuration aliases are resolved,
  all external type metadata is imported, or the effective runtime adapter registry matches Spring defaults.

### ARCH-SPRING-013 - Async should not be used in configuration classes

- **Severity**: MEDIUM
- **Inspects**: `@Async` on `@Configuration` classes or methods declared inside `@Configuration` classes.
- **Fires when**: configuration code is annotated for asynchronous execution.
- **Why it matters**: Spring's `@Async` Javadoc explicitly states that it is not supported on methods declared within
  `@Configuration` classes.
- **Recommendation**: move asynchronous work to a regular Spring bean and call it through that bean's proxy.

### ARCH-SPRING-014 - Classes should not call AopContext.currentProxy

- **Severity**: LOW
- **Inspects**: calls to `org.springframework.aop.framework.AopContext.currentProxy()`.
- **Fires when**: application code looks up the current Spring AOP proxy directly.
- **Why it matters**: Spring documents this as a discouraged last resort because it couples application code to Spring AOP
  internals and requires proxy exposure.
- **Recommendation**: refactor to avoid self-invocation, or inject a self-reference when a proxy call is truly required.

### ARCH-SPRING-015 - Configuration properties classes should be immutable

- **Severity**: INFO
- **Inspects**: non-static instance fields declared in classes annotated with `@ConfigurationProperties`.
- **Fires when**: a `@ConfigurationProperties` class has a non-`final` instance field, i.e. it relies on mutable setter
  binding instead of immutable constructor binding.
- **Why it matters**: Spring Boot favors immutable configuration bound through records or constructors; mutable
  configuration state can be changed after binding and is harder to reason about.
- **Recommendation**: bind configuration through a record or a constructor with `final` fields so configuration state is
  immutable.

### ARCH-SPRING-017 - Lite-mode @Bean methods should not call sibling @Bean methods

- **Severity**: HIGH
- **Inspects**: direct calls between `@Bean` methods declared in the same class when that class is not a full
  `@Configuration(proxyBeanMethods=true)`.
- **Fires when**: a `@Bean` method directly calls a different sibling `@Bean` method in lite mode, where Spring treats
  each factory method with ordinary Java semantics rather than intercepting inter-bean calls.
- **Why it matters**: the call bypasses container resolution and directly creates whatever the sibling factory method
  returns. For the common singleton case this is a duplicate unmanaged instance, but the exact consequence depends on
  the factory method's scope and implementation.
- **Recommendation**: declare the class as `@Configuration` (the default `proxyBeanMethods=true`), or pass the dependency
  as a `@Bean` method parameter instead of calling the sibling `@Bean` method directly.

### ARCH-SPRING-018 - Lifecycle callbacks should not be proxy-driven

- **Severity**: HIGH
- **Inspects**: `@PostConstruct` or `@PreDestroy` methods that are also annotated with `@Transactional` (Spring's own or
  the portable `jakarta.transaction.Transactional`), `@Async`, a Spring cache operation (`@Cacheable`, `@CachePut`,
  `@CacheEvict`, or `@Caching`), `@Retryable`, `@ConcurrencyLimit`, or a method security annotation.
- **Fires when**: a lifecycle callback is annotated with a proxy-driven annotation.
- **Why it matters**: Spring invokes lifecycle callbacks before the bean is wrapped in its proxy, and after it is unwrapped
  at destruction, so the proxy behaviour never applies.
- **Recommendation**: move the transactional, asynchronous, or cached work to a separate proxied bean method and invoke it
  after initialization rather than annotating the lifecycle callback itself.
- **Quarkus/CDI note**: also a deliberate dual-framework true positive. Per the `jakarta.transaction.Transactional`
  Javadoc (Jakarta Transactions specification): "The Transactional interceptor interposes on business method invocations
  only and not on lifecycle events. Lifecycle methods are invoked in an unspecified transaction context." So a
  `@PostConstruct`/`@PreDestroy` method combined with the portable `@Transactional` silently runs without a transaction on
  Quarkus/CDI exactly as it does on Spring. See `ArchitectureCdiNeutralityTests` for the pinned true-positive case.

### ARCH-SPRING-019 - Async and transactional semantics on one method should be reviewed

- **Severity**: MEDIUM
- **Inspects**: methods annotated with both `@Async` and Spring or Jakarta `@Transactional`.
- **Fires when**: one method combines asynchronous execution with transactional semantics.
- **Why it matters**: the transaction runs on the async worker thread, so the caller's transaction and security context do
  not propagate.
- **Recommendation**: review the design; usually the transactional work belongs in a separate bean method that the
  `@Async` method calls, so the transaction is scoped correctly on the async thread.
- **Does not fire when**: the method is a transactional event listener that runs after the publishing transaction
  completed — `@TransactionalEventListener` in its default `AFTER_COMMIT` phase, or in `AFTER_ROLLBACK` /
  `AFTER_COMPLETION`. Spring Modulith's `@ApplicationModuleListener` is exactly that shape: it composes `@Async`,
  `@Transactional(propagation = REQUIRES_NEW)` and `@TransactionalEventListener`, and the whole point is that the
  listener does *not* join the publisher's transaction, which has already committed by the time the listener runs. The
  listener annotation is recognised on the method itself and through a composed annotation, so a project's own
  meta-annotation is exempt too, and `@ApplicationModuleListener` is additionally matched by name (both
  `org.springframework.modulith.events` and the Spring Modulith 1.x `org.springframework.modulith` package) so the
  exemption holds even when that annotation type cannot be resolved.
- **Still fires for**: `@TransactionalEventListener(phase = BEFORE_COMMIT)` combined with `@Async`. There the publishing
  transaction really is still open while the listener runs on another thread, so the listener's own transaction observes
  state the publisher has not committed. The message names the phase; move the listener to `AFTER_COMMIT` or drop
  `@Async`.

### ARCH-SPRING-020 - Async event listeners should return void

- **Severity**: MEDIUM
- **Inspects**: `@EventListener` methods that run asynchronously because `@Async` is declared on the method or its class.
- **Fires when**: an asynchronous event listener declares a non-`void` return type.
- **Why it matters**: Spring supports return values from synchronous event listeners by publishing them as follow-up
  events, but its
  [`@EventListener` contract](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/event/EventListener.html)
  explicitly states that asynchronous listeners cannot publish a subsequent event through their return value.
- **Recommendation**: return `void`; when a follow-up event is needed, inject `ApplicationEventPublisher` and publish it
  explicitly from the listener.

### ARCH-SPRING-021 - BeanPostProcessor and BeanFactoryPostProcessor @Bean methods should be static

- **Severity**: MEDIUM
- **Inspects**: non-static `@Bean` methods that return a `BeanPostProcessor` or `BeanFactoryPostProcessor`.
- **Fires when**: a post-processor factory method is declared as a non-static `@Bean` method.
- **Why it matters**: a non-static post-processor factory method forces its configuration class to be instantiated before
  bean post-processing is fully set up, which can disable post-processing of other beans.
- **Recommendation**: declare these `@Bean` methods `static` so the post-processor can be created without instantiating
  the surrounding configuration class.

### ARCH-SPRING-022 - Legacy javax.transaction.Transactional should be migrated

- **Severity**: HIGH
- **Inspects**: `javax.transaction.Transactional` on classes and methods.
- **Fires when**: application bytecode still uses the legacy Java EE transaction annotation.
- **Why it matters**: Spring Framework 7 uses a Jakarta EE 11 baseline. Its
  [`AnnotationTransactionAttributeSource`](https://github.com/spring-projects/spring-framework/blob/v7.0.8/spring-tx/src/main/java/org/springframework/transaction/annotation/AnnotationTransactionAttributeSource.java)
  registers parsers for Spring's own annotation and `jakarta.transaction.Transactional`, not the old
  `javax.transaction.Transactional`. On BootUI's Spring Boot 4 baseline, the legacy annotation therefore does not create
  the intended transaction boundary. Quarkus 3 is Jakarta-only as well, so the finding applies on both stacks.
- **Recommendation**: replace it with Spring's `org.springframework.transaction.annotation.Transactional` or
  `jakarta.transaction.Transactional`, and replace the legacy Java EE API dependency with its Jakarta equivalent.

### ARCH-SPRING-023 - Injection annotations on static members are ignored

- **Severity**: HIGH
- **Inspects**: static fields and static methods annotated with `@Autowired` or `@Value` on any class, or with
  `jakarta.inject.Inject` on a recognized Spring or CDI bean.
- **Fires when**: an injection annotation sits on a static member.
- **Why it matters**: Spring Framework 7's
  [`AutowiredAnnotationBeanPostProcessor`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-beans/src/main/java/org/springframework/beans/factory/annotation/AutowiredAnnotationBeanPostProcessor.java)
  logs "Autowired annotation is not supported on static fields" at INFO and skips the member, and Quarkus Arc
  [warns and ignores](https://github.com/quarkusio/quarkus/blob/3.33.3.1/independent-projects/arc/processor/src/main/java/io/quarkus/arc/processor/Injection.java)
  a static `@Inject` field or initializer. The container never populates the member, so it keeps its previous value,
  usually `null`.
- **Not reported**: `@Resource` on a static member, which Spring rejects at startup; a static `jakarta.inject.Inject`
  outside a recognized bean, which Guice may inject on request; and the supported workaround of a non-static setter
  that assigns a static field. These fields are not also reported as field injection by ARCH-SPRING-001 or
  ARCH-CODE-016.
- **Recommendation**: inject into an instance field, or preferably a constructor parameter. If a static holder is truly
  required, assign it from a non-static setter or `@PostConstruct` method of a managed bean.

### ARCH-SPRING-024 - Legacy javax injection and lifecycle annotations should be migrated

- **Severity**: HIGH
- **Inspects**: `javax.annotation.PostConstruct` and `javax.annotation.PreDestroy` on any class, and
  `javax.inject.Inject` or `javax.annotation.Resource` on fields, methods, and constructors of recognized Spring or CDI
  beans: Spring stereotypes (including composed ones), types returned by `@Bean` or CDI `@Produces` methods, CDI scope
  or stereotype annotations, and JAX-RS resources and providers.
- **Fires when**: a member carries one of these legacy annotations without its Jakarta counterpart.
- **Why it matters**: Spring Framework 7 registers only `jakarta.annotation.PostConstruct`, `PreDestroy`, and
  `Resource` in
  [`CommonAnnotationBeanPostProcessor`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/context/annotation/CommonAnnotationBeanPostProcessor.java),
  and only `jakarta.inject.Inject` beside `@Autowired` and `@Value`. Quarkus 3's Arc is Jakarta-only as well. The
  callback silently never runs and the injection point is never populated.
- **Not reported**: a member that also carries the Jakarta annotation; a class's only constructor, which both
  containers inject without any annotation; and `javax.inject.Inject` or `javax.annotation.Resource` on classes that
  are not recognized beans, where Guice or Dagger may legitimately own the wiring and ARCH-CODE-016 keeps reporting
  field injection. Kept separate from ARCH-SPRING-022 so that rule's meaning and existing dismissals stay unchanged.
- **Recommendation**: switch the imports to `jakarta.annotation.*` and `jakarta.inject.*`, and depend on the Jakarta APIs
  (`jakarta.annotation-api`, `jakarta.inject-api`). If Dagger or Guice deliberately owns a bean's `javax.inject`
  wiring, dismiss the finding.

## Retired rule IDs

These IDs stay reserved and are never reused, so an existing dismissal can never silently hide a different check.
`ArchitectureRuleRegistryTests` fails if a retired ID is registered again.

| ID | Previous subject | Reason for retirement |
| --- | --- | --- |
| ARCH-CODE-005 | `Throwable.printStackTrace(PrintStream/PrintWriter)` | `printStackTrace(System.err)` already reads `System.err`, which ARCH-CODE-001 reports. The remaining matches were mostly the legitimate `StringWriter` capture idiom or a stream the author chose deliberately. |
| ARCH-CODE-011 | Interfaces named with an `Interface` suffix | A naming opinion with no authoritative source and false positives on domain nouns such as `UserInterface` or `NetworkInterface`. |
| ARCH-SPRING-005 | Spring stereotypes in the default package | Unreachable: imports are bounded to named base packages, a blank base package fails the Spring scan, and Quarkus discovery drops the default package. The live Spring advisor's SPRING-WIRING-008 inspects registered beans instead. |
| ARCH-SPRING-016 | Layered web → service → repository dependencies | Its violations were exactly the union of ARCH-SPRING-002, ARCH-SPRING-003, ARCH-SPRING-006, and ARCH-SPRING-007, so every finding was reported twice. |

## Audit sources

The 2026 catalog audit verified rule behavior against these primary sources:

- Spring Framework 7.0.9 source:
  [`AsyncExecutionAspectSupport`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-aop/src/main/java/org/springframework/aop/interceptor/AsyncExecutionAspectSupport.java),
  [`AutowiredAnnotationBeanPostProcessor`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-beans/src/main/java/org/springframework/beans/factory/annotation/AutowiredAnnotationBeanPostProcessor.java),
  [`CommonAnnotationBeanPostProcessor`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/context/annotation/CommonAnnotationBeanPostProcessor.java),
  the [resilience annotations](https://github.com/spring-projects/spring-framework/tree/v7.0.9/spring-context/src/main/java/org/springframework/resilience/annotation),
  and the [declarative transaction documentation](https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/data-access/transaction/declarative/annotations.adoc).
- Quarkus 3.33: the [logging guide](https://github.com/quarkusio/quarkus/blob/3.33.3.3/docs/src/main/asciidoc/logging.adoc),
  the [CDI reference](https://github.com/quarkusio/quarkus/blob/3.33.3.3/docs/src/main/asciidoc/cdi-reference.adoc), and
  Arc's [`Injection`](https://github.com/quarkusio/quarkus/blob/3.33.3.1/independent-projects/arc/processor/src/main/java/io/quarkus/arc/processor/Injection.java).
- ArchUnit 1.5.0
  [`GeneralCodingRules`](https://github.com/TNG/ArchUnit/blob/v1.5.0/archunit/src/main/java/com/tngtech/archunit/library/GeneralCodingRules.java).
- OpenJDK [JEP 403](https://openjdk.org/jeps/403), [JEP 471](https://openjdk.org/jeps/471), and
  [JEP 498](https://openjdk.org/jeps/498).
