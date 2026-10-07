package io.github.jdubois.bootui.engine.mcp;

import java.util.Map;

/** Agent-oriented descriptions for every BootUI MCP tool. */
public final class McpToolDescriptions {

    private static final Map<String, String> COMMON = Map.ofEntries(
            Map.entry(
                    "architecture_scan",
                    "Actively scan application classes for architecture and dependency violations. Use for structural "
                            + "reviews; verify each finding against intended module boundaries before changing code."),
            Map.entry(
                    "hibernate_scan",
                    "Actively inspect JPA/Hibernate mappings and persistence configuration for correctness and "
                            + "performance risks. Verify findings against actual query paths and database behavior."),
            Map.entry(
                    "database_advisor_scan",
                    "Actively introspect the physical database schema (tables, columns, keys, indexes) via read-only "
                            + "JDBC metadata, plus PostgreSQL/MySQL/MariaDB catalog checks and Hibernate mapping "
                            + "cross-reference when available. The scan is bounded; anything it could not read is "
                            + "reported as a diagnostic rather than as a passing check. Verify findings against the "
                            + "live schema before changing it."),
            Map.entry(
                    "memory_scan",
                    "Actively analyze JVM memory and return prioritized findings. This can trigger a class histogram "
                            + "and full GC, so run only when memory evidence is needed."),
            Map.entry(
                    "security_scan",
                    "Actively review application security configuration and return prioritized findings. Treat results "
                            + "as review evidence and verify exploitability before proposing a fix."),
            Map.entry(
                    "pentest_scan",
                    "Actively send bounded synthetic probes to this application's loopback endpoint and return security "
                            + "findings. Run only with permission and verify findings before remediation."),
            Map.entry(
                    "get_live_activity",
                    "Newest events and entry ids only. For why a route is slow, what a reload changed, or what a test "
                            + "run did not exercise, call get_runtime_insights or get_runtime_run_comparison first. At most limit "
                            + "(25) entries; query selects an entry type (SQL, EXCEPTION, REST_CLIENT, ...), a severity "
                            + "(SLOW, WARN, ERROR), or text in the summary, detail, path, or method, such as a route; "
                            + "pageInfo.hasMore means more entries matched, and typeCounts counts every retained entry."),
            Map.entry(
                    "get_runtime_insights",
                    "Return what this run did that no single panel shows, compacted: coverage first, the checks that "
                            + "did not fully run, then at most limit (8) observations with an id, status, one "
                            + "sentence, counts, an exemplar request id for get_request_profile, a verify line, and "
                            + "whether the default list shows it. query is empty, all, new, security, diff, latency, "
                            + "an observation kind, or a route, table, bean, or class; past the limit, listed rows "
                            + "come first and every kind is listed once before any kind twice. "
                            + "requests counts completed HTTP exchanges only: zero is not proof nothing ran when "
                            + "an observation names a request or execution, a non-HTTP limitation, or eviction says "
                            + "otherwise. A run-level observation with no exemplar does not. The empty query is the "
                            + "default list: only the kinds that passed their external validation or stayed silent "
                            + "on it, so no time breakdown, exception hotspot, repeated SELECT, connection, AI, "
                            + "garbage collection, or heap row; a limitation counts what it left out, names the "
                            + "kinds not externally validated, and all, a kind, or a route lists it. An empty list "
                            + "means not exercised only when limitations say so. INSUFFICIENT, PARTIAL, "
                            + "NOT_APPLICABLE, and UNAVAILABLE are not successes. next names at most three follow-up "
                            + "calls, each a tool with its arguments and the equivalent bootui command."),
            Map.entry(
                    "get_runtime_insight",
                    "Return one Runtime Insights observation by its id from get_runtime_insights, with every check "
                            + "to verify and at most 20 evidence rows. Drill down with get_request_profile on its "
                            + "exemplar request. An unknown or evicted id returns available=false with a reason, and next names the call "
                            + "that lists the current ids."),
            Map.entry(
                    "get_runtime_impact",
                    "For a route, bean, class, method (Class#method, with parameter types for one overload), "
                            + "repository, table, cache, host, or event type id: the routes this run exercised through "
                            + "it, those it did not, and those sharing a resource with it, at most 8 each, or AMBIGUOUS "
                            + "with candidates. With the BootUI agent, a method's observed routes are those whose "
                            + "requests executed it; notObserved routes ran without showing it, which proves nothing. "
                            + "A checklist of what was and was not exercised, never a verdict that a change is safe. For AMBIGUOUS, "
                            + "NOT_FOUND, or UNAVAILABLE, next names the call that resolves it."),
            Map.entry(
                    "get_runtime_run_comparison",
                    "Compare this run with a kept one: id is optional, previous or a run id. Omitted or previous selects the newest kept run "
                            + "including runs without HTTP traffic; runs lists the others. Comparability first, then "
                            + "codeChanges (with the BootUI agent: changed and added methods, executed or not, and the "
                            + "routes that ran them), then sideEffects (with the agent: hosts, file patterns, processes, and "
                            + "variable names a route, job, or startup uses new or no longer, per sensor COMPARED only when "
                            + "it recorded both runs whole, else NOT_COMPARED with the reason), then at most 8 behavior rows "
                            + "and edges; latency is left out. INSUFFICIENT and NOT_COMPARABLE never "
                            + "mean no change. Call after tests to verify a change. next names the follow-up calls, or the run ids to use "
                            + "after an unknown one."),
            Map.entry(
                    "get_request_profile",
                    "Open a profileable request or scheduled/message execution id from get_live_activity or a "
                            + "get_runtime_insights exemplar. Returns source=journal with the recorded timeline, route or "
                            + "execution label, resources and touched metadata when retained, plus HTTP-exchange "
                            + "details when available; otherwise source=buffers "
                            + "with the HTTP-exchange profile (SQL N+1 groups, exceptionGroupId, and correlation "
                            + "tiers). Source=none and available=false explain when neither retains the id. Journal "
                            + "events follow panel visibility and exposure policy; do not infer absent work from a "
                            + "missing event."),
            Map.entry(
                    "get_exceptions",
                    "List recent exception groups, newest first. Use a returned id with get_exception_detail for stack "
                            + "frames, causes, and individual occurrences. With the BootUI agent's caught-exceptions "
                            + "sensor, caughtInCode summarizes exceptions application code caught: a finding was not "
                            + "seen rethrown or logged at WARN or above while the evidence was complete; unknown "
                            + "means incomplete evidence, never swallowed."),
            Map.entry(
                    "get_exception_detail",
                    "Return stack frames, causes, and occurrences for one exact exception-group id obtained from "
                            + "get_exceptions or get_live_activity."),
            Map.entry(
                    "get_security_logs",
                    "Return a bounded, newest-first snapshot of authentication and authorization audit events. "
                            + "Correlate timestamps and principals with live activity."),
            Map.entry(
                    "get_sql_traces",
                    "Return the current bounded SQL trace snapshot with statements and timings. Application SQL may "
                            + "contain sensitive values; correlate it locally with request or trace identifiers. "
                            + "The retention object reports capacity and retained, reserved, and evicted counts: "
                            + "failed and slow statements are kept longer, so the window is not complete. At most limit (20) "
                            + "newest entries matching query (SQL text, category, call site, error, or request, trace, "
                            + "or execution id); page.hasMore means narrow the query or raise limit."),
            Map.entry(
                    "get_transactions",
                    "Return the current bounded transaction-boundary snapshot with outcomes, timings, nesting, and "
                            + "correlated SQL counts. Use it to verify which local operations actually ran in a "
                            + "transaction."),
            Map.entry(
                    "get_traces",
                    "Return a bounded, newest-first snapshot of distributed and local traces captured by BootUI. Use "
                            + "trace ids to correlate activity, exceptions, SQL, and HTTP exchanges."),
            Map.entry(
                    "get_log_tail",
                    "Return the latest buffered application log snapshot. Messages follow bootui.expose-values: "
                            + "secret-like assignments are masked by default and messages are omitted under "
                            + "METADATA_ONLY, but other sensitive text may remain; use them only in the local "
                            + "diagnostic context. At most limit (50) newest lines matching query (level, logger, thread, or "
                            + "message); page.hasMore means more lines matched."),
            Map.entry(
                    "get_http_exchanges",
                    "Return a bounded, newest-first snapshot of application HTTP request/response metadata. Correlate "
                            + "paths, statuses, and timings with live activity and traces. The retention object "
                            + "reports capacity and retained, reserved, and evicted counts: 5xx and slow exchanges "
                            + "are kept longer, so the window is not complete. At most limit (25) exchanges; page.hasMore means "
                            + "more were retained."),
            Map.entry(
                    "get_http_routes",
                    "Return route performance rankings over the retained HTTP exchanges: per method and route "
                            + "template, request and status-class counts, average, p50, p95, p99 and maximum "
                            + "duration, and share of request time, plus the evidence window. limit is the number "
                            + "of routes per ranking criterion. Figures cover the retained window only, not "
                            + "service-level metrics."),
            Map.entry(
                    "get_overview",
                    "Return stable application identity and runtime context, including versions, active profiles, and "
                            + "BootUI status. Use this before interpreting other results."),
            Map.entry(
                    "get_config",
                    "Search effective configuration by name or displayed value and return a bounded result. Name "
                            + "search is relaxed-binding aware: it ignores case and treats `_` and `-` as `.`, so the "
                            + "dotted, kebab-case, and `UPPER_SNAKE_CASE` spellings of one property all find it — "
                            + "`bootui.mcp.enabled` also finds a value supplied as the environment variable "
                            + "`BOOTUI_MCP_ENABLED`, which is enumerated under that literal name. Values are matched "
                            + "literally, and each row reports the exact name and source its property source published. "
                            + "In `page`, `total` counts every property before filtering while `matched` counts the "
                            + "query hits, so `matched: 0` means this query found nothing, not that the property is "
                            + "absent — retry with a shorter query before concluding it is unset. Secret-like "
                            + "configuration values are masked; prefer a narrow query. At most limit (25) properties; "
                            + "propertySuggestions, the browser's completion list, is always empty here."),
            Map.entry(
                    "get_mappings",
                    "Search request routes and handlers and return a bounded result. Use a path, HTTP concept, or handler "
                            + "name as the query when locating an endpoint."),
            Map.entry(
                    "vulnerabilities_scan",
                    "Actively query OSV.dev for known vulnerabilities in this application's dependencies and return "
                            + "severity-ranked findings. This sends package names/versions to OSV and, when enabled, CVE "
                            + "ids to FIRST for optional EPSS enrichment; run only with approval. Check `scan.status`, "
                            + "`scan.message`, `coverage` and `scan.packagesSkipped`: partial or unknown evidence is "
                            + "not a clean result, and inventory coverage does not prove reachability. Fixed versions are "
                            + "supported affected-interval candidates, not verified installable upgrades. Verify compatibility "
                            + "before changing a dependency. EPSS is the highest available per-CVE probability, not a combined "
                            + "probability or severity; optional enrichment failure retains OSV findings."),
            Map.entry(
                    "get_loggers",
                    "Search configured loggers by case-insensitive name and return their configured and effective "
                            + "levels, bounded by limit. Use to confirm actual logging levels before or after a code or "
                            + "configuration change."),
            Map.entry(
                    "get_scheduled_tasks",
                    "Return the current scheduled task inventory and recent run history, including timing and outcome. "
                            + "Use to confirm a scheduled job actually ran, and when, rather than assuming from source "
                            + "alone."),
            Map.entry(
                    "get_cache_stats",
                    "Return current cache manager and cache statistics (hits, misses, size) for each configured cache. "
                            + "Use to verify cache behavior before proposing a caching change."),
            Map.entry(
                    "get_database_connection_pools",
                    "Return current connection pool configuration and live metrics (active, idle, pending connections) "
                            + "for each configured datasource. Use to diagnose pool exhaustion or misconfiguration."),
            Map.entry(
                    "get_architecture_report",
                    "Return the last completed Architecture advisor report without starting a new classpath scan. Use "
                            + "this cached evidence before deciding whether an active architecture_scan is necessary."),
            Map.entry(
                    "get_hibernate_report",
                    "Return the last completed Hibernate advisor report without starting a new mapping scan. Use this "
                            + "cached evidence before deciding whether an active hibernate_scan is necessary."),
            Map.entry(
                    "postgresql_read",
                    "Actively read PostgreSQL's own pg_stat_* and pg_catalog views for the application datasources "
                            + "and return what the server currently reports: the live session snapshot, cache hit and "
                            + "rollback ratios, connection usage, transaction-ID age, the top normalized statements, "
                            + "index and relation activity, autovacuum state, replication and notable settings. This "
                            + "is a runtime view, not an advisor: it grades nothing and emits no findings. The read is "
                            + "bounded and read-only. Read every section's `reason` before trusting its rows: a "
                            + "section BootUI read only partly stays `AVAILABLE` and carries a non-null `reason`, so "
                            + "`status` alone does not mean complete, while `SKIPPED` and `FAILED` mark a section it "
                            + "did not read at all. A `hint` is a methodology caveat on rows that were read, not "
                            + "missing evidence. `truncated` means the row cap was reached, and any of these degrades "
                            + "the database and report `status` to `PARTIAL`. The usual cause is a role without "
                            + "pg_monitor membership, which hides other backends from the session list and the "
                            + "statement text and replica details from their sections. Budget exhaustion is reported "
                            + "in `reason`, not as row-cap truncation. An empty replica list proves absence only when "
                            + "`replication.replicasAvailable` is true; otherwise the list was not read."),
            Map.entry(
                    "get_postgresql_report",
                    "Return the last completed PostgreSQL runtime view without querying the server again. Before any "
                            + "read has run its `status` is `NOT_READ` and it carries no rows, which means nothing has "
                            + "been looked at rather than that nothing is wrong. Its session snapshot is only as "
                            + "current as that read, so prefer an active postgresql_read when the question is about "
                            + "what the database is doing right now. Check each section's `reason` for missing coverage; "
                            + "an empty replica list proves absence only when `replication.replicasAvailable` is true."),
            Map.entry(
                    "get_database_advisor_report",
                    "Return the last completed Database advisor report without querying schema metadata again. Use this "
                            + "cached evidence before deciding whether an active database_advisor_scan is necessary."),
            Map.entry(
                    "mysql_read",
                    "Actively read the application's MySQL JDBC datasources using bounded, read-only statistics "
                            + "queries. Returns sessions and blocking, normalized statement rankings, table/index "
                            + "activity, InnoDB metrics, local replication channels and curated settings. This is "
                            + "an operational observation, not an advisor or health score. Inspect each section's "
                            + "scope, reason and hint: server-wide counters include other clients, and default-schema "
                            + "association is not exhaustive access to that schema. Unknown values are null; large "
                            + "counters are exact decimal strings. Row-cap truncation is distinct from permission, "
                            + "instrumentation and timeout limitations. An empty replication list establishes absence "
                            + "only when its section was successfully read. Never enables monitoring, reads application "
                            + "records or returns raw session/sample SQL."),
            Map.entry(
                    "get_mysql_report",
                    "Return the last completed MySQL operational report without contacting the database. NOT_READ "
                            + "means no current evidence, not a healthy database; exposure-policy changes invalidate "
                            + "the cached report. Check readAt, per-section scope and limitations before using it. "
                            + "Use mysql_read only when an explicitly approved fresh observation is needed."),
            Map.entry(
                    "get_memory_report",
                    "Return the last completed Memory advisor report without triggering a class histogram or full GC. "
                            + "Use this cached evidence before deciding whether an active memory_scan is necessary."),
            Map.entry(
                    "get_security_report",
                    "Return the last completed Security advisor report without starting a new configuration scan. Use "
                            + "this cached evidence before deciding whether an active security_scan is necessary."),
            Map.entry(
                    "get_pentest_report",
                    "Return the last completed Pentesting advisor report without sending any HTTP probes. Use this "
                            + "cached evidence before deciding whether an active pentest_scan is necessary."),
            Map.entry(
                    "get_rest_api_report",
                    "Return the last completed REST API advisor report without starting a new endpoint scan. Use this "
                            + "cached evidence before deciding whether an active rest_api_scan is necessary."),
            Map.entry(
                    "get_vulnerabilities_report",
                    "Return the cached vulnerability report, or the local dependency inventory before the first scan, "
                            + "without contacting OSV.dev or any other network service. Inspect `scan.status`, `scan.message`, "
                            + "`coverage` and `scan.packagesSkipped` before interpreting absent findings. Partial results "
                            + "retain available evidence; UNKNOWN severity is not zero risk. Coverage describes the "
                            + "inventory provider's accounting, not shaded-library discovery or exploitability. With the "
                            + "BootUI agent, `runtimeReach` says whether a dependency's classes, or a class its advisory "
                            + "names, loaded in this JVM: a prioritization hint that never changes severity, and "
                            + "NOT_LOADED means not loaded yet, not unreachable. At most limit (10) dependencies, vulnerable "
                            + "first, matching query (coordinates, severity, or an advisory id or alias); totals stay "
                            + "whole-report counts and page.matched counts the query hits."),
            Map.entry(
                    "get_metrics",
                    "Search the current application metrics inventory and return a bounded page of local meter values. "
                            + "Use a narrow metric-name query when diagnosing one runtime signal. At most limit (25) meters; "
                            + "page.hasMore means more matched."),
            Map.entry(
                    "get_live_memory",
                    "Return a passive snapshot of current JVM heap, non-heap, garbage collection, class-loading, and "
                            + "thread measurements without requesting GC or a class histogram."),
            Map.entry(
                    "get_agent_status",
                    "Return the BootUI Java agent's status: NOT_ATTACHED, DORMANT, ARMED, HELD by another application, "
                            + "DISARMED, UNAVAILABLE, FAILED, or DISABLED with a reason; versions, the current claim, "
                            + "sensors, and setup snippets that attach it. This read never claims, installs, or "
                            + "changes the agent. Sensors are summarized (state, counters, failures); query with a sensor id, such "
                            + "as executors, to list only matching sensors with their hooks and self-test steps."),
            Map.entry(
                    "get_code_inventory",
                    "Return Code Inventory: did the code that changed since the previous run execute in this run? "
                            + "Advertised only while the BootUI agent's inventory sensor records this run (see "
                            + "get_agent_status). Counts "
                            + "first (N of M tracked methods executed, changed, added, removed, dependencies), then at "
                            + "most limit (25) rows of query: changed (the default; methods changed or added since the "
                            + "previous DevTools restart or Quarkus live reload, not executed first, with the first "
                            + "request id and route that ran each), never-executed, not-tracked, executed, "
                            + "dependencies (declared jars not loaded in this run first), or a package or class. "
                            + "NOT_TRACKED is not NEVER_EXECUTED; a jar not loaded in this run is not proof it is "
                            + "unused."),
            Map.entry(
                    "get_code_paths",
                    "Return Code Paths: which application bean methods each route spends its time in, from the BootUI "
                            + "agent's code-paths sensor. Advertised only while that sensor records this run (see "
                            + "get_agent_status). Without query, the routes slowest warm median first, at most limit "
                            + "(10), each with its top methods by self time; with query, the routes whose label or top "
                            + "methods contain it, and for a single route its method nodes with the most self time. "
                            + "Times are per warm request; each node's calls list the SQL, REST client, cache, and AI "
                            + "calls recorded while it was the innermost instrumented method on their thread, whose time "
                            + "is part of its self time; calls recorded on another thread carry no stamp and show under "
                            + "no node. An assemblyOnly route's handler ran on an event loop, returned a reactive or "
                            + "asynchronous result, or BootUI could not tell where its work ran, so its tree times "
                            + "assembly, not the work. Node percentiles are approximate, from log2 buckets."),
            Map.entry(
                    "start_method_probe",
                    "Start a method probe: an action that retransforms one application method of the running "
                            + "application to record its next 20 invocations, for at most 60 seconds (five probes at "
                            + "once), then removes itself; it ends with the run. Ask the user for separate approval "
                            + "before starting one, even when other tools were approved. id is the method, "
                            + "binary.Class#name, with its JVM descriptor for an overloaded one, as Code Paths, Code "
                            + "Inventory, and get_code_paths name it (com.example.PriceService#quote(I)J); it must be a "
                            + "method the agent instrumented, in the application's packages. Refused while the Code "
                            + "Paths panel or BootUI is read-only, without the BootUI agent, or when five probes run. "
                            + "Returns the probe, starting; call get_method_probe with its id after the code runs. "
                            + "Metadata only, in every exposure mode: durations, thread kind, request id, outcome, "
                            + "exception type, and calling frame, never argument or return values."),
            Map.entry(
                    "get_method_probe",
                    "Return a method probe by the id start_method_probe returned: its state (starting, active, ending, ended, failed), why it ended "
                            + "or failed, and each recorded invocation's duration, thread kind, request id, outcome or "
                            + "exception type, and calling frame. Metadata only, never argument or return values. No "
                            + "invocations after the code ran is evidence the path never reached the method; an active "
                            + "probe waitingForClass has not seen its class load in this run yet, and an async method's "
                            + "durations time the assembly of its result only."),
            Map.entry(
                    "get_side_effects",
                    "Return Side Effects: what the application does outside the JVM, from the BootUI agent's "
                            + "side-effect sensors; this version records the processes it starts, its network: "
                            + "connects, datagram sends, and host names the JVM resolved, the blocking calls (sleep, wait, "
                            + "park, a blocking network or file operation) started on an event loop, and, opt-in, the "
                            + "files it opens, deletes, moves, and copies, the environment variables and system "
                            + "properties it reads, the threads it starts and executors it creates (thread-activity), "
                            + "and the thread locals a request or a job left set on its pooled thread (thread-locals). "
                            + "Advertised only while "
                            + "the agent is armed for this run (see get_agent_status). Every sensor first, with its "
                            + "coverage (recording, not-claimed, not-available in this version, ...), without its hooks "
                            + "or, when none of its rows is listed, its fixed limitations unless query names that sensor, "
                            + "then at most limit "
                            + "(20) rows, most frequent first, matching query: a sensor id such as processes, network, "
                            + "files, or blocking, 'not captured' for hidden outbound calls, or part of a route, target, client, or "
                            + "call site. A row is attributed to a request's route, work no request owns, startup, or a "
                            + "thread family, with the call site, the bean method it ran inside, counts, failures, "
                            + "exits or connections, times, and up to three request ids. A process row names the "
                            + "command's file name only, never its arguments or environment; a network row names a host "
                            + "and port, the client recognized from the calling frames, and whether a visible panel "
                            + "(REST Client Trace, SQL Trace, a broker's panel, Email) captured the work, never a byte "
                            + "sent or received; a file row a path pattern (./ for the working directory, $TMPDIR, ~, ids "
                            + "as {n}) with its kind, location, and origin (application, library, class-path, jdk, "
                            + "logging), never contents; an environment row a name, never its value; a blocking row the "
                            + "operation, the event loop's thread family, and how long it blocked, not-applicable on "
                            + "Spring MVC, which runs no event loop; a thread-activity row a thread's family or an "
                            + "executor's class, how many a request started (count / requests), how many were still "
                            + "running when their request ended (leftRunning), and executors shut down (completed) or "
                            + "reclaimed without a shutdown (failed), library and JDK pools by origin, never what a "
                            + "thread holds; a thread-locals row the static field holding a thread local left set "
                            + "(kind left set, inheritable, or with initial value), by how many requests, set during the "
                            + "request with no call site, never its value."),
            Map.entry(
                    "get_jvm_tuning",
                    "Return the current JVM sizing facts and generated tuning recommendations using detected defaults. "
                            + "This is a passive calculation and does not change JVM or container settings."),
            Map.entry(
                    "get_heap_dump_report",
                    "Return passive heap-dump status, file metadata, and any already-cached analysis. This does not "
                            + "capture, analyze, download, or delete a heap dump."),
            Map.entry(
                    "get_threads",
                    "Search the current JVM thread snapshot and return a bounded page of thread states and stack "
                            + "summaries. This does not generate or download the raw thread-dump artifact. At most limit (25) "
                            + "threads; page.hasMore means more matched."),
            Map.entry(
                    "get_profile_diff",
                    "Return the current active-profile configuration comparison using masked local configuration data. "
                            + "Use it to identify profile-specific differences without changing profiles."),
            Map.entry(
                    "get_flyway_migrations",
                    "Return the current Flyway migration history and status without running migrate or clean. Verify "
                            + "pending and failed migrations against the configured datasource."),
            Map.entry(
                    "get_liquibase_changesets",
                    "Return the current Liquibase change-set history and status without running update. Verify pending "
                            + "and failed changesets against the configured datasource."),
            Map.entry(
                    "get_rest_client_traces",
                    "Return the current bounded REST-client trace snapshot with masked headers and bodies according to "
                            + "BootUI exposure policy. This does not send requests or change recording state. The "
                            + "retention object reports capacity and retained, reserved, and evicted counts: failed, "
                            + "error, and slow calls are kept longer, so the window is not complete."),
            Map.entry(
                    "get_ai_overview",
                    "Return the local AI-framework telemetry overview derived from already-captured OTLP spans. This "
                            + "does not invoke a model, send a prompt, or make any network request."),
            Map.entry(
                    "get_emails",
                    "Return the bounded local email-capture inventory with content governed by BootUI exposure policy. "
                            + "This does not send, download, or delete any message."),
            Map.entry(
                    "get_kafka_activity",
                    "Return the bounded local Kafka activity snapshot captured from application producers and consumers. "
                            + "This does not publish, consume, clear, or contact a broker."),
            Map.entry(
                    "get_hibernate_statistics",
                    "Return the live Hibernate ORM statistics of the application's persistence unit: sessions, "
                            + "transactions, entity and collection loads, fetches, and writes, query executions with "
                            + "the slowest query, and query and second-level cache hits, misses, and puts per region. "
                            + "Counters are cumulative since startup or since statistics were enabled. available=false "
                            + "with unavailableReason when no SessionFactory is found or statistics are off; this read "
                            + "never enables them, and enableAvailable=true means the user can enable them for this "
                            + "run from the Hibernate Statistics panel."),
            Map.entry(
                    "get_websockets",
                    "Return the WebSocket endpoints, live sessions, STOMP subscriptions and broker prefixes, and a "
                            + "bounded newest-first activity log of connects, disconnects, and frames, with aggregate "
                            + "counters. Metadata only: a frame's size, never its payload. frameCaptureSupported and "
                            + "sessionTrackingSupported say what this stack can observe, with the reason when not, so "
                            + "an empty list is not proof no client connected; the *Truncated flags mark capped lists. "
                            + "This read never opens, closes, or sends on a connection, and does not clear the log."),
            Map.entry(
                    "get_rabbitmq_activity",
                    "Return the bounded local RabbitMQ activity snapshot captured from application publishers and "
                            + "listeners. This does not publish, consume, clear, or contact a broker."),
            Map.entry(
                    "get_dev_services",
                    "Return the current local Dev Services or development-service inventory and masked connection "
                            + "metadata. This does not start, stop, restart, or contact a service."),
            Map.entry(
                    "get_github_dashboard",
                    "Return local repository identity plus the last cached GitHub dashboard report. This passive read "
                            + "never calls GitHub; only the panel's explicit refresh action can use the network."),
            Map.entry(
                    "get_copilot_sessions",
                    "Return the bounded, sanitized Copilot CLI session inventory already parsed from local session files. "
                            + "Raw prompts, tool arguments, command output, diffs, and network calls are excluded. At "
                            + "most limit (10) sessions matching query (id, model, working directory, status, or last "
                            + "activity)."),
            Map.entry(
                    "get_claude_code_sessions",
                    "Return the bounded, sanitized Claude Code session inventory already parsed from local session files. "
                            + "Raw prompts, tool arguments, command output, diffs, and network calls are excluded. At "
                            + "most limit (10) sessions matching query (id, model, working directory, status, or last "
                            + "activity)."),
            Map.entry(
                    "clear_sql_traces",
                    "Clear the bounded in-memory SQL trace buffer and return the resulting report. This does not execute "
                            + "SQL or change whether trace recording is enabled."),
            Map.entry(
                    "pause_sql_trace_recording",
                    "Pause SQL trace recording and return the resulting report. Existing buffered traces remain available "
                            + "until explicitly cleared."),
            Map.entry(
                    "resume_sql_trace_recording",
                    "Resume SQL trace recording and return the resulting report. This only affects BootUI's bounded local "
                            + "capture and does not execute SQL."),
            Map.entry(
                    "clear_traces",
                    "Clear BootUI's bounded in-memory trace buffer. This does not contact a telemetry backend or alter "
                            + "application tracing configuration."),
            Map.entry(
                    "clear_rest_client_traces",
                    "Clear the bounded in-memory REST-client trace buffer and return the resulting report. This does not "
                            + "send an HTTP request or change recording state."),
            Map.entry(
                    "pause_rest_client_recording",
                    "Pause REST-client trace recording and return the resulting report. Existing buffered calls remain "
                            + "available until explicitly cleared."),
            Map.entry(
                    "resume_rest_client_recording",
                    "Resume REST-client trace recording and return the resulting report. This only affects BootUI's "
                            + "bounded local capture and sends no HTTP request."),
            Map.entry(
                    "clear_exceptions",
                    "Clear BootUI's bounded in-memory exception groups and occurrences. This does not suppress, handle, or "
                            + "change application exceptions."),
            Map.entry(
                    "analyze_heap_dump",
                    "Analyze the existing BootUI heap dump and return the resulting report. This never captures, downloads, "
                            + "or deletes a heap dump."));

    /** Advisors whose findings carry structured violation locations. */
    private static final java.util.Set<String> LOCATED_ADVISORS =
            java.util.Set.of("architecture", "rest_api", "hibernate");

    private McpToolDescriptions() {}

    public static String spring(String name) {
        return springDescription(name) + advisorGuidance(name, false);
    }

    private static String springDescription(String name) {
        return switch (name) {
            case "spring_scan" ->
                "Actively inspect Spring configuration and bean usage for correctness and maintainability risks. "
                        + "Verify each finding against effective configuration before changing code.";
            case "rest_api_scan" ->
                "Actively inspect Spring REST controllers and API design for correctness and maintainability risks. "
                        + "Verify recommendations against the public API contract.";
            case "graalvm_scan" ->
                "Actively assess Spring native-image readiness without the longer dependency metadata scan. Verify "
                        + "reflection and resource findings against the intended native build.";
            case "crac_scan" ->
                "Actively assess Spring checkpoint/restore readiness. Verify resource-lifecycle findings in an actual "
                        + "CRaC checkpoint and restore test.";
            case "get_spring_report" ->
                "Return the last completed Spring advisor report without starting a new application scan. Use this "
                        + "cached evidence before deciding whether an active spring_scan is necessary.";
            case "get_graalvm_report" ->
                "Return the last completed GraalVM readiness report without starting a new classpath or dependency "
                        + "scan. Use cached findings before deciding whether graalvm_scan is necessary.";
            case "get_crac_report" ->
                "Return the last completed CRaC readiness report without starting a new resource-lifecycle scan. Use "
                        + "cached findings before deciding whether crac_scan is necessary.";
            case "get_health" ->
                "Return the current aggregated Actuator health tree. Distinguish unavailable health infrastructure "
                        + "from an unhealthy application.";
            case "get_beans" ->
                "Search Spring beans by name or type and return a bounded result. Use this to verify runtime wiring, "
                        + "not as proof that a bean is exercised. At most limit (25) beans; page.hasMore means more "
                        + "matched.";
            case "get_conditions" ->
                "Search Spring auto-configuration condition evaluation outcomes by case-insensitive name and return "
                        + "matched, unmatched, and unconditional entries. Use to confirm why a bean or auto-configuration "
                        + "was or was not applied, rather than guessing from source. At most limit (25) entries, "
                        + "positive matches then negative ones; query also narrows unconditionalClasses and "
                        + "exclusions, whose counts stay totals.";
            case "get_http_sessions" ->
                "Return the bounded active embedded-Tomcat HTTP-session inventory without creating, clearing, or "
                        + "invalidating a session. Attribute values remain masked by the panel contract.";
            case "get_startup_timeline" ->
                "Return the captured Spring startup-step timeline after filtering BootUI's own steps. This passive read "
                        + "does not restart the application or start a new recording. At most limit (25) slowest "
                        + "steps first (a parent includes its children) matching query (a step name or tag value, such "
                        + "as a bean name).";
            case "get_spring_data_repositories" ->
                "Return Spring Data repository metadata already computed by the application context. This read never "
                        + "invokes a repository method or executes a database query.";
            case "get_spring_security" ->
                "Return the current Spring Security filter-chain and authentication configuration report. This passive "
                        + "read does not authenticate, authorize, or send a request through a chain.";
            case "get_devtools_status" ->
                "Return the current Spring Boot DevTools restart and LiveReload status. This passive read does not "
                        + "trigger LiveReload, restart the application, or modify watched files.";
            case "get_jms_activity" ->
                "Return the bounded local JMS activity snapshot captured from application templates and listeners. "
                        + "This does not send, receive, clear, or contact a broker.";
            case "get_fault_tolerance" ->
                "Return the configured Resilience4j and Spring Retry policy inventory with live counters and circuit "
                        + "breaker state, plus a bounded metadata-only event history. This read never opens, closes, "
                        + "resets, or otherwise mutates a policy.";
            case "clear_transactions" ->
                "Clear the bounded in-memory Spring transaction trace buffer and return the resulting report. This does "
                        + "not begin, commit, or roll back an application transaction.";
            case "pause_transaction_recording" ->
                "Pause Spring transaction-boundary recording and return the resulting report. Existing buffered "
                        + "transactions remain available until explicitly cleared.";
            case "resume_transaction_recording" ->
                "Resume Spring transaction-boundary recording and return the resulting report. This only affects "
                        + "BootUI's bounded local capture.";
            case "trigger_devtools_livereload" ->
                "Trigger the existing local Spring Boot DevTools LiveReload notification and return its action result. "
                        + "This does not restart the application or modify watched files.";
            default -> common(name);
        };
    }

    public static String quarkus(String name) {
        return quarkusDescription(name) + advisorGuidance(name, true);
    }

    private static String quarkusDescription(String name) {
        return switch (name) {
            case "spring_scan" ->
                "Actively inspect Quarkus configuration and idioms for correctness and maintainability risks. Verify "
                        + "each finding against effective configuration before changing code.";
            case "get_spring_report" ->
                "Return the last completed Quarkus application advisor report without starting a new application scan. "
                        + "Use this cached evidence before deciding whether an active spring_scan is necessary.";
            case "rest_api_scan" ->
                "Actively inspect JAX-RS resources and API design for correctness and maintainability risks. Verify "
                        + "recommendations against the public API contract.";
            case "get_health" ->
                "Return the current aggregated SmallRye Health tree. Distinguish unavailable health infrastructure "
                        + "from an unhealthy application.";
            case "get_beans" ->
                "Search Arc/CDI beans by name or type and return a bounded result. Use this to verify runtime wiring, "
                        + "not as proof that a bean is exercised. At most limit (25) beans; page.hasMore means more "
                        + "matched.";
            case "get_fault_tolerance" ->
                "Return the SmallRye Fault Tolerance policy inventory declared by application annotations, including "
                        + "effective MicroProfile configuration overrides and live named circuit breaker state, plus a "
                        + "bounded metadata-only event history. This read never mutates a policy.";
            default -> common(name);
        };
    }

    private static String common(String name) {
        if (McpToolCatalog.byName(name)
                .map(entry -> entry.schema() == McpToolSchema.RULE_VIOLATIONS)
                .orElse(false)) {
            return "Read one page of retained violations for the exact rule id from "
                    + name.replace("_rule_violations", "_report")
                    + " and that cached report's violationDetails.scanId. "
                    + "This never starts a scan. Default offset 0 and limit 100; limit is capped at min(1000, transport max-results). "
                    + "Advance by page.returned while page.hasMore; page.total and page.matched count retained entries, "
                    + "not violationCount. If truncated, retention overflow or unavailable upstream details prevent a complete list. Verify each finding "
                    + "before changing code. Unknown rule returns 404; stale or missing snapshot returns 409: reread the "
                    + "cached report, not a new scan. On MCP -32003 byte-budget refusal, retry the same scanId and offset "
                    + "with a smaller limit; a refusal is not an empty or completed page. A non-empty locations list "
                    + "aligns index-for-index with violations (a null entry has no location); an empty list means no "
                    + "violation on the page has one.";
        }
        String description = COMMON.get(name);
        if (description == null) {
            throw new IllegalArgumentException("Missing MCP tool description: " + name);
        }
        return description;
    }

    private static String advisorGuidance(String name, boolean quarkus) {
        String advisor =
                switch (name) {
                    case "architecture_scan", "get_architecture_report" -> "architecture";
                    case "hibernate_scan", "get_hibernate_report" -> "hibernate";
                    case "spring_scan", "get_spring_report" -> "spring";
                    case "rest_api_scan", "get_rest_api_report" -> "rest_api";
                    case "memory_scan", "get_memory_report" -> "memory";
                    case "security_scan", "get_security_report" -> "security";
                    case "database_advisor_scan", "get_database_advisor_report" -> "database_advisor";
                    default -> null;
                };
        if (advisor == null) {
            return "";
        }
        if (name.endsWith("_scan")) {
            // The scan answers with the report get_<advisor>_report returns; its guidance is written once, there.
            return " It answers with the report get_" + advisor + "_report returns: read that tool's description"
                    + " for sampleViolations, truncated, and paging retained violationDetails with get_" + advisor
                    + "_rule_violations without scanning again.";
        }
        int sampleLimit = quarkus && (advisor.equals("spring") || advisor.equals("security")) ? 20 : 10;
        return " sampleViolations are bounded previews (up to " + sampleLimit + "), not the full violationCount. "
                + "Use violationDetails.scanId with get_" + advisor + "_rule_violations to page cached retained "
                + "details without scanning again. Check truncated for missing details, including retention overflow; a terminal page does not "
                + "guarantee completeness when truncated. Verify each finding before changing code."
                + (LOCATED_ADVISORS.contains(advisor)
                        ? " sampleLocations aligns index-for-index with sampleViolations when non-empty (a null entry"
                                + " has no location): className, memberName, kind, sourceFile, line, sourcePath and"
                                + " precision (LINE, MEMBER or CLASS). Open sourcePath at line to go straight to the"
                                + " code; violationDetails.locationNotes says why a path is missing."
                        : "")
                + (advisor.equals("hibernate")
                        ? " When scan.status is PARTIAL, read diagnostics (source rule id or discovery, unit, level,"
                                + " message) for rules and units that failed or lacked evidence, and a result's"
                                + " coverageNote for units whose findings come from a partial evaluation; INFO"
                                + " diagnostics are advisor limits by design. Diagnostics are capped at 200: when"
                                + " capped, a final entry with source diagnostics states how many were omitted, so a"
                                + " missing unit entry does not prove complete coverage."
                        : "");
    }
}
