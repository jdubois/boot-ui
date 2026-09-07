# Vulnerabilities checks

The Vulnerabilities advisor looks up known advisories for locally discovered Maven coordinates. It does not probe the
application for exploitability. Spring MVC, Spring WebFlux, and Quarkus share the report contract and neutral evidence
interpretation; Spring and Quarkus retain their native HTTP/JSON adapters.

This catalogue records the evidence rules and complete audit disposition for
[#978](https://github.com/jdubois/boot-ui/issues/978). Research did not submit a dependency inventory or run an external
scan. The change concerns OSV interpretation and reporting, not inventory repairs or a new scanner. **Advisor scores,
Overview, gauges, score eligibility, and dismissal refresh are handed off to the independent central scoring workstream;
they are not implemented by this change.** Security and Pentesting remain separate advisors.

## Reading the result

Keep three kinds of evidence separate:

1. **Inventory coverage** describes what the local provider could identify. Its reported `COMPLETE` is not independent
   verification of every runtime component; known provider overclaims remain deferred below.
2. **OSV scan status** describes query/detail completion and whether the returned association can be interpreted.
   A positive package/version query remains the detection authority. Unsupported or contradictory detail evidence
   retains the finding, reports `PARTIAL` with an explanation, and cannot justify an unaffected verdict.
3. **Optional EPSS enrichment** supplies a per-CVE prioritization signal. Its failures append an explanation without
   changing OSV status, CVSS severity, or finding counts.

`NOT_SCANNED`/`DISABLED`, `ERROR`, and `PARTIAL` are not clean scans. An empty partial finding list means only that no
finding was retained in that partial result. Even a completed lookup does not prove the application safe, reachable code
free of vulnerabilities, or the upstream database exhaustive.

The immutable DTO fields, routes, MCP tools, CLI commands, configuration defaults, and
`advisoryId::packageName` dismissal identities do not change. Findings count **advisory occurrences per dependency**,
not unique CVEs: different advisory IDs can describe the same CVE.

## Package and affected-version interpretation

The engine consumes neutral evidence, not Jackson nodes or framework types. Its internal applicability has three
outcomes: **matched**, **not matched**, and **unresolved**. Unresolved is never silently converted into not affected.

- Match the ecosystem exactly as `Maven` and the package exactly as `groupId:artifactId`. The OSV package name `*`
  is the one literal wildcard value, for that ecosystem; arbitrary patterns such as `org.example:*` are not globs.
  Repository-specific Maven ecosystems and other ecosystems are not interchangeable with Maven Central.
- Explicit `versions` membership and supported ranges form a **union**, across all matching affected entries.
  Only Maven `ECOSYSTEM` ranges are locally evaluated. `SEMVER`, `GIT`, unknown domains, and malformed evidence do
  not supply Maven upgrade targets or negative vulnerability verdicts.
- Order range events using the existing Maven comparator, not SemVer or lexical order. `introduced` is inclusive;
  `fixed` is exclusive; `last_affected` is inclusive; `limit` is an exclusive scope boundary, not a fix.
  `introduced: "0"` precedes every version and `limit: "*"` is unbounded. Multiple limits expand scope rather than
  intersecting it. Equal `introduced` and `last_affected` boundaries describe an inclusive singleton, including
  Maven-equivalent spellings and either input order. Reintroduced intervals and unsorted events must be interpreted,
  not flattened.
- Validate event shape: exactly one supported event type per event, an introduction in each range, and no coexistence
  of `fixed` and `last_affected` in an event array. Missing or unusable evidence remains unresolved.
- Do not remove a query-derived advisory because local detail interpretation cannot establish its association.
  Omit unsupported package-specific claims, retain genuinely global severity where applicable, and explain incomplete
  or contradictory evidence through the existing scan status/message.

These are supported-domain interpretation rules, not a replacement OSV matching service. OSV documents case-sensitive
queries and potentially fuzzy version matching. Provider-specific platform, reachability, and repository metadata are
not independently evaluated. See [OSV schema](#sources-and-version-caveats) and the Maven ordering references below.

## Severity: applicable assessments, strict CVSS v3 Base only

Selection is a disclosed BootUI policy, not an OSV requirement to choose the maximum:

1. Collect valid supported CVSS v3 assessments from **all applicable matching affected entries** and select the highest
   Base score. An unrelated branch must not raise the installed version's severity.
2. When no applicable package severity is supplied, select the highest valid top-level v3 assessment.
3. When applicable package severity is supplied but invalid or unsupported, do not borrow a conflicting top-level
   score from a dual-level record: OSV prohibits package-level and top-level severity coexisting. Retain the recognized
   top-level `database_specific.severity` label fallback, otherwise `UNKNOWN`.

Only `CVSS_V3` vectors with a `CVSS:3.0` or `CVSS:3.1` prefix are scored. Require all eight Base metrics, valid
metric/value pairs, and no duplicates, empty/trailing segments, unknown metrics, or malformed segments. Accept valid
metric orderings and optional Temporal/Environmental metrics, **validate those optional values**, but compute only
the Base score. Scope-dependent equations and FIRST's one-decimal Roundup remain unchanged.

A valid zero is `NONE`, not unknown. Positive Base scores use FIRST's LOW/MEDIUM/HIGH/CRITICAL bands. Recognized
database-specific labels remain compatibility behavior for existing providers, not a universal OSV severity scale;
`MODERATE` maps to `MEDIUM`. Bare numeric strings and CVSS v2/v4 are not v3 vectors. Unsupported assessments retain
the finding with a recognized database label or `UNKNOWN`.

CVSS v4 has a public FIRST reference calculator, but requires its own MacroVector lookup/interpolation and validation;
implementing it is deferred, not blocked by unavailable reference data. Full assessment vector/source provenance is
also not exposed by the stable DTO.

## Fix candidates: relevant interval and evidence-backed target

`fixedVersions` is a bounded list of **reported upgrade candidates**, not a promise of compatibility, artifact
publication, reachability remediation, or an automated upgrade.

- A candidate must come from a `fixed` event closing a supported affected interval containing the installed version.
  A fix from an unrelated branch or an open-ended installed interval is not enough.
- The target must be positively comparable and newer under Maven ordering. An inconclusive comparison is not evidence
  that a fix is available.
- Recheck each target against **all matching affected entries**, including explicit versions, overlapping intervals,
  and reintroductions. A candidate still affected anywhere, or unresolved under any relevant evidence, is not a
  verified target.
- Neither `last_affected` nor `limit` identifies an upgrade; Git hashes, SEMVER ranges, and arbitrary range types do
  not become Maven fixes.
- Filter relevant newer targets before de-duplication, Maven ordering, and the ten-candidate display limit. Unrelated
  old fixes must not crowd out a useful candidate.

`fixAvailable=false` and an empty `fixedVersions` list mean no candidate was established under these rules. They do
not mean the installed dependency is safe, no upstream fix exists, or every upstream fixed event was absent.

For example, installed `1.5` with affected branches `[1.0,1.9)` LOW and `[2.0,2.4)` CRITICAL uses the first branch's
severity and closing fix, not the second branch's assessment. If another matching entry explicitly lists `1.9` as
affected, `1.9` cannot be presented as a verified upgrade.

## OSV queries, details, and partial completion

Passive GET/report reads return local inventory or cached data; they never initiate an OSV or FIRST call. Explicit
enabled scans send Maven package names, ecosystem, and installed versions to the configured OSV service; detail
requests send advisory IDs. No application source, local paths, classpath contents, or credentials are submitted.
Localhost/Host protection, cross-site-write checks, per-panel enable/read-only policy, and single-flight admission
remain unchanged. A busy conflict preserves the cached report.

Distinct package/version inputs are capped before querying. Requests contain at most **1,000 queries**, with
per-query continuation tokens and at most **20 page rounds per chunk**. Every successful page must have exactly one
structurally valid result per submitted query; advisory references require nonblank IDs and tokens must be string/null.
An empty result without a token completes a query; an empty token-bearing page does not.

Validated successful pages are retained incrementally. A later network, HTTP, JSON, shape/cardinality, timeout, or
byte-bound failure preserves earlier pages, earlier completed queries in the same chunk, and earlier chunks, then
reports `PARTIAL`. Failure before any valid query page returns `ERROR` while retaining local inventory. Remaining
chunks are not attempted after a query failure. Token cycles terminate at the fixed page bound, not through retries.

`packagesScanned` counts only queries exhausted without a continuation token, including when the cap is reached.
`packagesSkipped` counts only the configured max-packages omission, not failed or unfinished queries; explain those
in the message. A retained page can contribute a finding even when its query is not yet counted as scanned.

Distinct advisory IDs are sorted and capped by max-advisories before detail fetching, with at most **10** requests
active. This limits details, not the number of query matches. Repeated IDs are de-duplicated per dependency; missing or
mismatched detail IDs count as failed fetches. Successful details survive other failures as `PARTIAL`. Withdrawn
records are excluded at the detail stage because a detail GET can still return a withdrawn advisory.

Existing streaming response caps remain **5 MiB** for querybatch and **1 MiB** for details/EPSS, with configured
per-request timeouts and no automatic redirect following. Configurable service bases remain supported, including
loopback fixtures; this change adds no external destination. A per-request timeout is **not a whole-scan deadline**.
Whole-scan deadlines, retry/backoff, and a cancellation-preserving redesign are deferred; existing interrupt handling
and restoration remain the contract.

## EPSS: independent, bounded, and explicit

When independently enabled, explicit scans extract canonical CVE IDs from the advisory's own ID and retained aliases
(the DTO retains at most 20 aliases). De-duplicate IDs and use chunks whose comma-separated `cve` parameter is at most
**2,000 characters**. Disabled enrichment or no canonical CVEs makes no FIRST request.

Validate an object response root and array `data`, plus supplied numeric `total`, `offset`, and `limit` metadata.
Empty bodies, malformed JSON/envelopes, non-success responses, and invalid rows are unavailable/incomplete enrichment,
not exceptions that discard OSV findings. Rows must belong to the requested chunk; probability and percentile must
both be finite and in `[0,1]`. Shared selection defensively validates these values too.

Honor pagination metadata, including when the service returns a smaller page than requested. Each chunk is bounded to
at most **min(20, requested CVE count)** pages; detect lack of pagination progress rather than looping or declaring
unreturned CVEs absent. Once a page supplies pagination metadata, subsequent pages must preserve it; a metadata-free
continuation is incomplete, not a successful no-data result. Retain successful earlier pages and chunks on later failure.
A successful exhausted query with
no row is **no data**, not a returned zero. Append a requested/available/no-data summary, or an incomplete/failure
summary, to `scan.message` without dumping the CVE inventory and without changing OSV scan status.

For a multi-CVE advisory, select the **maximum AVAILABLE per-CVE probability**, with the percentile from that same
record and a stable CVE tie-break. A missing alias must not hide another alias's valid result; zero is an available
score, not unknown. This maximum is a BootUI prioritization heuristic, **not the combined probability that the
advisory or application will be exploited**. Probabilities are never summed or combined by multiplying complements.

EPSS estimates exploitation in the wild in the next 30 days, whereas CVSS describes severity if exploited.
Probability and percentile are distinct. The unchanged DTO does not expose the selected CVE, score date, or model
version; separate latest-data requests can straddle a daily update. Do not imply the scalar represents every alias,
is date-pinned, or demonstrates zero risk.

## Inventory limitations: explicitly deferred

Spring merges SBOM, Maven descriptors, and adjacent-POM/classpath evidence by coordinate/version with source priority
for identical coordinates. That source is discovery provenance, not a dependency path. Quarkus uses a build-time
runtime dependency model rather than an SBOM requirement.

Neither provider supplies a verified runtime graph. Spring's filename census de-duplicates bare filenames and uses
case-insensitive matching without group identity, so ambiguous classifiers/same-basename archives can overstate
identified coverage. PURL form decoding can turn literal `+` into a space; malformed escapes and namespace rewriting
need separate fixes. SBOM traversal caps **resolved distinct coordinates**, not inspected nodes, and parses the whole
JSON first; it is not a whole-document traversal/memory bound. Components are not rigorously filtered to live runtime
scope, and conflicting versions can remain.

Quarkus can currently report `COMPLETE` even when its model key is missing/blank or malformed entries were skipped.
Unreadable container/repackaged archives and missing census information also require more precise diagnostics.
These coverage overclaims are **not repaired by #978**; consumers must not equate a reported `COMPLETE` with
independently verified inventory completeness.

Coverage still transports exact reported counts and at most 200 unidentified names with truncation information.
An SBOM can help identify artifacts without Maven descriptors, but is not proof of full coverage. No hash lookup,
external coordinate resolution, shaded-library bytecode discovery, dependency-path graph, or reachability analysis
is added.

## Complete audit disposition

**KEEP** preserves an intentional behavior; **UPDATE** belongs to #978; **DEFER** is a known limitation not repaired
here; **HANDOFF** belongs to independent central scoring work. IDs below identify audit rows, **not public finding IDs**.
Mixed dispositions intentionally preserve an existing behavior while acknowledging its unresolved limitations.

### Inventory

| ID | Disposition | Behavior and boundary |
| --- | --- | --- |
| INV-01 | KEEP | Merge Spring discovery sources with priority for identical coordinates; source is not dependency-path provenance. |
| INV-02 | KEEP / DEFER | Coordinate-only Maven PURL lookup ignores qualifiers/subpath; stricter inventory parsing deferred. |
| INV-03 | DEFER | Literal-plus form decoding, malformed percent escapes, and slash-namespace rewriting can change identity. |
| INV-04 | DEFER | SBOM recursion bounds resolved coordinates, not inspected nodes; whole-document parsing remains. |
| INV-05 | DEFER | SBOM runtime scope/type attribution and conflicting-version precision. |
| INV-06 | KEEP / DEFER | Retain readable Maven descriptors when siblings fail; malformed-properties/runtime exceptions and richer diagnostics deferred. |
| INV-07 | KEEP | Adjacent POM must match artifact/version; parent group/version allowed; external entities, DTDs, and schema access blocked. |
| INV-08 | KEEP | Infer group only below literal `repository`; filename must match artifact/version with optional classifier. |
| INV-09 | KEEP | Census conventional/manifest-selected nested libraries or classpath JARs without extracting nested contents. |
| INV-10 | DEFER | Bare-filename de-duplication, case-insensitive attribution, descriptor-owner attribution, and classifier ambiguity can overclaim coverage. |
| INV-11 | KEEP / DEFER | Preserve unavailable census and unreadable names; repackaged/container/outer-filename fallback precision deferred. |
| INV-12 | KEEP | Exact reported coverage counts, at most 200 unidentified names, explicit truncation. |
| INV-13 | KEEP | Quarkus non-production build-time model emits de-duplicated JAR coordinates and excludes malformed entries. |
| INV-14 | DEFER | Missing/blank or partially decoded Quarkus model can still report default COMPLETE. |
| INV-15 | KEEP | Explicit non-capabilities: dependency paths, reachability, shaded-content discovery, and hash lookup. |

### Query and detail transport

| ID | Disposition | Behavior and boundary |
| --- | --- | --- |
| QRY-01 | KEEP | Passive GET, explicit enabled POST, single-flight conflict preserving cache; inventory collection precedes admission. |
| QRY-02 | KEEP | De-duplicate package/version before cap; skipped is cap omission; minimum effective limit one. |
| QRY-03 | KEEP | Send exact Maven ecosystem, package, and installed version, not local source/paths/credentials. |
| QRY-04 | KEEP | At most 1,000 queries/request and 20 page rounds/chunk, per-query tokens. |
| QRY-05 | KEEP | Validate object results, exact cardinality, nonblank advisory IDs, and string/null tokens. |
| QRY-06 | KEEP | Empty valid result without a continuation token completes the query. |
| QRY-07 | KEEP | Initial failure before any successful page is ERROR with local inventory. |
| QRY-08 | KEEP | Later chunk failure preserves earlier chunks as PARTIAL and stops subsequent chunks. |
| QRY-09 | UPDATE | Later page failure retains earlier validated pages and completed queries in that chunk. |
| QRY-10 | UPDATE | Page-cap accounting counts only exhausted queries; message explains unfinished work. |
| QRY-11 | KEEP | Token cycles terminate at cap; repeated IDs are de-duplicated before details/report. |
| QRY-12 | KEEP | Per-request timeout and strict streaming byte budgets: 5 MiB query, 1 MiB detail/EPSS. |
| QRY-13 | DEFER | No total elapsed scan deadline across chunks/pages/detail waves/enrichment. |
| QRY-14 | KEEP | No automatic redirects; encoded detail IDs; configurable bases; bounded error messages, not response-body dumps. |
| DET-01 | KEEP | Sorted distinct IDs, configured detail cap, concurrency ten, executor shutdown. |
| DET-02 | KEEP | Missing/mismatched detail ID is a failed fetch. |
| DET-03 | KEEP | Retain successful details when other fetches fail; PARTIAL rather than fabricated complete data. |
| DET-04 | KEEP | Nonblank withdrawal excludes a record; malformed withdrawal-field interpretation remains an upstream-schema limitation. |
| DET-05 | UPDATE | Interpret query/detail association without dropping query matches on unsupported local evidence. |
| DET-06 | UPDATE | Aggregate applicable matching entries, not first severity/all flattened fixes. |
| DET-07 | UPDATE | Tri-state explicit versions/range union with introduced, fixed, last_affected, and limit semantics. |
| DET-08 | UPDATE | Maven ECOSYSTEM evidence only for local range evaluation and targets; no hash/SEMVER reinterpretation. |

### Fixes and severity

| ID | Disposition | Behavior and boundary |
| --- | --- | --- |
| FIX-01 | KEEP | Only fixed identifies a target, never last_affected or limit. |
| FIX-02 | UPDATE | Candidate closes installed affected interval and is unaffected across all matching entries. |
| FIX-03 | UPDATE | Filter applicable/newer targets before de-duplication/order/ten-candidate truncation. |
| FIX-04 | KEEP | Existing Maven comparator and test-only ComparableVersion oracle; no production Maven dependency. |
| FIX-05 | UPDATE | Inconclusive comparison no longer establishes fixAvailable; false is not an unaffected verdict. |
| SEV-01 | KEEP | Typed CVSS_V3 with v3.0/v3.1 prefix only; no bare score inference. |
| SEV-02 | KEEP | Base equations, scope-dependent PR, zero impact, integer-based Roundup. |
| SEV-03 | UPDATE | Validate full vector, including optional metrics; reject empty/unknown/duplicate/invalid segments; Base-only scoring. |
| SEV-04 | UPDATE | Maximum applicable package assessment; genuinely global fallback only; reject conflicting dual-level score borrowing. |
| SEV-05 | KEEP | Zero NONE, positive standard bands, MODERATE to MEDIUM, invalid labels UNKNOWN. |
| SEV-06 | KEEP / DEFER | Keep unsupported v2/v4 findings with label/UNKNOWN; validated additional calculators deferred. |

### EPSS

| ID | Disposition | Behavior and boundary |
| --- | --- | --- |
| EPS-01 | KEEP | Explicit independently enabled scan, canonical CVEs from own ID/retained aliases only. |
| EPS-02 | KEEP | De-duplicated CVEs, 2,000-character chunks, explicit limit, request byte/time bounds. |
| EPS-03 | KEEP | Requested IDs only; finite probability/percentile in [0,1]; missing is not zero; defend shared selection. |
| EPS-04 | UPDATE | Validate root/data and pagination metadata; isolate malformed/empty envelopes as unavailable/incomplete enrichment. |
| EPS-05 | UPDATE | Maximum AVAILABLE per-CVE probability, same-record percentile, stable tie-break; not combined likelihood. |
| EPS-06 | UPDATE | Bound pages/progress, retain earlier pages/chunks, append honest requested/available/no-data or failure summary; OSV status unchanged. |
| EPS-07 | DEFER | Selected CVE/date/model provenance and cross-request date pinning require separate DTO work. |
| EPS-08 | KEEP | At most 20 retained aliases bounds enrichment scope; no claim to cover every upstream alias. |

### Reports, presentation, and API

| ID | Disposition | Behavior and boundary |
| --- | --- | --- |
| RPT-01 | KEEP | Immutable DTOs; active findings determine counts; fixed severity ordering. |
| RPT-02 | KEEP / DEFER | Count distinct advisory IDs per dependency; alias-cluster merging deferred to preserve identities. |
| RPT-03 | KEEP | Version-independent advisoryId::packageName dismissals; active counts/order; raw cached report unchanged. |
| RPT-04 | KEEP | Restoring final dismissal returns original cached flags/counts. |
| RPT-05 | KEEP | ERROR may replace cache; DISABLED and busy conflict do not; EPSS failure must not lose OSV report. |
| UI-01 | KEEP | Initial/disabled means not scanned, error unknown, partial means no finding in partial result—not clean. |
| UI-02 | KEEP | Surface reported incomplete/unavailable inventory and max-packages omissions; provider limitations still apply. |
| UI-03 | HANDOFF | Dedicated-panel/Overview score mismatch; complete-evidence eligibility and explicit non-score reasons owned centrally. |
| UI-04 | HANDOFF | Overview cached-report dismissal refresh, using GET only, owned centrally. |
| UI-05 | HANDOFF | Overall finite-contribution denominator/scored count with available total unchanged, owned centrally. |
| UI-06 | HANDOFF | Retain incomplete Overview counts and explicit reason instead of idle hint, owned centrally. |
| UI-07 | DEFER | Browser's same-package lexical version sort differs from server Maven ordering; no second comparator. |
| UI-08 | UPDATE | Applicable-target-only fix list; EPSS copy describes highest available per-CVE prioritization signal. |
| UI-09 | KEEP / DEFER | Bounded ADVISORY/FIX link priority and known alias links; reference-scheme normalization deferred. |
| API-01 | KEEP | All three stacks, configured mounts, localhost/Host/cross-site-write and read-only policy unchanged. |
| API-02 | KEEP | REST/MCP/CLI share interpretation; names/schema stable, generated descriptions remain mechanical projections. |

## Regression obligations

These are acceptance cases for the implementation, **not a claim that validation was run while writing this page**:

- Exact ecosystems/package/wildcard, repository-specific mismatch, multiple branches, explicit-list-only and union
  matches; unsorted/reintroduced intervals, all endpoint equalities, zero/infinite/multiple limits, malformed events,
  missing introductions, unsupported domains, and Maven qualifiers/aliases against the existing oracle.
- Applicable low severity versus unrelated high severity; multiple applicable maximum assessments; invalid package
  severity with contradictory top-level score; global/database-label/zero/UNKNOWN fallbacks; valid optional CVSS
  metrics versus unknown, duplicate, empty, trailing, malformed, and invalid values.
- Closing fix versus unrelated branch/open interval, explicit affected target/overlap/reintroduction, unknown comparison,
  and more than ten unrelated old fixes.
- Completed query plus token-only query, later HTTP/JSON/cardinality/timeout/body-limit failure, first failure, later
  chunk failure, page cap/cycles/duplicate IDs, retained findings, and exact exhausted-query accounting.
- Reversed CVE aliases, own CVE, missing first/all aliases, true zero, stable ties and matching percentile, invalid
  values/unrequested IDs, malformed/empty envelopes, short pages, stalled pagination, bounded pages, failed later
  pages/chunks, and disabled/no-CVE no-call behavior.
- Equivalent neutral results through Jackson 3 and Jackson 2, unchanged cached/dismiss/restore identities and policy,
  no GET-triggered external calls, and retained partial browser rows with accurate local EPSS wording.

Scoring/Overview regression obligations remain with the central scoring change. Inventory repair acceptance cases,
CVSS v4, total scan deadline, date/model provenance, reachability, automated upgrades, and presentation version sorting
are deferred, not silently included in this evidence-interpreter change.

## Sources and version caveats

The audit used official sources. OSV schema links below are pinned to revision
`b388a18021a32b55da40c31eaef9fd4ce780447d`, whose documentation identifies schema **1.9.0**. Rendered/current
documentation may differ; old records can omit `schema_version` (default **1.0.0**). Optional modern fields must not be
assumed present in every advisory. API pagination thresholds and upstream model versions can change.

| Source | What it establishes |
| --- | --- |
| [OSV affected/package schema](https://github.com/ossf/osv-schema/blob/b388a18021a32b55da40c31eaef9fd4ce780447d/docs/schema.md#affected-field) | Ecosystem/package identity, Maven repository distinctions, multiple affected entries, literal wildcard. |
| [OSV versions/ranges/evaluation](https://github.com/ossf/osv-schema/blob/b388a18021a32b55da40c31eaef9fd4ce780447d/docs/schema.md#evaluation) | Explicit/range union, domain ordering, inclusive/exclusive events, introduction zero and limit semantics. |
| [OSV severity](https://github.com/ossf/osv-schema/blob/b388a18021a32b55da40c31eaef9fd4ce780447d/docs/schema.md#severity-field) | Typed vectors and mutually exclusive package/top-level assessments; maximum selection is BootUI policy. |
| [OSV query](https://google.github.io/osv.dev/post-v1-query/) and [querybatch](https://google.github.io/osv.dev/post-v1-querybatch/) | Case sensitivity, potentially fuzzy versions, ordered batch responses, detail fetch requirement, per-query tokens. |
| [OSV OpenAPI](https://osv.dev/docs/osv_service_v1.swagger.json) | Service response contract and 1,000-query batch bound; errors are not empty success. |
| [Maven version order](https://maven.apache.org/pom.html#Version_Order_Specification) and [ComparableVersion 3.9.11 Javadoc](https://maven.apache.org/ref/3.9.11/maven-artifact/apidocs/org/apache/maven/artifact/versioning/ComparableVersion.html) | Qualifier aliases, numeric transitions, separator nesting and release normalization, not SemVer 2.0. The repository's test-only **3.9.16** oracle remains the executable compatibility target, distinct from this versioned Javadoc. |
| [FIRST CVSS 3.0](https://www.first.org/cvss/v3.0/specification-document), [3.1](https://www.first.org/cvss/v3.1/specification-document), and [3.1 user guide](https://www.first.org/cvss/v3.1/user-guide) | Full vector validation, Base metrics/equations, scope, optional metrics, Roundup, qualitative zero. |
| [FIRST CVSS 4.0](https://www.first.org/cvss/v4.0/specification-document) and [reference calculator](https://github.com/FIRSTdotorg/cvss-v4-calculator) | Separate MacroVector calculation and public reference implementation; not covered by v3 equations. |
| [FIRST EPSS endpoint](https://api.first.org/epss/), [global API contract](https://api.first.org/), and [FAQ](https://www.first.org/epss/faq.html) | CVE parameter bounds, row identity, total/offset/limit, probability versus percentile, and no-data semantics. |
| [FIRST EPSS data/model history](https://www.first.org/epss/data) | Daily updates; research recorded model **v5 starting 2026-06-15**. This is a dated upstream fact, not an API/DTO guarantee; API `/v1` is not the model version. |
| [Google SRE overload guidance](https://sre.google/sre-book/handling-overload/) and [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) | Concurrency, request deadlines, retries, and overall budgets are separate resilience decisions. No retries or total-scan timeout are introduced here. |
| [PURL parsing](https://github.com/package-url/purl-spec/blob/main/docs/specification/how-to-parse.md) and [Maven type](https://github.com/package-url/purl-spec/blob/main/types/maven-definition.json) | Coordinate/percent-decoding context for deferred inventory defects. These main-branch links are mutable; no external coordinate resolver is added. |

See the [feature guide](features/advisors.md#vulnerabilities) for the user workflow and
[specification §5.11](SPECIFICATION.md#_5-11-vulnerabilities-panel) for the stable panel contract.
