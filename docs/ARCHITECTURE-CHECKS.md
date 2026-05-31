# Architecture checks

The Architecture panel runs a fixed, zero-config [ArchUnit](https://www.archunit.org/) ruleset against the host
application's own classes. This page lists every rule that ships with BootUI today, what it inspects, when it fires, and
what to do about it.

Each rule is a small class registered in
[`ArchitectureRuleRegistry`](../bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/architecture/ArchitectureRuleRegistry.java)
and implemented in
[`ArchitectureRules.java`](../bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/architecture/ArchitectureRules.java).
The list intentionally stays compact and reviewable; adding a new rule means adding one focused class plus a registry
entry.

## What BootUI does

The scanner detects the host application's base package(s) from the `@SpringBootApplication` configuration via
`AutoConfigurationPackages`, imports the compiled `.class` files from those packages with ArchUnit's
`ClassFileImporter`, and evaluates every registered rule against the imported classes. Importing is bounded to the
application's own base package(s) — never the entire classpath — and runs only on demand when the scan action is
invoked, caching the last report in the controller. When several base packages are detected, all of them are imported
and analysed together.

The panel is available only when:

- ArchUnit is on the classpath (it ships as an `optional` dependency of `bootui-autoconfigure`), and
- a base package is resolvable from the running application.

If no classes can be imported (for example in some fat-jar or DevTools restart-classloader situations), the panel
degrades to a stable, empty report with an explanatory reason rather than failing.

## What BootUI does not do

- It does not run project-specific layered-architecture rules — BootUI cannot know the host app's intended layering, so
  it ships only universally-sensible heuristics.
- It does not modify, compile, or instrument application code; it reads already-compiled bytecode.
- It is **not a replacement for a project-authored ArchUnit test suite**. Generic rules are necessarily weaker than
  rules written with knowledge of the application's design. Treat the panel as a starting point and review aid, and
  consider writing your own ArchUnit tests for project-specific invariants.

## Severity scale

Severity reflects the worst plausible impact if the finding is real, not the likelihood:

- **HIGH** — a serious structural problem with clear maintenance impact.
- **MEDIUM** — weakens maintainability or layering and usually warrants a fix (e.g. package cycles, field injection,
  layering inversions).
- **LOW** — defense-in-depth / hygiene gap (e.g. standard-stream use, generic exceptions, `java.util.logging`).
- **INFO** — informational convention prompt (e.g. legacy library use, deprecated APIs).

Each rule result is reported with a status (`PASS`, `VIOLATION`, `SKIPPED`, or `ERROR`), the number of violating
instances, and up to a handful of sample detail lines from ArchUnit.

---

## Package structure

### ARCH-PKG-001 — Packages should be free of cycles

- **Severity**: MEDIUM
- **Inspects**: cyclic dependencies between the top-level package slices under the application base package
  (`<basePackage>.(*)..`).
- **Fires when**: two or more slices depend on each other directly or transitively, forming a cycle. Evaluated per
  detected base package and aggregated.
- **Why it matters**: package cycles make code hard to understand, test, and modularize, and they block clean extraction
  of modules.
- **Recommendation**: break the dependency cycle by extracting shared types or inverting one of the dependencies so
  packages form a directed acyclic graph.

## Coding practices

### ARCH-CODE-001 — Classes should not access standard streams

- **Severity**: LOW
- **Inspects**: direct use of `System.out` or `System.err` (via ArchUnit's `GeneralCodingRules`).
- **Fires when**: any class writes to a standard stream instead of using a logging framework.
- **Recommendation**: replace `System.out` / `System.err` calls with a logger (e.g. SLF4J) so output is structured and
  configurable.

### ARCH-CODE-002 — Classes should not throw generic exceptions

- **Severity**: LOW
- **Inspects**: throwing of generic exception types such as `Exception`, `RuntimeException`, or `Throwable`.
- **Fires when**: a class throws one of the generic types instead of a specific exception.
- **Recommendation**: throw specific, meaningful exception types so callers can handle failures precisely.

### ARCH-CODE-003 — Classes should not use java.util.logging

- **Severity**: LOW
- **Inspects**: direct use of `java.util.logging`.
- **Fires when**: a class references `java.util.logging` instead of the project logging facade.
- **Recommendation**: use the project logging facade (SLF4J over Logback by default in Spring Boot) for consistent
  logging.

### ARCH-CODE-004 — Classes should not use Joda-Time

- **Severity**: INFO
- **Inspects**: use of the legacy Joda-Time library.
- **Fires when**: a class references Joda-Time types instead of `java.time`.
- **Recommendation**: migrate Joda-Time usage to the standard `java.time` API.

### ARCH-CODE-005 — Classes should not call Throwable.printStackTrace()

- **Severity**: LOW
- **Inspects**: calls to `Throwable.printStackTrace()` on any exception type.
- **Fires when**: a class calls `printStackTrace()` instead of logging the exception.
- **Recommendation**: log the exception through the project logging facade (e.g. SLF4J) so the stack trace is structured
  and configurable.

### ARCH-CODE-006 — Classes should not call System.exit

- **Severity**: MEDIUM
- **Inspects**: calls to `System.exit(int)`.
- **Fires when**: a class abruptly terminates the JVM instead of letting the framework manage shutdown.
- **Recommendation**: let the container or application framework manage the lifecycle instead of calling
  `System.exit()`.

### ARCH-CODE-007 — Classes should not access JDK-internal APIs

- **Severity**: LOW
- **Inspects**: dependencies on unsupported JDK-internal packages such as `sun..`, `com.sun..`, or `jdk.internal..`.
- **Fires when**: a class depends on a non-public JDK-internal type.
- **Recommendation**: depend only on public, supported APIs so the code stays portable across JDK versions.

### ARCH-CODE-008 — Classes should not use legacy date and time classes

- **Severity**: INFO
- **Inspects**: use of legacy date/time classes such as `java.util.Date`, `Calendar`, `GregorianCalendar`, or
  `java.sql` date types (via ArchUnit's `GeneralCodingRules`).
- **Fires when**: a class references one of the legacy date/time types instead of `java.time`.
- **Recommendation**: prefer the `java.time` API (`LocalDate`, `Instant`, `ZonedDateTime`, ...) for clearer, immutable
  date/time handling.

### ARCH-CODE-009 — Classes should not use deprecated APIs

- **Severity**: INFO
- **Inspects**: access to members or types annotated with `@Deprecated` (via ArchUnit's `GeneralCodingRules`).
- **Fires when**: a class references a deprecated API.
- **Recommendation**: migrate to the recommended replacement API; deprecated members may be removed in future releases.

## Spring stereotypes

### ARCH-SPRING-001 — Classes should not use field injection

- **Severity**: MEDIUM
- **Inspects**: `@Autowired`, `@Inject`, `@Value`, or `@Resource` annotations on fields.
- **Fires when**: a dependency is injected directly into a field instead of through a constructor.
- **Why it matters**: field injection hides required dependencies, prevents `final` fields, and makes classes harder to
  instantiate in tests.
- **Recommendation**: prefer constructor injection so dependencies are explicit, final, and easy to test.

### ARCH-SPRING-002 — Controllers should not depend on repositories

- **Severity**: LOW
- **Inspects**: `@Controller` / `@RestController` classes that depend directly on `@Repository` beans.
- **Fires when**: a controller references a repository, bypassing a service layer.
- **Recommendation**: introduce a service layer between controllers and repositories to keep web and persistence
  concerns separated.

### ARCH-SPRING-003 — Repositories should not depend on controllers

- **Severity**: MEDIUM
- **Inspects**: `@Repository` beans that depend on `@Controller` / `@RestController` classes.
- **Fires when**: persistence code references web-layer classes, inverting the expected layering.
- **Recommendation**: keep persistence code free of web concerns; dependencies should flow from controllers toward
  repositories, not back.

### ARCH-SPRING-004 — Beans should not self-invoke their own proxied methods

- **Severity**: HIGH
- **Inspects**: direct self-invocation (`this.method()`) of methods annotated with `@Transactional`, `@Async`, or
  `@Cacheable`.
- **Fires when**: a bean calls one of its own proxied methods directly, bypassing the Spring proxy.
- **Why it matters**: the transaction, async execution, or caching behaviour is silently lost because the call never
  passes through the proxy — a real correctness bug, not just a style issue.
- **Recommendation**: move the proxied method to a separate bean (or inject a self-reference) so the call goes through
  the Spring proxy.

### ARCH-SPRING-005 — Spring stereotypes should not reside in the default package

- **Severity**: MEDIUM
- **Inspects**: `@Component` / `@Service` / `@Repository` / `@Controller` / `@Configuration` classes in the default
  (unnamed) package.
- **Fires when**: a stereotype-annotated class has no package declaration.
- **Recommendation**: move Spring stereotype beans into a named package so component scanning and proxying work as
  expected.
