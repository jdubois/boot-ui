# JVM performance and memory

## Goal and entry

Use for high CPU, GC pressure, suspected retention, hangs/contention, thread growth, or process memory unexplained by
ordinary request evidence. Load the [shared contract](README.md). Escalate from passive observations to one justified,
approved capture, not "capture everything." This is a local development workflow, not production incident automation.

## Prerequisites

Confirm repository/runtime identity, symptom, representative workload, and target JVM PID/start identity. Check JVM
implementation/version, owner, container boundaries, attachment permissions, output directory, disk headroom, duration,
and disclosure/retention policy. A native executable is not a HotSpot JVM.

Prefer the target JVM's compatible JDK tools. Discover installed `jcmd`/`jfr` and an already-installed local heap
analyzer; do not install them silently. Use target-specific help before version-dependent commands. Do not enumerate
unrelated JVMs, attach on a guessed name, use elevated privileges, or work around read-only/disabled policies.
Do not expose command lines that may contain credentials when establishing identity.
BootUI does not report the PID or process RSS. Obtain them from the user or a permitted, scoped OS lookup of the
confirmed application's listener/process, accounting for proxies and containers; otherwise mark them unmeasured.
Every `jcmd` invocation attaches locally, including `help` and NMT status checks: obtain local-attachment approval
before any such call. Use already available JVM arguments for initial NMT configuration evidence where possible.

## Investigation

### Start with BootUI evidence

| MCP tool | CLI equivalent | What it actually does |
| --- | --- | --- |
| `get_live_memory` | `bootui memory live --json` | Passive heap/non-heap/pool usage; no requested GC |
| `get_metrics` | `bootui metrics --query jvm --limit 25 --json` | Bounded available meters; narrow further and record missing signals |
| `get_jvm_tuning` | `bootui jvm tuning --json` | Current sizing facts and suggestions, not a measured bottleneck or configuration change |
| `get_threads` | `bootui threads --limit 25 --json` | Platform-thread page; read totals/pagination and masking limits before interpreting absence |
| `get_heap_dump_report` | `bootui memory heap report --json` | Passive dump status/metadata and cached analysis |
| `analyze_heap_dump` | `bootui memory heap analyze --json` | **Action:** live shallow class histogram requesting GC; never HPROF retained analysis |

BootUI's raw heap capture/download, raw thread-dump download, and resource/JFR profiler are not these catalog reads.
Its JFR control is browser-only. Do not invent `bootui` commands for them. If a user performs a permitted browser
capture, keep the same artifact/disclosure policy. Local JDK collection below is a separate authorized operation.
For blocked platform threads, prefer the existing [bounded thread-investigation workflow](../thread-investigation.md)
when its tools are advertised. It reuses retained evidence and requires approval before fresh snapshots or observations;
it does not require the Java agent. Keep this playbook's shared budget and verification baseline.

Record CPU/process memory, available heap/GC/thread trends, route context, and workload timestamps before escalation.
No single heap reading or WAITING thread proves a defect.

For thread growth, inspect `totalThreads`, repeated counts, and `page.hasMore`: the first page is not the whole
snapshot. `get_threads` uses `ThreadMXBean`, which does not enumerate virtual threads; `virtualThreadsSupported`
only detects the JDK API, not virtual-thread coverage. `deadlockDetected: false` cannot rule out deadlocks involving
virtual threads. Under `METADATA_ONLY`, empty stacks and missing lock names are hidden detail, not evidence that
application frames or contention are absent. Narrow relevant reads within budget and report any remaining gaps.

### Choose the smallest confirming capture

| Symptom | First hypothesis check | Escalation if justified |
| --- | --- | --- |
| High CPU | Sustained CPU under the relevant workload, not startup/warmup alone | Bounded JFR CPU/execution samples; correlate hottest application frames with source |
| Hung requests/contention | Repeated relevant platform-thread stacks, owner/waiter relationships where exposed, pool and request context | Approved repeated dumps or available JFR lock/park events; virtual threads need a supported virtual-thread-aware capture |
| Allocation/GC pressure | GC frequency/time and heap trends per comparable amount of work | Approved histogram or JFR allocation events; allocation is not retention |
| Suspected retention leak | Growing comparable retained/live-set trend over repeated work after warmup | Dedicated HPROF approval and local retained-size/dominator/GC-root analysis |
| RSS growth with stable heap | Threads/stacks, buffers, pools, process/container limits | NMT if already enabled; otherwise propose an approved restart enabling it |
| Thread growth | Available platform-thread counts/stacks and executor lifecycle; virtual-thread coverage remains unknown | Approved virtual-thread-aware capture where supported, targeted recording, or working Java-agent thread-activity evidence |

### Local JDK command recipes

These examples are **not permission to execute**. Confirm `TARGET_PID`, an absolute user-approved `CAPTURE_DIR`, and
recording/output names before any action. Use a dedicated capture directory accessible only to the intended user
(for example mode `0700` on POSIX, or equivalent ACLs); check ownership and permissions before capture. The target JVM
writes JFR/heap files: setting `umask` only on the `jcmd` client does not restrict those files. Check actual output
permissions too. The shell quotes do not survive unchanged into every target diagnostic parser. The POSIX JFR recipe
below deliberately accepts only a narrow absolute-path alphabet: reject spaces, `%` substitutions, quotes, backslashes,
and other parser metacharacters in the **complete output path**, including its filename, before starting it. If the
approved path is incompatible, stop and obtain approval for a different private path; never silently truncate or
relocate output. On Windows, use a separately verified
target/client quoting recipe rather than translating this POSIX example by guesswork.

Choose a unique investigation-owned recording name and unused output filename. Keep their assignment and all
non-capturing guards in one shell invocation before the JFR recipe:

```bash
case "${CAPTURE_DIR:-}" in
  /?*) ;;
  *) printf '%s\n' 'CAPTURE_DIR must be the approved absolute private directory, not empty or root' >&2; exit 1 ;;
esac
JFR_NAME=bootui-investigation-unique
JFR_OUTPUT="$CAPTURE_DIR/investigation-unique.jfr"
case "$JFR_OUTPUT" in
  /*) ;;
  *) printf '%s\n' 'JFR_OUTPUT must be an approved absolute POSIX path' >&2; exit 1 ;;
esac
case "$JFR_OUTPUT" in
  *[!A-Za-z0-9_./-]*)
    printf '%s\n' 'JFR_OUTPUT contains unsupported JFR parser characters; request another approved path' >&2
    exit 1 ;;
esac
case "$JFR_NAME" in
  ''|*[!A-Za-z0-9_-]*)
    printf '%s\n' 'JFR_NAME must contain only ASCII letters, digits, underscores, or hyphens' >&2
    exit 1 ;;
esac
```

Confirm that the complete output path's resolved parent in the target JVM's filesystem is exactly the approved private
directory, including symlinks and container mappings; the client's path alone is insufficient. Re-run the guard and
parent check after any name/path change. Validate the target's available diagnostic commands first:

```bash
jcmd "$TARGET_PID" help
jcmd "$TARGET_PID" help Thread.print
jcmd "$TARGET_PID" help JFR.start
```

For an approved thread investigation, a suggested bounded default is three snapshots five seconds apart. Capture each
with a command supported by the target, for example `jcmd "$TARGET_PID" Thread.print -l`, to three distinct local files.
Do not paste entire raw dumps into a cloud-model conversation. Traditional dumps may omit virtual threads; use the
target's supported virtual-thread-aware dump facility only after checking its help/impact, or report the coverage gap.

For an approved recording, choose a unique investigation-owned name and an unused absolute output path. A common
HotSpot example, only after confirming support, is:

```bash
jcmd "$TARGET_PID" JFR.start name="$JFR_NAME" settings=profile \
  duration=30s maxsize=64m filename="$JFR_OUTPUT"
jcmd "$TARGET_PID" JFR.check name="$JFR_NAME"
```

The name/path are placeholders: choose fresh values, never collide with or stop an existing recording. Confirm the
recording is running and then completed; inspect file existence/size and parser success before interpreting it.
`maxsize` bounds recording retention, not total JVM/process memory or all temporary disk usage. Profile overhead and
event availability depend on the JVM and workload. Use local `jfr summary` and, when supported, `jfr view` or a local
analyzer; check available commands first. A summary proves event presence, not a hotspot diagnosis. Do not print raw
event payloads to the agent. Review and sanitize selected structural aggregates locally.

If interrupted, check recording status and stop **only this investigation's** recording by its exact name when
authorized. Never use a wildcard or stop another recording. A rejected/failed start is not a successful capture.

For NMT, discover support and whether it is enabled with the target's help/status. When enabled, take a permitted
`VM.native_memory baseline` and later `VM.native_memory summary.diff` over the agreed workload; both alter/read NMT
diagnostic state and need scope-specific approval. When disabled, propose an explicit development restart with an
appropriate `-XX:NativeMemoryTracking` level. NMT cannot account for every native allocation; reserved, committed, and
resident memory are different measurements.

### Heap capture and local retained analysis

Before **any** raw heap capture, get dedicated approval covering full-GC/pause risk, uncertain file size, possibly
plaintext secrets/personal data, output location, available disk, analysis method, and retention/deletion. There is no
universal safe file-size cap; if conservative headroom cannot fit the agreed budget, decline the capture.
With attachment approval, discover `jcmd "$TARGET_PID" help GC.heap_dump` and use only supported options with an unused
absolute `.hprof` path. Apply the same nonempty-directory and complete-output-path guards above to that final filename,
including substituted parts, and confirm its target-resolved parent is exactly the approved private directory.
Do not assume a live histogram is a cheap substitute: `analyze_heap_dump` and some JDK histogram modes request GC.

Analyze HPROF with an installed local tool capable of dominators, retained sizes, and paths to GC roots, such as a
compatible Eclipse MAT installation. Check its local batch/help interface and resource requirements before launch.
Run bounded reports locally; a large dump can require substantial analyzer heap/disk. No analyzer available means
retained ownership is unverified: request installation permission or provide a justified next step, not a fake result.

Inspect candidate retaining structures and their application ownership. Large shallow counts alone cannot identify
a leak. Distinguish expected bounded caches, long-lived shared data, and in-flight work from unbounded retention.
Do not expand objects to obtain credentials, strings, payloads, or unrelated application data.

### Artifact and measurement discipline

- Store raw captures and analyzer output outside tracked source in a user-approved local directory with restrictive
  access. Check actual ownership/permissions before collection; do not assume a directory is private.
- Raw HPROF/JFR/dumps and generated reports can be sensitive. Never attach, upload, commit, or automatically forward
  them to any model/provider. The agent receives only a reviewed, sanitized structural summary: selected class/method
  identities, aggregate counts/sizes, ownership relationships, and limitations, never object values.
- Agree on retention/cleanup, including analyzer indexes, temporary files, and derived reports. Delete only exact
  investigation-owned paths when approved. Report incomplete cleanup; never remove a shared capture directory.
- Record capture mode, GC side effects, JVM flags/limits, workload, timestamps, and event/sampling coverage.
  CPU samples are statistical evidence, not exact method CPU duration. NMT/RSS/heap and allocation/retention are distinct.
- Do not silently use low-footprint mode before capture: it clears buffers and changes conditions. If approved, retain
  the initial baseline first, record the change, and compare only equivalently configured runs.

## Fix and verify

Validate the source mechanism: unbounded retention, missing resource closure, runaway executors, contention, blocking
on event loops, or avoidable allocation. Propose the smallest lifecycle/algorithm fix; do not reflexively enlarge heap,
grow pools, change collector, or force GC to make a chart look better. In review-first mode stop before edits.

For approved fixes, use [Verify my changes](verify-my-changes.md) and repeat the same workload with equivalent warmup,
data, flags, limits, concurrency, capture profile, and measurement window. Agree on sample count and acceptance before
execution: e.g. bounded live-set trend across repeated cycles, lower allocation per operation, stable thread count,
or reduced confirmed contention. Include functional correctness and error rate. Comparing a post-full-GC dump with an
ordinary pre-GC heap reading is not a leak-resolution test.

## Stop and report

Stop on denied attachment, insufficient disk, unsupported commands/events, missing analyzer, recording/analysis
failure, unsafe disclosure, or exhausted budget. Explain measured scope, hypotheses, next justified action, and whether
the fix is resolved or unverified. A successfully written dump or green test suite alone does not prove a diagnosis.
