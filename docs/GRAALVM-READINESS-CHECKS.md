# GraalVM readiness checks

The GraalVM panel surveys the host application for [GraalVM native-image](https://www.graalvm.org/latest/reference-manual/native-image/)
readiness and can generate a `reachability-metadata.json` scaffold from the scan. This page lists every check that ships
with BootUI today, what it inspects, when it fires, and what to do about it.

Each check is a small class registered in
[`GraalVmCheckRegistry`](../bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/graalvm/GraalVmCheckRegistry.java)
and implemented in
[`GraalVmChecks.java`](../bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/graalvm/GraalVmChecks.java).
The list intentionally stays compact and reviewable; adding a new check means adding one focused class plus a registry
entry.

## What BootUI does

The scanner detects the host application's base package(s) from the `@SpringBootApplication` configuration via
`AutoConfigurationPackages`, imports the compiled `.class` files from those packages with [ArchUnit](https://www.archunit.org/)'s
`ClassFileImporter`, and evaluates every registered check against the imported classes. Importing is bounded to the
application's own base package(s) — never the entire classpath — and runs only on demand when the scan action is
invoked, caching the last report in the controller.

In addition to the checks, the scan does two things:

- **Surveys classpath dependencies** (when the _Include dependencies_ toggle is on; it is off by default) to report which
  third-party JARs already ship bundled reachability metadata under `META-INF/native-image/`. BootUI counts metadata only
  when a `.json` file exists under that directory; a JAR that only has `native-image.properties` is reported as bundling
  native-image build arguments, not reachability metadata. The survey opens only classpath JARs, stops after 500 JARs,
  and adds a warning when that cap is hit; libraries without bundled metadata may need your own configuration, repository
  metadata, or the tracing agent.
- **Builds a `reachability-metadata.json` scaffold** from the application's own classes — reflection and serialization
  candidates plus the standard externalized-configuration resource globs — which you can download from the panel.
- **Installs the scaffold into the source tree** when the application is detectably running from an exploded build (for
  example `mvn spring-boot:run` or an IDE) rather than a packaged jar. The **Write into project** action
  writes the scaffold to `src/main/resources/META-INF/native-image/<groupId>/<artifactId>/reachability-metadata.json`
  (coordinates resolved from `build-info.properties` or the project `pom.xml`, falling back to a `bootui-generated`
  namespace). The write is confined under `src/main/resources` and refuses to overwrite a `reachability-metadata.json`
  that BootUI did not generate.
- **Generates a tailored `Dockerfile-native`** for the host application — a multi-stage build that detects the project's
  build system (Maven or Gradle, with or without the wrapper) and compiles a GraalVM native image with the matching
  command (`./mvnw`/`mvn -Pnative -DskipTests clean package`, or `./gradlew`/`gradle nativeCompile`), then packages the
  resulting executable (named after the resolved `artifactId`) into a minimal Debian runtime image with a non-root user
  and an Actuator health check. When the project carries no wrapper, the build stage installs a pinned Maven/Gradle
  release so the image is self-contained. You can download it, or — under the same exploded-build constraint as the
  scaffold install — write it to the project root. That write is fail-closed and refuses to overwrite a
  `Dockerfile-native` that BootUI did not generate.

When BootUI is installed through `bootui-spring-boot-starter`, ArchUnit is included transitively so the panel works
without an extra application dependency. The panel is available only when ArchUnit is on the classpath and a base
package is resolvable from the running application. If no classes can be imported, the panel degrades to a stable, empty
report with an explanatory reason rather than failing.

Separately from the panel scan, BootUI registers Spring AOT runtime hints for its own native-image needs from
[`BootUiRuntimeHints`](../bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/BootUiRuntimeHints.java).
Those built-in hints cover BootUI's runtime-scanned classpath resources, BootUI DTO records used by Jackson, and the
well-known reflective calls used by the Heap Dump, Security, and Pentesting panels. They are contributed by
`BootUiAutoConfiguration`, so applications using the starter should not need to copy BootUI-specific hints into their own
native-image configuration.

## What BootUI does not do

- It is **not a replacement for the [GraalVM tracing agent](https://www.graalvm.org/latest/reference-manual/native-image/metadata/AutomaticMetadataCollection/)
  or an actual native-image build**. Static analysis cannot see reflection driven by runtime data, so the checks are
  heuristic review prompts, and the generated metadata is a scaffold to review and complete — not a finished file.
- It does not analyse third-party dependency bytecode for readiness; for dependencies it only reports whether classpath
  JARs ship bundled reachability metadata JSON or native-image build arguments.
- It does not modify, compile, or instrument application code; it reads already-compiled bytecode.
- Spring-managed beans are already covered by Spring AOT, so findings that overlap with Spring's own AOT processing may
  be safe to ignore.

## The generated `reachability-metadata.json`

The scaffold uses the modern unified
[reachability metadata schema](https://www.graalvm.org/latest/reference-manual/native-image/metadata/). Each reflection
and serialization entry carries a `condition.typeReached` guard so the registration only activates once the type is
actually reachable. Reflection candidates are concrete application types that typically need reflection in a native
image (records, `Serializable` types, and JPA entities); serialization candidates are the application's `Serializable`
types; resource globs cover the standard `application*.properties` / `application*.yml` / `application*.yaml` files.

Review the generated file with the tracing agent, then place it under
`src/main/resources/META-INF/native-image/<groupId>/<artifactId>/` in your application. The panel substitutes the
resolved `groupId`/`artifactId` into that hint whenever it can determine them — from `build-info.properties` (which works
even when running from a packaged jar) or the project `pom.xml` — and keeps the `<groupId>`/`<artifactId>` placeholders
only when no coordinates can be resolved.

## Severity scale

Severity reflects the worst plausible impact if the finding is real, not the likelihood:

- **CRITICAL** — a construct with the most severe native-image impact if the finding is real. No active GraalVM check
  currently emits this severity.
- **HIGH** — a construct native images generally cannot support or Spring AOT cannot capture at run time (runtime class
  generation, runtime classpath scanning, runtime bean registration/suppliers).
- **MEDIUM** — a construct GraalVM cannot resolve at build time that will usually fail at run time without metadata
  (reflection, dynamic class loading, deep reflection, dynamic proxies, active JDK serialization, build-machine state
  captured in static initializers).
- **LOW** — a construct that often needs extra configuration (runtime resource loading, resource bundles, service
  loading, reflective annotation access, static-initializer side effects, native access, native methods).
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
  reflected members (`Method`, `Field`, `Constructor`, `Parameter`). Reads on `java.lang.Class` are intentionally ignored
  as too common to be actionable.
- **Fires when**: a class reads annotations from a reflected member whose annotations native-image only retains when the
  element is registered for reflection.
- **Recommendation**: register the inspected members under `reflection` in `reachability-metadata.json` so their
  annotations are available at run time.

## Dynamic proxies

### GRAAL-PROXY-001 — Dynamic JDK proxies may need proxy metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `Proxy.newProxyInstance` and `Proxy.getProxyClass`.
- **Fires when**: a class creates or obtains a JDK dynamic proxy whose interface list must be known to native-image.
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
- **Inspects**: application classes that implement `java.io.Serializable`.
- **Fires when**: an application type implements `Serializable`; types that are actually serialized at runtime require
  serialization metadata.
- **Recommendation**: if these types are serialized (e.g. via the JDK serialization protocol), register them under
  `serialization` in `reachability-metadata.json`.

### GRAAL-SER-002 — Active JDK serialization may need serialization metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `ObjectOutputStream.writeObject` / `writeUnshared` and `ObjectInputStream.readObject` /
  `readUnshared`.
- **Fires when**: a class serializes or deserializes types through the JDK serialization protocol at run time, which
  native-image must be told about explicitly.
- **Recommendation**: register every serialized type under `serialization` in `reachability-metadata.json` (or with
  Spring's RuntimeHints serialization registration), or prefer a serialization format that does not need build-time
  registration.

## Build-time initialization

### GRAAL-INIT-001 — Static initializer I/O or thread starts may break build-time initialization

- **Severity**: LOW
- **Inspects**: static initializers that directly perform file I/O (`java.io` file streams or filesystem-touching
  `java.nio.file.Files` calls) or start threads / processes (`Thread.start`, `Runtime.exec`, `ProcessBuilder.start`).
  Lightweight `Files` metadata predicates such as `exists`, `isDirectory`, and `isReadable` are intentionally ignored;
  side effects in helper methods or lambdas are out of scope.
- **Fires when**: a class runs I/O or starts a thread/process from its static initializer; with build-time class
  initialization these run during the native build, capturing build-time state or failing the build.
- **Recommendation**: move the side effect out of the static initializer, or initialize the class at run time (e.g.
  `--initialize-at-run-time=<class>`) so the I/O or thread starts when the application runs.

### GRAAL-INIT-002 — Static initializer captures build-machine state

- **Severity**: MEDIUM
- **Inspects**: static initializers that read environment- or time-sensitive state (`System.getenv` / `getProperty` /
  `getProperties`, current time, `java.time` `now()`, default `Locale` / `TimeZone`, `InetAddress`, `Random` /
  `SecureRandom` constructors or `next*` calls, `UUID.randomUUID`).
- **Fires when**: a class captures those values in a static initializer; with build-time class initialization they are
  frozen at native build time instead of being read when the application runs.
- **Recommendation**: move the state capture into a runtime code path, or mark the class `--initialize-at-run-time` so the
  values are read when the native image starts rather than baked in during the build.

## Native access

### GRAAL-NATIVE-001 — Native access (JNI / Unsafe) may need native-image configuration

- **Severity**: LOW
- **Inspects**: loading of native libraries (`System.loadLibrary`, `Runtime.load`) and use of `sun.misc.Unsafe` /
  `jdk.internal.misc.Unsafe`.
- **Fires when**: a class loads a native library or uses an `Unsafe` API that often requires JNI or extra native-image
  configuration.
- **Recommendation**: confirm the native libraries are available to the native image and add JNI configuration if
  needed; prefer supported APIs over `Unsafe`.

### GRAAL-NATIVE-002 — Native method declarations may need JNI configuration

- **Severity**: LOW
- **Inspects**: application classes that declare `native` methods.
- **Fires when**: a class declares a `native` method whose JNI entry point and backing native library must be configured
  for the native image.
- **Recommendation**: provide JNI configuration under `jni` in `reachability-metadata.json` and ensure the native library
  is bundled with and loadable by the native image.

## Class generation

### GRAAL-CLASSGEN-001 — Runtime class generation is unsupported in native images

- **Severity**: HIGH
- **Inspects**: runtime bytecode/class generation (`ClassLoader.defineClass`, `MethodHandles.Lookup.defineClass` /
  `defineHiddenClass` / `defineHiddenClassWithClassData`, CGLIB `Enhancer`, ByteBuddy, Javassist).
- **Fires when**: a class generates or defines classes at run time; a closed-world native image cannot perform that work
  because it has no compiler.
- **Recommendation**: generate the classes at build time (e.g. via Spring AOT / build-time processing) instead of at run
  time, or replace the dynamically generated types with statically compiled equivalents. No metadata enables runtime
  class definition in a native image.

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

### SPRING-AOT-001 — Runtime bean singleton registration is not captured by Spring AOT

- **Severity**: HIGH
- **Inspects**: calls to `SingletonBeanRegistry.registerSingleton(...)`.
- **Fires when**: a class adds beans to the context at run time; Spring AOT processes the bean factory at build time, so
  dynamically registered singletons are invisible to the AOT-generated context and native-image.
- **Recommendation**: register the bean through standard build-time configuration (`@Bean` / `@Component` /
  `BeanFactoryInitializationAotContribution`) so Spring AOT can see it, instead of calling `registerSingleton` at run
  time.

### SPRING-AOT-002 — Programmatic instance suppliers are not captured by Spring AOT

- **Severity**: HIGH
- **Inspects**: bean definitions backed by a programmatic instance supplier (`setInstanceSupplier`, or Spring
  `registerBean` / `BeanDefinitionBuilder` methods with a `Supplier`).
- **Fires when**: a class registers a bean definition whose instance comes from a supplier lambda; Spring AOT cannot
  trace through that supplier at build time, so the bean's type and dependencies may be missing from the native image.
- **Recommendation**: prefer declarative bean definitions (`@Bean` methods / component scanning) whose types Spring AOT
  can resolve, or provide a `RuntimeHintsRegistrar` that registers the supplied type for reflection.
