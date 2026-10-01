# Vulnerabilities checks

The Vulnerabilities advisor looks up known advisories for locally discovered Maven coordinates. It does not probe the
application for exploitability. Spring MVC, Spring WebFlux, and Quarkus share the report contract and neutral evidence
interpretation; Spring and Quarkus retain their native HTTP/JSON adapters.

Security and Pentesting are separate advisors.

::: details Where these rules come from
This catalogue records the evidence rules and complete audit disposition for
[#978](https://github.com/jdubois/boot-ui/issues/978), which concerns OSV interpretation and reporting rather than
inventory repairs or a new scanner. That research submitted no dependency inventory and ran no external scan.
[#989](https://github.com/jdubois/boot-ui/issues/989) added evidence-based panel and Overview scoring, retaining the
cached GET-only dismissal refresh.

The 2026 advisor audit ([§ 2026 advisor audit](#_2026-advisor-audit)) added CVSS v4.0 scoring with a v4-over-v3
preference, `CRITICAL` malicious-package (`MAL-`) advisories, and honest Quarkus inventory coverage. It was grounded in
live OSV.dev Maven records and FIRST's reference calculator, and each change was reviewed by three independent models.
:::

## Reading the result

Each dependency exposes two flags: `assessment.queryComplete`, meaning all its query pages were exhausted, and
`assessment.detailAssessmentComplete`, meaning every returned detail was interpreted or conclusively excluded as
withdrawn. Successful withdrawal is distinct from absent, failed, capped, mismatched, malformed, and unresolved
details.

A genuine no-match has both flags true and an empty retained advisory list. Copies made for dismissal and EPSS
preserve the flags.

Known-severity findings, including NONE, or a fully assessed package establish `evidence.usable`. UNKNOWN stays
visible and carries no penalty, but it cannot establish usability and it limits coverage even after dismissal.
Inventory, query, and detail gaps qualify an otherwise usable known-findings score. See the shared
[score eligibility policy](features/advisors.md#score-eligibility) for the evidence contract and dismissal behavior.

No extra OSV or EPSS work runs on render.

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

## Severity: applicable assessments, CVSS v4 preferred over v3

Selection is a disclosed BootUI policy, not an OSV requirement to choose the maximum:

1. Collect supported CVSS assessments from **all applicable matching affected entries**. If any is a valid v4
   assessment, select the highest v4 score; otherwise select the highest valid v3 Base score. The preference spans the
   whole applicable set, so one entry's v3 number never competes with another entry's v4 number. An unrelated branch
   must not raise the installed version's severity.
2. When no applicable package severity is supplied, apply the same v4-then-v3 selection to the top-level assessments.
3. When applicable package severity is supplied but invalid or unsupported, do not borrow a conflicting top-level
   score from a dual-level record: OSV prohibits package-level and top-level severity coexisting. Retain the recognized
   top-level `database_specific.severity` label fallback, otherwise `UNKNOWN`.

v3 and v4 scores are never compared on one scale. Preferring the newer v4 assessment matches the source database: in a
live OSV.dev sample of 217 GitHub Advisory Database (GHSA)
records for common Maven packages, all 21 records carrying both vectors took their GitHub severity label from the v4
vector, whereas the v3 band disagreed (always higher) for 6 of them, for example GHSA-fpj8-gq4v-p354, CVSS v3 9.1
CRITICAL against GitHub's MODERATE (v4 6.3). The 16 v4-only records, an increasingly common shape for new advisories,
now carry a numeric score instead of only the label.

`CVSS_V3` vectors need a `CVSS:3.0` or `CVSS:3.1` prefix and all eight Base metrics. Accept valid metric orderings and
optional Temporal/Environmental metrics, **validate those optional values**, but compute only the Base score.
Scope-dependent equations and FIRST's one-decimal Roundup remain unchanged.

`CVSS_V4` vectors need a `CVSS:4.0` prefix and all eleven Base metrics (`AV AC AT PR UI VC VI VA SC SI SA`). Every
segment is validated like v3: no duplicates, empty/trailing segments, unknown metrics, malformed segments, or invalid
values, including the optional Threat, Environmental, and Supplemental metrics. The score is FIRST's MacroVector lookup
and interpolation, ported from FIRST's BSD-2-Clause reference calculator and verified against it for all 104,976 Base
metric combinations and 60,000 sampled Threat/Environmental vectors. The vector is scored **as published**: GitHub
often supplies the Threat metric `E:U`, and its label reflects that CVSS-BT score (GHSA-5j33-cvvr-w245 is HIGH 7.2, not
the 9.2 Base-only CRITICAL). Supplemental metrics never change a score.

A valid zero is `NONE`, not unknown. Positive scores use FIRST's LOW/MEDIUM/HIGH/CRITICAL bands, which are identical
for v3 and v4. Recognized database-specific labels remain compatibility behavior for existing providers, not a
universal OSV severity scale; `MODERATE` maps to `MEDIUM`. Bare numeric strings and CVSS v2 are not scored.
Unsupported assessments retain the finding with a recognized database label or `UNKNOWN`. The stable DTO does not say
which CVSS version produced `score`; full assessment vector/source provenance stays deferred.

## Malicious-package advisories

The OSV ID prefix `MAL-` belongs to the [OpenSSF Malicious Packages](https://github.com/ossf/malicious-packages)
database, which OSV.dev serves for Maven, for example MAL-2025-191470 for `org.mvnpm:posthog-node` 4.18.1 (an npm worm
republished through mvnpm). These records carry no severity and no `database_specific.severity`, so they previously read
as `UNKNOWN` with no score penalty.

An advisory whose **own** ID starts with `MAL-` is reported as `CRITICAL` with a null score, never a synthesized 10.0,
and its details lead with BootUI's removal guidance: remove the dependency rather than upgrading it, and treat any
machine that installed or ran it, and its credentials, as compromised. An alias naming a `MAL-` ID does not trigger this.
Package/version interpretation, unresolved evidence, `PARTIAL` status, and withdrawal exclusion are unchanged, so a
withdrawn false-positive report is still excluded. The override is a BootUI priority policy, not a CVSS assessment.

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

Spring MVC and WebFlux enumerate archive candidates from both `java.class.path` and local file URLs in the
application classloader hierarchy. This covers merged, extracted `jarmode=tools --layers --launcher` applications
started through `JarLauncher`, whose library JARs may be absent from the classpath property. The census does not
depend on each JAR having a manifest or Maven descriptor, search arbitrary directories, or open remote URLs.
Archive identification counts remain distinct from SBOM package totals and OSV query completion; an SBOM with no
enumerable archives still has `UNAVAILABLE` coverage.

An archive still unidentified after coordinate attribution is inspected once more, reading only its manifest and
entry names and stopping at the first class outside the application's base packages. A nested `BOOT-INF/lib/` entry is
inspected only when stored uncompressed; only its central directory and a manifest of at most 64 KiB are read, never
its other entries; the end record must end the archive exactly and the directory must parse to its declared size and
count. An archive carrying `META-INF/maven/` descriptors or bundling another archive is never first-party,
single-segment base packages are ignored, and when the Spring Boot `layers.idx` (fat JAR, merged extraction, or the
sibling `application/` of an in-place layered extraction) defines an `application` layer, only archives Boot's
first-match rule assigns to that layer qualify; a present but unreadable index admits none. An archive the index positively places in `application` may also use
each base package's parent when that parent has at least two segments (sibling modules of a launcher subpackage). An archive with at least one class, every class in the `@SpringBootApplication` base
packages, is reported as first-party (`archivesFirstParty`, at most 200 `firstPartyArchives` names plus truncation)
and does not count against `COMPLETE`; it is the application itself and is not scanned. `spring-boot-jarmode-tools`,
which Spring Boot adds at packaging time, is identified from its manifest only when the file name,
`Implementation-Title: Spring Boot Jarmode Tools`, and `Implementation-Version` agree and every class it carries is under
`org/springframework/boot/jarmode/tools/` (with no bundled archive), and is then scanned. For first-party
recognition, unreadable, resource-only, compressed-nested, and oversized (more than 20,000 entries) archives, archives
outside the layers-index `application` layer, a bare name that two different archives carry (the census counts it
once), and every archive when no usable base package is detected, stay unidentified. Extracted `WEB-INF/lib/` archives
honor an adjacent `WEB-INF/layers.idx` the same way. Plain `extract` without `--layers` writes no index, so only the
unwidened base packages apply there. Without a layers
index, a library relocated into the application's own package and stripped of its Maven descriptors cannot be told
apart from application code.

Neither provider supplies a verified runtime graph. Spring's filename census de-duplicates bare filenames and uses
case-insensitive matching without group identity, so ambiguous classifiers/same-basename archives can overstate
identified coverage. PURL form decoding can turn literal `+` into a space; malformed escapes and namespace rewriting
need separate fixes. SBOM traversal caps **resolved distinct coordinates**, not inspected nodes, and parses the whole
JSON first; it is not a whole-document traversal/memory bound. Components are not rigorously filtered to live runtime
scope, and conflicting versions can remain.

Quarkus reports `COMPLETE` only for a wholly decoded build-time model. A missing or blank model key (the build step never
ran; a real Quarkus application always has runtime JARs), a malformed entry, or a runtime JAR coordinate the build step
could not encode (counted in `bootui.internal.dependencies-skipped`) reports `UNAVAILABLE` coverage and keeps every
readable dependency. `UNAVAILABLE` is used rather than `INCOMPLETE` because the panel's unidentified-JAR copy and SBOM
advice describe classpath archives, not model entries. Unreadable container/repackaged archives and missing Spring
census information still require more precise diagnostics; consumers must not equate a reported `COMPLETE` with
independently verified inventory completeness.

Coverage still transports exact reported counts and at most 200 unidentified names with truncation information.
An SBOM can help identify artifacts without Maven descriptors, but is not proof of full coverage. No hash lookup,
external coordinate resolution, shaded-library bytecode discovery, dependency-path graph, or reachability analysis
is added.

## Complete audit disposition

**KEEP** preserves an intentional behavior; **UPDATE** belongs to #978; **DEFER** is a known limitation not repaired
here; **HANDOFF** records the original central-scoring boundary (implemented by #988/#989 below).
IDs below identify audit rows, **not public finding IDs**.
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
| INV-09 | KEEP | Census conventional/manifest-selected nested libraries or classpath JARs without extracting nested contents; only still-unidentified archives have their manifest and entry names read. |
| INV-10 | DEFER | Bare-filename de-duplication, case-insensitive attribution, descriptor-owner attribution, and classifier ambiguity can overclaim coverage. |
| INV-11 | KEEP / DEFER | Preserve unavailable census and unreadable names; repackaged/container/outer-filename fallback precision deferred. |
| INV-12 | KEEP | Exact reported coverage counts, at most 200 unidentified names, explicit truncation. |
| INV-13 | KEEP | Quarkus non-production build-time model emits de-duplicated JAR coordinates and excludes malformed entries. |
| INV-14 | UPDATE | A missing/blank Quarkus model, a malformed entry, or a build-time-skipped coordinate reports UNAVAILABLE, never COMPLETE (2026 audit). |
| INV-15 | KEEP | Explicit non-capabilities: dependency paths, reachability, shaded-content discovery, and hash lookup. |
| INV-16 | KEEP | Archives whose every class lives in the application's (multi-segment) base packages are first-party, counted and named (at most 200) separately, and never a coverage gap; one foreign class, a `META-INF/maven/` descriptor, a bundled archive, or placement outside (or an unreadable) `layers.idx` `application` layer keeps an archive unidentified. |
| INV-17 | KEEP | `spring-boot-jarmode-tools` is the only archive identified from its manifest, and only when file name, title, and version agree and it carries the jarmode tools classes. |

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
| SEV-01 | UPDATE | Typed CVSS_V3 (v3.0/v3.1 prefix) and CVSS_V4 (v4.0 prefix) only; no bare score inference (2026 audit). |
| SEV-02 | KEEP | Base equations, scope-dependent PR, zero impact, integer-based Roundup. |
| SEV-03 | UPDATE | Validate full vector, including optional metrics; reject empty/unknown/duplicate/invalid segments; Base-only scoring. |
| SEV-04 | UPDATE | Maximum applicable package assessment, v4 before v3 across the whole applicable set; genuinely global fallback only; reject conflicting dual-level score borrowing. |
| SEV-05 | KEEP | Zero NONE, positive standard bands, MODERATE to MEDIUM, invalid labels UNKNOWN. |
| SEV-06 | KEEP / DEFER | Keep unsupported v2 findings with label/UNKNOWN; a v2 calculator stays deferred. |
| SEV-07 | UPDATE | CVSS v4.0 scored as published with FIRST's reference algorithm, full validation, Supplemental metrics ignored (2026 audit). |
| SEV-08 | UPDATE | Own-ID `MAL-` advisories are CRITICAL with a null score and removal guidance; aliases never trigger it (2026 audit). |

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
| RPT-02 | KEEP / DEFER | Count distinct advisory IDs per dependency; alias-cluster merging deferred to preserve identities. The 2026 sample observed no GHSA/CVE duplicate pairs for Maven queries. |
| RPT-03 | KEEP | Version-independent advisoryId::packageName dismissals; active counts/order; raw cached report unchanged. |
| RPT-04 | KEEP | Restoring final dismissal returns original cached flags/counts. |
| RPT-05 | KEEP | ERROR may replace cache; DISABLED and busy conflict do not; EPSS failure must not lose OSV report. |
| UI-01 | KEEP | Initial/disabled means not scanned, error unknown, partial means no finding in partial result—not clean. |
| UI-02 | KEEP | Surface reported incomplete/unavailable inventory and max-packages omissions; provider limitations still apply. |
| UI-03 | HANDOFF | Resolved by #989: shared evidence-based eligibility and visible partial/non-score reasons in panel and Overview. |
| UI-04 | HANDOFF | Resolved by #988: Overview cached-report dismissal refresh using GET only; preserved by #989. |
| UI-05 | HANDOFF | #989 averages only eligible advisor and GitHub scores, with a contributing count; missing evidence never supplies a fake zero or 100. |
| UI-06 | HANDOFF | #989 retains incomplete Overview counts and explicit qualification/reasons instead of an idle hint. |
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

- CVSS v4 scores equal to FIRST's calculator for every reachable MacroVector and sampled Threat/Environmental vectors,
  real GHSA vectors including `E:U`, zero impact, metric order, Supplemental metrics, and malformed vectors; v4 preferred
  over a higher v3 at the top level and across mixed applicable package entries, an invalid v4 falling back to v3, and
  an unrelated branch's v4 never displacing the applicable v3 assessment.
- `MAL-` advisories CRITICAL with a null score, removal guidance, retained unresolved flags, and no alias or
  case-variant trigger, through both adapters.
- Quarkus wholly decoded, missing, blank, malformed, and build-time-skipped models.

Scoring/Overview regressions cover partial and complete evidence, UNKNOWN-only dismissal, missing details, completed
no-match dependencies, malformed metadata, exact penalties, qualified aggregates, and GET-only refresh.
Inventory repair acceptance cases, total scan deadline, date/model provenance, reachability, automated upgrades, and presentation version sorting
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
| [FIRST CVSS 4.0](https://www.first.org/cvss/v4.0/specification-document) and [reference calculator](https://github.com/FIRSTdotorg/cvss-v4-calculator) | MacroVector calculation, metric values, CVSS-B/BT/BE/BTE nomenclature, and the BSD-2-Clause reference implementation BootUI ports and tests against. |
| [OpenSSF Malicious Packages](https://github.com/ossf/malicious-packages) and [OSV ID prefixes](https://ossf.github.io/osv-schema/#id-modified-fields) | `MAL-` records and their Maven entries; a malicious package is removed, not upgraded. |
| [GitHub Advisory Database CVSS](https://docs.github.com/en/code-security/security-advisories/working-with-global-security-advisories-from-the-github-advisory-database/about-the-github-advisory-database#cvss-levels) | GitHub's severity levels from CVSS v4 or v3; observed live in OSV.dev GHSA records to follow the v4 vector when both exist. |
| [FIRST EPSS endpoint](https://api.first.org/epss/), [global API contract](https://api.first.org/), and [FAQ](https://www.first.org/epss/faq.html) | CVE parameter bounds, row identity, total/offset/limit, probability versus percentile, and no-data semantics. |
| [FIRST EPSS data/model history](https://www.first.org/epss/data) | Daily updates; research recorded model **v5 starting 2026-06-15**. This is a dated upstream fact, not an API/DTO guarantee; API `/v1` is not the model version. |
| [Google SRE overload guidance](https://sre.google/sre-book/handling-overload/) and [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) | Concurrency, request deadlines, retries, and overall budgets are separate resilience decisions. No retries or total-scan timeout are introduced here. |
| [PURL parsing](https://github.com/package-url/purl-spec/blob/main/docs/specification/how-to-parse.md) and [Maven type](https://github.com/package-url/purl-spec/blob/main/types/maven-definition.json) | Coordinate/percent-decoding context for deferred inventory defects. These main-branch links are mutable; no external coordinate resolver is added. |

## 2026 advisor audit

The audit re-read every interpretation rule against the sources above and live OSV.dev data (15 common Maven packages
at old versions, 217 GHSA records, plus the Maven entries of OpenSSF Malicious Packages). Every existing row above was
kept or updated as marked. Three changes were made, each first reviewed by three independent models
(GPT-6.1 Sol, Claude Opus 5, Grok 4.7), all three supporting each change with amendments that were applied:

| Change | Kind | Reviewer amendments applied |
| --- | --- | --- |
| CVSS v4.0 scoring, v4 preferred over v3 (SEV-01, SEV-04, SEV-07) | Fix | Preference spans the whole applicable set; never compare v3 with v4; score vectors as published after the reviewers questioned Base-only scoring, which GitHub's `E:U` labels disproved; verify against FIRST for every MacroVector. |
| `MAL-` advisories are CRITICAL (SEV-08) | Added signal | Pass the ID to the shared interpreter; own ID only; null score, never a synthesized one; removal guidance in details; unresolved evidence kept independent. |
| Quarkus coverage honesty (INV-14) | Fix | Count build-time skipped coordinates; ignore blank tokens; use UNAVAILABLE instead of INCOMPLETE, whose copy describes JAR files and SBOMs. |

Considered and deliberately not added:

| Candidate | Reason |
| --- | --- |
| CISA KEV flag | A second external source with a feed larger than the 1 MiB body bound, a DTO field, configuration, and UI on every stack. KEV (observed exploitation) is not equivalent to EPSS (predicted exploitation), but the cost outweighs the gain for now. |
| Alias/duplicate collapsing | No GHSA/CVE duplicate pairs observed for Maven queries; merging would change `advisoryId::packageName` dismissal identities (RPT-02). |
| CycloneDX `scope: excluded` filtering | `cyclonedx-maven-plugin` emits `excluded` only with the non-default `detectUnusedForOptionalScope`; SBOM scope attribution stays deferred as INV-05. |
| Version provenance for `score` | Needs a DTO change; the catalog documents that `score` is the selected CVSS version's score instead. |

See the [feature guide](features/advisors.md#vulnerabilities) for the user workflow and
[specification §5.11](SPECIFICATION.md#_5-11-vulnerabilities-panel) for the stable panel contract.
