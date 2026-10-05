# Agent benchmark fixtures with the BootUI agent

These fixtures extend the local agent benchmark of PLAN-v2 §2.2 ("Agent effectiveness") with the BootUI Java agent
(§5.17): the eleventh scripted investigation and the sixth refusal fixture. The first ten investigations and five
refusal fixtures are described in [the validation report](../../../../../docs/V2-VALIDATION-REPORT.md#agent-investigations).

They live here, not under `validation/`, on purpose: every file under `validation/` belongs to the protocol registered by
the immutable tag `m4-20-protocol-2`, and adding a file there would make the registered scorer refuse to score. These
fixtures are not part of that protocol; a later registered protocol may adopt them.

Each fixture is run like the others: a clean agent session, the `bootui` CLI or the MCP server as the only evidence (no
source, logs, or HTTP beyond the requests the fixture sends), one question, graded against the expected answer by an
independent reviewer. `AgentBenchmarkFixturesTests` keeps each fixture's `tools`, `cli`, and `guidance` lines true to the
tool catalog, the CLI command paths, and the MCP guidance text, so a fixture cannot name a tool or a step BootUI no
longer offers.

| Fixture | Question | Right answer |
| --- | --- | --- |
| [Investigation 11](investigation-11-did-my-change-run.md) | Did my change to `OrderService` run? | Not yet: the candidate route reaches another bean's method; a method probe confirms it, and names the route that does |
| [Refusal 6](refusal-6-agent-not-applicable.md) | Is my change to `OrderService` safe to ship? | Not measured: the BootUI agent is not attached, so `NOT_APPLICABLE` is not a pass |
