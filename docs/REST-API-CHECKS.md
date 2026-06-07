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

Findings are ranked `HIGH` / `MEDIUM` / `LOW` / `INFO`. The catalogue below ships **36 rules across 8 categories**
(7 HIGH, 9 MEDIUM, 11 LOW, 9 INFO). The `RAPI-DOC-*` documentation rules only run when springdoc-openapi is on the host
classpath; otherwise they are reported as `SKIPPED`.

## Rule catalogue

### Routing & HTTP method mapping

| ID | Rule | Severity | What it inspects | Recommendation |
|---|---|---|---|---|
| `RAPI-MAP-001` | Use HTTP-method-specific mappings | MEDIUM | Handlers mapped with @RequestMapping but no HTTP method match every verb, which hides intent and can expose state-changing operations over GET. | Replace @RequestMapping without a method with @GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping (or set the method attribute). |
| `RAPI-MAP-002` | No duplicate route mappings | HIGH | Two handlers mapped to the same HTTP method and path lead to ambiguous mapping exceptions at startup or unpredictable dispatch. | Ensure each (HTTP method, path) pair is handled by exactly one method. |
| `RAPI-MAP-003` | State-changing handlers are not mapped to GET | HIGH | GET must be safe and idempotent. A create/update/delete-style handler mapped to GET can be triggered by crawlers, prefetching, or caching. | Map state-changing operations to POST/PUT/PATCH/DELETE instead of GET. |
| `RAPI-MAP-004` | Prefer a class-level base path | LOW | Controllers that repeat the same leading path segment on every method but declare no type-level @RequestMapping duplicate routing information. | Hoist the shared prefix into a class-level @RequestMapping and keep method paths relative. |
| `RAPI-MAP-005` | Consistent path style (no trailing slash) | LOW | Trailing slashes and doubled slashes in mapping paths create inconsistent URLs; trailing-slash matching is also disabled by default in Spring 6+. | Declare mapping paths without trailing slashes and without empty segments. |
| `RAPI-MAP-006` | @PathVariable names match a path token | HIGH | A @PathVariable whose explicit name has no matching {token} in the mapping path fails at runtime with a missing-path-variable error. | Make each @PathVariable name match a {token} in the mapping path (or correct the path template). |
| `RAPI-MAP-007` | No @RequestBody on GET/HEAD/DELETE | MEDIUM | A @RequestBody on a GET, HEAD, or DELETE handler relies on a request body that proxies, caches, and HTTP clients may strip, so the payload is unreliable. | Move the payload to query/path parameters, or use POST/PUT/PATCH when a request body is required. |

### Naming & resource design

| ID | Rule | Severity | What it inspects | Recommendation |
|---|---|---|---|---|
| `RAPI-NAME-001` | Resource paths are nouns, not verbs | LOW | Verb-based path segments such as /getUser or /createOrder duplicate the HTTP method and break the resource-oriented REST model. | Model resources as nouns (/users, /orders) and express the action with the HTTP method. |
| `RAPI-NAME-002` | Collections use plural nouns | INFO | Endpoints returning a collection but addressed with a singular noun read inconsistently (/user vs /users). | Use plural nouns for collection resources and keep singular forms for single-item paths. |
| `RAPI-NAME-003` | Path segments are kebab-case/lowercase | LOW | camelCase, snake_case, or upper-case path segments produce inconsistent, case-sensitive URLs. | Use lower-case kebab-case path segments (/order-items, not /orderItems or /order_items). |

### Status codes & responses

| ID | Rule | Severity | What it inspects | Recommendation |
|---|---|---|---|---|
| `RAPI-RESP-001` | Creation endpoints return 201 Created | MEDIUM | A POST that creates a resource but returns the default 200 OK hides the created status and (usually) the Location of the new resource. | Return 201 via @ResponseStatus(HttpStatus.CREATED) or ResponseEntity.created(...). |
| `RAPI-RESP-002` | Void DELETE returns 204 No Content | LOW | A DELETE handler returning void but defaulting to 200 OK sends an empty 200 instead of the more precise 204 No Content. | Annotate void DELETE handlers with @ResponseStatus(HttpStatus.NO_CONTENT) or return ResponseEntity.noContent(). |
| `RAPI-RESP-003` | No untyped ResponseEntity body | LOW | `ResponseEntity<?>` or `ResponseEntity<Object>` erases the response contract, so clients and OpenAPI tooling cannot infer the body type. | Parameterize ResponseEntity with the concrete DTO type returned by the handler. |
| `RAPI-RESP-004` | Read endpoints return a representation | INFO | A GET returning a bare String or primitive exposes a value without a stable, evolvable representation. | Return a DTO/record representation from read endpoints instead of a raw String or primitive. |
| `RAPI-RESP-005` | GET endpoints return content | LOW | A GET handler that returns void responds with an empty 200 OK and no representation, which is rarely the intent for a read endpoint. | Return the resource representation from GET handlers (or use a more precise status when no body is expected). |
| `RAPI-RESP-006` | 204 No Content responses carry no body | HIGH | A handler annotated @ResponseStatus(NO_CONTENT) that still returns a body is contradictory: 204 forbids a response body and clients/proxies may drop or reject it. | Return void (or ResponseEntity) for 204 responses, or use 200 OK when a body is required. |
| `RAPI-RESP-007` | @ResponseStatus is not combined with ResponseEntity | MEDIUM | When a handler returns ResponseEntity, its status wins and a method-level @ResponseStatus is silently ignored, so the declared status is misleading. | Set the status through ResponseEntity (e.g. ResponseEntity.status(...)) and drop the redundant @ResponseStatus. |

### Input validation & binding

| ID | Rule | Severity | What it inspects | Recommendation |
|---|---|---|---|---|
| `RAPI-VALID-001` | @RequestBody is validated | HIGH | A @RequestBody parameter without @Valid/@Validated is bound without bean-validation, so malformed payloads reach the business logic unchecked. | Annotate @RequestBody parameters with @Valid (or @Validated) and declare constraints on the DTO. |
| `RAPI-VALID-003` | No mass-assignment via JPA entities | HIGH | Binding a request body directly to a JPA @Entity lets clients set any persistent field (mass-assignment / over-posting), including ids and relationships. | Bind requests to a dedicated request DTO and map explicitly to the entity. |
| `RAPI-VALID-004` | Optional @RequestParam is not a primitive | MEDIUM | A primitive @RequestParam with required=false and no defaultValue throws 500 (IllegalStateException) when the parameter is omitted, because null cannot be unboxed. | Use the boxed wrapper type (e.g. Integer) or provide a defaultValue for optional primitive query parameters. |

### DTO & payload contracts

| ID | Rule | Severity | What it inspects | Recommendation |
|---|---|---|---|---|
| `RAPI-DTO-001` | Don't expose JPA entities in responses | HIGH | Returning a JPA @Entity couples the API to the persistence model and can leak lazy associations or internal fields and trigger serialization-time queries. | Return a response DTO/record and map from the entity in the service or controller. |
| `RAPI-DTO-002` | No untyped response bodies | MEDIUM | Returning Map, Object, or JsonNode as the body produces an undocumented, untyped contract that clients and OpenAPI tooling cannot model. | Return a typed DTO/record instead of Map/Object/JsonNode. |
| `RAPI-DTO-003` | Wrap top-level collections | INFO | Returning a raw top-level array or List makes the response impossible to evolve (you cannot add paging or metadata without a breaking change). | Wrap collections in an object (e.g. a page/result wrapper) rather than returning a bare List/array. |
| `RAPI-DTO-004` | Request/response DTOs are immutable | INFO | Response payload types that expose public setters are mutable, which makes them easy to mutate accidentally and harder to reason about. | Prefer Java records or otherwise immutable DTOs without public setters. |

### Pagination & collections

| ID | Rule | Severity | What it inspects | Recommendation |
|---|---|---|---|---|
| `RAPI-PAGE-001` | Collection reads are paginated | MEDIUM | A GET returning a Collection with no Pageable parameter loads and serializes the entire result set, which does not scale. | Accept a Pageable parameter (or explicit page/size) and return a bounded result. |
| `RAPI-PAGE-002` | Pageable handlers return a paged type | LOW | A handler that accepts a Pageable but returns a raw List/array discards the paging metadata (total elements, total pages) that Page or Slice would carry. | Return Page or Slice when the handler accepts a Pageable, so paging metadata reaches the client. |

### Versioning & content negotiation

| ID | Rule | Severity | What it inspects | Recommendation |
|---|---|---|---|---|
| `RAPI-VER-001` | API is versioned | INFO | No version signal (no /vN path segment, version header, or versioned media type) was found, which makes breaking changes hard to roll out. | Adopt a versioning strategy (path, header, or media-type versioning) before the API is consumed externally. |
| `RAPI-VER-002` | Mutating endpoints declare a consumes media type | LOW | POST/PUT/PATCH handlers that accept a @RequestBody but declare no consumes media type accept any content type, which weakens content negotiation and input validation. | Declare consumes (e.g. application/json) on mutating endpoints that accept a request body. |
| `RAPI-VER-003` | No wildcard media types | LOW | Declaring produces/consumes with a wildcard (*/* or application/*) disables meaningful content negotiation and defeats the purpose of declaring media types. | Use concrete media types (e.g. application/json) instead of wildcard media types. |
| `RAPI-VER-004` | PATCH declares a patch media type | INFO | A PATCH handler that declares a consumes media type other than application/merge-patch+json or application/json-patch+json does not signal which patch document format it expects. | Declare consumes = application/merge-patch+json or application/json-patch+json on PATCH handlers. |

### Error handling & documentation

| ID | Rule | Severity | What it inspects | Recommendation |
|---|---|---|---|---|
| `RAPI-DOC-001` | Endpoints are documented | INFO | springdoc-openapi is on the classpath but some handlers have no @Operation, so the generated documentation is incomplete. | Add @Operation (summary/description) to handler methods to document the API. |
| `RAPI-DOC-002` | Controllers are grouped/tagged | INFO | springdoc-openapi is on the classpath but some controllers have no @Tag, so endpoints are not grouped in the generated documentation. | Add @Tag to controllers to group their endpoints in the OpenAPI documentation. |
| `RAPI-ERR-001` | Centralized exception handling exists | MEDIUM | Controllers are present but no @RestControllerAdvice/@ControllerAdvice with @ExceptionHandler methods (or a ResponseEntityExceptionHandler subclass) was found, so error responses are likely ad-hoc and inconsistent. | Add a @RestControllerAdvice with @ExceptionHandler methods (or extend ResponseEntityExceptionHandler) to produce consistent error responses. |
| `RAPI-ERR-002` | No broad throws on handlers | LOW | Handlers declaring throws Exception or Throwable obscure the real failure modes and discourage targeted exception handling. | Throw specific exceptions and map them with @ExceptionHandler instead of declaring throws Exception/Throwable. |
| `RAPI-ERR-003` | Prefer RFC 9457 ProblemDetail | INFO | @ExceptionHandler methods that model errors as ad-hoc maps/strings instead of ProblemDetail produce non-standard error payloads. | Return ProblemDetail (or ErrorResponse) from @ExceptionHandler methods for RFC 9457 compliant errors. |
| `RAPI-ERR-004` | Exception handlers set an explicit error status | MEDIUM | An @ExceptionHandler that renders a body but neither returns ResponseEntity nor declares @ResponseStatus falls back to 200 OK, masking the failure from clients. | Return ResponseEntity/ProblemDetail or add @ResponseStatus so the handler responds with an error status. |
