# GraalVM readiness checks

The GraalVM panel surveys the host application for [GraalVM native-image](https://www.graalvm.org/latest/reference-manual/native-image/)
readiness and can generate a `reachability-metadata.json` scaffold from the scan. This page lists every check that ships
with BootUI today, what it inspects, when it fires, and what to do about it.

Each check is a small class registered in
[`GraalVmCheckRegistry`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/graalvm/GraalVmCheckRegistry.java)
and implemented in
[`GraalVmChecks.java`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/graalvm/GraalVmChecks.java).
The list intentionally stays compact and reviewable; adding a new check means adding one focused class plus a registry
entry. The rule engine (checks, categories, the dependency scanner, and the reachability-metadata scaffold generator) is
framework-neutral and lives in `bootui-engine`; today it is surfaced only through thin Spring adapter wiring in
`bootui-spring-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/graalvm/` (the controller, the
Dockerfile generator, and the source-tree writer).

## What BootUI does

The scanner detects the host application's base package(s) from the `@SpringBootApplication` configuration via
`AutoConfigurationPackages`, imports the compiled `.class` files from those packages with [ArchUnit](https://www.archunit.org/)'s
`ClassFileImporter`, and evaluates every registered check against the imported classes. Importing is bounded to the
application's own base package(s) — never the entire classpath — and runs only on demand when the scan action is
invoked, caching the last report in the controller.

In addition to the checks, the scan does two things:

- **Surveys classpath dependencies** (when the _Include dependencies_ toggle is on; it is on by default) to report which
  third-party JARs already ship bundled reachability metadata under `META-INF/native-image/`. BootUI counts metadata only
  when a `.json` file exists under that directory; a JAR that only has `native-image.properties` is reported as bundling
  native-image build arguments, not reachability metadata. The survey opens only classpath JARs, stops after 500 JARs,
  and adds a warning when that cap is hit; libraries without bundled metadata may need your own configuration, repository
  metadata, or the tracing agent. A single classpath entry can expand into several reported dependencies: when the
  application runs as a Spring Boot fat/uber jar, `java.class.path` only ever contains the outer launcher jar (Spring
  Boot's `LaunchedURLClassLoader` resolves `BOOT-INF/lib/*.jar` through custom `nested:` URLs that never populate that
  system property), so the survey expands the launcher jar into one inspected dependency per nested `BOOT-INF/lib/`
  library instead of misreporting it as a single dependency named after the application's own launcher jar. When a
  shaded/uber jar (built with, for example, the Maven Shade or Gradle Shadow plugin) bundles more than one
  `META-INF/maven/<groupId>/<artifactId>/pom.properties` — one for itself and one for each dependency it relocated into
  itself — the survey prefers the descriptor whose `artifactId`/`version` matches the jar's own file name, recovering the
  shaded jar's own coordinates in the common case rather than misreporting it under one of the dependencies it relocated.
- **Builds a GraalVM 25 `reachability-metadata.json` scaffold** from the application's own classes — unified reflection,
  serialization and JNI registrations, standard configuration/logging resource globs, and explicit proxy/Unsafe/FFM
  completion guidance when those calls are detected — which you can download from the panel.
- **Installs the scaffold into the source tree** when the application is detectably running from an exploded build (for
  example `mvn spring-boot:run` or an IDE) rather than a packaged jar. The **Write into project** action
  writes the scaffold to `src/main/resources/META-INF/native-image/<groupId>/<artifactId>/reachability-metadata.json`
  (coordinates resolved from `build-info.properties` or the project `pom.xml`, falling back to a `bootui-generated`
  namespace). The write is confined under `src/main/resources` and refuses to overwrite a `reachability-metadata.json`
  that BootUI did not generate.
- **Generates a tailored `Dockerfile-native`** for the host application — a multi-stage build that detects the project's
  build system (Maven or Gradle, with or without the wrapper) and compiles a GraalVM native image with the matching
  command (`./mvnw`/`mvn -Pnative -DskipTests clean native:compile`, or `./gradlew`/`gradle nativeCompile`), then packages the
  resulting executable (named after the resolved `artifactId`) into a minimal, distroless runtime image
  (`gcr.io/distroless/base-debian12:nonroot`). That base runs as a non-root user and ships glibc but no shell, package
  manager, curl, perl or tar, so the runtime's OS-package CVE surface stays near zero; the native image is built *mostly
  static* (only glibc is linked dynamically) so it needs no extra libraries. Because distroless has no shell or curl
  there is no Docker `HEALTHCHECK` - probe `/actuator/health` (or the web root) from your orchestrator instead. When
  the project carries no wrapper, the build stage installs a known, pinned
  Maven/Gradle release (declared as a constant in the generator and exposed as a Docker `ARG`) so the image is
  self-contained. You can download it, or — under the same exploded-build constraint as the scaffold install — write it
  to the project root. That write is fail-closed and refuses to overwrite a `Dockerfile-native` that BootUI did not
  generate.
- **Writes both artifacts in one step.** The scaffold and the `Dockerfile-native` are offered in a three-drawer
  accordion whose default, top **All files** drawer generates and writes both files into the source tree in a single
  action — under the same exploded-build constraint and the same fail-closed guards — and reports each file's outcome
  individually.

When BootUI is installed through `bootui-spring-boot-starter`, ArchUnit is included transitively so the panel works
without an extra application dependency. The panel is available only when ArchUnit is on the classpath and a base
package is resolvable from the running application. If no classes can be imported, the panel degrades to a stable, empty
report with an explanatory reason rather than failing.

Separately from the panel scan, BootUI registers Spring AOT runtime hints for its own native-image needs from
[`BootUiRuntimeHints`](https://github.com/jdubois/boot-ui/blob/main/bootui-spring-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/BootUiRuntimeHints.java).
Those built-in hints cover BootUI's runtime-scanned classpath resources, BootUI DTO records used by Jackson, and the
well-known reflective calls used by the Heap Dump, Security, and Pentesting panels. They are contributed by
`BootUiAutoConfiguration`, so applications using the starter should not need to copy BootUI-specific hints into their own
native-image configuration.

## What BootUI does not do

- It is **not a replacement for the [GraalVM tracing agent](https://www.graalvm.org/latest/reference-manual/native-image/metadata/AutomaticMetadataCollection/)
  or an actual native-image build**. Static analysis cannot see reflection driven by runtime data, so the checks are
  heuristic review prompts, and the generated metadata is a scaffold to review and complete — not a finished file.
- It does not analyze third-party dependency bytecode for readiness; for dependencies it only reports whether classpath
  JARs ship bundled reachability metadata JSON or native-image build arguments.
- It does not modify, compile, or instrument application code; it reads already-compiled bytecode.
- Spring-managed beans are already covered by Spring AOT, so findings that overlap with Spring's own AOT processing may
  be safe to ignore.

## Detecting missing metadata at development time

BootUI's checks are static, build-independent heuristics; they cannot see reflection driven by runtime-only data (for
example, a class name read from a config file), so a clean scan is not a guarantee that a native image will run
correctly. GraalVM's own recommended complement to static analysis is to make missing metadata fail loudly during
development instead of surfacing as a silent runtime bug:

- Pass **`--exact-reachability-metadata`** (introduced in GraalVM 23 for debugging and still opt-in in GraalVM 25), or,
  to scope exact handling to specific packages,
  **`--exact-reachability-metadata=<comma-separated-package-list>`**, to `native-image` at build time to opt in to the
  stricter, more debuggable handling of reflection, resources, JNI and serialization. Use
  `--exact-reachability-metadata-path=<classpath-or-module-path-entry>` when exact handling should apply to all types
  originating from selected path entries.
- Run the native image with **`-XX:MissingRegistrationReportingMode=Warn`** to see every place a registration is
  missing without crashing, or with **`-XX:MissingRegistrationReportingMode=Exit`** — recommended for automated
  tests — to make the application print the error with a full stack trace and exit immediately the first time a
  missing registration is hit, including ones a broad `catch (Throwable t)` would otherwise silently swallow.

See the ["Reachability Metadata" reference](https://www.graalvm.org/latest/reference-manual/native-image/metadata/) for
the authoritative, up-to-date flag documentation. BootUI does not implement this as an automated check: unlike every
other check on this page, which fires only when a specific risky bytecode/reflection construct is present, these flags
are a blanket recommendation for essentially every native-image build regardless of what the code does — there is no
bytecode condition to scan for, so an automated check would either fire unconditionally (defeating the panel's
"only show what needs review" design) or require parsing the project's build file (`pom.xml` / `build.gradle`) to
detect existing native-image arguments, a data source no other check depends on. It is listed here as a recommended
practice to pair with the panel's static checks, not as another unconditional check.

## The generated `reachability-metadata.json`

The scaffold follows the GraalVM 25 unified
[reachability metadata schema](https://github.com/oracle/graal/blob/master/docs/reference-manual/native-image/assets/reachability-metadata-schema-v1.2.0.json).
Serialization is represented by `serializable: true` on a reflection registration (not the legacy top-level
`serialization` array), and classes declaring native methods receive `jniAccessible: true`. Each named type carries a
`condition.typeReached` guard. Reflection candidates include concrete records and `Serializable` types plus JPA entities
and mapped superclasses, including abstract persistence base types. Resource globs cover
`application*.properties` / `application*.yml` / `application*.yaml`, `logback-spring.xml`, and `log4j2-spring.xml`.

Static bytecode analysis cannot reliably recover runtime-computed proxy interface arrays, the `Class` argument passed to
`Unsafe.allocateInstance`, or FFM `FunctionDescriptor` layouts. When those checks fire, the generated file therefore
adds explicit review instructions rather than inventing unsafe registrations. FFM findings also scaffold the schema-valid
`foreign` object with empty `downcalls`, `upcalls`, and `directUpcalls` arrays for the developer or tracing agent to
complete. Dynamic proxies use a structured reflection type such as
`{"type":{"proxy":["com.example.Interface"]}}`; FFM entries require the real memory-layout descriptors.

Review the generated file with the tracing agent, then place it under
`src/main/resources/META-INF/native-image/<groupId>/<artifactId>/` in your application. The panel substitutes the
resolved `groupId`/`artifactId` into that hint whenever it can determine them — from `build-info.properties` (which works
even when running from a packaged jar) or the project `pom.xml` — and keeps the `<groupId>`/`<artifactId>` placeholders
only when no coordinates can be resolved.

## Severity scale

Severity reflects the worst plausible impact if the finding is real, not the likelihood:

- **CRITICAL** — a construct with the most severe native-image impact if the finding is real. No active GraalVM check
  currently emits this severity.
- **HIGH** — a construct native images generally cannot support or Spring AOT cannot safely capture at run time (runtime
  class generation or Java compilation, script-engine discovery without a native integration, runtime classpath scanning,
  runtime instance suppliers, bean-referencing expression conditions, secondary context creation, dynamic/model MBeans).
- **MEDIUM** — a construct GraalVM cannot resolve at build time that will usually fail at run time without metadata
  (reflection, dynamic class loading, deep reflection, unsafe allocation, dynamic proxies, active JDK serialization, SpEL,
  method handles, frozen AOT conditions, custom security providers, runtime singleton registration).
- **LOW** — a construct that often needs extra configuration (runtime resource loading, resource bundles, service
  loading, reflective annotation access, static-initializer side effects, native access, native methods, JMX, foreign
  functions).
- **INFO** — an informational prompt that only matters if the type is actually used that way (serialization).

The scan evaluates every registered check, but the panel only lists checks that found something to review. Findings are
ordered by importance (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`), then by the number of occurrences, and include up to
a handful of sample detail lines.

---

## Reflection

### GRAAL-REFLECT-001 — Reflective API usage may need reflection metadata

- **Severity**: MEDIUM
- **Inspects**: calls to the reflection API (`Class.forName`, `Class.newInstance`, `Class` method/field/constructor
  lookups and declared variants, `Method.invoke`, `Constructor.newInstance`, and `Field` value get/set accessors).
  Reflective metadata accessors such as `Field.getName()` are intentionally ignored.
- **Fires when**: an application class uses those reflection APIs; constant targets may be resolved by native-image, but
  runtime-computed reflective targets need explicit metadata.
- **Recommendation**: register the reflectively accessed types in `reachability-metadata.json`, or for application code
  register them with Spring's RuntimeHints (e.g. via `@ImportRuntimeHints` / `RuntimeHintsRegistrar`). Spring AOT already
  covers Spring-managed beans.

### GRAAL-REFLECT-002 — Dynamic class loading may need reflection metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `ClassLoader.loadClass`.
- **Fires when**: an application class loads a class by name at run time, which native-image cannot resolve at build
  time.
- **Recommendation**: register the dynamically loaded types under `reflection` in `reachability-metadata.json`, or
  replace `ClassLoader.loadClass` with direct class literals where possible.

### GRAAL-REFLECT-003 — Deep reflection (setAccessible / private lookups) may need reflection metadata

- **Severity**: MEDIUM
- **Inspects**: `AccessibleObject.setAccessible` / `trySetAccessible` and `MethodHandles.privateLookupIn`.
- **Fires when**: a class uses deep reflection that bypasses access checks, which native-image must be told about to keep
  the members reachable.
- **Recommendation**: register the accessed members (with `allowWrite` where needed) under `reflection` in
  `reachability-metadata.json` and ensure the required module opens are configured; prefer public APIs over deep
  reflection.

### GRAAL-REFLECT-004 — Reflective annotation access may need reflection metadata

- **Severity**: LOW
- **Inspects**: reflective annotation queries (`getAnnotation`, `getDeclaredAnnotations`, `isAnnotationPresent`, …) on
  reflected members (`Method`, `Field`, `Constructor`, `Parameter`). Reads on `java.lang.Class` and other
  `AnnotatedElement` subtypes (`Package`, `Module`, `RecordComponent`) are intentionally ignored — only calls whose
  receiver is exactly one of those four member types are flagged.
- **Fires when**: a class reads annotations from a reflected member whose annotations native-image only retains when the
  element is registered for reflection.
- **Recommendation**: register the inspected members under `reflection` in `reachability-metadata.json` so their
  annotations are available at run time.

### GRAAL-REFLECT-005 — Unsafe.allocateInstance bypasses construction and needs unsafeAllocated metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `allocateInstance(Class)` on `sun.misc.Unsafe` or `jdk.internal.misc.Unsafe`.
- **Fires when**: a class allocates an instance via `Unsafe` instead of a constructor. Unsafe allocation bypasses the
  construction path native-image's reachability analysis tracks, so the allocated type needs its own metadata; otherwise
  the call throws `MissingReflectionRegistrationError` at run time.
- **Recommendation**: register the allocated type under `reflection` in `reachability-metadata.json` with
  `"unsafeAllocated": true` (in addition to its normal type registration), or replace `Unsafe.allocateInstance` with a
  public constructor or factory method where possible.

## Dynamic proxies

### GRAAL-PROXY-001 — Dynamic JDK proxies may need proxy metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `Proxy.newProxyInstance` and `Proxy.getProxyClass`.
- **Fires when**: a class creates or obtains a JDK dynamic proxy whose interface list must be known to native-image.
  When the interface array is a compile-time constant, native-image may auto-register the proxy (similar to how
  constant-arg `Class.forName` is auto-folded); runtime-computed interface sets always need explicit registration.
- **Recommendation**: declare the proxied interfaces in `reachability-metadata.json`, or for application code register
  them with Spring's RuntimeHints (`RuntimeHints.proxies().registerJdkProxy(...)` via `@ImportRuntimeHints`). Spring's own
  proxy mechanisms are covered by Spring AOT.

## Resources

### GRAAL-RES-001 — Runtime resource loading may need resource metadata

- **Severity**: LOW
- **Inspects**: calls to `Class`/`ClassLoader` `getResource` and `getResourceAsStream`.
- **Fires when**: a class loads a resource by name that must be embedded in the native image to be available at runtime.
  Calls with a constant resource name are often already detected automatically by native-image; runtime-computed names
  always need registration.
- **Recommendation**: register the loaded resource paths (as globs) in `reachability-metadata.json`, or for application
  code register them with Spring's RuntimeHints (`RuntimeHints.resources()` via `@ImportRuntimeHints`) so native-image
  bundles them.

### GRAAL-RES-002 — Resource bundle loading may need resource-bundle metadata

- **Severity**: LOW
- **Inspects**: calls to `ResourceBundle.getBundle`.
- **Fires when**: a class loads a localized resource bundle whose `.properties` files must be embedded in the native
  image.
- **Recommendation**: register the bundle base names under `bundles` in `reachability-metadata.json` so native-image
  includes every locale variant.

## Service loading

### GRAAL-SERVICE-001 — Service loading may need service metadata

- **Severity**: LOW
- **Inspects**: calls to `ServiceLoader.load` / `loadInstalled`.
- **Fires when**: a class discovers providers through `META-INF/services`, which native-image must reach and reflectively
  instantiate.
- **Recommendation**: ensure the `META-INF/services` provider files are on the classpath and register the provider
  implementations under `reflection` in `reachability-metadata.json`.

## Serialization

### GRAAL-SER-001 — Serializable types may need serialization metadata

- **Severity**: INFO
- **Inspects**: application classes that implement `java.io.Serializable` (concrete, non-enum types).
- **Fires when**: an application type implements `Serializable`; types that are actually serialized at runtime require
  serialization metadata. If GRAAL-SER-002 (active JDK serialization) also fires, the listed types are likely
  serialized at runtime and should be reviewed carefully. Enum types are excluded because GraalVM handles standard enum
  serialization automatically.
- **Recommendation**: if these types are serialized (e.g. via the JDK serialization protocol), register them under
  `serialization` in `reachability-metadata.json`.

### GRAAL-SER-002 — Active JDK serialization may need serialization metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `ObjectOutputStream.writeObject` / `writeUnshared` and `ObjectInputStream.readObject` /
  `readUnshared`.
- **Fires when**: a class serializes or deserializes types through the JDK serialization protocol at run time, which
  native-image must be told about explicitly.
- **Recommendation**: register every serialized type under `reflection` with `serializable: true` in GraalVM 25's
  unified `reachability-metadata.json` schema (or with Spring's RuntimeHints serialization registration), or prefer a
  serialization format that does not need build-time registration.

## Build-time initialization

### GRAAL-INIT-001 — Build-time-initialized classes must not perform static I/O or start threads

- **Severity**: LOW
- **Inspects**: static initializers that directly perform file I/O (`java.io` file streams or filesystem-touching
  `java.nio.file.Files` calls) or start threads / processes (`Thread.start`, `Runtime.exec`, `ProcessBuilder.start`).
  Lightweight `Files` metadata predicates such as `exists`, `isDirectory`, and `isReadable` are intentionally ignored;
  side effects in helper methods or lambdas are out of scope.
- **Fires when**: a class runs I/O or starts a thread/process from its static initializer. This is a review prompt only:
  since GraalVM 21.3+ classes are run-time-initialized by default, it matters only when the class is explicitly
  initialized at build time via the
  native-image `--initialize-at-build-time` flag. Spring AOT is one way to arrive at that configuration (it can compute
  and pass the flag for Spring-managed classes on the application's behalf), but the flag itself — not Spring AOT — is
  the actual mechanism native-image reads, so this also applies to build-time initialization configured directly or by
  other means.
- **Recommendation**: if the class is listed under `--initialize-at-build-time`, move the side effect out of the static
  initializer or switch the class to `--initialize-at-run-time` so the I/O or thread starts when the application runs
  rather than during the native build.

### GRAAL-INIT-002 — Build-time-initialized classes must not capture build-machine state

- **Severity**: LOW
- **Inspects**: static initializers that read environment- or time-sensitive state (`System.getenv` / `getProperty` /
  `getProperties`, current time, `java.time` `now()`, default `Locale` / `TimeZone`, `InetAddress`, `Random` /
  `SecureRandom` constructors or `next*` calls, `UUID.randomUUID`).
- **Fires when**: a class captures those values in a static initializer. This is a review prompt only: since GraalVM
  21.3+ classes are run-time-initialized by default, it matters only when the class is explicitly initialized at build
  time via the
  native-image `--initialize-at-build-time` flag. Spring AOT is one way to arrive at that configuration (it can compute
  and pass the flag for Spring-managed classes on the application's behalf), but the flag itself — not Spring AOT — is
  the actual mechanism native-image reads, so this also applies to build-time initialization configured directly or by
  other means; GraalVM's simulated class-init safely refuses unsafe initializers in ambiguous cases.
- **Recommendation**: if the class is listed under `--initialize-at-build-time`, move the state capture into a runtime
  code path or switch the class to `--initialize-at-run-time` so the values are read when the native image starts
  rather than baked in during the build.

## Native access

### GRAAL-NATIVE-001 — Native libraries or unsupported Unsafe operations need native-image review

- **Severity**: LOW
- **Inspects**: loading of native libraries (`System.loadLibrary`, `System.load`, `Runtime.loadLibrary`, `Runtime.load`)
  and unsupported `Unsafe.defineClass` / `defineAnonymousClass` operations.
- **Fires when**: a class loads a native library or attempts runtime class definition through `Unsafe`. Ordinary
  `Unsafe` memory access is intentionally not flagged: GraalVM 25 supports common Unsafe field/memory patterns and
  rewrites static-final field offsets where possible. `Unsafe.allocateInstance` has its own precise
  GRAAL-REFLECT-005 check.
- **Recommendation**: confirm loaded native libraries are available to the native image and add JNI configuration when
  needed; replace Unsafe runtime class definition with build-time generation.

### GRAAL-NATIVE-002 — Native method declarations may need JNI configuration

- **Severity**: LOW
- **Inspects**: application classes that declare `native` methods.
- **Fires when**: a class declares a `native` method whose JNI entry point and backing native library must be configured
  for the native image.
- **Recommendation**: in GraalVM 25's unified schema, register the declaring type under `reflection` with
  `jniAccessible: true` and the required method descriptors, then ensure the native library is bundled with and loadable
  by the native image. BootUI seeds this declaring-type registration.

### GRAAL-FFM-001 — Foreign Function downcalls/upcalls may need foreign metadata in native images

- **Severity**: LOW
- **Inspects**: application classes that depend on `java.lang.foreign.Linker`.
- **Fires when**: a class uses `Linker` to build native downcall handles or upcall stubs; those down/upcalls reach native
  symbols that the closed-world analysis cannot see and must be described under `foreign` in `reachability-metadata.json`.
  Pure heap/off-heap `MemorySegment` or `Arena` usage that never touches `Linker` does not require this metadata and is
  not flagged.
- **Recommendation**: register the real native down/upcall descriptors under `foreign` in
  `reachability-metadata.json`, and pass `--enable-native-access=<module-name>` (or `ALL-UNNAMED` for classpath code) for
  modules that perform restricted native operations. FFM support is enabled by default starting with GraalVM 25, but
  metadata and native-access permission solve separate problems. BootUI emits empty `foreign` arrays as a safe scaffold;
  it does not invent function layouts.

## Class generation

### GRAAL-CLASSGEN-001 — Runtime class generation has no general-purpose support in native images

- **Severity**: HIGH
- **Inspects**: runtime bytecode/class generation (`ClassLoader.defineClass`, `MethodHandles.Lookup.defineClass` /
  `defineHiddenClass` / `defineHiddenClassWithClassData`, CGLIB `Enhancer`, ByteBuddy, Javassist).
- **Fires when**: a class generates or defines classes at run time. A closed-world native image has no compiler at run
  time, so these calls are not supported for arbitrary, build-time-unknown bytecode. The native-image agent's
  experimental ["Predefined Classes"](https://www.graalvm.org/latest/reference-manual/native-image/metadata/ExperimentalAgentOptions/)
  mode (`experimental-class-define-support`) can trace and replay a bounded set of previously-seen classes, but it is
  best-effort: it replays only the exact bytecode traced ahead of time, allows only one class definition per class
  loader per execution, has no build-time-initialization support, and cannot help when classes are generated with
  varying names or bytecode (e.g. driven by counters or timestamps) — so it is a narrow escape hatch, not a general fix.
- **Recommendation**: generate the classes at build time (e.g. via Spring AOT / build-time processing) instead of at run
  time, or replace the dynamically generated types with statically compiled equivalents. If runtime class generation
  truly cannot be avoided, evaluate the native-image agent's experimental Predefined Classes support as a narrow
  fallback — but note its known limitations before relying on it.

### GRAAL-JDK-001 — The system Java compiler is unavailable in native images

- **Severity**: HIGH
- **Inspects**: calls to `javax.tools.ToolProvider.getSystemJavaCompiler()`.
- **Fires when**: application code requests `javac` at run time. A native executable contains application code compiled
  ahead of time and cannot load newly compiled Java classes into the closed world.
- **Recommendation**: compile or generate code during the application build and include the resulting classes in the
  native image; do not compile Java source inside the running application.

### GRAAL-JDK-002 — JSR-223 script engines require native-image-specific support

- **Severity**: HIGH
- **Inspects**: construction of `javax.script.ScriptEngineManager`.
- **Fires when**: application code discovers JSR-223 engines at run time. The manager uses service loading, and engines
  commonly load or generate executable code dynamically. This is not a blanket claim that every engine is impossible:
  GraalVM languages can expose documented JSR-223 integrations, but that explicit language/runtime setup must be part of
  the image.
- **Recommendation**: remove runtime scripting, replace it with statically compiled application logic, or validate a
  specific engine's Native Image integration and register all service, resource, reflection, and native requirements.

## Classpath scanning

### GRAAL-SCAN-001 — Runtime classpath scanning does not work in native images

- **Severity**: HIGH
- **Inspects**: runtime classpath/component scanning (`ClassPathScanningCandidateComponentProvider.findCandidateComponents`,
  the Reflections library, or ClassGraph).
- **Fires when**: a class scans the classpath at run time; the closed-world native image has no scannable classpath at
  run time.
- **Recommendation**: resolve the scanning at build time. For Spring components rely on Spring AOT/component indexing
  rather than runtime scanning; replace library-based scanning with an explicit, statically known set of types.

## Spring AOT

`@ImportResource` is intentionally not a warning. Spring's AOT refresh invokes configuration parsing and
`BeanFactoryPostProcessor` implementations at build time, so statically declared imported bean definitions are part of
the generated context. Runtime-selected or mutable XML is subject to the same fixed-build-input constraint as other bean
definitions, but the annotation alone is not a high-confidence readiness problem.

### SPRING-AOT-001 — Runtime bean singleton registration is not captured by Spring AOT

- **Severity**: MEDIUM
- **Inspects**: calls to `SingletonBeanRegistry.registerSingleton(...)`.
- **Fires when**: a class adds beans to the context at run time; Spring AOT processes the bean factory at build time, so
  dynamically registered singletons are invisible to the AOT-generated context and native-image. Note: the singleton
  instance itself is present at runtime; the risk is only for reflective bean construction and AOT-generated context
  completeness.
- **Recommendation**: register the bean through standard build-time configuration (`@Bean` / `@Component` /
  `BeanFactoryInitializationAotContribution`) so Spring AOT can see it. For programmatic AOT-aware registration,
  consider Spring Framework 7's `BeanRegistrar` API.

### SPRING-AOT-002 — Programmatic instance suppliers are not captured by Spring AOT

- **Severity**: HIGH
- **Inspects**: bean definitions backed by a programmatic instance supplier (`setInstanceSupplier`, or Spring
  `registerBean` / `BeanDefinitionBuilder` methods with a `Supplier`).
- **Fires when**: a class registers a bean definition whose instance comes from a supplier lambda; Spring AOT cannot
  trace through that supplier at build time, so the bean's type and dependencies may be missing from the native image.
- **Recommendation**: prefer declarative bean definitions (`@Bean` methods / component scanning) whose types Spring AOT
  can resolve, or use Spring Framework 7's `BeanRegistrar` / `BeanRegistrarDsl` for AOT-friendly programmatic
  registration; alternatively provide a `RuntimeHintsRegistrar` that registers the supplied type for reflection.
- **Exclusion**: Spring AOT-generated `*__BeanDefinitions` classes intentionally use instance suppliers while replaying
  generated bean definitions. They are output of AOT processing, not unsupported application registration, and are
  excluded.

### SPRING-AOT-003 — Environment-sensitive bean conditions freeze selection at AOT build time

- **Severity**: MEDIUM
- **Inspects**: `@Profile`, `@ConditionalOnProperty`, custom `@Conditional`, and `@ConditionalOnExpression` on
  application `@Configuration` / `@Component` (and stereotype) classes or `@Bean` methods.
- **Fires when**: a Spring component or `@Bean` method carries a profile or property condition. Spring AOT evaluates
  these conditions once at build time; if the active profiles or application properties differ between the AOT build and
  the production runtime, the conditioned beans may be unexpectedly absent or present in the native image.
- **Stronger expression treatment**: a `@ConditionalOnExpression` that contains an explicit SpEL bean reference
  (`@beanName`) is HIGH. Spring Boot documents that such a reference initializes that bean very early, before normal
  post-processing such as configuration-properties binding, in addition to the general AOT build-time freeze.
  Property-only expressions retain MEDIUM severity.
- **Exclusion**: deliberate `@AutoConfiguration` classes are condition-driven by design and are handled by Spring's AOT
  processing, so they are not reported. Build-time classpath conditions such as `@ConditionalOnClass` are not reported
  merely for being normal auto-configuration patterns.
- **Recommendation**: ensure the profiles and properties active during the AOT build (native-image compilation) match
  the intended production configuration, or restructure the configuration to use explicit build-time selection rather
  than runtime conditions. Replace bean-referencing expression conditions with property/class conditions where possible.

### SPRING-AOT-004 — Runtime ApplicationContext creation outside main entry point is not AOT-processed

- **Severity**: HIGH
- **Inspects**: constructor calls to `AnnotationConfigApplicationContext` or `GenericApplicationContext`, and
  `SpringApplicationBuilder.child(...)` calls.
- **Fires when**: a class programmatically creates a secondary application context; such contexts are never processed by
  Spring AOT, so their beans, runtime hints, and configuration are absent from the native image.
- **Recommendation**: consolidate configuration into the main application context processed by Spring AOT, or use
  `@Import` / `@ImportResource` to include additional configuration statically at build time.

### GRAAL-SPEL-001 — Programmatic SpEL expression parsing relies on reflection with no AOT visibility

- **Severity**: MEDIUM
- **Category**: this check moved from `Reflection` to `Spring AOT` — SpEL reachability is a Spring-library-specific
  concern (the SpEL bytecode compiler and reflective property access are part of Spring's own AOT story), not a
  general-purpose reflection construct. The check ID is unchanged (`GRAAL-SPEL-001`, not renumbered into the
  `SPRING-AOT-*` sequence) because check IDs are stable identifiers persisted in user dismissals.
- **Inspects**: calls to `ExpressionParser.parseExpression` and `parseRaw` (the SpEL programmatic parsing API).
- **Fires when**: a class parses a SpEL expression at run time; the parsed expression uses reflection to access object
  properties that is not visible to native-image, and the SpEL bytecode compiler is unsupported in native images.
- **Recommendation**: replace programmatic SpEL with direct Java code or annotation-driven evaluation (`@PreAuthorize`,
  `@Value`, `@Cacheable`) that Spring AOT processes statically. If programmatic SpEL is required, register all
  reflectively accessed types under `reflection` in `reachability-metadata.json`.

## Method handles

### GRAAL-MH-001 — Non-constant MethodHandle lookups may need reflection metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `MethodHandles.Lookup` lookup methods: `findVirtual`, `findStatic`, `findConstructor`,
  `findSpecial`, `findGetter`/`findSetter` variants, `unreflect` and `unreflect*` variants, and `findVarHandle` /
  `findStaticVarHandle`.
- **Fires when**: a class performs a `MethodHandles.Lookup` lookup; non-constant method handles require reflection
  metadata for the target members that is not visible to the existing REFLECT checks. For compile-time-constant
  handles, native-image may fold the lookup automatically.
- **Recommendation**: register the target members under `reflection` in `reachability-metadata.json` so native-image
  retains the necessary member descriptors.

## Security providers

### GRAAL-SEC-001 — Custom security providers may not initialize correctly in native images

- **Severity**: MEDIUM
- **Inspects**: calls to `Security.addProvider` / `Security.insertProviderAt` and application classes that extend
  `java.security.Provider`.
- **Fires when**: a class registers a custom or third-party security provider (e.g. BouncyCastle) or extends
  `Provider` directly; such providers rely on reflection-based service registration that is invisible to native-image.
- **Recommendation**: register the provider and all its service implementations under `reflection` in
  `reachability-metadata.json`. Many providers (BouncyCastle, Conscrypt) publish a native-image integration guide or
  companion module.

## JMX

### GRAAL-JMX-001 — JMX usage requires --enable-monitoring in the native image

- **Severity**: LOW
- **Inspects**: calls to `ManagementFactory.getPlatformMBeanServer` and `MBeanServer.registerMBean`.
- **Fires when**: a class uses JMX; JMX is disabled by default in native images and requires `--enable-monitoring=jmxserver`
  plus MBean reflection metadata.
- **Recommendation**: add `--enable-monitoring=jmxserver` to the native-image build arguments and register all MBean
  interfaces and implementations under `reflection` in `reachability-metadata.json`.

### GRAAL-JMX-002 — Dynamic/model MBeans are not supported by native-image JMX

- **Severity**: HIGH
- **Inspects**: application classes assignable to `javax.management.DynamicMBean` (this also covers Model MBeans, since
  `ModelMBean` extends `DynamicMBean`), other than classes based on the JDK's `StandardMBean` wrapper (`StandardMBean`
  itself implements `DynamicMBean`, so a naive assignability check would otherwise misflag the JDK's own supported
  "standard MBean via subclassing" pattern).
- **Fires when**: a class implements or extends a dynamic/model MBean type. GraalVM's native-image JMX support only
  covers MXBeans and standard (interface-naming-convention) MBeans; dynamic and model MBeans define their management
  interface at run time, which is unsupported because there is no metadata registration that makes a dynamic or model
  MBean work in a native image.
- **Recommendation**: replace the dynamic/model MBean with a standard MBean (a `FooMBean` interface plus a `Foo`
  implementation, or `javax.management.StandardMBean` composition/subclassing) or an MXBean; both work with
  `--enable-monitoring=jmxserver`.
