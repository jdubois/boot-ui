# Appendix B - Troubleshooting

Use the smallest safe check first. Keep guards on and preserve your evidence. Tell the facilitator which checkpoint
is incomplete; pairing is preferable to spending an entire chapter reinstalling tools.

## Setup and connection

| Symptom | Check | Recovery |
| --- | --- | --- |
| Missing commands/features | Release tag/SHA, CLI and Java agent versions | Use the workshop's 2.0.0 components, not a 1.x CLI or moving branch |
| JDK/toolchain failure | `java -version`, `JAVA_HOME`, wrapper output | Use a supported local JDK; early-access JDKs are not required |
| Maven cannot find BootUI modules | Repository root and isolated `.m2` | Repeat Chapter 01's `-am install` warm build; then run just the sample |
| First build is slow/offline | Dependency/toolchain download logs | Warm beforehand; use a helper's prepared checkpoint or pair |
| 8080 already belongs to another app | Existing listener and application identity | Pick a free port; update launcher, browser, MCP, `BOOTUI_URL`, and curl |
| Console 404 | Profile, activation, root path, port | Return to the dev launch; do not force activation in production |
| CLI/traffic points at the wrong port after restart | Terminal 2 `BOOTUI_URL`, browser and overview identity | Reuse the selected URL; a new terminal needs it set again |
| SQL says seed table missing | Startup runners not finished | Wait for the sample welcome/startup readiness, then retry once |
| Optional services start | Launcher and active profiles | Use the Chapter 01 MVC dev launch, not the all-services launch; Docker is not required for the core |
| CLI not found | Installer location/new terminal PATH | Use the reviewed installer guidance; do not reinstall the app |
| MCP tools absent | Opt-in toggle, loopback URL, client format/trust, client reload | Copy the panel's config for that client and test a real read; use CLI fallback |
| MCP reset after JVM restart | Runtime toggle/persisted config | Re-enable MCP explicitly for the new workshop process |
| Local MCP/CLI gets 401/403 | Host app security, actual caller address, transport response | Preserve guards; do not add a wildcard remote allow rule to proceed |
| Skill not discovered | Whole consumer directory and client skill support | Reload client and check `bootui` discovery; contributor skill is different |

## Runtime evidence

| Symptom | Check | Recovery |
| --- | --- | --- |
| Empty activity | Journal/source policy, exact port, agreed route traffic | Send only the authorized workload and inspect Recording |
| Missing profile/exception detail | Current id, retained journal vs panel buffer | Select a fresh profileable request; report eviction/missing rich details |
| No repeated SELECT observation | Show all routes/search, >=3 qualifying requests, >=5 repeated SELECTs | Use six sequential samples, allow processing, inspect SQL groups |
| Earlier advisor/import evidence disappeared | Chapter 05 full process restart | Use labeled worksheet history, collect a new eager-order baseline; fresh scans need separate approval |
| PowerShell request loop fails | `Invoke-RestMethod -ErrorAction Stop` and actual response error | Stop immediately; do not count incomplete traffic as six samples |
| Windows agent jar path fails | Warm build result, exact jar, comma in path | Stop on missing jar; rebuild or use a supported path, never an empty agent argument |
| Quarkus Dev Services cannot start | Docker/Podman and PostgreSQL prerequisite | Prepare Appendix C's container runtime or local PostgreSQL separately |
| Missing time breakdown | Five warm requests and capture coverage | Warm/collect; unknown time is not proof of slow business code |
| Java Agent `NOT_ATTACHED` | Correct agent launcher or startup `-javaagent` | Full-stop and attach only at the Chapter 05 boundary |
| `ARMED` but sensor unavailable | Installed state and self-tests, packages, release/protocol | Read reason; attachment alone does not prove capture |
| No Code Path | Instrumented application bean/method, same run, row/depth limits | Exercise the route; explain excluded or assembly-only work |
| Async work missing | Executors sensor, request identity, waited-for completion | Use the exact fixture and allow one second; do not invent correlation |
| Probe has zero invocations | Correct descriptor, active state, window | Wait active, then send two calls; it stops at 20 invocations/60 seconds |
| Side Effects empty | Sensor installed/whole-run coverage, actual trigger | Absence with missing coverage is not a no-effects verdict |

## Change and comparison

| Symptom | Check | Recovery |
| --- | --- | --- |
| Source saved but unchanged method inventory | Running clone/classpath, compiler output, DevTools run id | Compile the sample; observe actual same-JVM restart |
| Compiled but no restart | Chapter 05 trigger-file launch and marker timestamp | After compile/test finishes, touch `.workshop-reload` in sample `target/classes` |
| Changed but not exercised | Original route traffic in the new run | Send agreed samples and refresh; this state is not a passed fix |
| Comparison unavailable | Prior exercised run, same application/JVM, retained baseline | Re-establish a baseline; label a lost comparison incomplete |
| Comparison points at an idle intermediate run | Multiple compile/reload events and run ids | Restore the reference query in your disposable clone, exercise, then redo one edit/reload |
| Side effects not comparable | Sensor switching, partial capture, configuration drift | Keep settings stable; report not compared rather than no effects |
| Acceptance test initially fails with 17 statements | Failure is query-budget assertion only | Expected red; apply the approved edit, rerun |
| Upstream eager fixture test fails after fix | Test intentionally expects N+1 behavior | Record known exercise incompatibility; use positive workshop acceptance, no silent skip |
| Test passes but live route still slow | Test process vs running app | Verify live run/method/request; test process is separate |
| Agent/provider offline | Client auth/network/provider policy | Pair or use the labeled transcript; mark live agent use incomplete |

Restore only your own exercise edits, by reviewing/replacing the small service method. Do not use
`git reset --hard`, discard another person's changes, clear all recordings, or erase a Maven repository as a recovery
shortcut.

**Return:** [Workshop overview](README.md).
