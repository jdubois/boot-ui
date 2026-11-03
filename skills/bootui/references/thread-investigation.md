# Investigate a blocked thread and verify the fix

Use this workflow for blocked requests, lock contention, deadlocks, or suspected stuck platform threads.
It works on Spring MVC, WebFlux, and Quarkus without the optional BootUI Java agent.
BootUI supplies evidence; the coding agent must inspect the application's source and make only an approved fix.

## Discover and reuse evidence

Use the already-connected MCP tools, otherwise the CLI or its plain HTTP endpoint described in `SKILL.md`.
Read the tool catalog and per-panel policy first; do not enable MCP just to run these diagnostics.
`get_thread_snapshots` (`bootui thread-analysis list --json`) lists retained captures and the latest
`observationId` without collecting. Check capture time, `run.runId`, expiry, and quality before reusing one.
An old capture is evidence of that moment, not the current state.

When useful, `get_threads` with `{"query":"BLOCKED","limit":10}` (`bootui threads --query BLOCKED --limit 10 --json`)
reads live states and stacks. This reads the JVM again: its numeric thread IDs and cumulative CPU are not a retained
capture or a current CPU percentage. `WAITING` / `TIMED_WAITING` alone is not a defect; pools, conditions, and timers
normally wait. Virtual threads are outside this population, including many WebFlux bounded-elastic workers on JDK 21+.

If existing evidence is missing or cannot answer the question, obtain approval for `capture_thread_snapshot`
(`bootui thread-analysis capture --json`). It performs bounded JVM stack acquisition that can briefly pause the
application. Do not retry acquisition automatically on busy, expiry, or partial results.

## Trace the waiter to the owner and source

1. Keep the returned capture `id`. Call `get_thread_analysis` with `{"id":"<capture-id>"}`
   (`bootui thread-analysis show <capture-id> --json`). Always pass this ID during the investigation instead of
   repeatedly selecting "latest", which another client can change.
2. Read `capture.quality` and the four result sections: `rootBlockers`, `cycles`, `unresolvedWaits`, and `stackGroups`.
   Prioritize a root's `transitiveBlocked` and `directBlocked`, but inspect `partial` and
   `blockedListTruncated` before claiming the chain is complete.
3. For a root, call `get_thread_detail` with its exact `observationId`, then with a returned
   `blockedObservationIds` entry (`bootui thread-analysis thread <thread-observation-id> --json`).
   Inspect both threads' `thread.stackTrace`, state, `lockName`, and `lockOwnerId`, plus `activity`,
   `stackTruncated`, `ownershipComplete`, and `ownedLocks`. Roots can themselves be waiting on an unresolved resource.
   For cycles, follow returned `participantIds`; for unresolved waits, follow `waiterId` and a non-null `ownerId`.
   A root's waiter IDs include transitive dependents, not just direct waiters. For a multi-hop chain, compare each
   retained waiter's `thread.lockOwnerId` with the `thread.id` of the root and other returned waiters from that same
   capture. This identifies the immediate upstream owner without inventing opaque IDs. A truncated waiter list can
   omit an intermediate owner; use the capture's REST lock context below rather than assuming a direct dependency.
4. Locate the first relevant application frames in the owner and waiter stacks in the repository. Read the actual
   synchronized block, lock acquisition/release, and calls inside the critical section, including other acquisition
   orders. A top JDK parking frame is not the application fix location. Do not invent file names or line numbers
   when frames are masked, omitted, truncated, or have no source location.
5. Correlate with the user's reproduction and, when available, request profiles, SQL/REST traces, or Code Paths.
   Never assume a thread name proves a particular request caused the contention. The lock owner may be a different
   request, scheduled job, or worker.

Three different IDs occur here: the capture ID, a **capture-scoped thread observation ID**, and a finite
**observation session ID**. Pass returned opaque IDs unchanged; do not construct them from live numeric thread IDs.
A thread observation ID names one thread in one capture, not a thread to interrupt or an observation to cancel.

### Coverage is not a verdict

- `populationComplete: false`, `omittedThreads`, `vanishedThreads`, stack truncation, or partial ownership means
  missing evidence, not "no contention". Native deadlock detection and derived supported-wait cycles are separate
  evidence; an empty result does not clear an incomplete or virtual-thread population.
- `MONITOR_ACQUISITION` and supported exclusive-synchronizer acquisitions can identify dependency edges.
  Condition waits, shared waits, and missing owners must not be interpreted as exclusive lock dependencies.
  Read/write locks require a visible JDK write-lock acquisition frame; shared readers and hidden/truncated entry
  points remain unresolved even when a writer owner is reported. This is limited classification, not no contention.
- Ownable-synchronizer inventory is opt-in (`bootui.threads.include-synchronizers=true`) and may walk the heap.
  It requires an approved configuration change/restart; do not silently enable it. Missing ownership alone is not
  permission to broaden collection.
- `METADATA_ONLY` intentionally omits stacks and lock graphs; masking can exclude a stack from equality comparisons.
  Explain the limitation, use source/tests or ask for specifically approved evidence; never disable masking.
- The browser hides `bootui-` list rows by default, but owner, contention, deadlock, and exported evidence remains
  complete. MCP retained analysis does not apply this display filter.

Each analysis section has its own page metadata (`blockerPage`, `cyclePage`, `unresolvedPage`, `groupPage`).
The current analysis/observation MCP tools accept an optional `id`, not arbitrary `query`, `limit`, or `offset`.
If a section has `hasMore` or a list is truncated, do not claim all threads were searched. Use the same retained
capture's bounded REST reads when needed: `GET <api-base>/threads/captures/<capture-id>?offset=25&limit=25` and
`GET <api-base>/threads/captures/<capture-id>/threads?state=BLOCKED&offset=0&limit=25`.
The thread list also accepts `q`; use it for the known thread name or application frame, then follow returned IDs.
`GET <api-base>/threads/captures/<capture-id>/locks/<lock-id>` provides owner/waiter context for a returned lock ID.
These reads do not collect again; use the configured API mount and existing authentication, never loosen access.
Unresolved acquisitions sort before ordinary idle waits; comparisons sort by longest consecutive sampled
acquisition streak, then interval CPU, then numeric thread ID. Ordering is a triage aid, not a severity verdict.

## Confirm persistence only when needed

With approval, `start_thread_observation` (`bootui thread-analysis observe --json`) starts a finite session
using configured defaults: three samples, five seconds apart. MCP/CLI do not take custom sample/interval arguments;
the browser's **Observe & compare** tab and REST start can configure bounded values.
Inventory `maxObservationSamples` is the effective ceiling, not a promise that all five samples fit: default
reservation budgets permit at most three. Larger sessions require an approved static budget change, not retries.
Read `get_thread_observation` with the returned session `id`
(`bootui thread-analysis observation <session-id> --json`), at the configured cadence rather than in a tight loop.
Stop at a terminal status; `FAILED`, `POLICY_STOPPED`, and cancellation are not successful collection.
Each retained sample's ID appears in `captureIds`; read its analysis explicitly.
While the session is `RUNNING` or `CANCEL_REQUESTED`, poll retained status rather than calling live `get_threads`,
capturing another snapshot, or downloading a fresh dump. All share a non-queuing acquisition gate; a busy or missed
tick ends the session with `FAILED`, without retrying. This also applies to concurrent browser clients.

If the comparison `page.hasMore` is true, continue through
`GET <api-base>/threads/observations/<session-id>?offset=25&limit=25` using the same API mount/authentication.
To test a contention hypothesis across samples, read each retained `captureId` and match the root's numeric
`threadId` and waiter/owner lock context within that run. Use each capture's returned thread observation IDs for
details; never reuse an ID from another sample. Disappearance/reappearance is identity uncertainty, not continuity.

`observedSamples`, `matchingStackOccurrences`, and `consecutiveStackStreak` are sampled observations, not proof of
continuous inactivity. Interval CPU uses adjacent valid counters on one core; missing/reset counters are unknown.
Single captures never populate a comparison, and manually capturing twice is not a finite observation.
Use `cancel_thread_observation` with the session ID to stop future samples before clearing retained evidence.
Neither cancellation nor clear interrupts a native JVM call already in flight.

## Make and verify a source fix

State the causal hypothesis and exact owner/waiter frames before proposing a change. For example, an owner performing
a slow remote call inside a synchronized region can serialize unrelated requests: check which shared state genuinely
needs protection before moving I/O outside the lock. For a verified inconsistent acquisition order, make ordering
consistent rather than deleting synchronization. Preserve visibility, atomicity, error paths, and cancellation.
Do not replace a lock or enlarge a pool merely because threads wait.

When the user approves the fix, make a focused application change and add a bounded concurrency regression test:
coordinate threads with latches/barriers, enforce a timeout, prove required progress, and always release/close workers.
Do not use `Thread.stop`, kill the JVM, interrupt unrelated workers, or mutate lock state as remediation.
Run the relevant application tests, reload/restart only as approved, and reproduce the same workload.
With approval for fresh collection, compare new contention evidence with the baseline, including capture time/run
identity, progress, root impact, and quality. Absence of the old thread after a restart is not proof of a fix.
If the optional Java agent is already present, Code Inventory can additionally show that the changed method ran;
it is not required for this workflow.

Report the evidence, code change, regression result, and any unverified limitations separately. An empty or partial
capture, masked stack, failed reproduction, or unexecuted changed path must never become "fixed".
Entering low-footprint mode clears old evidence and stops old sessions; new explicitly approved bounded captures and
observations remain usable while it stays on. Resume does not restart a session or restore evidence.
