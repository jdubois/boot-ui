# Architecture checks

The Architecture panel runs a fixed, zero-config [ArchUnit](https://www.archunit.org/) ruleset against the host
application's own classes. This page lists every rule that ships with BootUI today, what it inspects, when it fires, and
what to do about it.

Each rule is a small class registered in
[`ArchitectureRuleRegistry`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/architecture/ArchitectureRuleRegistry.java)
and implemented in
[`ArchitectureRules.java`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/architecture/ArchitectureRules.java).
The list intentionally stays compact and reviewable; adding a new rule means adding one focused class plus a registry
entry. The rules, the scanner, and the base-package-discovery seam all live in the framework-neutral `bootui-engine`
module, so the exact same ruleset runs unmodified on both the Spring and Quarkus adapters — see
[`docs/QUARKUS-SUPPORT.md`](QUARKUS-SUPPORT.md) for how base-package discovery differs per adapter.

## What BootUI does

The scanner detects the host application's base package(s) — via the Spring adapter's `@SpringBootApplication`
configuration (`AutoConfigurationPackages`) or, on Quarkus, via a build-time `BasePackageProvider` seam that reduces the
Jandex application index to a package root antichain (see [`docs/QUARKUS-SUPPORT.md`](QUARKUS-SUPPORT.md)) — imports the
compiled `.class` files from those packages with ArchUnit's `ClassFileImporter`, and evaluates every registered rule
against the imported classes. Importing is bounded to the application's own base package(s) — never the entire classpath
— and runs only on demand when the scan action is invoked, caching the last report in the controller. When several base
packages are detected, all of them are imported and analyzed together. ArchUnit still resolves the external types those
classes reference (super-classes, interfaces) from the classpath so hierarchy-aware checks work; BootUI keeps that
resolution enabled but quietly skips any referenced class whose resource location uses a URL scheme the JVM cannot open —
such as the Quarkus runtime classloader's `quarkus:` scheme — so a scan never floods the console with per-class resolution
warnings.

When BootUI is installed through `bootui-spring-boot-starter`, ArchUnit is included transitively so the panel works
without an extra application dependency; the Quarkus adapter bundles ArchUnit itself. The panel is available only when:

- ArchUnit is on the classpath, and
- a base package is resolvable from the running application.

If no classes can be imported (for example in some fat-jar or DevTools restart-classloader situations), the panel
degrades to a stable, empty report with an explanatory reason rather than failing.

The exact same rules, including the `SPRING_STEREOTYPES` category below, run unmodified against Quarkus/CDI
applications: rules keyed on Spring-only annotations (`@Autowired`, `@Component`, `@Service`, …) simply match zero
classes and degrade to a no-op pass — never a false positive — while a handful of rules are deliberately dual-framework
because they also key on the shared `jakarta.*` annotations (`jakarta.transaction.Transactional`,
`jakarta.annotation.PostConstruct`/`PreDestroy`) that both Spring and CDI containers recognize. See each rule's entry
below for which category it falls into, and `ArchitectureCdiNeutralityTests` for the automated check that pins this
property across every `SPRING_STEREOTYPES` rule against a pure-CDI fixture set.

## What BootUI does not do

- It does not run project-specific layered-architecture rules — BootUI cannot know the host app's intended layering, so
  it ships only universally-sensible heuristics.
- It does not modify, compile, or instrument application code; it reads already-compiled bytecode.
- It is **not a replacement for a project-authored ArchUnit test suite**. Generic rules are necessarily weaker than
  rules written with knowledge of the application's design. Treat the panel as a starting point and review aid, and
  consider writing your own ArchUnit tests for project-specific invariants.

## Severity scale

Severity reflects the worst plausible impact if the finding is real, not the likelihood:

- **CRITICAL** — supported for the most severe correctness or safety problems. No active check currently emits this
  severity.
- **HIGH** — a serious structural problem with clear maintenance impact (e.g. package cycles — see ARCH-PKG-001 — or
  forcibly terminating the JVM).
- **MEDIUM** — weakens maintainability or layering and usually warrants a fix (e.g. field injection, layering
  inversions).
- **LOW** — defense-in-depth / hygiene gap (e.g. standard-stream use, generic exceptions, `java.util.logging`).
- **INFO** — informational convention prompt (e.g. legacy library use, deprecated APIs).

The scan evaluates every registered rule, but the Rule results panel only lists rules that found violations. Violations
are ordered by importance (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`), then by the number of violating instances, and
include up to a handful of sample detail lines from ArchUnit.

---

## Package structure

### ARCH-PKG-001 — Packages should be free of cycles

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

### ARCH-MOD-001 — Internal packages should not be accessed from other modules

- **Severity**: HIGH
- **Inspects**: direct dependencies from application classes to packages under a literal `internal` segment within the
  detected application base packages.
- **Fires when**: a class outside the owning module prefix accesses a type in another module's `internal` package (for
  example, `base.order` accessing `base.inventory.internal`).
- **Why it matters**: `internal` marks an encapsulation boundary; crossing it couples modules to each other's
  implementation details.
- **Recommendation**: depend only on a module's public API (the packages outside its `internal` subpackage), or move the
  shared type into a published package.

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

### ARCH-CODE-005 — Classes should not call Throwable.printStackTrace(PrintStream/PrintWriter)

- **Severity**: LOW
- **Inspects**: calls to the `Throwable.printStackTrace(PrintStream)` or `printStackTrace(PrintWriter)` overloads.
- **Fires when**: a class calls one of the arg-taking `printStackTrace` overloads instead of logging the exception. The
  no-arg `printStackTrace()` overload is deliberately **not** matched here: it is already covered by ARCH-CODE-001
  (ArchUnit's built-in standard-streams check matches the no-arg overload directly), so this rule only reports the
  overloads ARCH-CODE-001 does not, instead of double-reporting the same no-arg call site under two rule IDs.
- **Recommendation**: log the exception through the project logging facade (e.g. SLF4J) so the stack trace is structured
  and configurable.

### ARCH-CODE-006 — Classes should not forcibly terminate the JVM

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

### ARCH-CODE-007 — Classes should not access JDK-internal APIs

- **Severity**: LOW
- **Inspects**: dependencies on unsupported JDK-internal packages such as `sun..`, `jdk.internal..`, or
  `com.sun..internal..` subtrees.
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

### ARCH-CODE-010 — Exceptions should be named ending with Exception

- **Severity**: LOW
- **Inspects**: classes that extend `Exception` or `RuntimeException`.
- **Fires when**: an exception type's simple class name does not end with `Exception`.
- **Recommendation**: rename exception classes to end with `Exception` so their purpose is immediately clear.

### ARCH-CODE-011 — Interfaces should not have names ending with 'Interface'

- **Severity**: LOW
- **Inspects**: Java interfaces.
- **Fires when**: an interface simple name ends with `Interface`.
- **Recommendation**: name interfaces after the role or behaviour they expose instead of appending an `Interface`
  suffix.

### ARCH-CODE-012 — Loggers should be private static final

- **Severity**: LOW
- **Inspects**: logger fields whose raw type is SLF4J, Log4j2, Commons Logging, JBoss Logging, `java.util.logging`, or
  Logback.
- **Fires when**: a logger field is not `private`, `static`, and `final` — with two recognized alternate patterns.
  Container-managed injection points (`@Inject`, `@Autowired`, or `jakarta.annotation.Resource`, e.g. Quarkus's idiomatic
  `@Inject Logger log;`) are exempt entirely, since a field wired by the container is non-static by construction — see the
  [Quarkus Logging guide's "logging with injection" section](https://quarkus.io/guides/logging#logging-with-injection).
  Legacy `javax.annotation.Resource` is deliberately not exempt: Spring Framework 7 removed support for
  `javax.annotation` annotations, and Quarkus 3 uses the Jakarta namespace, so it is not a container-managed injection
  point on either supported baseline.
  A `protected`, non-static, `final` logger declared in an abstract base class and initialized via
  `LoggerFactory.getLogger(getClass())` is also accepted: subclasses inherit the field and each logs under its own
  runtime class name, which requires the field to be an instance member; the SLF4J FAQ explicitly declines to
  recommend static over instance loggers ("we no longer recommend one approach over the other") and documents instance
  loggers as IOC-friendly — see the [SLF4J FAQ](https://www.slf4j.org/faq.html#declared_static). A plain non-final,
  non-static, non-injected, non-abstract-base-class logger field (e.g. a mutable public field) still fails.
- **Recommendation**: make logger fields `private static final` to avoid accidental external access and per-instance
  logger allocations. For a logger shared with subclasses, declare it `protected`, non-static, and `final` in an
  abstract base class, initialized with `LoggerFactory.getLogger(getClass())`. Container-managed logger injection
  points are exempt because the container wires them, not the class itself.

### ARCH-CODE-013 — Application classes should not depend on test frameworks

- **Severity**: MEDIUM
- **Inspects**: dependencies on common test-only APIs such as JUnit, Mockito, AssertJ, Hamcrest, Spring Test, Spring Boot
  Test, Testcontainers, Quarkus's `@QuarkusTest` (`io.quarkus.test..`), or RestAssured (`io.restassured..`).
- **Fires when**: an application class references a test framework type.
- **Why it matters**: production code that depends on test frameworks is usually an accidental source-set leak and can
  pull unnecessary or unavailable test libraries into runtime code.
- **Recommendation**: move assertions, fixtures, containers, and test helpers to test sources; keep production classes
  independent of test APIs.

### ARCH-CODE-014 — Classes should not have public mutable static fields

- **Severity**: MEDIUM
- **Inspects**: `public static` fields that are not `final`.
- **Fires when**: a class exposes a public static field that can be reassigned, creating shared, globally reachable
  mutable state.
- **Why it matters**: public mutable static state is hard to reason about, is not thread-safe by default, and couples
  unrelated code through a hidden global.
- **Recommendation**: make the field `final` so it cannot be reassigned, reduce its visibility, or move the mutable state
  into a managed bean.

### ARCH-CODE-015 — Utility classes should be final with a private constructor

- **Severity**: LOW
- **Inspects**: classes that expose only static members (at least one static method, no instance methods, and no instance
  fields), excluding interfaces, enums, records, abstract classes, and Spring stereotypes.
- **Fires when**: such a utility class is not `final`, or it can be instantiated through a non-private constructor.
- **Recommendation**: make utility classes `final` and give them a single private constructor so they cannot be
  instantiated or subclassed.

### ARCH-CODE-016 — Classes should not use standard-annotation field injection

- **Severity**: MEDIUM
- **Inspects**: `jakarta.inject.Inject`, `javax.inject.Inject`, `jakarta.annotation.Resource`,
  `javax.annotation.Resource`, or `com.google.inject.Inject` annotations on fields — the standard JSR-330 / Jakarta /
  Guice injection annotations a CDI container such as Quarkus' Arc (or plain Guice) uses.
- **Fires when**: a dependency is injected directly into a field via one of these standard annotations instead of
  through a constructor.
- **Why it matters**: field injection hides required dependencies, prevents `final` fields, and makes classes harder to
  instantiate in tests — the same rationale as ARCH-SPRING-001, just for the framework-neutral annotation set. Kept as a
  separate rule (rather than folded into ARCH-SPRING-001) so it fires correctly on a Quarkus/CDI application that has no
  Spring annotations anywhere on its classpath.
- **Recommendation**: prefer constructor injection so dependencies are explicit, final, and easy to test; CDI containers
  such as Quarkus' Arc inject constructor parameters just as readily as fields.

### ARCH-CODE-017 — Classes should not directly instantiate Thread

- **Severity**: MEDIUM
- **Inspects**: `new Thread(...)` constructor calls, including instantiating a class that extends `Thread`.
- **Fires when**: application code directly constructs a `Thread` (or a `Thread` subclass) instead of using a managed
  executor.
- **Why it matters**: an unmanaged thread bypasses pool sizing, naming, and uncaught-exception handling, and sits
  outside both frameworks' managed-concurrency story — Spring's `TaskExecutor` / `@Async` (and
  `spring.threads.virtual.enabled` on Java 21+), or Quarkus's `ManagedExecutor` / `@RunOnVirtualThread`. This mirrors
  [Effective Java Item 80](https://www.oreilly.com/library/view/effective-java-3rd/9780134686097/), "Prefer executors,
  tasks, and streams to threads", and the JDK `java.util.concurrent.Executor` Javadoc. See the
  [Quarkus context-propagation guide](https://quarkus.io/guides/context-propagation).
- **Recommendation**: use a managed executor instead of instantiating `Thread` directly:
  `java.util.concurrent.ExecutorService`/`Executors`, Spring's `TaskExecutor` or `@Async`, or Quarkus's
  `ManagedExecutor` or `@RunOnVirtualThread`.

### ARCH-CODE-018 — Assertions should have a detail message

- **Severity**: INFO
- **Inspects**: `assert` statements, which compile to a no-arg `new AssertionError()` when they have no detail message
  (via ArchUnit's built-in `GeneralCodingRules.ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE`).
- **Fires when**: a class contains an `assert` statement with no detail message (`assert x > 0;`), which produces a
  near-useless failure diagnostic. An `assert` with a message (`assert x > 0 : "x must be positive";`) compiles to the
  message-taking overload and is not matched.
- **Recommendation**: add a detail message, e.g. `assert x > 0 : "x must be positive";`, so a failure explains what was
  expected.

## Spring stereotypes

### ARCH-SPRING-001 — Classes should not use field injection

- **Severity**: MEDIUM
- **Inspects**: `@Autowired` or `@Value` (Spring's own field-injection annotations) on fields.
- **Fires when**: a dependency is injected directly into a field instead of through a constructor.
- **Why it matters**: field injection hides required dependencies, prevents `final` fields, and makes classes harder to
  instantiate in tests.
- **Recommendation**: prefer constructor injection so dependencies are explicit, final, and easy to test.
- **Quarkus/CDI note**: deliberately scoped to Spring's own annotations only, so it never fires on plain
  `jakarta.inject.Inject` / `@Resource` field injection — the idiomatic style on a CDI/Quarkus application. See
  ARCH-CODE-016 for the framework-neutral equivalent that covers those standard annotations instead.

### ARCH-SPRING-002 — Controllers should not depend on repositories

- **Severity**: MEDIUM
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

### ARCH-SPRING-007 — Repositories should not depend on services

- **Severity**: MEDIUM
- **Inspects**: `@Repository` beans that depend directly on `@Service` beans.
- **Fires when**: persistence code references business services, inverting the usual service-to-repository dependency
  direction.
- **Recommendation**: keep repository beans focused on persistence concerns; dependencies should flow from services
  toward repositories, not back.

### ARCH-SPRING-006 — Services should not depend on controllers

- **Severity**: MEDIUM
- **Inspects**: `@Service` beans that depend directly on `@Controller` / `@RestController` classes.
- **Fires when**: service-layer code references web-layer classes, coupling business logic back to HTTP concerns.
- **Recommendation**: keep service beans free of controller dependencies; web dependencies should flow from controllers
  toward services, not back.

### ARCH-SPRING-004 — Beans should not self-invoke their own proxied methods

- **Severity**: HIGH
- **Inspects**: direct self-invocation (`this.method()`) of methods proxied through `@Transactional` (Spring's own or the
  portable `jakarta.transaction.Transactional`), `@Async`, or any Spring cache operation (`@Cacheable`, `@CachePut`,
  `@CacheEvict`, or `@Caching`) on the method, or `@Async` / a cache operation on the declaring class.
- **Fires when**: a bean calls one of its own proxied methods directly, bypassing the Spring proxy.
- **Why it matters**: the transaction, async execution, or caching behaviour is silently lost because the call never
  passes through the proxy — a real correctness bug, not just a style issue.
- **Recommendation**: refactor so the call goes through the Spring proxy: move the proxied method to a separate bean, or,
  only if necessary, inject a `@Lazy` self-reference and call through it.
- **Quarkus/CDI note**: this is a deliberate dual-framework true positive, not a false-positive risk to guard against —
  self-invocation bypasses a CDI client proxy exactly the same way it bypasses a Spring proxy (Jakarta CDI specification,
  "Client proxy invocation"), so a CDI bean that self-invokes its own `jakarta.transaction.Transactional` method has the
  identical bug and should be flagged. See `ArchitectureCdiNeutralityTests` for the pinned true-positive case.

### ARCH-SPRING-005 — Spring stereotypes should not reside in the default package

- **Severity**: MEDIUM
- **Inspects**: `@Component` / `@Service` / `@Repository` / `@Controller` / `@Configuration` classes in the default
  (unnamed) package.
- **Fires when**: a stereotype-annotated class has no package declaration.
- **Recommendation**: move Spring stereotype beans into a named package so component scanning and proxying work as
  expected.

### ARCH-SPRING-008 — Services and repositories should not depend on web request types

- **Severity**: MEDIUM
- **Inspects**: `@Service` and `@Repository` beans that depend on `jakarta.servlet`, `javax.servlet`, or Spring web
  request types, including WebFlux functional request/response types (`ServerRequest` / `ServerResponse`), reactive
  server exchange/session types (`ServerWebExchange`, `WebSession`, and their families), and low-level reactive HTTP
  server types.
- **Fires when**: business or persistence code accepts, stores, or otherwise references servlet or reactive web
  infrastructure.
- **Why it matters**: service and repository code should be transport-agnostic so it can be reused from HTTP
  controllers, CLI runners, scheduled jobs, tests, and message consumers.
- **Recommendation**: extract request data in the controller and pass plain application values into services and
  repositories.

### ARCH-SPRING-009 — Transactional annotations should not be declared on interfaces

- **Severity**: MEDIUM
- **Inspects**: Spring or Jakarta `@Transactional` annotations on interfaces and interface methods.
- **Fires when**: an interface or one of its methods declares transaction metadata.
- **Why it matters**: Spring recommends annotating concrete classes or methods because interface-declared annotations can
  behave differently across proxy modes and may be silently ignored with AspectJ weaving.
- **Recommendation**: move transaction annotations to concrete implementation classes or methods.

### ARCH-SPRING-010 — Proxy-driven methods should be publicly overridable

- **Severity**: MEDIUM
- **Inspects**: methods annotated with `@Transactional` (Spring's own or the portable
  `jakarta.transaction.Transactional`), `@Async`, or a Spring cache operation (`@Cacheable`, `@CachePut`, `@CacheEvict`,
  or `@Caching`).
- **Fires when**: `@Async`, a Spring cache operation, or Spring's own `@Transactional` is applied to a method that is
  private, protected, package-private, static, or final; or the portable `jakarta.transaction.Transactional` is applied
  to a method that is private, static, or final.
- **Why it matters**: interface-based JDK proxies and the default CGLIB subclass proxy only intercept public, overridable
  instance methods — CGLIB additionally warns and silently calls the original, un-intercepted method when a `final`
  method carries the annotation — so the proxy behaviour can be silently skipped.
- **Recommendation**: make the annotated method public, non-static, and non-final so it can be invoked through a Spring
  proxy, or move the annotation to a method that can be invoked through one.
- **Quarkus/CDI note**: the portable `jakarta.transaction.Transactional` annotation is held to a different, more
  permissive bar than Spring's own annotations. Per the Jakarta CDI specification's "Unproxyable bean types" section, a
  CDI client proxy can intercept public, protected, **and** package-private methods alike — only `private`, `static`, or
  `final` methods are excluded. Applying Spring's stricter "public only" bar to the shared annotation would false-positive
  on a protected or package-private `jakarta.transaction.Transactional` method in a Quarkus/CDI application, where the
  container's own client-proxy mechanism intercepts it correctly.

### ARCH-SPRING-011 — Async methods should return void or Future

- **Severity**: MEDIUM
- **Inspects**: methods annotated with `@Async`, and methods declared on `@Async` classes.
- **Fires when**: an async method returns a value type that is neither `void` nor assignable to
  `java.util.concurrent.Future`.
- **Why it matters**: Spring supports async methods with `void` return values or `Future`/`CompletableFuture` handles;
  other return values do not provide the caller a valid asynchronous result.
- **Recommendation**: use `void` for fire-and-forget async work, or return `Future` / `CompletableFuture` when callers
  need a result.

### ARCH-SPRING-012 — Scheduled methods should have supported signatures

- **Severity**: MEDIUM
- **Inspects**: methods annotated with `@Scheduled`.
- **Fires when**: a scheduled method declares parameters, or returns a non-`void`, non-reactive value type whose result
  Spring will ignore.
- **Why it matters**: Spring invokes scheduled methods without arguments; synchronous return values are discarded, which
  often indicates a misunderstood job contract. Spring's `ScheduledAnnotationReactiveSupport` recognizes a fixed,
  evolving set of deferred reactive return types via `ReactiveAdapterRegistry`: any `org.reactivestreams.Publisher`
  (Reactor's `Mono`/`Flux` included), the JDK's own `java.util.concurrent.Flow.Publisher`, Kotlin's
  `Flow`/`Deferred`, RxJava **3** types (`io.reactivex.rxjava3.*`), and SmallRye Mutiny's `Uni`/`Multi` when on the
  classpath — but never RxJava 2 (`io.reactivex.*`, without the `rxjava3` segment) or `CompletionStage`/
  `CompletableFuture`, both of which Spring registers as non-deferred and so discards exactly like any other synchronous
  return value.
- **Recommendation**: declare scheduled methods without parameters and return `void` unless using a supported deferred
  reactive type (a Reactor/Reactive Streams `Publisher`, `java.util.concurrent.Flow.Publisher`, RxJava 3, or SmallRye
  Mutiny `Uni`/`Multi`).

### ARCH-SPRING-013 — Async should not be used in configuration classes

- **Severity**: MEDIUM
- **Inspects**: `@Async` on `@Configuration` classes or methods declared inside `@Configuration` classes.
- **Fires when**: configuration code is annotated for asynchronous execution.
- **Why it matters**: Spring's `@Async` Javadoc explicitly states that it is not supported on methods declared within
  `@Configuration` classes.
- **Recommendation**: move asynchronous work to a regular Spring bean and call it through that bean's proxy.

### ARCH-SPRING-014 — Classes should not call AopContext.currentProxy

- **Severity**: LOW
- **Inspects**: calls to `org.springframework.aop.framework.AopContext.currentProxy()`.
- **Fires when**: application code looks up the current Spring AOP proxy directly.
- **Why it matters**: Spring documents this as a discouraged last resort because it couples application code to Spring AOP
  internals and requires proxy exposure.
- **Recommendation**: refactor to avoid self-invocation, or inject a self-reference when a proxy call is truly required.

### ARCH-SPRING-015 — Configuration properties classes should be immutable

- **Severity**: INFO
- **Inspects**: non-static instance fields declared in classes annotated with `@ConfigurationProperties`.
- **Fires when**: a `@ConfigurationProperties` class has a non-`final` instance field, i.e. it relies on mutable setter
  binding instead of immutable constructor binding.
- **Why it matters**: Spring Boot favors immutable configuration bound through records or constructors; mutable
  configuration state can be changed after binding and is harder to reason about.
- **Recommendation**: bind configuration through a record or a constructor with `final` fields so configuration state is
  immutable.

> **Removed: ARCH-SPRING-016** ("Layered architecture dependencies should flow from web to service to repository"). This
> holistic rule used ArchUnit's `layeredArchitecture()` over the same three stereotype layers (web/service/persistence)
> that ARCH-SPRING-002 (controllers → repositories), ARCH-SPRING-003 (repositories → controllers), ARCH-SPRING-006
> (services → controllers), and ARCH-SPRING-007 (repositories → services) already check individually. Its violation set
> was verified to be the exact union of what those four pairwise rules already catch, so every real violation was being
> reported **twice** — once under its specific pairwise rule ID, once under ARCH-SPRING-016 — inflating the panel's
> violation and severity counts. The rule was removed and the four granular pairwise rules were kept, since they give
> clearer, more specific per-pair messages (e.g. "Controller X depends on Repository Y" is more actionable than a
> generic layer-violation message).

### ARCH-SPRING-017 — Lite-mode @Bean methods should not call sibling @Bean methods

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

### ARCH-SPRING-018 — Lifecycle callbacks should not be proxy-driven

- **Severity**: HIGH
- **Inspects**: `@PostConstruct` or `@PreDestroy` methods that are also annotated with `@Transactional` (Spring's own or
  the portable `jakarta.transaction.Transactional`), `@Async`, or a Spring cache operation (`@Cacheable`, `@CachePut`,
  `@CacheEvict`, or `@Caching`).
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

### ARCH-SPRING-019 — Async and transactional semantics on one method should be reviewed

- **Severity**: MEDIUM
- **Inspects**: methods annotated with both `@Async` and Spring or Jakarta `@Transactional`.
- **Fires when**: one method combines asynchronous execution with transactional semantics.
- **Why it matters**: the transaction runs on the async worker thread, so the caller's transaction and security context do
  not propagate.
- **Recommendation**: review the design; usually the transactional work belongs in a separate bean method that the
  `@Async` method calls, so the transaction is scoped correctly on the async thread.

### ARCH-SPRING-020 — Async event listeners should return void

- **Severity**: MEDIUM
- **Inspects**: `@EventListener` methods that run asynchronously because `@Async` is declared on the method or its class.
- **Fires when**: an asynchronous event listener declares a non-`void` return type.
- **Why it matters**: Spring supports return values from synchronous event listeners by publishing them as follow-up
  events, but its
  [`@EventListener` contract](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/event/EventListener.html)
  explicitly states that asynchronous listeners cannot publish a subsequent event through their return value.
- **Recommendation**: return `void`; when a follow-up event is needed, inject `ApplicationEventPublisher` and publish it
  explicitly from the listener.

### ARCH-SPRING-021 — BeanPostProcessor and BeanFactoryPostProcessor @Bean methods should be static

- **Severity**: MEDIUM
- **Inspects**: non-static `@Bean` methods that return a `BeanPostProcessor` or `BeanFactoryPostProcessor`.
- **Fires when**: a post-processor factory method is declared as a non-static `@Bean` method.
- **Why it matters**: a non-static post-processor factory method forces its configuration class to be instantiated before
  bean post-processing is fully set up, which can disable post-processing of other beans.
- **Recommendation**: declare these `@Bean` methods `static` so the post-processor can be created without instantiating
  the surrounding configuration class.

### ARCH-SPRING-022 — Legacy javax.transaction.Transactional should be migrated

- **Severity**: HIGH
- **Inspects**: `javax.transaction.Transactional` on classes and methods.
- **Fires when**: application bytecode still uses the legacy Java EE transaction annotation.
- **Why it matters**: Spring Framework 7 uses a Jakarta EE 11 baseline. Its
  [`AnnotationTransactionAttributeSource`](https://github.com/spring-projects/spring-framework/blob/v7.0.8/spring-tx/src/main/java/org/springframework/transaction/annotation/AnnotationTransactionAttributeSource.java)
  registers parsers for Spring's own annotation and `jakarta.transaction.Transactional`, not the old
  `javax.transaction.Transactional`. On BootUI's Spring Boot 4 baseline, the legacy annotation therefore does not create
  the intended transaction boundary.
- **Recommendation**: replace it with Spring's `org.springframework.transaction.annotation.Transactional` or
  `jakarta.transaction.Transactional`, and replace the legacy Java EE API dependency with its Jakarta equivalent.
