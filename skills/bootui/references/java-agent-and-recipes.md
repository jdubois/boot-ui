# Java agent probes and runtime recipes

## Attach the optional Java agent

The BootUI Java agent powers Code Inventory, Code Paths, Side Effects, method probes, caught-exception findings, and
executor-propagation diagnostics. When the user asks whether a change ran, where a route's handler time goes, what the
application starts or contacts, or which exceptions code catches, and the agent is not attached, propose attaching it
and ask permission first: it changes how the application is launched. It is optional, local-only, and development-time
only; never add it to a production or AOT-cached JVM.
Read `bootui agent status --json` / `get_agent_status` first: the report includes the exact jar path and copyable
Maven, Gradle, Quarkus dev, Surefire/Failsafe, IntelliJ, and `JAVA_TOOL_OPTIONS` snippets. If the jar is missing, follow
the report's `curl-download` snippet (the agent must be BootUI's exact version) before adding `-javaagent:<path>`.

The default sensors are `executors`, `inventory`, `code-paths`, `processes`, `network`, `files`, `blocking`, and
`resources`; `environment` is opt-in. Setting `bootui.agent.sensors` replaces the list rather than extending it.
Use the application's reported snippets, including JVM quoting for paths with spaces; for a custom local Maven
repository, the download and the application JVM's expected path must refer to the same repository.

Before relying on anything that needs the agent (Code Paths, Code Inventory, Side Effects, method probes, or an
observation such as `changed-code-not-executed`), read `bootui agent status --json` once. An observation
`NOT_APPLICABLE` because it requires the BootUI agent was not measured: never read it, or a missing agent command, as
healthy, as "nothing to worry about", or as a passed check. `ARMED` alone does not prove a sensor passed its asynchronous
self-test: inspect the sensor state and reason. Agent status takes `query`, not `limit`. Executor overflow warns that a
shared task object may have run under another request's context; do not treat affected propagation as exact evidence.
Task and thread snapshot registries each retain at most 32,768 application entries across all claims plus 64 reserved
self-test markers. `overflow` and `neverApplied` remain cumulative after disabling a sensor, until the JVM exits.

## Verify that a change ran, then probe

With the agent attached, a "did my change run?" question is one named workflow, verify then probe:

1. After the edit and a DevTools restart or Quarkus live reload, run `bootui code inventory --json`
   (`get_code_inventory`, query `changed` by default). It lists the methods changed or added since the previous run,
   each `EXECUTED`, `NEVER_EXECUTED`, or `NOT_TRACKED` in this run, with the first request and route that ran it.
   `NOT_TRACKED` is not evidence either way.
2. A changed method still `NEVER_EXECUTED` has not run yet: run the test or send the request that should reach it, then
   read the inventory again before claiming the change works or reading any latency.
3. If it still did not run, the next step is a method probe. Ask the user for separate approval, then
   `bootui probe start <method> --json` with the method as Code Inventory names it. It returns `starting`: poll
   `bootui probe show <id> --json` until it is `active`, then, within its 60-second window (before its `endsAt`; send
   the request rather than a slow test run), rerun the same test or request and read it again (see
   [Check whether a method runs, and how](#check-whether-a-method-runs-and-how)). `invocations` at 0 is evidence that
   path never reaches the method (the wrong route, the wrong bean, such as another bean's method of the same name, or
   never wired) only when the probe was active before the rerun and is still active after it, or the rerun finished
   before its `endsAt` and it did not end early. A probe that never became active, failed, or ended before the rerun
   finished is inconclusive. `bootui code paths --query "<route>" --json` shows which methods the route did
   run.
4. Report plainly whether the change ran and on which route; never report it verified while it is `NEVER_EXECUTED`.

Without the agent these commands are not available here: they exit `1` with the panel's reason (`2` when the panel is
disabled), and MCP answers with the same reason. `bootui agent status --json` says why, and whether the change ran is
not measured. Say so, rather than calling the change verified.

## Find where a slow route's handler time goes

With the agent attached, run `bootui code paths --json` (`get_code_paths`): routes ranked by their warm median, each
with its top application methods by self time per request. Pass a route as `--query` (for example
`"GET /api/orders/{id}"`) to get that route's method nodes with the most self time, each with `calls`: the SQL, REST
client, cache, and AI calls it issued per request. Those calls are part of its self time, so read it beside
`bootui insights list --json`, whose `route-time-breakdown` splits the handler by each method's own time, without its
stamped calls, and whose `repeated-selects` names the method that issued a repeated statement. A statement Hibernate
flushes at commit shows under the method that called the `@Transactional` one, since the commit runs after it returned.
A call recorded on another thread than the one that issued it, as a streaming AI call, carries no stamp and shows under
no method. An `assemblyOnly` route's handler ran on an event loop, returned a reactive or
asynchronous result, or BootUI could not tell where its work ran: its tree times assembly, not the work, so do not
optimize from it. A class or method as `--query` lists the routes whose requests ran it, including a route sent only
once, which has no method times until it is sent again.

## Check whether a method runs, and how

With the agent attached and the user's separate approval, `bootui probe start <method> --json`
(`start_method_probe`) records one application method's next 20 invocations, for at most 60 seconds: name it
`binary.Class#name`, with its JVM descriptor when it is overloaded (`com.example.PriceService#quote(I)J`), as Code Paths
and Code Inventory name it. It is an action: read-only policy refuses it, and it changes the running code for its
window. It starts `starting` and retransforms the method afterwards, so poll `bootui probe show <id> --json`
(`get_method_probe`) until it is `active` before you run the test or send the request that should reach the method, then
read it again before its `endsAt` (60 seconds from activation): each invocation's duration, thread kind, request id,
outcome or exception type, and calling frame, never argument or return values; shapes the user asked for in the panel
stay there. `invocations` at 0 is evidence the path never reaches the method only when the probe was active before the
code ran and is still active, or the code finished before its `endsAt` and it did not end early; a probe that never
became active, failed, or ended before the code finished is inconclusive (`dropped` above 0 means invocations it could
not record). A
probe `waitingForClass` has not seen this run load its class yet; an `async` method's durations time its result's
assembly only.

## Find what a route started

With the agent attached, run `bootui side-effects --json` (`get_side_effects`): every sensor's coverage first, then the
most frequent rows. In this version, `processes` records the sanitized command name only, never its arguments or
environment: it is cut at whitespace or `=`, reduced to the basename, and unsafe characters become `?`; `network`
records the hosts and ports the application connects to, datagrams, and names the JVM resolved, with the client
recognized from the calling frames, never a byte sent or received; `files` records path patterns (`./` working
directory, `$TMPDIR`, `~`, ids as `{n}`), never contents, with class loading, the JDK, and logging grouped apart; the
opt-in `environment` records variable and property names, never values; `blocking` records `Thread.sleep`,
`Object.wait`, `LockSupport.park`, and blocking network or file operations started on an event loop (Spring WebFlux,
Quarkus), by operation, loop family, and call site, with how long it blocked, not applicable on Spring MVC until a
recorded Reactor Netty WebClient response registers an event loop; the opt-in
`thread-activity` records the threads the application starts and the executors it creates per route, how many a request
starts (`count` / `requests`), and those its code left running when the request ended (`leftRunning`), library and JDK
pools apart, never what a thread holds; the opt-in `thread-locals` names the static field of each thread local a
request or a job left set on its pooled thread, never its value; `resources`, on by default, records the streams, channels,
and sockets a request or a job opened (files through `files`, on by default), by resource kind, target, and origin: `failed`
counts those the garbage collector reclaimed never closed, the leak, while `leftRunning` (still open after the
request) and `completed` (closed after it) are a pool's or a cache's hand-off, never contents. Pass `--query
not-captured` to list the outbound calls no panel shows (`capture: not-captured`), or `--query processes`, `network`,
`files`, `blocking`, `thread-activity`, `thread-locals`, `resources`, `security-sinks`, a route, target,
client, or call site to narrow it. All nine current Side Effects sensors ship; a sensor an older attached agent lacks
is reported unavailable. A sensor omitted from the claim is not evidence of no side effects.

The opt-in `security-sinks` sensor reports `request-input-in-sink`: a query or path parameter's value that reached SQL
text, a command, a file path, or an outbound URL unchanged, by parameter name and sink, never the value itself. It
records only while `security-sinks` is enabled, by configuration or an approved runtime switch, and
`bootui.agent.security-sinks.request-values=true` was set at startup;
without both, no row is not evidence of safety, so say the check did not run. A row is a check to perform, never a
vulnerability verdict: "request input reached this SQL text unchanged" asks you to confirm in source that the value is
bound as a parameter, escaped, or chosen from a fixed list; a command argument or file path validated and kept in its
directory; an outbound URL unable to change its host. A row seen in one request so far is weaker evidence. Do not turn
the sensor on yourself: suggest the two properties to the user.

With `security-sinks` enabled by configuration or an approved runtime switch, its JDK checks run even without request-value matching:
deserialization without an `ObjectInputFilter`, weak algorithms, and trust managers or hostname verifiers installed by
application or library code. Treat these rows as checks to perform, never vulnerability verdicts: verify the
deserialized input's origin and filtering, whether an algorithm protects security or serves a non-security checksum,
and certificate-chain and hostname validation in source and effective configuration. An application's trust-manager
class alone does not prove it accepts every certificate. Read each group's coverage and disabled reason first; a
missing row from an unrecorded check means unavailable, not clean. `bootui.agent.security-sinks.request-values=true`
separately enables the request-input matching above; it is not needed for these JDK checks.

## Read MySQL operational evidence

The MySQL operational panel supports Oracle MySQL 8.4 LTS and 9.7 LTS, with live coverage on 8.4.6 and 9.7.2; check
the running catalog for the application's actual capability. It uses JDBC on Spring MVC, WebFlux, and Quarkus,
including named datasources.
MariaDB reached through MySQL Connector/J is read but unsupported: its report has `serverFlavor` `MARIADB`, an INFO
diagnostic, no replication receiver state, and no counter changes. R2DBC-only and reactive-client-only
applications are unsupported; do not install another pool just to enable diagnostics.

1. Prefer `bootui db mysql report --json` / `get_mysql_report`. This reads the sanitized cache, never MySQL.
2. Only after an explicit request or approval, use `bootui db mysql read --json` / `mysql_read`. It is an action
   that performs bounded external collection despite being read-only at the database. Both tools take no arguments;
   never supply SQL, a schema, or a server address.
3. Read report status, section reasons, capabilities, observation times, and limitations. The eight areas are vital
   signs, sessions/blocking, statements, indexes, tables, InnoDB, basic replication, and settings. This is not an
   advisor: there are no grades or recommendations, and partial/unknown evidence is not a pass.
4. Preserve scope: server-wide counters include other clients, and default-schema-associated sessions/digests do not
   cover every cross-schema access. Table sizes/rows may be cached estimates; no recorded index activity is not
   evidence that an index is safe to drop. Failed channel reads do not establish absence of replication.
5. Preserve exact decimal-string counters, byte sizes, and numeric IDs; never round them through JavaScript `Number`.
   `null` means unknown, not zero. Row caps (`truncated`) differ from permissions, disabled instrumentation, server
   digest overflow, or timeout failures. Local filters cover retained rows only.

Do not retry busy/partial/failed reads automatically, enable instrumentation, grant `PROCESS`/other privileges,
request raw session/sample SQL or lock keys, or relax exposure/read-only policy. Exposure changes invalidate the cache
without SQL, including when relaxed; an explained `NOT_READ` still needs approval for new collection.
The seven `bootui.mysql.max-*` row limits are static positive integers below `2147483647`, requiring restart;
raising a CLI timeout does not extend collector or pool budgets. Consult the
[MySQL guide](https://github.com/jdubois/boot-ui/blob/main/docs/features/database.md#mysql) for exact keys,
capability-specific permissions, tested driver/pool combinations, and execution bounds.

## Read retained advisor violations

The Architecture, Hibernate, Spring/Quarkus application, REST API, Memory, Security, and Database advisors report
true `violationCount` values but only bounded `sampleViolations` previews (ten, or twenty for Quarkus application
and Security). Never treat the preview as the full affected-target list. Read the cached report first:

```bash
scan_id=$(bootui architecture report --json | jq -er '.violationDetails.scanId')
bootui architecture violations ARCH-SPRING-004 --scan-id "$scan_id" --offset 0 --limit 100 --json
```

Equivalent commands are `hibernate violations`, `spring violations`, `rest-api violations`, `memory violations`,
`security violations`, and `db violations`, each with positional rule ID and required `--scan-id`.
The seven MCP tools are `get_architecture_rule_violations`, `get_hibernate_rule_violations`,
`get_spring_rule_violations`, `get_rest_api_rule_violations`, `get_memory_rule_violations`,
`get_security_rule_violations`, and `get_database_advisor_rule_violations`. Their arguments are required
`id` and `scanId`, optional integer `offset` (default zero, nonnegative) and `limit` (default 100, positive,
capped at `min(1000, transport max-results)`). Obtain `scanId` from `get_<advisor>_report` first.

Architecture, REST API, and Hibernate reports and pages also carry structured locations: `sampleLocations` aligned
with `sampleViolations`, and `locations` aligned with `violations` (a `null` entry has no location). Each gives
`className`, `memberName`, `kind`, `sourceFile`, `line`, `sourcePath`, and `precision` (`LINE`, `MEMBER`, `CLASS`).
Open `sourcePath` at `line` to go straight to the code; `violationDetails.locationNotes` explains a missing path.
Never parse a location out of the violation text.

After a fix, rescan and read `violationDetails.changes` instead of comparing two reports. The Hibernate and Database
advisors set it from the second scan on (`null` on the first scan and for the other advisors): `newCount`,
`resolvedCount`, `unchangedCount`, up to 50 `resolved` findings (a scan summary lists five), and the `rules` that
moved. Trust `complete`: when it is `false`, `notes` says why and `unverifiedCount` counts findings that could not be
compared (a rule that failed or was skipped, truncated retention, an unreadable datasource), which are **not** fixed.
Their detail pages add `identities` (a stable key such as `v1:unit=orders|entity=com.acme.Order|member=lines`) and
`changes` (`NEW`, `UNCHANGED`, or `null`), aligned with `violations`; follow one finding across scans by identity,
never by message.

Keep the rule and scan ID fixed, advance the offset by `page.returned`, and stop when `page.hasMore` is false.
`page.total` and `page.matched` count retained entries, not the full `violationCount`. Inspect rule/report
`truncated`: retention overflow means even a terminal page is incomplete. Report `violationDetails` contains
`scanId`, `total`, `retained`, `retentionLimit`, and `truncated`; the default is 10,000 sanitized details per
advisor scan (`bootui.advisors.max-retained-violations`). Raising it cannot recover already discarded details
without an explicitly authorized new scan. Detail completeness is not the same as evidence coverage.
Truncation can also reflect upstream observations that count affected targets without supplying every identity.
Preserve that diagnostic instead of inventing details or assuming a larger retention budget will recover them.
Paging does not expand existing observation bounds, such as Memory rules that inspect only their top-five inputs.
A `PARTIAL` Hibernate report names incomplete rules and units in `diagnostics` (`source`, `unit`, `level`,
`message`); a finding whose units were only partly evaluated carries a `coverageNote`. `INFO` diagnostics are
advisor limits by design, not missing application configuration. The list is capped at 200 entries; a final entry
with source `diagnostics` states how many were omitted, so a missing unit entry does not prove full coverage. Report
these gaps rather than treating the rule as clean.

Detail reads never rerun checks or query a database and remain permitted in read-only mode. Only the latest
completed snapshot is kept; dismissal preserves its ID and details. On stale/no-snapshot client error 409,
**reread the cached report, not the scan tool**, and restart pages using its ID. A rule id outside the advisor's
rule catalogue answers `Unknown advisor rule: ...`, distinct from a catalogue rule that passed, was skipped, or failed
(`Advisor rule has no findings in the current scan.`); both are REST 404 and MCP in-band `isError: true` failures
(CLI facade 400, exit `1`). On MCP rendered-byte
refusal `-32003` (legacy) or `-31003` (modern), retry the same scan ID and offset with a smaller limit; never advance after a failure or treat
it as an empty page. Stop rather than retry indefinitely when one detail cannot fit. Verify every finding against
source and effective configuration before proposing a fix; do not claim complete coverage when truncated.
