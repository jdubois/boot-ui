---
name: bootui-rubber-duck
description: Use when asked to rubber duck, critique, or get a second opinion on a proposed BootUI design, plan, or implementation, either in the current agent session (uncommitted changes, plan, spike conclusions) or in a pull request. Runs three independent reviews with three different highly capable models and consolidates the findings. Not for routine code review of trivial changes.
metadata:
  internal: true
---

# BootUI rubber duck

Get independent, high-signal critique of a proposal from **three different powerful models**, then reconcile it.
Different models miss different things; agreement raises confidence, disagreement shows where to look harder.

Follow [repository instructions](../../../AGENTS.md). This skill reviews; it does not edit code unless the user asks
you to apply the accepted findings afterwards.

## 1. Establish the subject

Pick the target from the request:

- **Agent session**: the plan, spike conclusion, or working-tree change (`git status`, `git diff`, `git diff --staged`,
  plus the relevant commits on the branch).
- **Pull request**: the PR description, `git diff` against the base (fetch the base if the clone is shallow), changed
  files, linked issue, and any existing review comments or failing checks.

Collect the goal, the constraints that apply (architecture invariants, local-only/fail-closed safety, panel and
adapter parity, the path-scoped instructions under `.github/instructions/`), and the files that matter. Do not
include secrets or unrelated private data in the prompts.

## 2. Build one self-contained brief

Each reviewer starts with no context. Write a single brief and send the same one to all three:

- What is proposed and why; the acceptance case.
- Exact paths, diff or summary, and where to read more. Tell reviewers to read the code themselves.
- Repo invariants most likely at risk (package direction core <- engine/SPI <- adapters, DTO immutability and
  Jackson 2/3 byte-compatibility, masking via `SecretMasker`, no network or mutation on page load, optional-dependency
  classloading safety, Spring MVC / WebFlux / Quarkus availability honesty, MCP/CLI schema compatibility).
- The ask: find bugs, logic errors, missed cases, design flaws, and missing tests or docs. Skip style and nitpicks.
  Rank by severity, cite file and line, state confidence, and say what evidence would settle each point.

## 3. Run three reviews in parallel

Launch three `rubber-duck` sub-agents in the same call, each with a **different** model override. Use the three most
capable models available in the `task` tool's model list, from three different families where possible, preferring
the highest-capability tier of each (for example one GPT, one Claude, and one Gemini or Grok model at high or
maximum reasoning effort). Check the current list rather than assuming names; do not use the same model twice, and
do not substitute small or "mini"/"flash"/"haiku" models.

Respect the load rule: three sub-agents is within the usual limit of four; do not start more.

If a model is unavailable or fails, replace it with the next most capable unused model and say so. If fewer than
three distinct strong models exist, run what is available and state the shortfall.

## 4. Consolidate

Do not paste three transcripts. Produce one report:

1. **Verdict**: proceed, proceed after fixes, or rethink, with the reason.
2. **Consensus findings**: raised by two or three models, highest severity first.
3. **Single-model findings**: keep only those you can verify; verify against the code before including.
4. **Disagreements**: state each position and your resolution after checking the code.
5. **Rejected findings**: false positives with a one-line reason.
6. **Suggested changes**: concrete, ordered, with files and tests/docs to update.

Verify every finding that would change the design by reading the cited code. Never relay an unverified claim as fact.

## 5. Follow-up

For a PR, post the consolidated report as a comment only if the user asked; otherwise return it in the session. After
fixes are applied, re-run only the reviewers whose findings were significant, and only for substantive changes.
