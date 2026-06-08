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

When BootUI is installed through `bootui-spring-boot-starter`, ArchUnit is included transitively so the panel works
without an extra application dependency. The panel is available only when:

- ArchUnit is on the classpath, and
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

- **CRITICAL** — supported for the most severe correctness or safety problems. No active check currently emits this
  severity.
- **HIGH** — a serious structural problem with clear maintenance impact.
- **MEDIUM** — weakens maintainability or layering and usually warrants a fix (e.g. package cycles, field injection,
  layering inversions).
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
  detected base package and aggregated.
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

### ARCH-CODE-005 — Classes should not call Throwable.printStackTrace()

- **Severity**: LOW
- **Inspects**: calls to `Throwable.printStackTrace()` on any exception type.
- **Fires when**: a class calls `printStackTrace()` instead of logging the exception.
- **Recommendation**: log the exception through the project logging facade (e.g. SLF4J) so the stack trace is structured
  and configurable.

### ARCH-CODE-006 — Classes should not forcibly terminate the JVM

- **Severity**: HIGH
- **Inspects**: calls to `System.exit(int)`, `Runtime.exit(int)`, or `Runtime.halt(int)`.
- **Fires when**: a class abruptly terminates the JVM instead of letting the framework manage shutdown.
- **Recommendation**: let the container or application framework manage the lifecycle instead of calling
  `System.exit()`, `Runtime.exit()`, or `Runtime.halt()`.

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

### ARCH-CODE-011 — Interfaces should not have names ending with Interface

- **Severity**: LOW
- **Inspects**: Java interfaces.
- **Fires when**: an interface simple name ends with `Interface`.
- **Recommendation**: name interfaces after the role or behaviour they expose instead of appending an `Interface`
  suffix.

### ARCH-CODE-012 — Loggers should be private static final

- **Severity**: LOW
- **Inspects**: logger fields whose raw type is SLF4J, Log4j2, Commons Logging, JBoss Logging, `java.util.logging`, or
  Logback.
- **Fires when**: a logger field is not `private`, `static`, and `final`.
- **Recommendation**: make logger fields `private static final` to avoid accidental external access and per-instance
  logger allocations.

### ARCH-CODE-013 — Application classes should not depend on test frameworks

- **Severity**: MEDIUM
- **Inspects**: dependencies on common test-only APIs such as JUnit, Mockito, AssertJ, Hamcrest, Spring Test, Spring Boot
  Test, or Testcontainers.
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

## Spring stereotypes

### ARCH-SPRING-001 — Classes should not use field injection

- **Severity**: MEDIUM
- **Inspects**: `@Autowired`, `@Inject`, `@Value`, or `@Resource` annotations on fields.
- **Fires when**: a dependency is injected directly into a field instead of through a constructor.
- **Why it matters**: field injection hides required dependencies, prevents `final` fields, and makes classes harder to
  instantiate in tests.
- **Recommendation**: prefer constructor injection so dependencies are explicit, final, and easy to test.

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
- **Inspects**: direct self-invocation (`this.method()`) of methods proxied through `@Transactional`, `@Async`, or
  `@Cacheable` on the method, or `@Async` / `@Cacheable` on the declaring class.
- **Fires when**: a bean calls one of its own proxied methods directly, bypassing the Spring proxy.
- **Why it matters**: the transaction, async execution, or caching behaviour is silently lost because the call never
  passes through the proxy — a real correctness bug, not just a style issue.
- **Recommendation**: refactor so the call goes through the Spring proxy: move the proxied method to a separate bean, or,
  only if necessary, inject a `@Lazy` self-reference and call through it.

### ARCH-SPRING-005 — Spring stereotypes should not reside in the default package

- **Severity**: MEDIUM
- **Inspects**: `@Component` / `@Service` / `@Repository` / `@Controller` / `@Configuration` classes in the default
  (unnamed) package.
- **Fires when**: a stereotype-annotated class has no package declaration.
- **Recommendation**: move Spring stereotype beans into a named package so component scanning and proxying work as
  expected.

### ARCH-SPRING-008 — Services and repositories should not depend on servlet types

- **Severity**: MEDIUM
- **Inspects**: `@Service` and `@Repository` beans that depend on `jakarta.servlet`, `javax.servlet`, or Spring web
  request types.
- **Fires when**: business or persistence code accepts, stores, or otherwise references servlet request/response
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

### ARCH-SPRING-010 — Proxy-driven methods should be public and non-static

- **Severity**: MEDIUM
- **Inspects**: non-public or static methods annotated with `@Transactional`, `@Async`, or `@Cacheable`.
- **Fires when**: a proxy-driven Spring annotation is applied to a method that is private, protected, package-private, or
  static.
- **Why it matters**: interface-based proxies and the default transaction advisor only apply to public instance methods,
  so the proxy behaviour can be silently skipped.
- **Recommendation**: make the annotated method public and non-static so it can be invoked through a Spring proxy, or
  move the annotation to a method that can be invoked through one.

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
  often indicates a misunderstood job contract.
- **Recommendation**: declare scheduled methods without parameters and return `void` unless using a supported deferred
  reactive `Publisher` type.

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
- **Why it matters**: Spring Boot favours immutable configuration bound through records or constructors; mutable
  configuration state can be changed after binding and is harder to reason about.
- **Recommendation**: bind configuration through a record or a constructor with `final` fields so configuration state is
  immutable.

### ARCH-SPRING-016 — Layered architecture dependencies should flow from web to service to repository

- **Severity**: MEDIUM
- **Inspects**: dependencies among `@Controller` / `@RestController` (web), `@Service` (service), and `@Repository`
  (persistence) beans. Only dependencies whose source and target are both stereotype-annotated are considered, so plain
  classes never trigger a violation.
- **Fires when**: a dependency runs against the canonical `web → service → repository` direction — for example a
  controller depending directly on a repository (skipping the service layer), a repository depending on a service, or any
  lower layer depending on a higher one.
- **Why it matters**: this is the holistic, slice-based complement to the individual stereotype dependency rules; keeping
  the three stereotype layers in a single downward direction preserves a clean, testable layering.
- **Recommendation**: keep dependencies flowing downward — controllers depend on services, services depend on
  repositories, and lower layers never depend on higher ones.

### ARCH-SPRING-017 — Lite-mode @Bean methods should not call sibling @Bean methods

- **Severity**: HIGH
- **Inspects**: direct calls between `@Bean` methods declared in the same class when that class is not a full
  `@Configuration(proxyBeanMethods=true)`.
- **Fires when**: a `@Bean` method directly calls a different sibling `@Bean` method in lite mode, bypassing the Spring
  container.
- **Why it matters**: in lite mode the call is a plain method invocation, so Spring does not return the shared singleton
  and a second, unmanaged instance can be created.
- **Recommendation**: declare the class as `@Configuration` (the default `proxyBeanMethods=true`), or pass the dependency
  as a `@Bean` method parameter instead of calling the sibling `@Bean` method directly.

### ARCH-SPRING-018 — Lifecycle callbacks should not be proxy-driven

- **Severity**: HIGH
- **Inspects**: `@PostConstruct` or `@PreDestroy` methods that are also annotated with `@Transactional`, `@Async`, or
  `@Cacheable`.
- **Fires when**: a lifecycle callback is annotated with a proxy-driven annotation.
- **Why it matters**: Spring invokes lifecycle callbacks before the bean is wrapped in its proxy, and after it is unwrapped
  at destruction, so the proxy behaviour never applies.
- **Recommendation**: move the transactional, asynchronous, or cached work to a separate proxied bean method and invoke it
  after initialization rather than annotating the lifecycle callback itself.

### ARCH-SPRING-019 — Async and transactional semantics on one method should be reviewed

- **Severity**: MEDIUM
- **Inspects**: methods annotated with both `@Async` and Spring or Jakarta `@Transactional`.
- **Fires when**: one method combines asynchronous execution with transactional semantics.
- **Why it matters**: the transaction runs on the async worker thread, so the caller's transaction and security context do
  not propagate.
- **Recommendation**: review the design; usually the transactional work belongs in a separate bean method that the
  `@Async` method calls, so the transaction is scoped correctly on the async thread.

### ARCH-SPRING-021 — BeanPostProcessor and BeanFactoryPostProcessor @Bean methods should be static

- **Severity**: MEDIUM
- **Inspects**: non-static `@Bean` methods that return a `BeanPostProcessor` or `BeanFactoryPostProcessor`.
- **Fires when**: a post-processor factory method is declared as a non-static `@Bean` method.
- **Why it matters**: a non-static post-processor factory method forces its configuration class to be instantiated before
  bean post-processing is fully set up, which can disable post-processing of other beans.
- **Recommendation**: declare these `@Bean` methods `static` so the post-processor can be created without instantiating
  the surrounding configuration class.
