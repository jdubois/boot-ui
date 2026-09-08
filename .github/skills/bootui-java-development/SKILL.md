---
name: bootui-java-development
description: Use when implementing, debugging, refactoring, or testing existing BootUI Java code, including framework-neutral engine logic, Spring MVC and WebFlux adapters, Quarkus runtime and deployment integration, optional dependencies, DTO contracts, and Maven build failures. Not for frontend-only work, creating unrelated applications, or release operations.
---

# BootUI Java development

Use this skill for substantive Java maintenance in this repository. It provides an investigation and implementation
workflow, not a replacement for repository instructions or a requirement to delegate work.

Follow [repository instructions](../../copilot-instructions.md) and the relevant
[path-scoped instructions](../../instructions/). Consult [the module map](../../../docs/REPOSITORY.md) when locating
ownership. Build and delivery requirements remain authoritative in [CONTRIBUTING.md](../../../CONTRIBUTING.md) and,
when selected, the [vertical-PR agent](../../agents/bootui-vertical-pr.agent.md).

This skill develops BootUI itself. The separate [consumer skill](../../../skills/bootui/SKILL.md) teaches agents to
install and use BootUI in another application. Dr JSkill's application-creation workflow is not required here.

## 1. Establish the change boundary

Read the request, working-tree changes, relevant implementation, and nearby tests before editing. Identify:

- The behavior to preserve or change and a concrete regression or acceptance case.
- The owning layer: core DTO/helper, engine policy/service, adapter observation/wiring, or public transport.
- The affected stacks and consumers, including MCP/CLI where applicable. Read the support and specification documents
  required by repository instructions before changing behavior; do not infer parity from a similarly named class.
- The smallest validation that could disprove the proposed fix.

For a simple lookup or local edit, use direct tools. For a larger task, delegate only independent scopes with explicit
file ownership and acceptance boundaries. Tell Java subagents to load this skill; avoid concurrent Maven builds in
the same worktree. Loading it does not authorize committing, publishing, or spawning additional sessions.

## 2. Investigate with evidence

Find the file using a known path or a narrow glob. Read the implementation and its tests together, then trace the
relevant call chain. Search for an existing helper or SPI before adding one.

Use Java LSP when it answers a semantic question:

| Question | Operation |
| --- | --- |
| What type or overload is this? | `hover`, `goToDefinition` |
| Which code consumes this symbol? | `findReferences`, `incomingCalls` |
| Which classes implement this port? | `goToImplementation` |
| What is the structure of this large file? | `documentSymbol` |
| Where is a named symbol? | `workspaceSymbol` |
| Which usages must change with this name? | `rename`, followed by diff review |

Use the actual symbol position in the current file, not a guessed or pre-edit line number. Before relying on
cross-module results, compare them with one known declaration or caller. An empty workspace search or references
limited to the declaration's file do not establish absence. If results are suspect, use a narrow glob/text search
across the expected modules and inspect the matching code. Do not repeatedly retry the same query or install a new
language server as part of an unrelated fix.

LSP is an aid, not a gate: continue with targeted searches when it is unavailable. A successful tool call or symbol
outline proves neither complete indexing nor compilation. Semantic renames also require checking string-based
registrations, resources, configuration keys, and public contracts that LSP cannot safely rename.

## 3. Implement the smallest complete change

Load only the relevant section of [change recipes](references/change-recipes.md):

- Extract shared behavior or add an SPI.
- Change a DTO or a public response.
- Fix an advisor's false positive or missing evidence.
- Integrate an optional framework capability.

Keep the regression case close to the behavior's owner. Prefer deterministic fixtures, fake providers, and controlled
clocks to real network calls, host-dependent observations, sleeps, or an entire application context for pure logic.
Exercise the failure and absence paths as well as the positive case. Wire every affected adapter; do not compensate
for a missing binding with a silent fallback.

## 4. Validate and debug deliberately

Load [validation and debugging](references/validation.md) before choosing Maven commands. Start with the smallest
relevant selection, then satisfy the required integration and delivery gates. Inspect reports to confirm that the
intended tests and framework augmentation actually ran.

When a run fails, classify the first actionable failure before editing: dependency resolution, compilation,
framework bootstrap, assertion failure, or environment limitation. Preserve its diagnostic and reproduce it at the
smallest useful scope. Do not respond to each failure with another full build or unrelated source changes.

Review the final diff against the acceptance boundary and report any remaining blocker honestly. Do not claim
compilation, cross-stack compatibility, or absence safety from an LSP response or a skipped test run.
