# Change recipes

Read only the recipe matching the task. Paths below are starting points, not a mandate to read entire modules or copy
their implementation. Recheck the current code and tests before following an example.

## Extract shared behavior or add an SPI

First pin existing behavior with a regression case. Separate framework observation or execution from shared decisions,
ordering, error handling, and DTO assembly. Define or reuse a neutral port and move policy into the engine without
moving framework imports with it.

The cache feature illustrates this boundary:

- [CacheProvider](../../../../bootui-engine/src/main/java/io/github/jdubois/bootui/spi/CacheProvider.java) exposes native
  topology and eviction primitives.
- [CacheService](../../../../bootui-engine/src/main/java/io/github/jdubois/bootui/engine/cache/CacheService.java) owns
  shared orchestration; [CacheServiceTests](../../../../bootui-engine/src/test/java/io/github/jdubois/bootui/engine/cache/CacheServiceTests.java)
  use a fake provider to pin ordering, unavailable state, mutation races, and failures.
- [SpringCacheProvider](../../../../bootui-spring-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/cache/SpringCacheProvider.java)
  and [QuarkusCacheProvider](../../../../bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/cache/QuarkusCacheProvider.java)
  keep native integration outside the engine.

Find all constructor calls and registrations, including both Spring autoconfigurations and Quarkus producers and
deployment registration. Test property-to-policy mapping and adapter discovery separately from pure engine behavior.
Move behavioral tests with the implementation; retain native wiring tests. A cross-stack extraction also requires
the conformance runs specified by repository instructions, even when the JSON is intended to stay unchanged.

## Change a DTO or public response

Trace record construction, serialization, all transport consumers, and availability/error responses before editing.
Identify whether the change is internal or changes field names, null/empty behavior, ordering, types, or enum/status
values on the wire. Do not add Jackson annotations to shared records to repair one adapter.

Use [CoreDtoImmutabilityTests](../../../../bootui-core/src/test/java/io/github/jdubois/bootui/core/dto/CoreDtoImmutabilityTests.java)
for defensive-copy behavior and
[AbstractBootUiApiConformanceTest](../../../../bootui-conformance/src/main/java/io/github/jdubois/bootui/conformance/AbstractBootUiApiConformanceTest.java)
for shared HTTP expectations. Extend the relevant contract assertions or fixture when existing coverage does not
exercise the changed shape. Run that contract through all affected adapters; one serializer's unit test is not
evidence of Jackson 2/3 parity.

Check MCP descriptions/bindings, the generated CLI projection, UI consumption, and documentation when they expose the
response. Follow their path-scoped instructions and the vertical-PR agent's integration checklist rather than
maintaining another list of generation commands here.

## Fix an advisor's false positive or missing evidence

Write down the rule's claim and what observation would actually justify it. Distinguish a configured intent, a
discovered declaration, and observed runtime behavior. Verify version-sensitive framework claims against the root
POM's versions and primary documentation or source, not assumptions from another framework version.

Create fixtures for a justified finding, a plausible counterexample, and unavailable or incomplete evidence. Unknown
must not become healthy through zero clamping, an empty list, a broad catch, or default configuration inference.
Preserve the advisor's established unavailable/partial/skipped semantics rather than inventing a new status.

Inject controlled observations into the scanner or rule. For examples of deterministic scanner and collector
fixtures, consult [MemoryScannerTests](../../../../bootui-engine/src/test/java/io/github/jdubois/bootui/engine/memory/MemoryScannerTests.java)
and [MemoryCollectorTests](../../../../bootui-engine/src/test/java/io/github/jdubois/bootui/engine/memory/MemoryCollectorTests.java).
These are patterns, not a reason to change unrelated memory rules.

When behavior changes, review rule identity, dismissal compatibility, severity, counts, assessment/scoring effects,
and directly coupled advisor documentation. Test observation collection separately from rule evaluation so a perfect
synthetic fixture cannot hide a broken adapter.

## Integrate an optional framework capability

Separate dependency absence from dependency present with no beans/resources, disabled policy, read-only policy, and
genuine execution failure. State the expected result for each before implementing.

For Spring, use the existing context-runner and classloader-filtering patterns, testing MVC and WebFlux wiring where
supported. [HibernateAdvisorAbsenceTest](../../../../bootui-spring-autoconfigure/src/test/java/io/github/jdubois/bootui/autoconfigure/hibernate/HibernateAdvisorAbsenceTest.java)
is an entry point for optional-library absence. A missing-bean fixture alone does not prove classloading safety.

For Quarkus, inspect capability-gated build steps and exclusion of classes importing optional APIs.
[BootUiCacheProducer](../../../../bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/BootUiCacheProducer.java)
illustrates isolated optional wiring. Use the
[integration-test modules](../../../../bootui-quarkus-integration-tests/pom.xml): `base` omits optional extensions,
while capability-specific modules exercise their presence. Use the production-mode fixture when production gating
is affected; a dev/test application cannot prove that production stays dark.

Verify both absent and present classpaths. Do not add the optional library to the shared engine or the absence fixture
to make a failure disappear. Read the current adapter instructions for bean registration, capability gating, and
availability requirements.
