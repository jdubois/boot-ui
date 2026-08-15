# GraalVM readiness checks

The GraalVM panel surveys the host application for [GraalVM native-image](https://www.graalvm.org/latest/reference-manual/native-image/)
readiness and can generate a `reachability-metadata.json` scaffold from the scan. The active catalogue contains **27
checks: 22 GraalVM checks and 5 Spring AOT checks**. This page lists every active check, what it inspects, when it fires,
and what to do about it.

Each check is a small class registered in
[`GraalVmCheckRegistry`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/graalvm/GraalVmCheckRegistry.java)
and implemented in
[`GraalVmChecks.java`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/graalvm/GraalVmChecks.java).
The list intentionally stays compact and reviewable; adding a new check means adding one focused class plus a registry
entry. The rule engine (checks, categories, the dependency scanner, and the reachability-metadata scaffold generator) is
framework-neutral and lives in `bootui-engine`; today it is surfaced only through thin Spring adapter wiring in
`bootui-spring-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/graalvm/` (the controller, the
Dockerfile generator, and the source-tree writer).

The advisor is explicitly **not applicable on Quarkus**. Quarkus performs native-image configuration during its own
build-time augmentation, so BootUI keeps the panel unavailable with a platform-specific explanation instead of exposing
this Spring-oriented scan or its generated files.

## 2026 readiness audit

The catalogue was audited check by check against current GraalVM Native Image documentation and source, Spring
Framework 7 / Spring Boot 4.1 AOT documentation, the GraalVM tracing agent and reachability-metadata repository, and the
build-time approaches used by Spring AOT, Quarkus, and Micronaut. The audit deliberately changed a check only when the
application bytecode or classpath gives BootUI a reliable signal.

| Audited ID | Decision | Result |
| --- | --- | --- |
| `GRAAL-REFLECT-001` | MODIFY | Cover the current `Class` reflection lookup surface (`arrayType`, record, sealed, nest, signer, and nested-class lookups). |
| `GRAAL-REFLECT-002` | MODIFY | Retain dynamic class loading, but acknowledge constant resolution and experimental run-time class loading. |
| `GRAAL-REFLECT-003` | MODIFY | Remove legacy `allowWrite` advice, which is not part of the unified schema. |
| `GRAAL-REFLECT-004` | KEEP | Member-annotation access remains a precise reflection-metadata signal. |
| `GRAAL-REFLECT-005` | KEEP | `Unsafe.allocateInstance` still has an explicit `unsafeAllocated` metadata requirement. |
| `GRAAL-PROXY-001` | MODIFY | Keep the call-level signal and document the unified structured proxy type. |
| `GRAAL-RES-001` | MODIFY | Cover `ClassLoader.getResources/resources` and `Module.getResourceAsStream`; document `resource:` URL semantics. |
| `GRAAL-RES-002` | MODIFY | Correct bundle guidance to `resources[].bundle` in the unified schema. |
| `GRAAL-SERVICE-001` | REMOVE | Native Image's enabled-by-default `ServiceLoaderFeature` registers reachable service files and providers; a blanket call-site warning is no longer actionable. |
| `GRAAL-SER-001` | MODIFY | Keep the low-noise INFO inventory and use `reflection[].serializable` guidance. |
| `GRAAL-SER-002` | MODIFY | Keep active serialization calls and use the current unified schema. |
| `GRAAL-INIT-001` | REMOVE | Native Image will not automatically build-time-initialize classes with unsafe side effects; the remaining risk depends on an explicit build flag BootUI cannot observe. |
| `GRAAL-INIT-002` | REMOVE | Build-machine state is risky only when a class is forced to build-time initialization, which application bytecode does not reveal. |
| `GRAAL-NATIVE-001` | MODIFY | Limit this check to native-library loading; move Unsafe class-definition calls to class generation. |
| `GRAAL-NATIVE-002` | MODIFY | Native Image creates Java-to-native wrappers for reachable `native` declarations automatically; stop inventing JNI metadata for them and explain the native-to-Java callback boundary. |
| `GRAAL-CLASSGEN-001` | MODIFY | Account for experimental `-H:+RuntimeClassLoading` and Predefined Classes without presenting either as a transparent compatibility guarantee. |
| `GRAAL-JDK-001` | KEEP | Runtime `javac` lookup remains incompatible with the normal closed-world executable. |
| `GRAAL-JDK-002` | MODIFY | Service discovery is handled automatically, but each script engine's dynamic execution still needs a validated native integration. |
| `GRAAL-SCAN-001` | KEEP | Runtime classpath scanning remains an explicit Spring Framework AOT limitation. |
| `SPRING-AOT-001` | MODIFY | Use Spring's exact singleton-transformation boundary and recommend bean-definition registration APIs. |
| `SPRING-AOT-002` | MODIFY | Exclude classes marked `org.springframework.aot.generate.Generated` and pin Spring 7 `BeanRegistrar` suppliers as supported. |
| `SPRING-AOT-003` | SPLIT | Keep environment-sensitive conditions at MEDIUM; move bean-referencing expressions to unique `SPRING-AOT-005` at HIGH. Add `@ConditionalOnBooleanProperty` and stop flagging classpath-only Boot conditions. |
| `SPRING-AOT-004` | MODIFY | Programmatic contexts need review, but `GenericApplicationContext.refreshForAotProcessing` disproves the old absolute wording; exclude generated AOT code. |
| `GRAAL-SPEL-001` | MODIFY | Keep programmatic parsing and clarify that runtime property access can require reflection metadata. |
| `GRAAL-MH-001` | MODIFY | Add the omitted `MethodHandles.Lookup.findClass` lookup. |
| `GRAAL-SEC-001` | MODIFY | Flag observable `Security.addProvider/insertProviderAt` calls, not an unused `Provider` subclass. |
| `GRAAL-JMX-001` | MODIFY | Document experimental monitoring and the required structured proxy metadata for standard MBean interfaces. |
| `GRAAL-JMX-002` | KEEP | Dynamic/model MBeans remain explicitly unsupported; the `StandardMBean` exclusion is correct. |
| `GRAAL-FFM-001` | MODIFY | Flag actual `Linker.downcallHandle/upcallStub` calls instead of a passive `Linker` type reference. |

Primary references:

- [GraalVM Reachability Metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/), including
  reflection, resources, serialization, JNI, FFM, and the unified schema.
- [GraalVM class initialization](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/ClassInitialization/)
  and the [current run-time class-loading source documentation](https://github.com/oracle/graal/blob/master/substratevm/docs/runtime-class-loading.md).
- GraalVM's enabled-by-default
  [`ServiceLoaderFeature`](https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/ServiceLoaderFeature.java).
- [Spring Framework AOT](https://docs.spring.io/spring-framework/reference/core/aot.html) and Spring Boot's
  [static-hints location](https://docs.spring.io/spring-boot/reference/packaging/native-image/advanced-topics.html#packaging.native-image.advanced.custom-hints.static).

The removed initialization checks remain useful review topics when a build explicitly uses
`--initialize-at-build-time`, but they are documentation concerns rather than reliable bytecode findings. BootUI also
cannot recover string argument values or data flow, proxy interface arrays, Unsafe target classes, JNI calls made inside
native code, FFM memory layouts, native-image build flags, or the exact profiles/properties used during Spring AOT.

## What BootUI does

The scanner detects the host application's base package(s) from the `@SpringBootApplication` configuration via
`AutoConfigurationPackages`, imports the compiled `.class` files from those packages with [ArchUnit](https://www.archunit.org/)'s
`ClassFileImporter`, and evaluates every registered check against the imported classes. Importing is bounded to the
application's own base package(s) — never the entire classpath — and runs only on demand when the scan action is
invoked, caching the last report in the controller.

In addition to the checks, the scan does two things:

- **Surveys classpath dependencies** (when the _Include dependencies_ toggle is on; it is on by default) to report which
  third-party JARs already ship bundled reachability metadata under `META-INF/native-image/`. BootUI recognizes the
  unified `reachability-metadata.json` filename and canonical legacy files (`reflect-config.json`,
  `resource-config.json`, `proxy-config.json`, `serialization-config.json`, `jni-config.json`, and
  `predefined-classes-config.json`); arbitrary JSON does not count. A JAR that only has `native-image.properties` is
  reported as bundling native-image build arguments, not reachability metadata. The survey opens only classpath JARs,
  stops after 500 JARs,
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
  Repository coverage uses an exact metadata/tested-version match first, then the first valid Java regular expression in
  the repository row's `default-for` field.
- **Builds a GraalVM `reachability-metadata.json` scaffold** from the application's own classes — unified reflection and
  serialization registrations, standard configuration/logging resource globs, and explicit proxy/Unsafe/FFM
  completion guidance when those calls are detected — which you can download from the panel.
- **Installs the scaffold into the source tree** when the application is detectably running from an exploded build (for
  example `mvn spring-boot:run` or an IDE) rather than a packaged jar. The **Write into project** action
  writes the scaffold to
  `src/main/resources/META-INF/native-image/<groupId>/<artifactId>-additional-hints/reachability-metadata.json`
  (coordinates resolved from `build-info.properties` or the project `pom.xml`, falling back to
  `bootui-generated/additional-hints`). Spring Boot reserves `<groupId>/<artifactId>/` for AOT-generated output, so the
  suffix prevents BootUI's static scaffold from colliding with Spring AOT. The write is confined under
  `src/main/resources` and refuses to overwrite a `reachability-metadata.json` that BootUI did not generate.
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
Serialization is represented by `serializable: true` on a reflection registration (not a top-level `serialization`
array). BootUI does **not** mark Java `native` declarations `jniAccessible`: those declarations receive their
Java-to-native wrappers automatically, while the native-to-Java callbacks that do need JNI metadata are not observable
in Java bytecode. Each named type carries a `condition.typeReached` guard. Reflection candidates include concrete records
and `Serializable` types plus JPA entities and mapped superclasses, including abstract persistence base types. Resource
globs cover
`application*.properties` / `application*.yml` / `application*.yaml`, `logback-spring.xml`, and `log4j2-spring.xml`.

Static bytecode analysis cannot reliably recover runtime-computed proxy interface arrays, the `Class` argument passed to
`Unsafe.allocateInstance`, or FFM `FunctionDescriptor` layouts. When those checks fire, the generated file therefore
adds explicit review instructions rather than inventing unsafe registrations. FFM findings also scaffold the schema-valid
`foreign` object with empty `downcalls`, `upcalls`, and `directUpcalls` arrays for the developer or tracing agent to
complete. Dynamic proxies use a structured reflection type such as
`{"type":{"proxy":["com.example.Interface"]}}`; FFM entries require the real memory-layout descriptors.

Review the generated file with the tracing agent, then place it under
`src/main/resources/META-INF/native-image/<groupId>/<artifactId>-additional-hints/` in your application. Spring Boot
writes generated AOT hints to `<groupId>/<artifactId>/`, so static hints must use a non-clashing directory. The panel
substitutes the
resolved `groupId`/`artifactId` into that hint whenever it can determine them — from `build-info.properties` (which works
even when running from a packaged jar) or the project `pom.xml` — and keeps the `<groupId>`/`<artifactId>` placeholders
only when no coordinates can be resolved.

## Severity scale

Severity reflects the worst plausible impact if the finding is real, not the likelihood:

- **CRITICAL** — a construct with the most severe native-image impact if the finding is real. No active GraalVM check
  currently emits this severity.
- **HIGH** — a construct that needs substantial native-image integration or Spring AOT cannot safely capture at run time
  (runtime class generation or Java compilation, script-engine discovery without a native integration, runtime classpath scanning,
  runtime instance suppliers, bean-referencing expression conditions, secondary context creation, dynamic/model MBeans).
- **MEDIUM** — a construct GraalVM cannot resolve at build time that will usually fail at run time without metadata
  (reflection, dynamic class loading, deep reflection, unsafe allocation, dynamic proxies, active JDK serialization, SpEL,
  method handles, frozen AOT conditions, runtime security-provider registration, runtime singleton registration).
- **LOW** — a construct that often needs extra configuration (runtime resource loading, resource bundles, reflective
  annotation access, native access, native methods, JMX, foreign functions).
- **INFO** — an informational prompt that only matters if the type is actually used that way (serialization).

The scan evaluates every registered check, but the panel only lists checks that found something to review. Findings are
ordered by importance (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`), then by the number of occurrences, and include up to
a handful of sample detail lines.

---

## Reflection

### GRAAL-REFLECT-001 — Reflective API usage may need reflection metadata

- **Severity**: MEDIUM
- **Inspects**: calls to the reflection API (`Class.forName`, `Class.newInstance`, `Class.arrayType`, method/field/
  constructor lookups, record/sealed/nest/signer/nested-class lookups, `Method.invoke`, `Constructor.newInstance`, and
  `Field` value get/set accessors).
  Reflective metadata accessors such as `Field.getName()` are intentionally ignored.
- **Fires when**: an application class uses those reflection APIs; constant targets may be resolved by native-image, but
  runtime-computed reflective targets need explicit metadata.
- **Recommendation**: register the reflectively accessed types in `reachability-metadata.json`, or for application code
  register them with Spring's RuntimeHints (e.g. via `@ImportRuntimeHints` / `RuntimeHintsRegistrar`). Spring AOT already
  covers Spring-managed beans.

### GRAAL-REFLECT-002 — Dynamic class loading may need reflection metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `ClassLoader.loadClass`.
- **Fires when**: an application class loads a class by name at run time. Native Image can resolve some constant calls;
  runtime-computed names need metadata or experimental run-time class loading.
- **Recommendation**: register the dynamically loaded types under `reflection` in `reachability-metadata.json`, or
  replace `ClassLoader.loadClass` with direct class literals where possible.

### GRAAL-REFLECT-003 — Deep reflection (setAccessible / private lookups) may need reflection metadata

- **Severity**: MEDIUM
- **Inspects**: `AccessibleObject.setAccessible` / `trySetAccessible` and `MethodHandles.privateLookupIn`.
- **Fires when**: a class uses deep reflection that bypasses access checks, which native-image must be told about to keep
  the members reachable.
- **Recommendation**: register the accessed members under `reflection` in `reachability-metadata.json` and ensure the
  required module opens are configured; prefer public APIs over deep
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
- **Inspects**: calls to `Class`/`ClassLoader` `getResource`, `getResources`/`resources`, and `getResourceAsStream`, plus
  `Module.getResourceAsStream`.
- **Fires when**: a class loads a resource by name that must be embedded in the native image to be available at runtime.
  Native Image automatically registers `Class.getResource/getResourceAsStream` only when both the receiver class and
  resource name are constant; runtime-computed names need registration.
- **Recommendation**: register the loaded resource paths (as globs) in `reachability-metadata.json`, or for application
  code register them with Spring's RuntimeHints (`RuntimeHints.resources()` via `@ImportRuntimeHints`) so native-image
  bundles them. Embedded resources use `resource:` URLs; open a stream instead of treating `URL.getFile()` as a
  filesystem path.

### GRAAL-RES-002 — Resource bundle loading may need resource-bundle metadata

- **Severity**: LOW
- **Inspects**: calls to `ResourceBundle.getBundle`.
- **Fires when**: a class loads a localized resource bundle whose `.properties` files must be embedded in the native
  image.
- **Recommendation**: add each bundle base name as a `resources` entry with a `bundle` field in
  `reachability-metadata.json` so native-image includes every locale variant.

## Serialization

### GRAAL-SER-001 — Serializable types may need serialization metadata

- **Severity**: INFO
- **Inspects**: application classes that implement `java.io.Serializable` (concrete, non-enum types).
- **Fires when**: an application type implements `Serializable`; types that are actually serialized at runtime require
  serialization metadata. If GRAAL-SER-002 (active JDK serialization) also fires, the listed types are likely
  serialized at runtime and should be reviewed carefully. Enum types are excluded because GraalVM handles standard enum
  serialization automatically.
- **Recommendation**: if these types are serialized (e.g. via the JDK serialization protocol), add `reflection` entries
  with `serializable: true` in `reachability-metadata.json`.

### GRAAL-SER-002 — Active JDK serialization may need serialization metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `ObjectOutputStream.writeObject` / `writeUnshared` and `ObjectInputStream.readObject` /
  `readUnshared`.
- **Fires when**: a class serializes or deserializes types through the JDK serialization protocol at run time, which
  native-image must be told about explicitly.
- **Recommendation**: register every serialized type under `reflection` with `serializable: true` in GraalVM 25's
  unified `reachability-metadata.json` schema (or with Spring's RuntimeHints serialization registration), or prefer a
  serialization format that does not need build-time registration.

## Native access

### GRAAL-NATIVE-001 — Dynamically loaded native libraries need native-image review

- **Severity**: LOW
- **Inspects**: native-library loading (`System.loadLibrary`, `System.load`, `Runtime.loadLibrary`, `Runtime.load`).
- **Fires when**: a class loads a native library whose file and symbols must be available to the executable. Ordinary
  Unsafe memory access is intentionally not flagged; allocation and class definition are covered by their precise
  reflection and class-generation checks.
- **Recommendation**: link the library into the image or deploy it where the executable can load it. If the native code
  dynamically looks up Java callbacks, collect and register those targets with the tracing agent.

### GRAAL-NATIVE-002 — Native method declarations require a loadable native implementation

- **Severity**: LOW
- **Inspects**: application classes that declare `native` methods.
- **Fires when**: a class declares a `native` method. Native Image creates the Java-to-native wrapper for a reachable
  declaration automatically, but the backing implementation must still be linked or loadable. Dynamic calls in the
  opposite direction, from native code into Java, require JNI metadata that Java bytecode cannot identify.
- **Recommendation**: ensure the implementation is linked or loadable. Exercise native-to-Java callbacks under the
  tracing agent and add `jniAccessible: true` only to the actual Java targets. BootUI does not infer JNI entries from
  declarations.

### GRAAL-FFM-001 — Foreign Function downcalls/upcalls may need foreign metadata in native images

- **Severity**: LOW
- **Inspects**: calls to `java.lang.foreign.Linker.downcallHandle` and `Linker.upcallStub`.
- **Fires when**: a class builds native downcall handles or upcall stubs; those down/upcalls reach native
  symbols that the closed-world analysis cannot see and must be described under `foreign` in `reachability-metadata.json`.
  Pure heap/off-heap `MemorySegment`/`Arena` use, or a passive `Linker` field that never creates a call handle, does not
  require this metadata and is not flagged.
- **Recommendation**: register the real native down/upcall descriptors under `foreign` in
  `reachability-metadata.json`, and pass `--enable-native-access=<module-name>` (or `ALL-UNNAMED` for classpath code) for
  modules that perform restricted native operations. FFM support is enabled by default starting with GraalVM 25, but
  metadata and native-access permission solve separate problems. BootUI emits empty `foreign` arrays as a safe scaffold;
  it does not invent function layouts.

## Class generation

### GRAAL-CLASSGEN-001 — Runtime class generation needs experimental native-image support

- **Severity**: HIGH
- **Inspects**: runtime bytecode/class generation (`ClassLoader.defineClass`, `MethodHandles.Lookup.defineClass` /
  `defineHiddenClass` / `defineHiddenClassWithClassData`, Unsafe `defineClass` / `defineAnonymousClass`, CGLIB `Enhancer`,
  ByteBuddy, Javassist).
- **Fires when**: a class generates or defines classes at run time. Current GraalVM source includes experimental
  `-H:+RuntimeClassLoading` support (with optional JIT and reachability-preservation configuration). The native-image agent's
  experimental ["Predefined Classes"](https://www.graalvm.org/latest/reference-manual/native-image/metadata/ExperimentalAgentOptions/)
  mode (`experimental-class-define-support`) can trace and replay a bounded set of previously-seen classes, but it is
  best-effort: it replays only the exact bytecode traced ahead of time, allows only one class definition per class
  loader per execution, has no build-time-initialization support, and cannot help when classes are generated with
  varying names or bytecode (e.g. driven by counters or timestamps) — so it is a narrow escape hatch, not a general fix.
- **Recommendation**: generate classes at build time (e.g. with Spring AOT) or replace them with statically compiled
  equivalents. If generation cannot be avoided, validate the exact workload against `-H:+RuntimeClassLoading` and its
  `-H:Preserve` requirements, or evaluate Predefined Classes for bytecode that is stable across runs.

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
- **Fires when**: application code discovers JSR-223 engines at run time. Native Image handles reachable ServiceLoader
  providers automatically, but engines commonly load or generate executable code dynamically. This is not a blanket
  claim that every engine is impossible: GraalVM languages can expose documented JSR-223 integrations, but that explicit
  language/runtime setup must be part of the image.
- **Recommendation**: remove runtime scripting, replace it with statically compiled application logic, or validate a
  specific engine's Native Image integration and its resource, reflection, class-loading, and native requirements.

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

### SPRING-AOT-001 — Runtime bean singleton registration cannot be transformed by Spring AOT

- **Severity**: MEDIUM
- **Inspects**: calls to `SingletonBeanRegistry.registerSingleton(...)`.
- **Fires when**: a class registers a singleton instance directly. Spring AOT transforms bean definitions, not singleton
  instances registered with a BeanFactory, so the instance cannot contribute generated construction code or
  reachability hints. The singleton still exists at runtime.
- **Recommendation**: register a bean definition through `@Bean` / `@Component`, `BeanDefinitionRegistry`,
  `ImportBeanDefinitionRegistrar`, or Spring Framework 7's AOT-supported `BeanRegistrar`.

### SPRING-AOT-002 — Programmatic instance suppliers are not captured by Spring AOT

- **Severity**: HIGH
- **Inspects**: bean definitions backed by a programmatic instance supplier (`setInstanceSupplier`, or Spring
  `registerBean` / `BeanDefinitionBuilder` methods with a `Supplier`).
- **Fires when**: a class registers a bean definition whose instance comes from a supplier lambda; Spring AOT cannot
  trace through that supplier at build time, so the bean's type and dependencies may be missing from the native image.
- **Recommendation**: prefer declarative bean definitions (`@Bean` methods / component scanning) whose types Spring AOT
  can resolve, or use Spring Framework 7's `BeanRegistrar` / `BeanRegistrarDsl` for AOT-friendly programmatic
  registration; alternatively provide a `RuntimeHintsRegistrar` that registers the supplied type for reflection.
- **Exclusion**: Spring AOT-generated `*__BeanDefinitions` classes and classes annotated
  `org.springframework.aot.generate.Generated` intentionally use suppliers while replaying generated bean definitions.
  Spring 7 `BeanRegistrar` uses a `Consumer<BeanRegistry.Spec<?>>` whose nested supplier is explicitly AOT-supported and
  does not match this check.

### SPRING-AOT-003 — Environment-sensitive bean conditions freeze selection at AOT build time

- **Severity**: MEDIUM
- **Inspects**: `@Profile`, `@ConditionalOnProperty`, `@ConditionalOnBooleanProperty`, custom `@Conditional`, and
  property-only `@ConditionalOnExpression` on application `@Configuration` / `@Component` (and stereotype) classes or
  `@Bean` methods.
- **Fires when**: a Spring component or `@Bean` method carries a profile or property condition. Spring AOT evaluates
  these conditions once at build time; if the active profiles or application properties differ between the AOT build and
  the production runtime, the conditioned beans may be unexpectedly absent or present in the native image.
- **Exclusion**: deliberate `@AutoConfiguration` classes are condition-driven by design and are handled by Spring's AOT
  processing, so they are not reported. Classpath-only Spring Boot conditions such as `@ConditionalOnClass` are fixed by
  the build input and are not reported even on application configuration.
- **Recommendation**: ensure the profiles and properties active during the AOT build (native-image compilation) match
  the intended production configuration, or restructure the configuration to use explicit build-time selection rather
  than runtime conditions.

### SPRING-AOT-005 — Bean-referencing @ConditionalOnExpression can initialize beans too early

- **Severity**: HIGH
- **Inspects**: Spring components and `@Bean` methods whose `@ConditionalOnExpression` value contains an explicit SpEL
  bean reference (`@beanName`) outside quoted string literals.
- **Fires when**: evaluating the condition can initialize a referenced bean before normal post-processing such as
  configuration-properties binding. This has a distinct stable ID rather than changing the name and severity of
  `SPRING-AOT-003`.
- **Recommendation**: replace bean-referencing SpEL with property/class conditions. If the expression is unavoidable,
  keep it free of bean references and use build-time-stable inputs.

### SPRING-AOT-004 — Programmatic ApplicationContext creation requires AOT review

- **Severity**: HIGH
- **Inspects**: constructor calls to `AnnotationConfigApplicationContext` or `GenericApplicationContext`, and
  `SpringApplicationBuilder.child(...)` calls outside classes marked as Spring AOT-generated.
- **Fires when**: a class programmatically creates a context. Contexts created at application run time do not use the
  main context's generated initializer. This is a review rather than an absolute verdict:
  `GenericApplicationContext.refreshForAotProcessing` intentionally creates a context during build-time AOT processing.
- **Recommendation**: consolidate configuration into the main AOT-processed context or use `@Import` /
  `@ImportResource`. If this is build tooling, call `refreshForAotProcessing` and keep it out of runtime paths.

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
- **Inspects**: calls to `MethodHandles.Lookup` lookup methods: `findClass`, `findVirtual`, `findStatic`, `findConstructor`,
  `findSpecial`, `findGetter`/`findSetter` variants, `unreflect` and `unreflect*` variants, and `findVarHandle` /
  `findStaticVarHandle`.
- **Fires when**: a class performs a `MethodHandles.Lookup` lookup; non-constant method handles require reflection
  metadata for the target members that is not visible to the existing REFLECT checks. For compile-time-constant
  handles, native-image may fold the lookup automatically.
- **Recommendation**: register the target members under `reflection` in `reachability-metadata.json` so native-image
  retains the necessary member descriptors.

## Security providers

### GRAAL-SEC-001 — Runtime security-provider registration needs native-image review

- **Severity**: MEDIUM
- **Inspects**: calls to `Security.addProvider` / `Security.insertProviderAt`. A class that merely extends
  `java.security.Provider` is intentionally ignored.
- **Fires when**: code adds a provider at run time. Native Image automatically analyzes security services present at
  build time, but adding new providers at run time is restricted and may need provider-specific initialization and
  reachability support.
- **Recommendation**: prefer providers configured at image build time and follow the provider's Native Image integration
  guide. Review GraalVM's `--future-defaults=run-time-initialize-security-providers` migration behavior when applicable.

## JMX

### GRAAL-JMX-001 — JMX usage requires --enable-monitoring in the native image

- **Severity**: LOW
- **Inspects**: calls to `ManagementFactory.getPlatformMBeanServer` and `MBeanServer.registerMBean`.
- **Fires when**: a class uses JMX. Native-image JMX support is experimental and disabled by default; server, client, and
  JVM-statistics capabilities are enabled explicitly.
- **Recommendation**: add `--enable-monitoring=jmxserver` (and `jmxclient` / `jvmstat` when needed). Register each standard
  MBean interface as a structured reflection proxy type such as
  `{"type":{"proxy":["com.example.FooMBean"]}}`, plus any implementation members accessed reflectively.

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
