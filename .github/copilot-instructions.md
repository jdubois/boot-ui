# BootUI repository instructions

The repository instructions live in [`AGENTS.md`](../AGENTS.md) at the repository root, so that every agent
runtime reads the same source of truth. Read that file.

This file exists only for Copilot surfaces that still look up `.github/copilot-instructions.md`. Do not add
rules here; add them to `AGENTS.md`, to a path-scoped file under `.github/instructions/`, or to a custom agent
under `.github/agents/`.
