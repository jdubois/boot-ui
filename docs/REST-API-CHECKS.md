# REST API checks

The REST API panel runs a fixed, zero-config ruleset against the host application's compiled web declarations:
Spring MVC and Spring WebFlux controllers, or JAX-RS/Quarkus REST resource methods. It reports declaration conflicts
and conditional design-review prompts, not a verdict on the application's runtime HTTP behavior.

There are **56 stable rule definitions across 8 categories, with 52 potentially emitting rules**. Four definitions
(`RAPI-MAP-008`, `RAPI-NAME-004`, `RAPI-ERR-011`, and `RAPI-DOC-003`) retain their IDs but always return `SKIPPED`.
Their original evidence cannot establish the alleged defect. Their IDs are not reused, so saved dismissals keep
their identity. The [complete audit disposition ledger](#complete-audit-disposition-ledger) records all 56 decisions.

Rules are registered in
[`RestApiRuleRegistry`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/restapi/RestApiRuleRegistry.java)
and implemented by
[`RestApiRules.java`](https://github.com/jdubois/boot-ui/blob/main/bootui-engine/src/main/java/io/github/jdubois/bootui/engine/restapi/RestApiRules.java)
and the category rule classes in the framework-neutral `bootui-engine` module. MVC, WebFlux, and Quarkus share this
catalogue; Spring-only checks use Spring declarations, including when both frameworks occur in the imported model.

## What BootUI does

The scanner resolves application base packages from Spring's `AutoConfigurationPackages` or Quarkus's build-time
Jandex index, imports compiled classes with ArchUnit, and derives a bounded, read-only handler model. It records
observable HTTP methods, paths, binding annotations, media declarations, response shapes, validation annotations,
and declared exception types. Standard and custom JAX-RS `@HttpMethod` annotations are supported. Imports are limited
to application packages, never an unbounded classpath scan.

Scanning is explicit and on demand. Reading the panel does not invoke application handlers, send requests, or start
network work. The last report is cached. ArchUnit and resolvable application base packages are required for availability;
the Spring starter supplies ArchUnit transitively.

### Response, binding, and exception evidence

- **Body and status are separate facts.** `ResponseEntity<T>`, JAX-RS `Response`, and Quarkus `RestResponse<T>` can
  select status dynamically. Spring `HttpEntity<T>` carries headers/body, **not** status authority. Supported
  single-valued async forms such as `Mono<ResponseEntity<T>>`, `CompletionStage<ResponseEntity<T>>`, and
  `Uni<RestResponse<T>>` retain that distinction, as do inverse body-wrapper forms such as `ResponseEntity<Mono<T>>`.
  A multi-valued outer `Flux<ResponseEntity<T>>` is not equivalent to a single response with a streaming body
  ([Spring's single-response requirement][spring-single-response]).
- **Payload classification is bounded.** Common async/reactive wrappers, including Spring async wrappers, Mutiny,
  and Kotlin coroutine wrappers, expose their resolvable payload or element type. Nested `void`/`Void`/`Unit` in
  supported single-valued wrappers is no-body; a collection of nullable elements is not. Entity arrays retain their
  element type. Body-envelope detection includes nested `HttpEntity<Object>` without giving it status authority.
  Streaming shape survives supported envelopes such as `ResponseEntity<Flux<T>>` and `RestResponse<Multi<T>>`;
  `ResponseEntity<List<T>>` remains a collection, not a stream. Binary `byte[]` is not a pageable resource collection.
  Direct Spring `HttpHeaders` is headers-only; `HttpEntity<HttpHeaders>` and `ResponseEntity<HttpHeaders>` instead
  declare a serializable payload.
  Raw JAX-RS `Response` has an unknown body, not an inferred error DTO.
- **Imperative response arguments make the final response unknown.** Servlet `ServletResponse`, `OutputStream`,
  `Writer`, and reactive `ServerHttpResponse`/`ServerWebExchange` signatures can write responses directly. A `void`
  return does not prove an empty wire response. The scanner does not inspect builder chains, filters, advice, or
  emitted bytes to recover their status, headers, or body.
- **Paths are declarations, not full route enumeration.** Root/leaf composition preserves meaningful interior and
  trailing slashes. Spring type/method HTTP-method constraints combine by union. Required, explicitly named Spring
  path bindings are checked against each complete mapping alternative; optional/`Optional` and aggregate map
  bindings do not establish a missing-required-variable failure. Unresolved placeholders and unrooted JAX-RS
  subresource paths remain unknown. JAX-RS header/query parameter bindings are not dispatch conditions.
- **Validation annotations do not prove validation execution.** Reactive request payloads are unwrapped for shape
  checks. Supported Spring validation/meta-`@Validated` declarations are recognized conservatively; JAX-RS uses its
  own entity-validation semantics. Optional Spring primitive `boolean` is not a numeric-null failure. Kotlin
  optional numeric bindings with unobservable source defaults remain unknown rather than guaranteed failures.
- **Exception declarations are not runtime resolution.** Spring `@ExceptionHandler`'s `value` and `exception`
  aliases and Throwable-parameter inference are recognized. Explicit Quarkus mapper exception types take precedence
  over parameter fallback. Bounded hierarchy discovery includes registered inherited JAX-RS mappers and Spring's
  reactive `ResponseEntityExceptionHandler`. Exception-handler media types come from that declaration, not an
  unrelated controller `@RequestMapping`. Unknown error bodies do not count as contradictory known shapes.

Kotlin support reads bytecode by class name without adding a Kotlin runtime dependency. A supported `suspend`
signature hides its compiler-supplied `Continuation` parameter and recovers the payload from its generic argument.
Compiler-generated bridges and DTO accessors are excluded where recognized. This does not constitute complete Kotlin
source reconstruction; arbitrary generic substitution and source-default recovery remain outside this model.

### Complete, partial, and skipped analysis

The public report contains findings only: internal `PASS`, `SKIPPED`, and `ERROR` outcomes are not finding rows.
An **observed import, model, required-evidence extraction, or rule-evaluation failure produces `scan.status = PARTIAL`**,
with a bounded, sanitized explanation and any reliable findings retained. An import failure must not appear as a
clean empty `SCANNED` report. A successfully analyzed application with no eligible controllers can still have no
findings. Missing, empty, or malformed base-package names are rejected before importing and produce `PARTIAL` on an
attempted scan; they never broaden the import to the classpath root. A successful import that finds no supported
controllers remains `SCANNED`. Before an attempted scan, the initial report remains `NOT_SCANNED` and can explain a
base-package discovery failure.

Incomplete extraction suppresses absence-based ERR-001 and ERR-009 conclusions while preserving reliable positive
findings. An unresolved mapper type that the bounded model intentionally cannot resolve also makes ERR-009 `SKIPPED`,
but that uncertainty alone is not an extraction failure and does not automatically make the scan `PARTIAL`.

Intentional inapplicability is different: retired emissions, unsupported framework facts, and genuinely unknown dynamic
responses return `SKIPPED` where necessary and do **not** automatically make the scan partial. `SCANNED` means analysis
completed within this bounded model, not that every runtime endpoint or behavior was enumerated.

## What BootUI does not do

- It does not modify, compile, instrument, or execute application code during a scan.
- It does not check authentication, authorization, or CORS; these remain Security panel concerns.
- It excludes MicroProfile `@RegisterRestClient` interfaces, which represent outbound clients rather than inbound resources.
- It does not inspect actual response content, validation execution, database result limits, caching policy, retry
  deduplication, generated OpenAPI documents, or headers supplied elsewhere.
- It does not resolve every Spring composed/inherited mapping, functional endpoint, runtime route registration,
  arbitrary generic hierarchy, or dynamic JAX-RS subresource locator graph.
- It does not infer omitted parameter names from unavailable source metadata or interpret unresolved `${...}` and
  `#{...}` path expressions as literal naming violations.
- It does not replace contract tests or scope/selector/precedence-aware exception resolution. The separate declared
  error-contract catalogue is not a claim that the REST rule model resolves every runtime exception.

## Severity scale

Findings use `HIGH`, `MEDIUM`, `LOW`, and `INFO`; `CRITICAL` is supported but unused here. Severity includes evidence
confidence: a mutation-like method name is weaker evidence than a conflicting required path binding.

| Scope | HIGH | MEDIUM | LOW | INFO | Total |
| --- | --- | --- | --- | --- | --- |
| Stable definitions, including retired emissions | 7 | 6 | 20 | 23 | 56 |
| Potentially emitting rules | 6 | 6 | 18 | 22 | 52 |

The documentation checks are gated by the optional OpenAPI integration: Swagger/springdoc annotation availability on
Spring or MicroProfile OpenAPI on Quarkus. Both annotation families are recognized without making either dependency
mandatory. Missing annotations are not proof that generated or static documentation is absent.

The shared advisor score weights concrete findings, not just violated rule IDs; dismissing a rule removes its
findings from that calculation. Only a complete `SCANNED` report is score-eligible. This audit does not change the
score formula or UI.

## Routing & HTTP method mapping

### RAPI-MAP-001 - Use HTTP-method-specific mappings

- **Severity**: MEDIUM
- **Detects**: A Spring mapping with no HTTP-method constraint after combining type and method declarations.
  JAX-RS verb declarations are not subject to this Spring-specific recommendation.
- **Recommendation**: State the accepted verbs with a composed mapping or `@RequestMapping(method = ...)`.
  An unconstrained mapping does not itself prove a state-changing GET.
- **Learn more**: [Spring mapping conditions][spring-mapping].

### RAPI-MAP-002 - No duplicate route mappings

- **Severity**: HIGH
- **Detects**: Exact duplicate observable dispatch conditions, including path, HTTP method, media types, and applicable
  Spring params/headers/version conditions. Meaningful slash differences are preserved. JAX-RS query/header bindings
  do not disambiguate routes; incomplete subresource paths are not compared as complete routes.
- **Recommendation**: Give each exact dispatch combination one handler. This is not a complete overlap or ambiguity detector.
- **Learn more**: [Spring mapping conditions][spring-mapping]; [Jakarta REST matching][jaxrs-matching].

### RAPI-MAP-003 - Review mutation-like names on GET handlers

- **Severity**: LOW
- **Detects**: A GET handler with a create/update/delete/save-style name. Ambiguous prefixes such as `postProcess`,
  `putAside`, and `patchVersion` are excluded. A name is not evidence that a write executes.
- **Recommendation**: Review requested effects against GET's safety requirement; use a mutating HTTP method if the
  operation really changes resource state. Incidental logging does not make a safe method unsafe.
- **Learn more**: [RFC 9110 §9.2.1][http-safe].

### RAPI-MAP-004 - Prefer a class-level base path

- **Severity**: LOW
- **Detects**: Spring controller mapping alternatives repeat a common leading segment without a type-level base path.
  All alternatives must support that conclusion. Controller interfaces, including generated spec-first interfaces,
  are exempt; hand-written interfaces receive the same exemption.
- **Recommendation**: Optionally hoist the common prefix to the class. This is a maintainability preference, not HTTP correctness.
- **Learn more**: [Spring mapping declarations][spring-mapping].

### RAPI-MAP-005 - Review trailing and doubled slashes

- **Severity**: INFO
- **Detects**: Raw mapping declarations contain trailing or doubled slashes.
- **Recommendation**: Choose a deliberate path convention. Slash variants can identify distinct paths; the style
  check does not normalize route identity or transfer Spring's matching behavior to JAX-RS.
- **Learn more**: [Spring path matching][spring-mapping].

### RAPI-MAP-006 - Required Spring path bindings match each path

- **Severity**: HIGH
- **Detects**: An explicitly named, required Spring `@PathVariable` is absent from a complete individual mapping
  alternative. Optional/`Optional`, aggregate map bindings, and unresolved paths do not establish this failure.
- **Recommendation**: Correct the token or binding, or deliberately make the binding optional for alternatives
  without that token. JAX-RS binding/default semantics do not imply Spring's missing-required-variable error.
- **Learn more**: [Spring `PathVariable.required`][spring-pathvar]; [Jakarta REST `PathParam`][jaxrs-pathparam].

### RAPI-MAP-007 - Review request entities on GET/HEAD/DELETE

- **Severity**: MEDIUM
- **Detects**: A declared request entity on GET, HEAD, or DELETE.
- **Recommendation**: Prefer query/path parameters or an appropriate body-oriented operation for interoperable APIs.
  RFC 9110 gives this content no generally defined semantics; it is not a categorical prohibition. A private client/server
  agreement may be intentional but does not establish support by intermediaries.
- **Learn more**: [GET][http-get], [HEAD][http-head], and [DELETE][http-delete] in RFC 9110.

### RAPI-MAP-008 - Mutating item methods target an identified resource

- **Severity**: LOW (retained metadata)
- **Disposition**: Always `SKIPPED`; emissions retired. A literal URI such as `/configuration` already identifies a
  resource. Absence of `{id}` cannot establish accidental collection-wide mutation.
- **Recommendation**: Review resource semantics in the API contract, not through an English singleton/bulk allowlist.
  The rule and dismissal ID remain reserved for this original concern.
- **Learn more**: [RFC 5789's literal-URI PATCH example][patch-example].

### RAPI-MAP-009 - No duplicate Spring path-variable tokens

- **Severity**: HIGH
- **Detects**: Repeated capture names in a Spring path template, which its parser rejects.
- **Recommendation**: Use distinct Spring token names, such as `{userId}` and `{orderId}`. JAX-RS is skipped:
  repeated scoped names bind the latest occurrence rather than following Spring's parser rule.
- **Learn more**: [Spring path parser][spring-parser]; [Jakarta REST `PathParam`][jaxrs-pathparam].

### RAPI-MAP-010 - Review catch-all REST mappings

- **Severity**: INFO
- **Detects**: Spring `/**` or `{*path}`, or JAX-RS regex catch-alls such as `{path:.*}` and `{path:.+}`.
  A constrained token such as `{id:[0-9]+}` is not a catch-all.
- **Recommendation**: Review whether a broad routing surface is intentional. A catch-all does not prove shadowing
  of more-specific routes or that typos return 200.
- **Learn more**: [Spring pattern specificity][spring-mapping]; [Jakarta REST matching][jaxrs-matching].

### RAPI-MAP-011 - Review deeply nested resource paths

- **Severity**: INFO
- **Detects**: More than three visible collection/`{id}` pairs in a known path.
- **Recommendation**: Consider a flatter resource structure if it improves usability. Three levels is this
  heuristic's style threshold, not an HTTP limit; an unknown root cannot establish full nesting depth.
- **Learn more**: [RFC 9110 resource identification][http-resources] (no three-level requirement).

## Naming & resource design

### RAPI-NAME-001 - Consider noun-oriented resource paths

- **Severity**: INFO
- **Detects**: Action-like literal segments, using an English-name heuristic. Ambiguous `post`, `put`, and `patch`
  are flagged only in forms such as `/postMessage`, not a noun-like `/blog/post/{id}`.
- **Recommendation**: Prefer nouns where useful, while allowing intentional command/action resources. Verb spelling
  does not violate HTTP or by itself establish poor resource design.
- **Learn more**: [RFC 9110 resource identification][http-resources] (no noun-only grammar).

### RAPI-NAME-002 - Consider plural collection names

- **Severity**: INFO
- **Detects**: A known collection-shaped response with a singular-looking endpoint name, excluding recognized
  uncountable/collective words such as `history`, `inventory`, `staff`, and `news`.
- **Recommendation**: Use a consistent vocabulary appropriate to the API's language. Return shape and English
  spelling do not prove runtime cardinality; binary bodies are not resource collections.
- **Learn more**: [RFC 9110 resource identification][http-resources] (pluralization is optional style).

### RAPI-NAME-003 - Consider lowercase kebab-case paths

- **Severity**: INFO
- **Detects**: Literal camelCase, snake_case, or uppercase path segments; unresolved expressions are excluded.
- **Recommendation**: Choose a consistent convention. Case-sensitive paths and non-kebab spellings are legitimate,
  not protocol defects.
- **Learn more**: [RFC 3986 §6.2.2.1][uri-case].

### RAPI-NAME-004 - No format-extension suffixes in path segments

- **Severity**: LOW (retained metadata)
- **Disposition**: Always `SKIPPED`; emissions retired. Explicit `/export.json` and `/schema.xml` mappings are valid.
  Removal of implicit suffix matching does not invalidate literal dotted routes.
- **Recommendation**: Choose explicit representations or negotiated media types deliberately; no migration is
  inferred merely from a filename suffix. The dismissal ID remains unchanged.
- **Learn more**: [Spring 7.0.9 literal dotted-path tests][spring-dotted].

## Status codes & responses

### RAPI-RESP-001 - Review the default status of creation-like POST handlers

- **Severity**: LOW
- **Detects**: A creation-like method name on a POST with an apparent default 200, not an explicitly chosen status.
  Dynamic status envelopes and imperative response arguments prevent that conclusion.
- **Recommendation**: Use 201 for completed creation where appropriate; 202 can represent accepted asynchronous work.
  Creation intent is inferred from a name, and Location is conditional guidance rather than universally required.
- **Learn more**: [RFC 9110 POST][http-post] and [201 Created][http-created].

### RAPI-RESP-002 - Review default empty DELETE responses

- **Severity**: LOW
- **Detects**: A Spring DELETE with a no-body return and apparent default status, excluding explicit statuses,
  dynamic status envelopes, and direct response-writing arguments.
- **Recommendation**: Consider an explicit 204 when deletion completed without a representation. Explicit 202 and
  other deliberate statuses are not accidental defaults. JAX-RS void methods already default to 204.
- **Learn more**: [RFC 9110 DELETE][http-delete]; [Jakarta REST return semantics][jaxrs-return].

### RAPI-RESP-003 - Prefer informative response-envelope body types

- **Severity**: LOW
- **Detects**: A supported body envelope has a raw, wildcard, or `Object` body contract, including resolvable
  single-value async envelopes and nested `HttpEntity<Object>`. Body-envelope presence does not imply status authority.
  Plain non-generic JAX-RS `Response` is not a raw Spring generic.
- **Recommendation**: Supply a concrete DTO type where practical, or document a deliberate dynamic schema.
  Generic erasure limits inference but does not prove an undocumented API.
- **Learn more**: [Spring response envelopes][spring-response]; [OpenAPI schemas][openapi-schema].

### RAPI-RESP-004 - Consider structured read representations

- **Severity**: INFO
- **Detects**: A GET with a known bare String or primitive body, without an explicit `text/*` media declaration.
- **Recommendation**: Consider a DTO for an evolving contract. Scalars are valid HTTP/JSON representations, and
  a structured envelope is optional.
- **Learn more**: [RFC 9110 representations][http-representations].

### RAPI-RESP-005 - Review default no-body GET declarations

- **Severity**: LOW
- **Detects**: A Spring GET with a resolvable no-body result and apparent default status. Explicit statuses,
  dynamic envelopes, and imperative response arguments are excluded; nested single-value Void/Unit is recognized.
- **Recommendation**: Confirm that a bodyless read is intended, and declare the desired status or representation.
  This does not prove an empty wire response. JAX-RS void's default 204 is not reported as Spring's default 200.
- **Learn more**: [Spring return handling][spring-returns]; [Jakarta REST return semantics][jaxrs-return].

### RAPI-RESP-006 - 204 declarations must not promise content

- **Severity**: HIGH
- **Detects**: A declared 204 with a content-capable return, where neither a dynamic status envelope nor an
  imperative response path makes the conclusion unknown. Plain `HttpEntity<T>` does not override annotation status;
  supported no-body wrappers and headers-only results are not content-capable.
- **Recommendation**: Align the declared return with 204's no-content requirement, or choose a content-bearing
  status. The finding concerns contradictory declarations, not proof of transmitted forbidden bytes or a non-null result.
- **Learn more**: [RFC 9110 §15.3.5][http-no-content].

### RAPI-RESP-007 - Review method-level status and response-envelope overlap

- **Severity**: MEDIUM
- **Detects**: A Spring method-level `@ResponseStatus` combined with a status-bearing `ResponseEntity`, including
  supported single-value async wrapping. `HttpEntity` is not status-bearing; class-level defaults are not flagged.
- **Recommendation**: Review status ownership and the native framework's dispatch behavior. In normal entity handling
  the envelope selects status; a `@ResponseStatus(reason = ...)` error-response path can short-circuit processing.
  The annotation is not universally ignored or redundant, so blanket removal is not the recommendation.
- **Learn more**: [Spring `ResponseStatus`][spring-status]; [MVC entity processing][spring-entity-processor].

### RAPI-RESP-008 - Consider discoverability for declared 201 responses

- **Severity**: INFO
- **Detects**: A plain-body declared 201 without a visible header-setting response path, as an optional review prompt.
- **Recommendation**: Consider Location when a newly created resource differs from the target URI. RFC 9110 uses
  the target URI when Location is absent. Filters/advice may add headers; this is not a missing-Location finding.
- **Learn more**: [RFC 9110 §15.3.2][http-created].

### RAPI-RESP-009 - Review dedicated HEAD handler efficiency

- **Severity**: INFO
- **Detects**: A dedicated HEAD handler with a known content-capable declaration. Shared GET/HEAD mappings,
  headers-only results, no-body wrappers, and Unit are excluded.
- **Recommendation**: Avoid unnecessary body construction when metadata alone suffices, or let the framework derive
  HEAD from GET. Framework suppression means the signature does not prove content is sent on the wire.
- **Learn more**: [RFC 9110 HEAD][http-head]; [Spring HEAD suppression][spring-head].

## Input validation & binding

### RAPI-VALID-001 - Review request-payload cascade validation

- **Severity**: LOW
- **Detects**: A complex request payload without a recognized cascade-validation declaration, including payloads
  inside supported reactive wrappers. Spring annotation conventions are not imposed on JAX-RS.
- **Recommendation**: If DTO field constraints should cascade, use the framework's supported validation trigger.
  Missing `@Valid` does not prove unchecked input: direct constraints and programmatic validation may be intentional,
  and a cascade annotation alone does not prove constraints exist or execute.
- **Learn more**: [Spring validation annotation recognition][spring-validation]; [Jakarta REST entity validation][jaxrs-validation].

### RAPI-VALID-002 - Avoid binding requests directly to JPA entities

- **Severity**: HIGH
- **Detects**: A resolvable request payload, including supported reactive payloads, is a JPA entity.
- **Recommendation**: Consider a request DTO and explicit mapping to reduce persistence coupling and over-posting risk.
  The signature does not prove every persistent field is writable or that any actual disclosure occurred.
- **Learn more**: [Spring data-binding design guidance][spring-binding].

### RAPI-VALID-003 - Optional Spring numeric parameters need a nullable/defaulted binding

- **Severity**: MEDIUM
- **Detects**: An optional Java numeric primitive Spring binding with no nonblank default. Omission cannot bind null,
  and a literal empty/blank default can fail numeric conversion even when `required` retains its annotation default.
  Primitive boolean is excluded because Spring supplies false; uncertain Kotlin source-default
  cases are not reported as guaranteed numeric failures.
- **Recommendation**: For the applicable Java numeric case, use a boxed type or an explicit default.
  This does not transfer Spring's resolver behavior to JAX-RS.
- **Learn more**: [Spring named-value resolver][spring-named-values]; [WebFlux named-value resolver][spring-reactive-named-values].

### RAPI-VALID-004 - Review aggregate query-map contracts

- **Severity**: LOW
- **Detects**: An unnamed Spring `@RequestParam Map`/`MultiValueMap` aggregate binding. An explicitly named map uses
  conversion for that parameter and is not classified as binding every query parameter.
- **Recommendation**: Consider typed parameters or an explicit allowlist/schema. Maps can be documented and validated;
  this signature alone does not establish those policies.
- **Learn more**: [Spring request parameters][spring-requestparam].

### RAPI-VALID-005 - Consider retry deduplication for creation-like POSTs

- **Severity**: INFO
- **Detects**: A creation-like POST name without a visible Idempotency-Key header binding, using Spring or JAX-RS
  header annotations.
- **Recommendation**: Review retry behavior where duplicate creation matters. Natural keys, filters, gateways, or
  application logic may already deduplicate; absence of an argument does not prove unsafe retries. The
  Idempotency-Key proposal is draft/convention guidance, not an HTTP requirement.
- **Learn more**: [Idempotency-Key draft history][idempotency-draft].

## DTO & payload contracts

### RAPI-DTO-001 - Avoid persistence entities in responses

- **Severity**: HIGH
- **Detects**: A known response payload or collection/array element is a JPA entity, including supported wrapped returns.
- **Recommendation**: Consider DTOs to isolate persistence structure and serialization side effects. Lazy loads and
  internal-field exposure are risks, not observed queries or disclosures; serializer policy is not inspected.
- **Learn more**: [Spring response-body handling][spring-responsebody].

### RAPI-DTO-002 - Prefer informative response body types

- **Severity**: LOW
- **Detects**: A known body uses Map, Object, or JsonNode, including Jackson 2's `com.fasterxml.jackson` and Jackson 3's
  `tools.jackson` type names.
- **Recommendation**: Prefer a typed DTO when it improves inference, or explicitly document the dynamic schema.
  OpenAPI can describe arbitrary objects; these declarations do not prove missing documentation.
- **Learn more**: [OpenAPI Schema Object][openapi-schema].

### RAPI-DTO-004 - Consider immutable response DTOs

- **Severity**: INFO
- **Detects**: A known response DTO exposes public setters.
- **Recommendation**: Consider Java records or immutable DTOs, including Kotlin read-only properties. No observed
  setter is not proof of complete immutability; the model does not inspect every mutation path.
- **Learn more**: [Java record classes][java-record].

### RAPI-DTO-005 - Consider java.time in response DTOs

- **Severity**: LOW
- **Detects**: Response DTO fields use `java.util.Date` or `Calendar`. This rule does not inspect request-only DTO fields.
- **Recommendation**: Consider `Instant`, `LocalDate`, or another appropriate `java.time` type for explicit temporal
  semantics. This is not proof of serializer failure, nor does Boot rewrite application field types.
- **Learn more**: [Java date/time API][java-time].

## Pagination & collections

### RAPI-PAGE-001 - Review collection reads without visible pagination

- **Severity**: LOW
- **Detects**: A collection-shaped GET without recognized paging inputs such as Pageable, page/size, limit/offset,
  or cursor/after/before. Binary bodies and streaming return types with explicit SSE, NDJSON, or JSON-sequence media
  are excluded, including streams inside supported response envelopes. An envelope-wrapped List remains a collection
  and is not exempt merely because its outer type is ResponseEntity.
- **Recommendation**: Confirm bounds or a streaming contract. A collection declaration cannot prove an unbounded
  database load; limits may be fixed or implemented elsewhere. A reactive wrapper alone does not establish streaming.
- **Learn more**: [Spring Data web/paging support][spring-paging].

### RAPI-PAGE-002 - Preserve paging metadata for Pageable handlers

- **Severity**: LOW
- **Detects**: A Spring handler accepts Pageable but returns a plain collection/array rather than a known paging
  representation. Unknown JAX-RS paging-envelope conventions are `SKIPPED`.
- **Recommendation**: Return a stable pagination envelope, such as a suitable PagedModel, or document header links.
  Do not expose PageImpl serialization as a universal fix: Spring Data warns its representation is unstable, and
  Slice does not promise total counts.
- **Learn more**: [Spring Data stable page representations][spring-paging].

### RAPI-PAGE-003 - Review pagination vocabulary differences

- **Severity**: INFO
- **Detects**: Recognized page/size, offset/limit, or cursor/after/before families differ across handlers.
  Classification selects a family by priority rather than modeling every simultaneous pagination mode.
- **Recommendation**: Prefer consistency where workloads are comparable; different pagination strategies can be
  deliberate. HTTP does not mandate one vocabulary.
- **Learn more**: [Pagination design guidance][pagination-style] (optional style).

## Versioning & content negotiation

### RAPI-VER-001 - Review absent or uneven version signals

- **Severity**: INFO
- **Detects**: No recognized version signal, or signals on only some handlers. Signals include `/vN`, positive
  version-header/query declarations, genuinely versioned media types, and Spring version conditions.
  An unversioned vendor media type or a negated condition is not evidence of versioning.
- **Recommendation**: Choose a versioning policy if the API needs one. The Spring integration also considers supported
  versioning configuration for the **actual active MVC or WebFlux application context**; inactive-stack properties,
  arbitrary `use.*` keys, and configuration presence alone are not proof of a working resolver. JAX-RS bindings
  are version hints, not dispatch constraints.
- **Learn more**: [Boot MVC version properties][boot-mvc]; [Boot WebFlux version properties][boot-webflux].

Boot 4.1.1 declares separate `spring.mvc.apiversion` and `spring.webflux.apiversion` namespaces. The integration selects
the latter for an active `ReactiveWebApplicationContext`, not merely because WebFlux classes or properties exist.
Within the selected namespace, `supported` is a list of strings, `default`, `use.header`, and `use.query-parameter` are
strings, `use.path-segment` is an integer, and `use.media-type-parameter` maps media types to parameter names.
For example, `spring.webflux.apiversion.use.media-type-parameter[application/json]=v` declares a media-type-parameter
resolver input. `use.path` and `use.media-type` are not the Boot 4.1.1 property names. These configuration facts are
bounded versioning hints, not a runtime request proving that version selection works.

### RAPI-VER-002 - Consider explicit consumes declarations

- **Severity**: LOW
- **Detects**: A POST/PUT/PATCH request-entity declaration without an explicit consumes constraint.
- **Recommendation**: Declare supported formats when useful to the contract. Absence of consumes does not make every
  media type readable: Spring converters/readers and JAX-RS providers still constrain decoding.
- **Learn more**: [Spring media mapping][spring-mapping]; [Jakarta REST media declarations][jaxrs-media].

### RAPI-VER-003 - Review wildcard media ranges

- **Severity**: INFO
- **Detects**: Broad consumes/produces ranges such as `*/*` or `application/*`.
- **Recommendation**: Prefer concrete types when broad matching is unintended. Wildcards, including structured-suffix
  ranges where supported, are legitimate negotiation behavior, not disabled negotiation.
- **Learn more**: [RFC 9110 Accept][http-accept]; [Jakarta REST media declarations][jaxrs-media].

### RAPI-VER-004 - State the PATCH document format

- **Severity**: INFO
- **Detects**: Missing or broad PATCH consumes declarations, not merely a non-JSON format.
- **Recommendation**: State a concrete supported format and its semantics. JSON Patch, Merge Patch, documented plain
  JSON, XML, vendor, binary, and parameterized concrete media types can all be intentional. RFC 5789 requires no
  single default patch format.
- **Learn more**: [RFC 5789 §2][patch] and [its non-JSON example][patch-example].

### RAPI-VER-005 - Review inconsistent produces declarations

- **Severity**: LOW
- **Detects**: Some body-producing handlers in a controller declare produces while others omit it. No-body,
  headers-only, and unknown imperative responses do not establish a missing body-media declaration.
- **Recommendation**: Consider consistent explicit media declarations. Writers/providers can still select supported
  formats without them, so this is not proof of an inconsistent wire contract.
- **Learn more**: [Spring media mapping][spring-mapping]; [Jakarta REST media declarations][jaxrs-media].

### RAPI-VER-006 - Review mixed versioning strategies

- **Severity**: INFO
- **Detects**: Different recognized path, header, query-parameter, or versioned-media signals across handlers, using
  the same positive-signal classification as VER-001. Header and query parameters are separate transport strategies;
  vendor prefixes alone do not count.
- **Recommendation**: Prefer a coherent policy where appropriate. Spring's `version` condition is not a separate
  transport strategy from its configured resolver. Binding hints do not prove runtime version selection.
- **Learn more**: [Spring API versioning][spring-versioning].

## Error handling & documentation

### RAPI-ERR-001 - Review application-wide exception handling declarations

- **Severity**: INFO
- **Detects**: Controllers exist but no recognized application-wide advice/mapper declaration is found. Discovery
  includes Spring MVC/WebFlux ResponseEntityExceptionHandler advice, registered inherited JAX-RS ExceptionMapper
  types, and Quarkus ServerExceptionMapper declarations.
- **Not evaluated**: Incomplete extraction cannot support an absence finding. With a true aggregate handling flag,
  mixed Spring/JAX-RS declarations are also `SKIPPED`: the flag cannot prove application-wide handling separately for
  each stack. Pure JAX-RS with a true flag but no mapper declarations is likewise unknown. Recognized inherited Spring
  reactive advice can pass without enumerated exception methods; a false aggregate flag in an otherwise complete
  mixed-framework model can still support the absence-review prompt.
- **Recommendation**: Review the intended error policy. Application-wide advice is optional; framework defaults and
  local handlers may already be sufficient. Absence from this model does not prove absence of error handling.
- **Learn more**: [Spring reactive exception advice][spring-reactive-advice]; [Jakarta REST exception mapping][jaxrs-exceptions].

### RAPI-ERR-002 - Prefer informative throws declarations

- **Severity**: LOW
- **Detects**: A handler declares `throws Exception` or `Throwable`.
- **Recommendation**: Prefer specific declared failures when useful to callers and maintainers. This is not an HTTP
  violation; absence of a throws clause, especially in Kotlin, is not proof that no failures occur.
- **Learn more**: [Java throws clauses][java-throws].

### RAPI-ERR-003 - Consider Spring ProblemDetail convenience types

- **Severity**: INFO
- **Detects**: Informative Spring error-body declarations use alternative shapes rather than known
  ProblemDetail/ErrorResponse types. View, no-body, dynamic, and unknown returns do not establish this comparison;
  JAX-RS is skipped because arbitrary DTO schema compliance is not observable.
- **Recommendation**: Consider Spring's convenience types if adopting RFC 9457. Problem Details is optional, and a
  custom DTO can implement it; neither type choice nor a return signature proves wire conformance.
- **Learn more**: [RFC 9457][problem-details].

### RAPI-ERR-004 - Make error-handler status ownership explicit

- **Severity**: MEDIUM
- **Detects**: An applicable Spring body-rendering exception handler has no observable explicit error-status path.
  Supported async status envelopes, error-response types, native response arguments, and no-body results are
  distinguished; unknown error-body shapes are excluded, and plain HttpEntity does not supply status.
- **Recommendation**: Declare the intended status through an appropriate annotation, response envelope, or error type.
  Unknown Quarkus mapper semantics are skipped rather than assigned Spring's default 200.
- **Learn more**: [Spring exception handling][spring-exceptions]; [Jakarta REST exception mapping][jaxrs-exceptions].

### RAPI-ERR-005 - Review broad handlers with fixed non-5xx statuses

- **Severity**: LOW
- **Detects**: A broad Exception/Throwable handler has a known fixed non-5xx status without a dynamic status or
  imperative response path overriding that inference.
- **Recommendation**: Review whether unrelated failures should share that status. A sole ResponseEntity/Response
  mapper can choose many statuses; a sole 500 fallback does not prove all errors collapse into one response.
- **Learn more**: [Spring reactive exception advice][spring-reactive-advice]; [RFC 9110 server errors][http-server-errors].

### RAPI-ERR-006 - Review mixed Spring error-declaration approaches

- **Severity**: INFO
- **Detects**: The application uses Spring ProblemDetail support and a `@ResponseStatus` exception class actually
  appears in a handler's declared throws, has known ancestry, and has no declared Spring handler covering that class,
  an ancestor, or broad Exception. Unused or possibly covered annotations do not establish this finding;
  uncertain cases and JAX-RS are skipped.
- **Recommendation**: Consider ErrorResponseException if it simplifies a deliberate Spring error policy. Advice may
  already translate annotated exceptions to problem documents; coexistence does not prove inconsistent payloads,
  and RFC 9457 mandates no Spring type.
- **Learn more**: [Spring error responses][spring-errors]; [RFC 9457][problem-details].

### RAPI-ERR-007 - Consider Retry-After for declared 429/503 statuses

- **Severity**: INFO
- **Detects**: An observable declared 429 or 503 offers a retry-policy review opportunity, excluding dynamic status
  paths that prevent treating the annotation as the effective status.
- **Recommendation**: Consider Retry-After where a useful retry time is known. It is optional, and headers may be
  added imperatively or elsewhere; this scanner cannot prove Retry-After is absent.
- **Learn more**: [RFC 9110 §10.2.3][http-retry]; [RFC 6585 §4][http-429].

### RAPI-ERR-008 - Consider structured error bodies

- **Severity**: LOW
- **Detects**: A known body-rendering exception handler returns a raw String, not a view name or unknown body.
- **Recommendation**: Consider a typed error DTO or Problem Details if clients need stable fields. Intentional
  text errors are valid; this is not an RFC 9457 conformance failure.
- **Learn more**: [RFC 9457][problem-details]; [Spring exception handling][spring-exceptions].

### RAPI-ERR-009 - Review declared exceptions without a mapping in the model

- **Severity**: MEDIUM
- **Detects**: An endpoint's declared application exception has no matching handler/mapper or applicable
  status-annotated exception declaration in the imported model; recognized supertypes count. Alias, inferred,
  explicit mapper, and bounded inherited exception types participate. The rule stays silent if no exception
  handlers are declared, leaving the broad review to ERR-001.
- **Not evaluated**: Incomplete extraction or any imported handler/mapper with unresolved handled-exception types
  prevents an absence-based finding. The latter is an intentional `SKIPPED` limitation, not automatically `PARTIAL`;
  an observed required-evidence extraction failure is what makes the scan partial.
- **Recommendation**: Confirm the intended error mapping. The global declaration comparison is not scope-, selector-,
  or precedence-aware and cannot prove runtime fall-through, successful resolution, or absence of failures.
- **Learn more**: [Spring exception handling][spring-exceptions]; [Jakarta REST exception mapping][jaxrs-exceptions].

### RAPI-ERR-010 - Review differing known error contracts

- **Severity**: LOW
- **Detects**: Informative body-rendering exception declarations differ in known body categories under compatible or
  unspecified media types. Dynamic Map, Object, JAX-RS Response, raw envelopes, JsonNode, and other unknown shapes
  do not count as an incompatible second contract. Spring error media evidence comes from `@ExceptionHandler` itself.
- **Recommendation**: Confirm clients can handle the intended formats. Negotiated representations can legitimately
  differ: distinct negotiation media alone no longer produce a finding. Category comparison does not inspect fields,
  emitted content, or complete runtime schemas. Different custom DTO names alone are not different categories.
- **Learn more**: [RFC 9457, including XML][problem-details]; [Spring exception media negotiation][spring-exceptions].

### RAPI-ERR-011 - Exception handlers do not expose stack traces

- **Severity**: HIGH (retained metadata)
- **Disposition**: Always `SKIPPED`; emissions retired. Calling `printStackTrace` or `getStackTrace` may support server
  logging and does not prove a trace reaches the HTTP response. The bounded call graph has no return-value data flow.
- **Recommendation**: Keep response diagnostics non-revealing and log details safely, but do not infer a leak from an
  accessor call. No response capture or new security rule is introduced; the dismissal ID is preserved.
- **Learn more**: [RFC 9457 security considerations][problem-security].

### RAPI-DOC-001 - Consider explicit operation documentation

- **Severity**: INFO
- **Detects**: With the optional documentation integration available, a non-hidden handler lacks a recognized
  explicit Operation annotation.
- **Recommendation**: Add useful summaries/descriptions where needed. Generated operations, static documents,
  model readers, and filters may already supply documentation; no annotation does not mean no documented endpoint.
- **Learn more**: [MicroProfile OpenAPI generation][mp-generation]; [OpenAPI Operation Object][openapi-operation].

### RAPI-DOC-002 - Consider explicit operation grouping

- **Severity**: INFO
- **Detects**: With the optional documentation integration available, no recognized explicit grouping is present.
  Swagger and MicroProfile Tag annotations, repeatable tag containers, and Operation tag lists count.
- **Recommendation**: Add meaningful tags if default grouping is insufficient. Tags are optional and generators
  can group automatically; this does not prove missing groups in the final document.
- **Learn more**: [OpenAPI Operation Object][openapi-operation]; [MicroProfile OpenAPI processing][mp-processing].

### RAPI-DOC-003 - Deprecated endpoints signal deprecation to HTTP clients

- **Severity**: INFO (retained metadata)
- **Disposition**: Always `SKIPPED`; emissions retired. Missing `@Operation(deprecated = true)` cannot prove missing
  client signals: SmallRye recognizes Java/Kotlin deprecation, and static documents or filters can also supply it.
- **Recommendation**: Review generated documentation and, where useful, Deprecation/Sunset response headers rather
  than requiring a redundant annotation. The dismissal ID remains unchanged.
- **Learn more**: [SmallRye 4.2.4 deprecation handling][smallrye-deprecation]; [RFC 9745][http-deprecation]; [RFC 8594][http-sunset].

## Complete audit disposition ledger

This is the disposition of the **56-rule accuracy audit in [#962](https://github.com/jdubois/boot-ui/issues/962)**,
not a validation-run report. It replaces the earlier statement that all 53 then-active rules and severities were
retained unchanged. No IDs were added or repurposed. "Retire" below means keep the definition and return `SKIPPED`.

### Sources and version applicability

The audit baseline is Spring Boot **4.1.1 → Spring Framework 7.0.9**
([Boot release properties][boot-versions]) and Quarkus **3.33.3.1 → SmallRye OpenAPI 4.2.4**
([Quarkus BOM][quarkus-bom]), absent application overrides. Spring 7.0.9 mapping/response sources and SmallRye 4.2.4
sources are pinned below. Some resolver/annotation references are the generic **Spring 7.0.0** documentation/source
baseline; they explain the semantic boundary, not a claim that 7.0.0 is the shipped dependency. Applicability must be
pinned by tests against the actual 7.0.9 dependency. This ledger does not claim those tests were run.

Jakarta REST references cover the relevant 3.1/4.0 return, binding, matching, and mapper semantics, not an assertion that
every framework implements every optional feature identically. OpenAPI 3.1.1 and MicroProfile OpenAPI 4.0 references
distinguish schemas/generated documents from optional annotation enrichment. HTTP RFCs are normative only for the
requirements they actually state; resource naming, pagination vocabulary, versioning, and universal Problem Details
adoption are not HTTP mandates.

| ID | Disposition and severity | Primary evidence; applicability and inference boundary |
| --- | --- | --- |
| RAPI-MAP-001 | Correct model; MEDIUM retained | [Spring mapping][spring-mapping], [method-condition union][spring-methods]. MVC/WebFlux declarations; no inferred mutation. |
| RAPI-MAP-002 | Correct identity/applicability; HIGH retained | [Spring mapping][spring-mapping], [Jakarta matching][jaxrs-matching]. Preserve slashes; binding is not JAX-RS dispatch; unknown roots excluded; exact duplicates only. |
| RAPI-MAP-003 | Calibrate HIGH → LOW | [RFC 9110 safety][http-safe]. All stacks; mutation-like name is not data flow. |
| RAPI-MAP-004 | Tighten alternatives; LOW retained | [Spring mappings][spring-mapping]. Spring layout preference; interface exemption; every alternative must support shared prefix. |
| RAPI-MAP-005 | Calibrate LOW → INFO | [Spring mappings][spring-mapping]. Optional slash style, not portable normalization or route identity. |
| RAPI-MAP-006 | Restrict required-binding assertion; HIGH retained | [Spring required binding][spring-pathvar], [JAX-RS scoped binding][jaxrs-pathparam]. Required explicit Spring alternatives only; optional/map/unknown excluded. |
| RAPI-MAP-007 | Correct normative wording; MEDIUM retained | [RFC 9110 GET][http-get], [HEAD][http-head], [DELETE][http-delete]. All stacks; interoperability warning, not blanket prohibition. |
| RAPI-MAP-008 | Retire; LOW metadata retained | [RFC 5789 literal URI][patch-example]. No `{id}` does not imply wrong resource cardinality. |
| RAPI-MAP-009 | Restrict parser assertion to Spring; HIGH retained | [Spring parser][spring-parser], [Jakarta PathParam][jaxrs-pathparam]. Repeated JAX-RS scoped names are not Spring parser failures. |
| RAPI-MAP-010 | Calibrate MEDIUM → INFO | [Spring specificity][spring-mapping], [Jakarta matching][jaxrs-matching]. Catch-all is observable; shadowing and response status are not. |
| RAPI-MAP-011 | Qualify threshold; INFO retained | [RFC 9110 resources][http-resources]. Three levels is optional style; full depth needs known root. |
| RAPI-NAME-001 | Calibrate LOW → INFO | [RFC 9110 resources][http-resources]. English noun heuristic, not protocol grammar. |
| RAPI-NAME-002 | Correct payload facts; INFO retained | [RFC 9110 resources][http-resources]. Pluralization optional; no inferred runtime cardinality; binary is not a collection. |
| RAPI-NAME-003 | Calibrate LOW → INFO | [RFC 3986 case][uri-case]. Kebab-case optional; legitimate case-sensitive paths remain valid. |
| RAPI-NAME-004 | Retire; LOW metadata retained | [Spring 7.0.9 literal-path tests][spring-dotted]. Explicit dotted mappings do not depend on implicit suffix matching. |
| RAPI-RESP-001 | Narrow; MEDIUM → LOW | [RFC 9110 POST][http-post], [201][http-created]. Name-based creation hint; explicit 202/status and dynamic response paths excluded. |
| RAPI-RESP-002 | Narrow; LOW retained | [RFC 9110 DELETE][http-delete], [Jakarta returns][jaxrs-return]. Spring default-empty review only; explicit status and JAX-RS void 204 respected. |
| RAPI-RESP-003 | Correct envelope facts; LOW retained | [Spring envelopes][spring-response], [entity processor][spring-entity-processor], [OpenAPI schema][openapi-schema]. Nested body envelopes, including HttpEntity, retain payload facts without status authority; generics are not the complete schema. |
| RAPI-RESP-004 | Qualify representation claim; INFO retained | [RFC 9110 representations][http-representations]. Scalars valid; structured shape is optional. |
| RAPI-RESP-005 | Narrow no-body inference; LOW retained | [Spring returns][spring-returns], [Jakarta returns][jaxrs-return]. Nested no-body understood; imperative response paths unknown. |
| RAPI-RESP-006 | Correct status/no-body facts; HIGH retained | [RFC 9110 204][http-no-content], [Spring entity processor][spring-entity-processor]. No-content normative; content-capable declaration is not emitted bytes; HttpEntity has no status. |
| RAPI-RESP-007 | Review status overlap; MEDIUM retained | [Spring ResponseStatus][spring-status], [entity processor][spring-entity-processor]. Async ResponseEntity counts, HttpEntity does not; reason-aware native dispatch prevents blanket precedence/removal advice. |
| RAPI-RESP-008 | Reword; MEDIUM → INFO | [RFC 9110 201][http-created]. Target URI can identify resource; no runtime header-absence proof. |
| RAPI-RESP-009 | Narrow; LOW → INFO | [RFC 9110 HEAD][http-head], [Spring HEAD suppression][spring-head]. Dedicated-handler efficiency only; shared GET/HEAD, headers, Void/Unit excluded. |
| RAPI-VALID-001 | Correct payload/trigger facts; HIGH → LOW | [Spring validation][spring-validation], [Jakarta entity validation][jaxrs-validation]. Cascade prompt, not proof of unchecked input or executed constraints. |
| RAPI-VALID-002 | Correct reactive request shape; HIGH retained | [Spring binding guidance][spring-binding]. Entity coupling/over-posting risk, not proven writability of every field. |
| RAPI-VALID-003 | Correct boolean/default/Kotlin handling; MEDIUM retained | [MVC resolver][spring-named-values], [WebFlux resolver][spring-reactive-named-values]. Java numeric null or blank-default conversion failure; false boolean default; uncertain Kotlin defaults skipped. |
| RAPI-VALID-004 | Restrict to aggregate bindings; LOW retained | [Spring RequestParam][spring-requestparam]. Unnamed maps only; schemas and allowlists may exist elsewhere. |
| RAPI-VALID-005 | Qualify retry guidance; INFO retained | [Idempotency-Key draft][idempotency-draft]. Optional convention; absent argument does not prove missing deduplication. |
| RAPI-DTO-001 | Correct payload/array extraction; HIGH retained | [Spring response handling][spring-responsebody]. Persistence exposure risk, no serializer/data-flow proof. |
| RAPI-DTO-002 | Correct inference claim; MEDIUM → LOW | [OpenAPI schema][openapi-schema]. Dynamic objects can have explicit schemas; Jackson 2/3 names supported. |
| RAPI-DTO-004 | Retain INFO | [Java records][java-record]. Public setters are a bounded signal, not a full immutability proof. |
| RAPI-DTO-005 | Correct scope/copy; LOW retained | [Java time API][java-time]. Response DTO fields only; no inferred serialization failure or Boot type replacement. |
| RAPI-PAGE-001 | Narrow collection review; LOW retained | [Spring Data paging][spring-paging], [response envelopes][spring-response]. No visible paging is not an unbounded query; explicit-media streams stay exempt inside envelopes, Lists do not; binary excluded. |
| RAPI-PAGE-002 | Correct remedy; LOW retained | [Spring Data page representation][spring-paging]. Spring-only; stable envelope/header alternatives; Slice has no totals guarantee. |
| RAPI-PAGE-003 | Qualify policy; INFO retained | [Pagination guidance][pagination-style]. Optional consistency; priority-based family detection, not complete mode analysis. |
| RAPI-VER-001 | Correct signals/active stack; INFO retained | [Boot MVC][boot-mvc], [Boot WebFlux][boot-webflux]. Actual context and supported configuration; no vendor-prefix, negated-condition, or arbitrary-property proof. |
| RAPI-VER-002 | Correct readability claim; LOW retained | [Spring mapping][spring-mapping], [Jakarta media][jaxrs-media]. No consumes does not bypass readers/providers. |
| RAPI-VER-003 | Calibrate LOW → INFO | [RFC 9110 Accept][http-accept]. Wildcards valid; broad negotiation is optional review, not disabled negotiation. |
| RAPI-VER-004 | Accept concrete non-JSON formats; INFO retained | [RFC 5789][patch], [non-JSON example][patch-example]. Missing/broad-format prompt only; parameters do not invalidate a concrete type. |
| RAPI-VER-005 | Correct no-body facts; LOW retained | [Spring mapping][spring-mapping], [Jakarta media][jaxrs-media]. Optional produces consistency; unknown/no-body paths do not prove omission. |
| RAPI-VER-006 | Share corrected version signals; INFO retained | [Spring versioning][spring-versioning]. Positive header and query hints are separate transports; native Spring version condition is not another transport. |
| RAPI-ERR-001 | Correct discovery; MEDIUM → INFO | [Reactive advice][spring-reactive-advice], [Jakarta mappers][jaxrs-exceptions]. Skip incomplete extraction and true aggregate flags with mixed stacks or no JAX-RS mapper evidence; inherited reactive Spring advice can pass; central advice optional. |
| RAPI-ERR-002 | Qualify throws guidance; LOW retained | [Java throws][java-throws]. Maintainability only; absent throws does not mean absent failures. |
| RAPI-ERR-003 | Narrow informative comparisons; INFO retained | [RFC 9457][problem-details]. Optional Spring convenience-type adoption; custom schemas may comply; unknown/view/void excluded. |
| RAPI-ERR-004 | Correct status observability; MEDIUM retained | [Spring exceptions][spring-exceptions], [Jakarta mappers][jaxrs-exceptions]. Dynamic status/error types distinguished, unknown bodies excluded; no invented Quarkus 200 default. |
| RAPI-ERR-005 | Require known fixed non-5xx status; LOW retained | [Reactive advice][spring-reactive-advice], [RFC 9110 5xx][http-server-errors]. Broad dynamic mappers and sole 500 fallbacks do not prove collapse. |
| RAPI-ERR-006 | Narrow/qualify migration; INFO retained | [Spring error responses][spring-errors], [RFC 9457][problem-details]. Spring-only known-ancestry exceptions actually declared in throws, without declared class/ancestor/broad coverage; annotation coexistence alone insufficient. |
| RAPI-ERR-007 | Correct header evidence wording; INFO retained | [RFC 9110 Retry-After][http-retry], [RFC 6585 429][http-429]. Optional review of declared status; runtime header absence unknown. |
| RAPI-ERR-008 | Qualify text-error guidance; LOW retained | [RFC 9457][problem-details], [Spring exceptions][spring-exceptions]. Body-rendering String only; text is not a protocol violation. |
| RAPI-ERR-009 | Correct exception extraction; MEDIUM retained | [Spring ExceptionHandler][spring-exception-annotation], [Jakarta mappers][jaxrs-exceptions]. Skip incomplete extraction or unresolved imported mapper types; intentional unknown alone is not PARTIAL; declaration union is not scoped resolution. |
| RAPI-ERR-010 | Exclude unknown shapes; LOW retained | [RFC 9457][problem-details], [Spring exceptions][spring-exceptions]. Known category differences only under compatible/unspecified media; dynamic Map/Object/Response/raw envelopes/JsonNode excluded; distinct negotiation media alone insufficient. |
| RAPI-ERR-011 | Retire; HIGH metadata retained | [RFC 9457 security][problem-security]. Stack-trace accessor calls do not prove response leakage. |
| RAPI-DOC-001 | Reframe as explicit enrichment; INFO retained | [MicroProfile generation][mp-generation], [OpenAPI operation][openapi-operation]. No annotation does not prove absent generated/static documentation. |
| RAPI-DOC-002 | Recognize tag forms; INFO retained | [OpenAPI operation][openapi-operation], [MicroProfile processing][mp-processing]. Repeatable tags/operation lists count; automatic grouping may exist. |
| RAPI-DOC-003 | Retire; INFO metadata retained | [SmallRye 4.2.4 deprecation][smallrye-deprecation], [RFC 9745][http-deprecation]. Java/Kotlin annotations or external documents can provide deprecation; header absence unobserved. |

## Deliberately deferred checks

- Full native route enumeration, dynamic locator traversal, arbitrary generic substitution, and complete Spring
  composed/inherited handler resolution need more than this bounded bytecode model.
- Scope/selector/priority-aware matching of every declared exception to runtime resolution is separate from the
  declaration union used by ERR-009.
- Response-header/content capture and interprocedural data flow are not added to infer status, Location, Retry-After,
  stack-trace leakage, or actual validation execution.
- Generated/static OpenAPI documents are not parsed; annotation advisories do not certify their completeness.
- Universal caching headers, Vary, Idempotency-Key, Problem Details, URL versions, plural nouns, or one pagination
  dialect are not mandated from these declarations. [RFC 9111][http-cache] permits caching policies beyond explicit
  expiration/validator declarations.
- New IDs for additional no-content statuses or additional declaration conflicts are deferred until their evidence
  can be distinguished from dynamic/null responses and framework suppression.

[boot-versions]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/gradle.properties#L23-L27
[quarkus-bom]: https://github.com/quarkusio/quarkus/blob/3.33.3.1/bom/application/pom.xml#L48-L52
[http-resources]: https://www.rfc-editor.org/rfc/rfc9110.html#section-3.1
[http-representations]: https://www.rfc-editor.org/rfc/rfc9110.html#section-3.2
[http-safe]: https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.1
[http-get]: https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.1
[http-head]: https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.2
[http-post]: https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.3
[http-delete]: https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.5
[http-created]: https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.2
[http-no-content]: https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.5
[http-server-errors]: https://www.rfc-editor.org/rfc/rfc9110.html#section-15.6
[http-accept]: https://www.rfc-editor.org/rfc/rfc9110.html#section-12.5.1
[http-retry]: https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3
[http-429]: https://www.rfc-editor.org/rfc/rfc6585.html#section-4
[http-cache]: https://www.rfc-editor.org/rfc/rfc9111.html#section-3
[http-deprecation]: https://www.rfc-editor.org/rfc/rfc9745.html
[http-sunset]: https://www.rfc-editor.org/rfc/rfc8594.html
[uri-case]: https://www.rfc-editor.org/rfc/rfc3986.html#section-6.2.2.1
[patch]: https://www.rfc-editor.org/rfc/rfc5789.html#section-2
[patch-example]: https://www.rfc-editor.org/rfc/rfc5789.html#section-2.1
[problem-details]: https://www.rfc-editor.org/rfc/rfc9457.html
[problem-security]: https://www.rfc-editor.org/rfc/rfc9457.html#section-5
[idempotency-draft]: https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
[spring-mapping]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/web/webmvc/mvc-controller/ann-requestmapping.adoc
[spring-methods]: https://github.com/spring-projects/spring-framework/blob/v7.0.0/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/condition/RequestMethodsRequestCondition.java
[spring-parser]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/web/util/pattern/InternalPathPatternParser.java
[spring-dotted]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/test/java/org/springframework/web/util/pattern/PathPatternTests.java#L562-L572
[spring-pathvar]: https://docs.spring.io/spring-framework/docs/7.0.0/javadoc-api/org/springframework/web/bind/annotation/PathVariable.html
[spring-response]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/web/webflux/controller/ann-methods/responseentity.adoc#L36-L48
[spring-entity-processor]: https://github.com/spring-projects/spring-framework/blob/v7.0.0/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/HttpEntityMethodProcessor.java
[spring-status]: https://github.com/spring-projects/spring-framework/blob/v7.0.0/spring-web/src/main/java/org/springframework/web/bind/annotation/ResponseStatus.java#L28-L50
[spring-returns]: https://github.com/spring-projects/spring-framework/blob/v7.0.0/framework-docs/modules/ROOT/pages/web/webmvc/mvc-controller/ann-methods/return-types.adoc#L59-L66
[spring-head]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/server/reactive/HttpHeadResponseDecorator.java#L41-L60
[spring-validation]: https://github.com/spring-projects/spring-framework/blob/v7.0.0/spring-context/src/main/java/org/springframework/validation/annotation/ValidationAnnotationUtils.java#L38-L69
[spring-binding]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/web/webmvc/mvc-data-binding.adoc
[spring-named-values]: https://github.com/spring-projects/spring-framework/blob/v7.0.0/spring-web/src/main/java/org/springframework/web/method/annotation/AbstractNamedValueMethodArgumentResolver.java
[spring-reactive-named-values]: https://github.com/spring-projects/spring-framework/blob/v7.0.0/spring-webflux/src/main/java/org/springframework/web/reactive/result/method/annotation/AbstractNamedValueArgumentResolver.java
[spring-requestparam]: https://docs.spring.io/spring-framework/docs/7.0.0/javadoc-api/org/springframework/web/bind/annotation/RequestParam.html
[spring-responsebody]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/web/webmvc/mvc-controller/ann-methods/responsebody.adoc
[spring-paging]: https://docs.spring.io/spring-data/commons/reference/repositories/core-extensions.html
[spring-versioning]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/web/webmvc-versioning.adoc
[boot-mvc]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-webmvc/src/main/java/org/springframework/boot/webmvc/autoconfigure/WebMvcProperties.java
[boot-webflux]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-webflux/src/main/java/org/springframework/boot/webflux/autoconfigure/WebFluxProperties.java
[spring-reactive-advice]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-webflux/src/main/java/org/springframework/web/reactive/result/method/annotation/ResponseEntityExceptionHandler.java#L94-L143
[spring-single-response]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-webflux/src/main/java/org/springframework/web/reactive/result/method/annotation/ResponseEntityResultHandler.java#L134-L144
[spring-exceptions]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/web/webmvc/mvc-controller/ann-exceptionhandler.adoc
[spring-exception-annotation]: https://github.com/spring-projects/spring-framework/blob/v7.0.0/spring-web/src/main/java/org/springframework/web/bind/annotation/ExceptionHandler.java#L117-L136
[spring-errors]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/framework-docs/modules/ROOT/pages/web/webmvc/mvc-ann-rest-exceptions.adoc
[jaxrs-return]: https://github.com/jakartaee/rest/blob/4.0.0-RELEASE/jaxrs-spec/src/main/asciidoc/chapters/resources/_resource_method.adoc#L63-L99
[jaxrs-matching]: https://jakarta.ee/specifications/restful-ws/4.0/jakarta-restful-ws-spec-4.0.html
[jaxrs-pathparam]: https://jakarta.ee/specifications/restful-ws/3.1/apidocs/jakarta.ws.rs/jakarta/ws/rs/pathparam
[jaxrs-media]: https://github.com/jakartaee/rest/blob/4.0.0-RELEASE/jaxrs-spec/src/main/asciidoc/chapters/resources/_declaring_method_capabilities.adoc#L14-L21
[jaxrs-validation]: https://github.com/jakartaee/rest/blob/4.0.0-RELEASE/jaxrs-spec/src/main/asciidoc/chapters/validation/_entity_validation.adoc#L14-L66
[jaxrs-exceptions]: https://jakarta.ee/specifications/restful-ws/4.0/jakarta-restful-ws-spec-4.0.html
[mp-generation]: https://github.com/eclipse/microprofile-open-api/blob/4.0/spec/src/main/asciidoc/microprofile-openapi-spec.asciidoc#L176-L206
[mp-processing]: https://github.com/eclipse/microprofile-open-api/blob/4.0/spec/src/main/asciidoc/microprofile-openapi-spec.asciidoc#L730-L757
[openapi-operation]: https://github.com/OAI/OpenAPI-Specification/blob/3.1.1/versions/3.1.1.md#operation-object
[openapi-schema]: https://github.com/OAI/OpenAPI-Specification/blob/3.1.1/versions/3.1.1.md#schema-object
[smallrye-deprecation]: https://github.com/smallrye/smallrye-open-api/blob/4.2.4/core/src/main/java/io/smallrye/openapi/runtime/util/TypeUtil.java#L791-L803
[java-record]: https://docs.oracle.com/en/java/javase/17/language/records.html
[java-time]: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/package-summary.html
[java-throws]: https://docs.oracle.com/javase/specs/jls/se17/html/jls-8.html#jls-8.4.6
[pagination-style]: https://opensource.zalando.com/restful-api-guidelines/#pagination
