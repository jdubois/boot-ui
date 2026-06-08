# REST API checks

The REST API panel runs a fixed, zero-config ruleset against the host application's own web layer
(`@RestController` / `@Controller` handler methods). This page lists every rule that ships with BootUI today, what it
inspects, when it fires, and what to do about it.

Each rule is a small class registered in
[`RestApiRuleRegistry`](../bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/restapi/RestApiRuleRegistry.java)
and implemented in
[`RestApiRules.java`](../bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/restapi/RestApiRules.java).
The list intentionally stays compact and reviewable; adding a new rule means adding one focused class plus a registry
entry.

## What BootUI does

The scanner detects the host application's base package(s) from the `@SpringBootApplication` configuration via
`AutoConfigurationPackages`, imports the compiled `.class` files from those packages with ArchUnit's
`ClassFileImporter`, derives a read-only handler model (HTTP method(s), path(s), parameters and their annotations,
return type, `produces`/`consumes`, validation flags, declared throws) once, and evaluates every registered rule against
that model. Importing is bounded to the application's own base package(s) — never the entire classpath — and runs only
on demand when the scan action is invoked, caching the last report in the controller.

When BootUI is installed through `bootui-spring-boot-starter`, ArchUnit is included transitively so the panel works
without an extra application dependency. The panel is available only when:

- ArchUnit is on the classpath, and
- a base package is resolvable from the running application.

Every rule catches `RuntimeException` and `LinkageError` and degrades to a `SKIPPED`/`ERROR` outcome so one
unresolvable class never aborts the scan, and the scanner degrades to a stable "scanned, nothing to analyse" report when
no controllers can be imported.

## What BootUI does not do

- It does not check security concerns (CORS, authentication, authorization) — those remain owned by the **Security**
  panel.
- It does not modify, compile, or instrument application code; it reads already-compiled bytecode.
- It is **not a replacement** for an API design review or contract testing. The heuristics are project-agnostic review
  prompts, not verdicts.

## Severities

Findings are ranked in the scanner's severity order:

- `CRITICAL` — supported by the scanner, but no active REST API rule currently assigns it.
- `HIGH`
- `MEDIUM`
- `LOW`
- `INFO`

The catalogue below ships **39 rules across 8 categories** (7 HIGH, 10 MEDIUM, 13 LOW, 9 INFO; no active CRITICAL
rules). The `RAPI-DOC-*` documentation rules only run when springdoc-openapi is on the host classpath; otherwise they are
reported as `SKIPPED`.

---

## Routing & HTTP method mapping

### RAPI-MAP-001 - Use HTTP-method-specific mappings

- **Severity**: MEDIUM
- **Detects**: Handlers mapped with @RequestMapping but no HTTP method match every verb, which hides intent and can expose state-changing operations over GET.
- **Recommendation**: Replace @RequestMapping without a method with @GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping (or set the method attribute).
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-MAP-002 - No duplicate route mappings

- **Severity**: HIGH
- **Detects**: Two handlers mapped to the same HTTP method and path lead to ambiguous mapping exceptions at startup or unpredictable dispatch.
- **Recommendation**: Ensure each (HTTP method, path) pair is handled by exactly one method.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-MAP-003 - State-changing handlers are not mapped to GET

- **Severity**: HIGH
- **Detects**: GET must be safe and idempotent. A create/update/delete-style handler mapped to GET can be triggered by crawlers, prefetching, or caching.
- **Recommendation**: Map state-changing operations to POST/PUT/PATCH/DELETE instead of GET.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

### RAPI-MAP-004 - Prefer a class-level base path

- **Severity**: LOW
- **Detects**: Controllers that repeat the same leading path segment on every method but declare no type-level @RequestMapping duplicate routing information.
- **Recommendation**: Hoist the shared prefix into a class-level @RequestMapping and keep method paths relative.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-MAP-005 - Consistent path style (no trailing slash)

- **Severity**: LOW
- **Detects**: Trailing slashes and doubled slashes in class-level or method mapping paths create inconsistent URLs; trailing-slash matching is also disabled by default in Spring 6+.
- **Recommendation**: Declare mapping paths without trailing slashes and without empty segments.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-MAP-006 - @PathVariable names match a path token

- **Severity**: HIGH
- **Detects**: A @PathVariable whose explicit name has no matching {token} in the mapping path fails at runtime with a missing-path-variable error.
- **Recommendation**: Make each @PathVariable name match a {token} in the mapping path (or correct the path template).
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-MAP-007 - No @RequestBody on GET/HEAD/DELETE

- **Severity**: MEDIUM
- **Detects**: A @RequestBody on a GET, HEAD, or DELETE handler relies on a request body that proxies, caches, and HTTP clients may strip, so the payload is unreliable.
- **Recommendation**: Move the payload to query/path parameters, or use POST/PUT/PATCH when a request body is required.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

### RAPI-MAP-008 - Mutating item methods target an identified resource

- **Severity**: LOW
- **Detects**: PUT/PATCH/DELETE handlers with a path but no {id} (or other path variable), excluding explicit bulk/batch/all and singleton/current-resource endpoints, mutate a collection URI rather than a specific resource.
- **Recommendation**: Add a path variable that identifies the resource (e.g. /orders/{id}); for intentional collection-wide mutations, name the endpoint explicitly (e.g. /orders/bulk).
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

## Naming & resource design

### RAPI-NAME-001 - Resource paths are nouns, not verbs

- **Severity**: LOW
- **Detects**: Verb-based path segments such as /getUser or /createOrder duplicate the HTTP method and break the resource-oriented REST model.
- **Recommendation**: Model resources as nouns (/users, /orders) and express the action with the HTTP method.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

### RAPI-NAME-002 - Collections use plural nouns

- **Severity**: INFO
- **Detects**: Endpoints returning a collection but addressed with a singular noun read inconsistently (/user vs /users).
- **Recommendation**: Use plural nouns for collection resources and keep singular forms for single-item paths.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

### RAPI-NAME-003 - Path segments are kebab-case/lowercase

- **Severity**: LOW
- **Detects**: camelCase, snake_case, or upper-case path segments produce inconsistent, case-sensitive URLs.
- **Recommendation**: Use lower-case kebab-case path segments (/order-items, not /orderItems or /order_items).
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

## Status codes & responses

### RAPI-RESP-001 - Creation endpoints return 201 Created

- **Severity**: MEDIUM
- **Detects**: A POST that creates a resource but returns the default 200 OK hides the created status and (usually) the Location of the new resource.
- **Recommendation**: Prefer ResponseEntity.created(uri) so the response carries both 201 and the Location header; use @ResponseStatus(HttpStatus.CREATED) only when the Location is set another way.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.2>

### RAPI-RESP-002 - Void DELETE returns 204 No Content

- **Severity**: LOW
- **Detects**: A DELETE handler returning void but defaulting to 200 OK sends an empty 200 instead of the more precise 204 No Content.
- **Recommendation**: Annotate void DELETE handlers with @ResponseStatus(HttpStatus.NO_CONTENT) or return ResponseEntity.noContent().
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

### RAPI-RESP-003 - No untyped ResponseEntity body

- **Severity**: LOW
- **Detects**: `ResponseEntity<?>` or `ResponseEntity<Object>` erases the response contract, so clients and OpenAPI tooling cannot infer the body type.
- **Recommendation**: Parameterize ResponseEntity with the concrete DTO type returned by the handler.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-RESP-004 - Read endpoints return a representation

- **Severity**: INFO
- **Detects**: A GET returning a bare String or primitive without explicitly producing text/* exposes a value without a stable, evolvable representation.
- **Recommendation**: Return a DTO/record representation from read endpoints instead of a raw String or primitive.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-RESP-005 - GET endpoints return content

- **Severity**: LOW
- **Detects**: A GET handler that returns void responds with an empty 200 OK and no representation, which is rarely the intent for a read endpoint.
- **Recommendation**: Return the resource representation from GET handlers (or use a more precise status when no body is expected).
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

### RAPI-RESP-006 - 204 No Content responses carry no body

- **Severity**: HIGH
- **Detects**: A handler annotated @ResponseStatus(NO_CONTENT) that still returns a body is contradictory: 204 forbids a response body and clients/proxies may drop or reject it.
- **Recommendation**: Return void (or ResponseEntity) for 204 responses, or use 200 OK when a body is required.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

### RAPI-RESP-007 - @ResponseStatus is not combined with ResponseEntity

- **Severity**: MEDIUM
- **Detects**: When a handler returns ResponseEntity, its status wins and a method-level @ResponseStatus is silently ignored, so the declared status is misleading.
- **Recommendation**: Set the status through ResponseEntity (e.g. ResponseEntity.status(...)) and drop the redundant @ResponseStatus.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-RESP-008 - Created responses expose a Location

- **Severity**: MEDIUM
- **Detects**: A handler annotated @ResponseStatus(CREATED) that returns a plain body (not ResponseEntity and with no servlet response argument) has no way to set the Location header of the newly created resource.
- **Recommendation**: Return ResponseEntity.created(uri).body(...) so the 201 response also carries the Location of the new resource.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.2>

## Input validation & binding

### RAPI-VALID-001 - @RequestBody is validated

- **Severity**: HIGH
- **Detects**: A complex @RequestBody parameter without @Valid/@Validated is bound without bean-validation, so malformed payloads reach the business logic unchecked.
- **Recommendation**: Annotate @RequestBody parameters with @Valid (or @Validated) and declare constraints on the DTO.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html>

### RAPI-VALID-002 - No mass-assignment via JPA entities

- **Severity**: HIGH
- **Detects**: Binding a request body directly to a JPA @Entity lets clients set any persistent field (mass-assignment / over-posting), including ids and relationships.
- **Recommendation**: Bind requests to a dedicated request DTO and map explicitly to the entity.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html>

### RAPI-VALID-003 - Optional @RequestParam is not a primitive

- **Severity**: MEDIUM
- **Detects**: A primitive @RequestParam with required=false and no defaultValue throws 500 (IllegalStateException) when the parameter is omitted, because null cannot be unboxed.
- **Recommendation**: Use the boxed wrapper type (e.g. Integer) or provide a defaultValue for optional primitive query parameters.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html>

## DTO & payload contracts

### RAPI-DTO-001 - Don't expose JPA entities in responses

- **Severity**: HIGH
- **Detects**: Returning a JPA @Entity couples the API to the persistence model and can leak lazy associations or internal fields and trigger serialization-time queries.
- **Recommendation**: Return a response DTO/record and map from the entity in the service or controller.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-DTO-002 - No untyped response bodies

- **Severity**: MEDIUM
- **Detects**: Returning Map, Object, or JsonNode as the body produces an undocumented, untyped contract that clients and OpenAPI tooling cannot model.
- **Recommendation**: Return a typed DTO/record instead of Map/Object/JsonNode.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-DTO-003 - Wrap top-level collections

- **Severity**: INFO
- **Detects**: Returning a raw non-GET top-level array or List outside ResponseEntity makes the response impossible to evolve (you cannot add metadata without a breaking change); GET collection reads are covered by RAPI-PAGE-001.
- **Recommendation**: Wrap non-GET collection responses in an object (e.g. a result wrapper) rather than returning a bare List/array.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9110.html>

### RAPI-DTO-004 - Request/response DTOs are immutable

- **Severity**: INFO
- **Detects**: Response payload types that expose public setters are mutable, which makes them easy to mutate accidentally and harder to reason about.
- **Recommendation**: Prefer Java records or otherwise immutable DTOs without public setters.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

## Pagination & collections

### RAPI-PAGE-001 - Collection reads are paginated

- **Severity**: MEDIUM
- **Detects**: A GET returning a Collection with no Pageable parameter loads and serializes the entire result set, which does not scale.
- **Recommendation**: Accept a Pageable parameter (or explicit page/size) and return a bounded result.
- **Learn more**: <https://docs.spring.io/spring-data/commons/reference/repositories/core-extensions.html>

### RAPI-PAGE-002 - Pageable handlers return a paged type

- **Severity**: LOW
- **Detects**: A handler that accepts a Pageable but returns a raw List/array discards the paging metadata (total elements, total pages) that Page or Slice would carry.
- **Recommendation**: Return Page or Slice when the handler accepts a Pageable, so paging metadata reaches the client.
- **Learn more**: <https://docs.spring.io/spring-data/commons/reference/repositories/core-extensions.html>

## Versioning & content negotiation

### RAPI-VER-001 - API uses a consistent versioning strategy

- **Severity**: INFO
- **Detects**: No version signal (no /vN path segment, version header/param, or versioned media type) was found, or only some handlers are versioned, which makes breaking changes hard to roll out consistently.
- **Recommendation**: Adopt one versioning strategy (path, header/param, or media-type versioning) and apply it across all API endpoints before the API is consumed externally.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc-versioning.html>

### RAPI-VER-002 - Mutating endpoints declare a consumes media type

- **Severity**: LOW
- **Detects**: POST/PUT/PATCH handlers that accept a @RequestBody but declare no consumes media type accept any content type, which weakens content negotiation and input validation.
- **Recommendation**: Declare consumes (e.g. application/json) on mutating endpoints that accept a request body.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-VER-003 - No wildcard media types

- **Severity**: LOW
- **Detects**: Declaring produces/consumes with a wildcard (*/* or application/*) disables meaningful content negotiation and defeats the purpose of declaring media types.
- **Recommendation**: Use concrete media types (e.g. application/json) instead of wildcard media types.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

### RAPI-VER-004 - PATCH declares a patch media type

- **Severity**: INFO
- **Detects**: A PATCH handler that declares a consumes media type other than application/merge-patch+json or application/json-patch+json does not signal which patch document format it expects.
- **Recommendation**: Declare consumes = application/merge-patch+json (RFC 7396) or application/json-patch+json (RFC 6902) on PATCH handlers.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc5789.html>

### RAPI-VER-005 - Response-producing endpoints declare produces consistently

- **Severity**: LOW
- **Detects**: Within a controller that declares produces media types on some response handlers, other body-returning handlers that omit produces create an inconsistent content contract.
- **Recommendation**: Declare produces (e.g. application/json) consistently on the response-producing handlers of a controller.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html>

## Error handling & documentation

### RAPI-ERR-001 - Centralized exception handling exists

- **Severity**: MEDIUM
- **Detects**: Controllers are present but no @RestControllerAdvice/@ControllerAdvice with @ExceptionHandler methods (or a ResponseEntityExceptionHandler subclass) was found, so error responses are likely ad-hoc and inconsistent.
- **Recommendation**: Add a @RestControllerAdvice with @ExceptionHandler methods (or extend ResponseEntityExceptionHandler) to produce consistent error responses.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9457.html>

### RAPI-ERR-002 - No broad throws on handlers

- **Severity**: LOW
- **Detects**: Handlers declaring throws Exception or Throwable obscure the real failure modes and discourage targeted exception handling.
- **Recommendation**: Throw specific exceptions and map them with @ExceptionHandler instead of declaring throws Exception/Throwable.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9457.html>

### RAPI-ERR-003 - Prefer RFC 9457 ProblemDetail

- **Severity**: INFO
- **Detects**: @ExceptionHandler methods that model errors as ad-hoc maps/strings instead of ProblemDetail produce non-standard error payloads.
- **Recommendation**: Return ProblemDetail (or ErrorResponse) from @ExceptionHandler methods for RFC 9457 compliant errors.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9457.html>

### RAPI-ERR-004 - Exception handlers set an explicit error status

- **Severity**: MEDIUM
- **Detects**: An @ExceptionHandler that renders a non-ProblemDetail body but neither returns ResponseEntity, declares @ResponseStatus, nor accepts a servlet response argument falls back to 200 OK, masking the failure from clients.
- **Recommendation**: Return ResponseEntity/ProblemDetail or add @ResponseStatus so the handler responds with an error status.
- **Learn more**: <https://www.rfc-editor.org/rfc/rfc9457.html>

### RAPI-DOC-001 - Endpoints are documented

- **Severity**: INFO
- **Detects**: springdoc-openapi is on the classpath but some handlers have no @Operation, so the generated documentation is incomplete.
- **Recommendation**: Add @Operation (summary/description) to handler methods to document the API.
- **Learn more**: <https://springdoc.org/>

### RAPI-DOC-002 - Controllers are grouped/tagged

- **Severity**: INFO
- **Detects**: springdoc-openapi is on the classpath but some controllers have no @Tag, so endpoints are not grouped in the generated documentation.
- **Recommendation**: Add @Tag to controllers to group their endpoints in the OpenAPI documentation.
- **Learn more**: <https://springdoc.org/>
