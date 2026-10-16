---
name: bootui-rubber-duck
description: Use to rubber duck a proposed BootUI design, plan, or implementation, either in the current agent session or in a pull request, by getting independent critiques from three different powerful models and synthesizing them. Not for routine reviews of trivial changes.
metadata:
  internal: true
---

# BootUI rubber duck

Get three independent, high-signal critiques of a proposal from three different powerful models, then reconcile them.
This develops BootUI itself and supplements [repository instructions](../../../AGENTS.md) and the relevant
[path-scoped instructions](../../instructions/).

## 1. Define the subject

Pick one target and state it precisely:

- **Session**: the plan, design, spike conclusion, or uncommitted working-tree change in this session
  (`git status`, `git diff`, plus the intent behind it).
- **PR**: a pull request number; read its description, `git diff` against the base (or `gh pr diff`), changed tests,
  and CI state.

Collect only what a reviewer needs: goal, constraints, files changed, tests run, and open doubts. Read the docs
that govern the area (`docs/SPECIFICATION.md`, `docs/features/`, `docs/WEBFLUX-SUPPORT.md`,
`docs/QUARKUS-SUPPORT.md`, `PRODUCT.md`/`DESIGN.md` for UI) so the brief names the invariants at stake.

## 2. Write one self-contained brief

Reviewers are stateless and must not see each other's output. The brief contains:

1. Goal and acceptance case.
2. The proposal (plan text or diff/PR reference) and where to find it in the repo.
3. Invariants to check: package direction core <- engine/SPI <- adapters; thin Spring/Quarkus adapters; immutable
   annotation-free DTOs; local-only fail-closed policy and masking; no network/scan/mutation on page load; classloading-safe
   optional integrations; parity across Spring MVC, WebFlux, and Quarkus or honest availability; MCP/CLI contract
   impact.
4. Instruction: report only high-confidence bugs, logic errors, security issues, missed stacks/consumers, missing
   tests, and design flaws, each with evidence (file and line) and a suggested fix. Ignore style. Say explicitly
   when nothing is wrong. Read-only: do not edit files.

## 3. Run three models in parallel

Launch three `rubber-duck` agents (use the `task` tool, `agent_type: rubber-duck`, background mode) at the same time,
each with a **different** model from the strongest available. Prefer one per model family, for example:

- `gpt-6-sol` (or `gpt-6.1-sol`)
- `claude-sonnet-5.5` (or `claude-sonnet-5` at `high`/`max` effort)
- `gemini-4-argon` (or `grok-4.7`)

Use the highest reasoning effort offered, and fall back to the next-best model if one is unavailable. Keep the same
brief for all three. Respect the repository's limit on parallel sub-agents (3 fits within it). While they run, do
independent work such as reproducing the riskiest claim yourself; do not poll.

## 4. Synthesize

Read all three results, then produce a single list:

- **Consensus**: issues raised by two or more models; treat as likely real.
- **Unique findings**: verify each against the code or by a targeted test before accepting or rejecting.
- **Disagreements**: resolve with evidence, not majority vote.
- **Rejected**: false positives, with a one-line reason.

Classify accepted items as blocking or follow-up. Do not accept a claim you have not verified.

## 5. Act and report

- Session: revise the plan or code for blocking items, rerun the smallest validation that proves the fix, and rerun
  the duck only if the change was significant.
- PR: post one concise summary comment with consensus, verified findings, and what was rejected. Push fixes only if the
  user asked for them; never push to a green PR unasked.

Finish by telling the user which models were used, what was accepted, rejected, and still open.
