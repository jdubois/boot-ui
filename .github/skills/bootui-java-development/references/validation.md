# Validation and debugging

[CONTRIBUTING.md](../../../../CONTRIBUTING.md) owns build, formatting, conformance, and publishing commands.
The [vertical-PR agent](../../../agents/bootui-vertical-pr.agent.md) defines its delivery gates. This guide helps choose
the inner loop; it does not relax either source's requirements.

## Establish the environment

Run commands from the current worktree root using `./mvnw`. Check the JDK actually used by Maven with `./mvnw -version`
before attributing toolchain failures to source changes. Read framework versions and active profile gates from the
current POMs instead of pinning another copy in this skill.

Use `-Dmaven.repo.local=.m2` consistently on Maven commands, including dependency installation and application runs.
If the environment already configures an isolated repository, reuse it rather than overriding it. Do not edit global
Maven settings or commit a repository override for worktree isolation. Keep builds sequential within a worktree;
different worktrees must not overwrite one another's snapshot artifacts.

Do not bootstrap dependencies speculatively. Resolve missing dependencies when a selected command reports them, and
use the wrapper and repository's existing scripts rather than installing replacement tools.

## Choose the test layer

| Changed behavior | First useful scope | Additional evidence when affected |
| --- | --- | --- |
| Core helper or DTO | Relevant `bootui-core` tests | DTO collection invariants and serializer/HTTP contracts |
| Engine service, rule, or SPI | Relevant `bootui-engine` tests with fake inputs/providers | Adapter mappings/wiring; cross-stack conformance for extraction or contract changes |
| Spring observations or configuration | Relevant `bootui-spring-autoconfigure` tests | MVC and WebFlux context/absence tests and affected sample conformance runners |
| Quarkus runtime/deployment integration | Relevant runtime/deployment unit tests | `base` and affected capability integration modules; production fixture for production gating |
| Browser-facing API, MCP, or CLI | Owning Java tests and relevant transport conformance | Required browser suites, generated CLI contract, and coupled documentation |

For an already installed current-worktree dependency graph, a concrete narrow example is:

```bash
./mvnw -B -ntp -Dmaven.repo.local=.m2 -pl bootui-engine test \
  -Dtest=MemoryScannerTests,MemoryCollectorTests
```

Substitute existing test classes that cover the actual change. Combine related selectors using the same runner in
one invocation. Do not run these memory tests for unrelated work.

If upstream worktree modules are missing or changed, use the appropriate `-pl ... -am install` command from
CONTRIBUTING, with the same isolated repository, before rerunning the owning module. Installing only an unchanged
consumer against stale dependencies does not validate an engine or DTO edit.

When intentionally combining a test selector with `-am`, Surefire may fail in upstream modules that contain none of
the selected classes. Only for that case, `-Dsurefire.failIfNoSpecifiedTests=false` can allow upstream modules through.
Confirm the selected classes actually ran in the target module; otherwise the result is not evidence. Never use this
flag to conceal a misspelled selector or substitute `-DskipTests` for validation.

## Confirm execution, not just exit status

Inspect the reactor summary and the relevant module's `target/surefire-reports` (or Failsafe reports if its POM uses
Failsafe). Confirm the reports belong to the current run, include the intended classes, and did not skip the behavior
being claimed.

Quarkus integration needs actual augmentation and application bootstrap, not only compilation of the extension.
Consult the current JDK gates in the POMs and Quarkus instructions; a green run on a JDK that skips augmentation does
not prove adapter behavior. Use a supported installed JDK, or report the missing evidence as a blocker rather than
changing the gate.

After the narrow regression passes, run the broader gates required by the affected contract and delivery workflow.
Do not launch every sample, browser suite, or the coverage reactor for each intermediate edit.

## Diagnose the first actionable failure

| Failure | Next action |
| --- | --- |
| Artifact resolution | Check selected reactor, isolated repository, upstream installation, and the reported network error before changing Java |
| `compile` or `testCompile` | Read the first compiler diagnostic and surrounding source; fix imports, signatures, syntax, or API-version mismatch in the owning layer |
| Spring context or Quarkus augmentation | Inspect the first causal exception; check registration, optional class linkage, capability gating, and dependency versions |
| Test assertion | Reproduce with the smallest selector; decide whether implementation or expectation violates the intended contract |
| Timeout or environment problem | Identify the specific process/resource or missing prerequisite; preserve diagnostics and report limits without weakening assertions |

Add or strengthen a regression test for a behavioral defect. After fixing a failure, rerun the failed selection and
then its affected integration boundary. An LSP outline, stale report, skipped suite, or successful parent POM is not
a substitute. Keep useful diagnostics in the session artifact directory; do not add scratch logs to the repository.
