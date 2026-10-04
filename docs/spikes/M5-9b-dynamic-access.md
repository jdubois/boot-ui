# M5-9b: dynamic access recording, spike report

Spike for PLAN-v2 M5-9b (D37). Can the BootUI agent record an application's dynamic accesses (reflection, proxies,
resources, deserialization) during a bounded, user-triggered session, and export what native-image needs as a
`reachability-metadata.json` fragment (§5.15, **Dynamic access recording**)? The spike gathers evidence first, then
builds a prototype behind an opt-in flag, and ends with an estimate and a recommendation.

Measured on 2026-10-04 with OpenJDK 17.0.20.1, 21.0.12.1, and 26.0.1 on an Apple M1 Pro (10 cores). The machine was
shared with other work (load average 20–48), so every timing is indicative. Each figure is the best of 5 to 7 rounds,
and each comparison was repeated in fresh JVMs. Sub-nanosecond deltas need JMH, with forks, on a quiet machine before
they are quoted as figures.

## Summary

- **Feasible and safe.** Byte Buddy retransforms `java.lang.Class`, `Method`, `Constructor`, and `Proxy` on JDK 17, 21,
  and 26, and, as count-only evidence, `Field`, `ClassLoader`, `ObjectInputStream`, and `ObjectStreamClass`. Inline
  advice keeps every caller-sensitive method's caller, including when the call goes through reflection. A self-test and
  forked-JVM tests prove it on each JDK, and beside the OpenTelemetry agent attached in both orders.
- **Cheap when off, affordable when on.** With no session running, an advised call costs one volatile read: +0.3 to
  0.5 ns on `Method.invoke`, and within noise on the others. With a session running, a recorded call costs about
  1.2–2 µs on a quiet machine (one stack walk to the immediate caller), and up to about 5 µs under load. Recording a
  whole Spring Boot startup added 3–4 % over the installed sensor.
- **A raw recording is mostly noise, and misses what matters.** A small Spring Boot and JPA application's startup on
  the JVM gave 1,313 distinct accesses, none of them called from the application. Spring AOT's generated metadata, the
  dependencies' bundled metadata, and Oracle's repository cover at most 742 of them. Recorded in AOT mode, 725 remain,
  517 at most covered; of the rest, 10 reach the application, all Spring infrastructure. Meanwhile, Jackson 3 binding an
  application record never appears: it doesn't go through the four recorded methods.
- **Recommendation: reshape it, take it out of 2.0.0's M5 scope, and gate it on a native ground truth** (see
  [Recommendation](#recommendation)). A useful version also records member queries and method-handle lookups, keeps by
  default only the accesses that reach an application type or come from the application's call path, and compares them
  with what Spring AOT and metadata cover. Before building it, a 2–4-day gate measures its precision and recall against
  the missing-registration errors of real native runs. The reshaped version is about 19–24 engineer-days with that
  gate, and the full §5.15 scope about 23–32, against the 8–10 days PLAN-v2 budgets for all of M5-9.

## 1. What must be advised

The table lists, for each kind of access, the JDK methods to advise, their annotations on each JDK, and what the
prototype does with them. The annotations are `CS` (`@CallerSensitive`), `FI` (`@ForceInline`), and `IC`
(`@IntrinsicCandidate`). An adapter is a private `@CallerSensitiveAdapter` overload that also takes the caller class.
On every JDK, every hook was retransformed and its self-test call reached the advice
(`DynamicAccessIT.everyExtendedHookIsAdvised`).

| Access | Methods to advise | JDK 17 | JDK 21 | JDK 26 | Prototype |
|---|---|---|---|---|---|
| Class lookup | `Class.forName(String)` | CS | CS, and its adapter | CS, and its adapter | Recorded |
| | `Class.forName(String, boolean, ClassLoader)` | CS | CS, and its adapter | — | Recorded |
| | `Class.forName(Module, String)` | CS | CS, and its adapter | — | Recorded |
| Reflective invocation | `Method.invoke(Object, Object...)` | CS FI IC | CS FI IC | CS FI IC | Recorded |
| Reflective construction | `Constructor.newInstance(Object...)` | CS FI | CS FI | CS FI | Recorded |
| JDK proxies | `Proxy.newProxyInstance(ClassLoader, Class[], InvocationHandler)` | CS | CS | — | Recorded |
| | `Proxy.getProxyClass(ClassLoader, Class...)` (deprecated) | CS | CS | — | Counted |
| Member queries | `Class.getDeclaredMethod(s)`, `getMethod(s)` | CS | CS | — | Counted |
| | `Class.getDeclaredConstructor(s)`, `getConstructor(s)` | CS | CS | — | Counted |
| | `Class.getDeclaredField(s)`, `getField(s)` | CS | CS | — | Counted |
| Field values | `Field.get(Object)`, `Field.set(Object, Object)`, and the typed variants | CS FI | CS FI | CS FI | Counted (`get`, `set`) |
| Resources | `Class.getResource`, `Class.getResourceAsStream` | CS | CS | CS | Counted |
| | `ClassLoader.getResource`, `getResources`, `getResourceAsStream` | — | — | — | Counted |
| Deserialization | `ObjectInputStream.resolveClass(ObjectStreamClass)`, which can be overridden | — | — | — | Counted |
| | `ObjectStreamClass.initNonProxy(...)`, package-private, reached for every class read | — | — | — | Counted |

The spike does not cover several accesses a useful product needs (§7): `MethodHandles.Lookup.find*`, `unreflect*`, and
`privateLookupIn` (Jackson 3 and many libraries use method handles; a product must record the lookup, since invoking a
handle cannot be advised, and attribute it to the lookup class), `ClassLoader.loadClass`, `Class.getRecordComponents`,
`Class.arrayType`, `Array.newInstance`, `ResourceBundle.getBundle`, `ServiceLoader`, and `Unsafe.allocateInstance`. FFM
`Linker.downcallHandle` and `upcallStub` are Java methods that can be advised too. Only JNI accesses happen in native
code, out of reach of bytecode advice; GraalVM's tracing agent sees them through JVMTI.

Four facts shape the design:

- **JDK 26 has far fewer caller-sensitive methods,** since JDK 24 permanently disabled the Security Manager (JEP 486).
  The advice must be correct with both sets of annotations. Inline advice is, since it never looks at them.
- **JDK 18+ reflection calls the adapter, not the public method.** It runs a caller-sensitive method reached through
  reflection by calling its private adapter with the caller passed explicitly, and each public overload that has an
  adapter delegates to it. The sensor therefore advises the adapter when the JDK declares one, and the public overload
  otherwise, so a `Class.forName` reached through reflection still records the class it loads. A direct call records
  once. The rubber-duck review found this: advising only the public overloads lost the type on JDK 21 and 26.
  `DynamicAccessBehaviors` now loads a class only through reflection.
- **A reflective `Class.forName` produces two entries,** and native-image needs both: `invoke` of
  `java.lang.Class#forName(java.lang.String)`, and `forName` of the class it loads.
- **Deserialization needs another hook and another attribution rule.** The JDK always calls
  `ObjectInputStream.resolveClass` itself, so the immediate-caller rule of §5 drops it, and frameworks override it. A
  product must advise `ObjectStreamClass.initNonProxy` and attribute the access to the first non-JDK frame (the caller
  of `readObject`), not to the immediate one.

## 2. Caller-sensitive safety

A caller-sensitive method asks `Reflection.getCallerClass()` for the frame right below its own. HotSpot checks that this
method is marked caller-sensitive, or throws `InternalError: CallerSensitive annotation expected at frame 1`. On JDK 17,
HotSpot's security stack walk also skips `Method.invoke` by its intrinsic id. Three conditions keep this true under
advice, and the prototype meets each one:

1. **Only inline advice.** Byte Buddy's `Advice` copies the advice bytecode into the method, so it adds no frame. Never
   use delegating advice, a `MemberSubstitution` around the call, or a wrapper method on these methods: each puts a
   frame between the method and its caller.
2. **Annotations kept.** Byte Buddy's `DECORATE` strategy keeps every annotation, and retransformation reparses the
   class file with the same boot loader, which honors the JDK's internal annotations. The evidence that HotSpot keeps
   `Method.invoke`'s intrinsic id is behavioral: JDK 17's reflective test below fails if the security stack walk stops
   skipping it.
3. **The recorder's frames don't count.** The recorder's `StackWalker` runs from the advice, below the advised method,
   and skips its own frames by package.

Evidence on JDK 17, 21, and 26, alone and beside OpenTelemetry in both orders:

- **The sensor's self-test.** It runs from the agent's isolated class loader and loads, by `Class.forName(String)`, a
  class only that loader defines. It does so directly and through `Class.class.getMethod("forName", …).invoke(…)`, and
  the class is found only if the caller frame survived the advice. A failure removes the sensor.
- **`DynamicAccessBehaviors`, with three classes of checks:**
  - **Isolated loading.** The application is loaded by a class loader whose parent is the platform loader. Its
    `Class.forName("bootuidynamicapp.Hidden")` must return that loader's copy, not the test loader's. This holds
    directly, through reflection, and for a class loaded only through reflection. It holds with the advice installed but
    no session, while recording, and after the advice is removed.
  - **JDK 17's path.** The reflective call goes through `NativeMethodAccessorImpl` into the public `forName(String)`, so
    the security stack walk must still skip the retransformed `Method.invoke` by its intrinsic id.
  - **JDK 21 and 26's path.** The reflective call goes through the advised adapter.
- **The JIT.** It inlines the retransformed `Method.invoke` exactly as before on JDK 17 (`-XX:+PrintInlining`: `force
  inline by annotation`, `Reflection::getCallerClass (intrinsic)`).

All of this ran on HotSpot with C2. The self-test guards correctness on other JVMs (Oracle GraalVM's Graal JIT, OpenJ9),
but neither was tested, and the cost figures hold for HotSpot only.

The prototype's `suppress = Throwable.class` and `onThrowable` add exception handlers to the advised methods. They leave
the caller frame unchanged, and §4 measured no cost from them.

## 3. Retransformation, recursion, and failure isolation

- **Install.** One transformer adds advice to 4 classes, or 8 with the count-only hooks. It uses `DECORATE`, retransforms
  in batches of 64 split on failure, and calls `assureReadEdgeTo` on the bridge so `java.base` can read it. Install and
  self-test take 200–250 ms on each JDK, off the claiming thread. The self-test calls each hook from the agent's thread
  and counts only that thread's calls. If any recorded hook is not reached, the transformer is removed and the sensor
  reports itself unavailable, with the reason.
- **Restore.** A release, or a claim that does not ask for the sensor, restores the classes through
  `ResettableClassFileTransformer.reset`. The tests check that the methods still work after the restore and that
  nothing records. A class that the restore could not retransform makes the state `release-failed`, since Byte Buddy's
  own answer only covers removing the transformer. The advice then stays, reading a flag that no session can set again.
- **Recursion.** The recorder runs `StackWalker`, `ConcurrentHashMap`, a `ThreadLocal`, and string building inside
  advised methods, and `StackWalker` itself creates each `StackFrameInfo` with `Constructor.newInstance`
  (`StackStreamFactory`). Every walk therefore re-enters the advice, about 20 times per walk during a Spring startup on JDK 26. A
  per-thread guard (`Reentrancy`) drops these nested accesses, and hook counters count only past it. BootUI's own work
  (`AgentBridge.bootUiWork`) is counted, never recorded. A session warms the walker on the thread that starts it, so the
  walker's classes are never first initialized inside an application's advised call.
- **Failure isolation.** Every bridge entry point catches `Throwable` and counts it, and the advice also suppresses.
  The session bounds (time and distinct entries) are enforced in the advice path itself, with no timer thread. A
  session ends with its run (claim, disarm, or release), and `start` checks the claim again after publishing the
  session, so a release racing a start cannot leave a session without a run. Ending a session at its time limit inside
  an advised call only swaps a reference and closes the session, so a thread that read it just before stops adding to
  it. The entries are built when `stop(token)` reads them, and only for the claim that recorded them; `status()` gives
  counts only, since the engine reads it on request paths.
- **Limits accepted for the spike.** A new claim or a release forgets the last session, so a product must fetch it into
  the engine's store before a DevTools restart. A class the restore cannot retransform leaves the sensor unavailable for
  the rest of the JVM's life.

## 4. Cost

### No session (the advice installed, only the flag read)

The table gives nanoseconds per call, called from the application class, best of 7 after warm-up, in fresh JVMs
(`DynamicAccessIT.benchmark`, opt-in). The JVM with the agent claims before warming up, so retransformation deoptimizes
nothing the benchmark measures. Both JVMs first make reflective calls to several targets, as any application does.

| Operation | JDK 17 no agent | JDK 17 advised, off | JDK 21 no agent | JDK 21 advised, off | JDK 26 no agent | JDK 26 advised, off |
|---|---|---|---|---|---|---|
| `Class.forName(String)` (a loaded class) | 352 | 354–356 | 346–347 | 348 | 333–335 | 337–338 |
| `Method.invoke` (an empty method) | 1.7 | 4.5–4.8 ¹ | 4.7 | 5.1 | 4.7–4.8 | 5.0 |
| `Constructor.newInstance` | 60 | 60–61 | 66 | 65 | 64 | 64 |
| `Proxy.newProxyInstance` (a cached class) | 15–16 | 15 | 12 | 15 ² | 12–14 | 14 |

¹ On JDK 17, a control JVM with the agent installed but `Method` not advised measures 4.1–4.3 ns, so the advice
itself costs 0.3–0.5 ns, as on JDK 21 and 26. The rest comes from profile pollution. Byte Buddy's own reflection while
installing, from any Byte Buddy agent including OpenTelemetry's, makes the JDK 17 `DelegatingMethodAccessorImpl` call
site megamorphic, so C2 no longer inlines the benchmark's generated accessor. An application that calls more than two
methods through reflection is in that state anyway. JDK 18+ reflection uses method handles, which have no such shared
call site.

² +3 ns (25 %) on JDK 21 in both rounds, not clearly noise; to be checked with JMH.

The first version of this benchmark warmed up before claiming, in the same JVM, and showed +4 to +6 ns on
`Method.invoke`. That came from deoptimization, not from the advice. Once the method was fixed, removing `suppress` or
reading a plain (non-volatile) field changed nothing measurable.

### A session on

Each recorded call walks the stack once, to its immediate caller, since that class is part of the key. Only a new entry
walks on, up to 64 frames, to find the application frame. On a quiet machine, a recorded call costs 1.7–1.9 µs for
`Class.forName`, 1.2–1.4 µs for `Method.invoke` and `Constructor.newInstance`, and 2.5 µs for a proxy; the walk itself
takes 1.3 µs. Under a load average of 25–48, the same runs measured 1.2–6 µs, and so did a library caller with the
application frame above it.

A Spring Boot 4.1 application (Spring MVC, Data JPA, H2) on JDK 26 started in 2.56–2.67 s with the sensor installed and
no session, and in 2.68–2.72 s with a session covering the whole startup: +70 to 110 ms (+3–4 %), for about 15,000
walks taking about 110 ms in total. The count-only hooks added nothing measurable on top. These runs were under a load
average of 15–35. The install itself is not in that figure: it retransforms `java.lang.Class` and the reflection classes
at a safepoint and deoptimizes their callers, 200–250 ms off the claiming thread. A product that records startup must
install synchronously at claim, and pay that during startup.

During that startup, the count-only hooks saw 7,900 method queries, 1,100 constructor queries, 380 field queries, 330
field accesses, and 1,390 class-loader resource lookups. A first run before the guard fix counted 15,800 constructor
queries and 337,000 constructor calls: the recorder's own walks, which the counters now leave out.

A product could make attribution cheaper. It could key a first lookup on `(kind, target)` alone, so a repeated access
from a known caller class costs a map lookup instead of a walk. Or it could take the immediate caller from the
caller-sensitive adapters' explicit `caller` argument where one exists, with no walk at all.

## 5. Finding the calling frame

The **immediate caller** of the JDK method decides whether an access is recorded, which is the rule GraalVM's tracing
agent applies with its caller filter. The prototype also uses it as the exported entry's `typeReached` condition. That
is a choice, not GraalVM's: its conditional mode takes the nearest frame of user code instead. The immediate caller is a
sound condition, since it is running, but for a library caller such as Spring's `ClassUtils` it is reached by every
application, so the entry is unconditional in practice. A product should use the application frame when there is one.

The prototype finds it with one bounded walk: `StackWalker` with `RETAIN_CLASS_REFERENCE`, and default options, so
reflection frames such as `Method.invoke` and hidden frames are not shown.

1. The walk skips the bridge's frames, then every frame of the advised method it shows. It shows a public
   `Class.forName` and the adapter it calls, and `Proxy.newProxyInstance`; it hides `Method.invoke` and
   `Constructor.newInstance`.
2. The next frame is the immediate caller. The access is counted, not recorded, when that class is:
   - the JDK's (boot or platform loader);
   - tooling: BootUI's modules (not its sample applications), the OpenTelemetry agent, IntelliJ's and JaCoCo's runtimes,
     or Byte Buddy's agent package;
   - generated: a class whose name contains `$Proxy` or `$$`, or ends with `_Subclass`. A JDK proxy's static
     initializer, for example, calls `Class.forName` for its interfaces.
3. Only for a new entry, the walk continues, within 64 frames, to the first frame in the claim's application packages.
   That **application frame** is shown beside the caller: `Reflector.instantiate`, called from `OrderService.load`.

In the Spring Boot startup above, 9,300 accesses had a JDK caller, 1,160 a generated caller, and 4,250 were recorded,
as 1,313 distinct entries. Every run recorded the same counts.

The work also hit a shading pitfall. The agent jar's shade plugin relocates every `net.bytebuddy` string constant,
including those in the bridge, so the prototype builds that prefix at run time. The existing `Exclusions` list has the
same defect, to be fixed apart from this spike: in the published jar it excludes
`io.github.jdubois.bootui.agent.shaded.bytebuddy.` instead of `net.bytebuddy.`.

## 6. Filtering what is already covered

A shown or exported entry is only useful if nothing the build already uses covers it. Four sources can cover it, from
cheapest to dearest:

1. **Metadata on the class path, read locally, with no network.** This covers every
   `META-INF/native-image/**/reachability-metadata.json` (GraalVM 23+), plus the legacy `reflect-config.json`,
   `proxy-config.json`, `resource-config.json`, `serialization-config.json`, and `jni-config.json`, in the application
   and in every dependency jar. Parse them once when a session ends, index them by type, and test each entry. It is
   covered when the type is present with its member, or with `allDeclared*` or `allPublic*`, or by a matching proxy
   list, and when the existing entry's condition is reached as well: an entry conditioned on another `typeReached` may
   not cover the access. The engine has no JSON library, so the parsing needs an adapter seam like
   `ReachabilityMetadataRepository`. No metadata file shows what a library registers in code, through a GraalVM
   `Feature` or a Spring `RuntimeHintsRegistrar` that only runs at build time.
2. **Spring AOT hints,** in two parts with different confidence:
   - **Evaluated at run time,** in the Spring adapter. This gathers `RuntimeHintsRegistrar`s from
     `META-INF/spring/aot.factories`, those named by `@ImportRuntimeHints` on bean definitions, and `@Reflective`,
     `@RegisterReflection`, and `@RegisterReflectionForBinding` on bean classes (Spring's
     `ReflectiveRuntimeHintsRegistrar`). They go into one `RuntimeHints`, then `RuntimeHintsPredicates` tests each
     entry. This is cheap, and free of side effects in practice.
   - **Generated by Spring AOT as code:** bean instantiation, `@Configuration` proxies, autowiring, and JPA
     `PersistenceManagedTypes`. That code replaces the reflection the JVM run performs, and no registrar names it at run
     time. Reproducing it would mean running the AOT engine, which is a build step. In practice:
     - Label a caller in `org.springframework.beans`, `org.springframework.cglib`, or `org.springframework.aop`, or
       Spring's `ClassUtils.forName` loading a bean class, as likely covered, and say so. Not all of it is:
       `BeanWrapper` data binding, `@ConfigurationProperties` binding of application types, and
       `BeanUtils.instantiateClass` on a type that is not a bean need their own hints.
     - Better, record with the AOT-generated code on the JVM (`-Dspring.aot.enabled=true`), which removes most of that
       reflection (§7), and requires the build to have run `process-aot`.
     - Read the build's AOT output, when it exists, as source 1, since it is exact:
       `target/spring-aot/main/resources/META-INF/native-image` or `build/generated/aotResources`.
3. **Oracle's reachability-metadata repository.** It is indexed per library in
   `metadata/<group>/<artifact>/index.json`, which the GraalVM panel already reads, with one
   `metadata/<group>/<artifact>/<version>/reachability-metadata.json` per tested version. Type-level coverage needs that
   second file: one bounded, user-triggered GET per library that owns a recorded caller, through the same seam, cached
   per session. Older versions still use the legacy per-kind files, so the parser must read both. GraalVM's Native Build
   Tools add this metadata to the build, choosing the metadata version from the index (its tested versions, `default-for`,
   or the latest), so its entries count as covered only when the build uses that plugin, and the version chosen must
   follow the same rule.
4. **Quarkus.** Its native metadata comes from extensions' build steps and from `@RegisterForReflection`, and dev mode
   shows neither. A Quarkus recording can be shown, compared only with `@RegisterForReflection` and the class-path
   metadata, and it must say so.

## 7. What a real application records

The spike recorded a Spring Boot 4.1 application's startup with the prototype. The application used Spring MVC, Data
JPA with one entity and one repository, and H2, plus a `CommandLineRunner` that writes and reads a record through
Jackson 3.

- **1,313 distinct entries:** 822 `forName`, 237 `newInstance`, 230 `invoke`, and 24 proxies.
- **Callers:**
  - Spring: 933 (`org.springframework.util` 357, `beans` 284, `boot` 98, and others).
  - Hibernate: 360.
  - Byte Buddy, Hibernate's bytecode provider: 94.
  - Tomcat and others: about 30.
- **The application: none.** No entry was called from the application. 17 had an application frame: the runner's
  repository calls, through Spring's AOP and JPA proxies.
- **Entries naming an application type: 14,** all from Spring's bean instantiation, CGLIB, the JPA proxy, and
  Hibernate's entity instantiator. Spring AOT's generated code and its JPA hints for the managed entity types are meant
  to cover each one.
- **Missing: Jackson 3 writing and reading `OwnerView`,** a record. Jackson finds its members through queries
  (`getDeclaredMethods`, `getRecordComponents`) and, we believe, method handles; a captured stack has not confirmed the
  latter. Either way, `Method.invoke` and `Constructor.newInstance` never see it. That binding is a common reason a
  native Spring application fails at run time. Spring AOT covers it for `@RequestMapping` and `@HttpExchange`
  signatures, `@RegisterReflectionForBinding`, `@ConfigurationProperties`, and Spring Data's domain types, not for a
  type an application serializes itself, which the GraalVM panel's static check already says.

The spike then ran Spring's `process-aot` on the same application and tested the entries against every metadata source
it could read: the AOT-generated `reachability-metadata.json`, the 12 metadata files bundled in the dependencies, and
Oracle's repository for Hibernate (7.3.0.Final, its nearest tested version to 7.4.5) and Hikari. Conditions were ignored,
so these are upper bounds.

| Recording | Entries | Covered by AOT metadata | Covered with the repository too | Uncovered, reaching the application |
|---|---|---|---|---|
| JVM mode | 1,313 | 429 | 742 | 11 |
| AOT mode on the JVM (`-Dspring.aot.enabled=true`) | 725 | 205 | 517 | 10 |

- AOT mode removes most of Spring's own reflection: `org.springframework.beans` callers go from 284 to 42, `boot` from
  98 to 6, and `util` from 357 to 112.
- What stays uncovered in AOT mode (208 entries) is Hibernate's bootstrap (59), Hibernate's Byte Buddy proxy factory
  (63, which a native image does not use), Spring's utilities (30), and a few others: noise again, unless some of it
  really fails natively.
- The 10 entries reaching the application are Spring Data and JPA infrastructure invoked through AOP and JPA proxies
  (`CrudMethodMetadata`, `Session.persist`), with the runner as their application frame. Whether a native run needs
  them could not be checked: no GraalVM was available, so the spike never built the application natively.

So the useful product is not "every reflective call on the JVM". It is "the dynamic accesses that reach application
types or come from application code, minus what AOT and metadata cover", recorded in AOT mode when the build allows it,
and it must record member queries and method handles to see the Jackson case. Its precision and recall stay unknown
until they are measured against the missing-registration errors of a real native run.

## 8. The export format

GraalVM for JDK 23 and later reads `reachability-metadata.json` from `META-INF/native-image/<groupId>/<artifactId>/`
([schema 1.2.0](https://github.com/oracle/graal/blob/master/docs/reference-manual/native-image/assets/reachability-metadata-schema-v1.2.0.json)).
BootUI's `GraalVmMetadataGenerator` already writes this format. Recorded entries map to it as follows:

| Entry | `reflection` element |
|---|---|
| `forName` of `T` from caller `C` | `{"condition": {"typeReached": "C"}, "type": "T"}` |
| `invoke` of `T#m(P1, P2)` | `… "type": "T", "methods": [{"name": "m", "parameterTypes": ["P1", "P2"]}]` |
| `newInstance` of `T(P1)` | `… "methods": [{"name": "<init>", "parameterTypes": ["P1"]}]` |
| a proxy of `I1, I2` | `… "type": {"proxy": ["I1", "I2"]}` (order kept) |
| a field read or write of `T.f` | `… "fields": [{"name": "f"}]` |
| a member query (`getDeclaredMethods` on `T`) | `… "type": "T"` only, which makes its members queryable in the unified format; a method goes into `methods` only once it is invoked, through reflection or a method handle |
| a deserialized `T` | `… "type": "T", "serializable": true` |
| a resource `p` | `"resources": [{"condition": …, "glob": "p"}]` |
| a bundle `b` | `"resources": [{"bundle": "b"}]` |

Type names come from `Class.getTypeName()`, as the schema's `typeName` requires: binary names with `$`, and arrays as
`T[]`. Hidden classes (lambdas, `Lookup.defineHiddenClass`) have a `/` in their names, which the schema rejects, so they
are left out. Entries are grouped by `(condition, type)`. Under exact reachability, GraalVM 23+ also needs the name of a failed
`Class.forName` registered. That name is a string the caller passed, so the prototype only counts failed lookups. A
product could record one only when it is a valid class name, and show it masked below `FULL` exposure.

This fragment, abridged, was made by the prototype's test from one session (`DynamicAccessBehaviors.fragment`):

```json
{
  "reflection": [
    {
      "condition": {"typeReached": "bootuidynamicapp.Isolated"},
      "type": "bootuidynamicapp.Hidden",
      "methods": [
        {"name": "<init>", "parameterTypes": []},
        {"name": "greet", "parameterTypes": ["java.lang.String"]}
      ]
    },
    {
      "condition": {"typeReached": "bootuidynamicapp.Isolated"},
      "type": {"proxy": ["java.lang.Runnable", "bootuidynamicapp.Hidden$Marker"]}
    },
    {
      "condition": {"typeReached": "bootuidynamiclib.Reflector"},
      "type": "bootuidynamicapp.Hidden",
      "methods": [{"name": "<init>", "parameterTypes": []}]
    }
  ]
}
```

## 9. The prototype

The prototype runs only when the JVM sets `-Dbootui.agent.experimental.dynamic-access=true`, and only for a claim that
lists the `dynamic-access` sensor, which no engine sends. Nothing user-visible ships.

- **`bootui-agent`:**
  - `DynamicAccessSensor`: the transformer, its self-test, install, and restore. With `…dynamic-access.extended=true`, it
    also adds the count-only hooks of §1.
  - `DynamicAccessAdvice`: inline advice, with the hook id bound as a constant.
- **`bootui-agent-bridge`: `DynamicAccess`, the recorder.**
  - Its API is `start(token, {seconds ≤ 600, maxEntries ≤ 10,000})`, `stop(token)`, and `status()`.
  - One session runs at a time. It ends at its time limit, which is checked in the advice, at its run's end, or on a
    release.
  - A claim can ask for a session to start as soon as the sensor is installed, with
    `dynamicAccess.startupSeconds`, so startup's accesses are recorded. Accesses made before the install completes,
    within about 250 ms of the claim, are missed. A product that records startup should install synchronously at claim.
  - Each entry is `(kind, type, member, parameter types, caller class)`, with the first caller method, the application
    frame, and a count, never an argument value.
  - The session counts, without recording, these accesses: JDK, tooling, and generated callers; BootUI's own work;
    failed lookups; entries over the cap; and oversized names.
- **Tests.** `DynamicAccessIT` runs `DynamicAccessBehaviors` in forked JVMs against the published jar. It checks 45
  behaviors: caller sensitivity, recording, attribution, privacy, bounds, the run's end, release, and startup sessions.
  It runs them alone and beside OpenTelemetry in both orders. It also checks that every count-only hook is advised, that
  the sensor stays off without the flag, and runs the opt-in benchmark. The tests are green on JDK 17, 21, and 26, with
  the rest of the agent's suites.

Not demonstrated: "both orders" only orders the agents' `premain`. The sensor installs at claim time, after
OpenTelemetry's install, in either order, and OpenTelemetry also retransforms `java.lang.Class`.

## 10. Estimate of the productized version

| Part | Days |
|---|---|
| Ground-truth gate: native builds of M4-20's applications (GraalVM in CI, multi-minute builds), their missing-registration errors, and the recorder's precision and recall against them | 2–4 |
| Recorder: member queries (with the member queried), method-handle lookups attributed to their lookup class, `ClassLoader.loadClass`, `getRecordComponents`, field access, resources, `ObjectStreamClass.initNonProxy`, `ResourceBundle.getBundle`, `Array.newInstance`; cheaper attribution (§4); a synchronous install for startup sessions | 6–8 |
| Privacy: queried member names, resource names, and failed-lookup names are caller strings, masked under the exposure policy, with tests through the API, MCP, and the CLI | 1 |
| Coverage: class-path metadata through an adapter seam for JSON, conditions compared; Spring `RuntimeHints` evaluated at run time, and the rules for code Spring AOT generates; the build's AOT output when present, and recording in AOT mode; Quarkus `@RegisterForReflection` | 4–5 |
| Oracle repository metadata per library, both formats, user-triggered and bounded | 1–2 |
| Engine store under the agent evidence contract (M5-11: exposure, **Clear recording**, memory accounting), fetching a session before a restart forgets it; API on Spring MVC, WebFlux, and Quarkus (start and stop as actions, blocked by the read-only policy; entries; the export); conformance catalog | 3–4 |
| UI: a **Dynamic access** tab in the GraalVM panel (start and stop with bounds, running state, entries with caller, application frame, and what covers them, a filter to uncovered entries, export); browser specs on both stacks | 3–4 |
| MCP and CLI tools (`start_dynamic_access_recording`, `get_dynamic_access`): a new tool schema needs CLI changes | 1–2 |
| Documentation, sample seeds, JDK matrix | 2 |
| **Total, full §5.15 scope** | **23–32** |

A reshaped first version is about **19–24 days**, gate included. It covers Spring only; reflection, proxies, member
queries, method handles, and resources; class-path metadata, the build's AOT output, and `RuntimeHints` evaluated at run
time; no repository lookup; and the UI, export, API, and one MCP read tool. PLAN-v2 budgets 8–10 days for all of M5-9,
M5-9a included, so either version is two to three times what M5-9b was given.

## Recommendation

**Reshape it, take it out of 2.0.0's M5 scope, and run the ground-truth gate before building it.**

M5 does not gate 2.0.0 (D20), so the decision is not about the critical path. It is whether M5-9b belongs in 2.0.0's
M5 scope at all, and at two to three times its budget, it should not.

- **Don't ship it as specified.** The technique works. But recording "reflection whose calling frame is in the
  application or its dependencies" on the JVM reports the framework's own reflection, much of which Spring AOT replaces
  with generated code. The application startup above gave 1,313 entries. Every metadata source the spike could read
  covered at most 742, and the 571 left are mostly Hibernate and Byte Buddy internals. And it missed the Jackson
  binding that does break native images.
- **Don't drop it either.** BootUI is the one place where a JVM run, Spring's hints, and the application's packages
  meet, on any JDK and without installing GraalVM. Its GraalVM panel's static checks already point at the same gaps,
  without proof. A recording that keeps only the accesses that reach application types or come from the application's
  call path, compared with the hints AOT and metadata provide, gives that proof, and an export the developer can
  review.
- **The reshaped scope** differs from §5.15 in four ways. §5.15 already lists member queries, field access, and the
  comparison with AOT hints.
  - Record method handles, and record in AOT mode on the JVM when the build allows it.
  - Filter by the application by default (the target type or the application frame), with the rest one click away.
  - Compare with class-path metadata, the build's AOT output, and `RuntimeHints` evaluated at run time, conditions
    included.
  - Spring first. Show Quarkus recordings, uncompared.
- **The gate, first:** native builds of M4-20's applications, their missing-registration errors, and the recorder's
  precision and recall against them. Its findings are GraalVM panel rows, never a Runtime Insights kind (D36), so D35's
  gate does not apply; this precision and recall is its own usefulness criterion. Build the reshaped version only if the
  gate shows it finds the native failures (the Jackson case among them) with few false alarms.
- **GraalVM's tracing agent stays the reference** for a complete configuration (JNI, FFM, every access). BootUI's
  version is the filtered, explained view of what the developer must act on, and it says so.

## Review

The prototype and these conclusions were rubber-ducked with Claude Opus 5.5. The review found no safety defect in the
advice. It confirmed the following:

- Inline advice adds no frame.
- The exception handlers leave the caller frame unchanged.
- The annotations are kept.
- The recorder never calls user code or records an argument.
- With no session running, the cost is one volatile read and a branch.
- Every bridge entry point catches `Throwable`.
- The bounds hold.
- Sessions end with their run.

Fixed from its findings:

- A reflective `Class.forName` lost its class on JDK 18+. The caller-sensitive adapters are now advised.
- The self-test had no caller-sensitive reflective call. It now has one.
- Startup recording was missing. A claim can now start a session at install.
- Every repeated access walked the full stack. A repeated access now walks only to its caller.
- BootUI's sample applications were classified as tooling.
- A release racing a start could leave a session without a run.
- The last session outlived its claim.
- The walker was first initialized inside application calls.
- Ending a session built its results on an application thread.
- A failed restore was reported as success.

A second pass reviewed these conclusions. It found that the recommendation had no ground truth, which led to the AOT
coverage, AOT-mode recording, and repository measurements of §7, and to the gate. It found contradictory cost figures,
which led to the four-configuration startup measurement and to finding the self-inflicted counts. It also found a
member-query mapping that over-registered, overstated Spring and GraalVM claims, and missing estimate lines; all are
corrected above. It found one regression from the first fixes, now fixed: `status()` built every entry of the last
session on each call. It also flagged the benchmark's limits (§4), the tests' limit on agent order (§9), and the
limits accepted for the spike (§3).
