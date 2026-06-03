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

- **Surveys classpath dependencies** (when the _Include dependencies_ toggle is on, which is the default) to report which
  third-party JARs already ship reachability metadata under `META-INF/native-image/`. Libraries that ship metadata work
  out of the box in a native image; the rest may need your own configuration or the tracing agent.
- **Builds a `reachability-metadata.json` scaffold** from the application's own classes — reflection and serialization
  candidates plus the standard externalized-configuration resource globs — which you can download from the panel.

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
- It does not analyse third-party dependency bytecode for readiness; for dependencies it only reports whether they ship
  their own metadata.
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
`src/main/resources/META-INF/native-image/<groupId>/<artifactId>/` in your application.

## Severity scale

Severity reflects the worst plausible impact if the finding is real, not the likelihood:

- **MEDIUM** — a construct GraalVM cannot resolve at build time that will usually fail at run time without metadata
  (reflection, dynamic proxies).
- **LOW** — a construct that often needs extra configuration (runtime resource loading, native access).
- **INFO** — an informational prompt that only matters if the type is actually used that way (serialization).

The scan evaluates every registered check, but the panel only lists checks that found something to review. Findings are
ordered by importance (`MEDIUM`, `LOW`, `INFO`), then by the number of occurrences, and include up to a handful of sample
detail lines.

---

## Reflection

### GRAAL-REFLECT-001 — Reflective API usage may need reflection metadata

- **Severity**: MEDIUM
- **Inspects**: calls to the reflection API (`Class.forName`, `Method.invoke`, `Field` get/set, `Class.getDeclared*`,
  `Constructor.newInstance`).
- **Fires when**: an application class reflectively accesses types that GraalVM cannot resolve at build time.
- **Recommendation**: register the reflectively accessed types under `reflection` in `reachability-metadata.json`, or
  replace reflection with direct calls. Spring AOT already covers Spring-managed beans.

## Proxies

### GRAAL-PROXY-001 — Dynamic JDK proxies may need proxy metadata

- **Severity**: MEDIUM
- **Inspects**: calls to `Proxy.newProxyInstance`.
- **Fires when**: a class creates a JDK dynamic proxy whose interface list must be known to native-image.
- **Recommendation**: declare the proxied interfaces in `reachability-metadata.json`, or prefer Spring's proxy
  mechanisms which are covered by Spring AOT.

## Resources

### GRAAL-RES-001 — Runtime resource loading may need resource metadata

- **Severity**: LOW
- **Inspects**: calls to `Class`/`ClassLoader` `getResource` and `getResourceAsStream`.
- **Fires when**: a class loads a resource by name that must be embedded in the native image to be available at runtime.
- **Recommendation**: register the loaded resource paths (as globs) under `resources` in `reachability-metadata.json` so
  native-image bundles them.

## Serialization

### GRAAL-SER-001 — Serializable types may need serialization metadata

- **Severity**: INFO
- **Inspects**: application classes that implement `java.io.Serializable`.
- **Fires when**: an application type implements `Serializable`; types that are actually serialized at runtime require
  serialization metadata.
- **Recommendation**: if these types are serialized (e.g. via the JDK serialization protocol), register them under
  `serialization` in `reachability-metadata.json`.

## Native access

### GRAAL-NATIVE-001 — Native access (JNI / Unsafe) may need native-image configuration

- **Severity**: LOW
- **Inspects**: loading of native libraries (`System.loadLibrary`, `Runtime.load`) and use of `sun.misc.Unsafe` /
  `jdk.internal.misc.Unsafe`.
- **Fires when**: a class loads a native library or uses an `Unsafe` API that often requires JNI or extra native-image
  configuration.
- **Recommendation**: confirm the native libraries are available to the native image and add JNI configuration if
  needed; prefer supported APIs over `Unsafe`.
