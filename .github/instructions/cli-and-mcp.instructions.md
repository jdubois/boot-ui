---
applyTo: "bootui-cli/**,bootui-client/**,bootui-engine/**/mcp/**,bootui-engine/**/cli/**,docs/AI-AGENTS.md,docs/CLI.md,skills/**"
---

# CLI, client, and MCP tools

- `McpToolCatalog` is the single registry of BootUI diagnostics. The CLI is a mechanical projection of that catalog, not
  a hand-maintained copy: it must never offer a command the MCP server does not expose, nor lack one it does.
- Every new tool needs a `CliCommandPaths` entry and a regenerated `bootui-cli/src/main/resources/bootui-tools.json`.
  Regenerate with `-Dbootui.manifest.write=true` instead of editing the manifest by hand; `ToolManifestGeneratorTests`
  fails when the catalog, the command paths, and the checked-in manifest disagree.
- Command paths must be unique, and no path may be a prefix of another. `bootui traces` cannot be both a command and the
  parent of `bootui traces clear`, because picocli would make one of the two tools uninvokable.
- `bootui-client` stays dependency-free. It depends on nothing at runtime, not even `bootui-core`, so it can be dropped
  into any build; keep its JSON handling self-contained rather than reaching for a JSON library or a shared DTO.
- Adding an MCP tool is a lockstep change: `McpToolCatalog` and `McpToolDescriptions` (plus their tests), the Spring MVC
  `BootUiMcpTools`, the WebFlux `ReactiveBootUiMcpTools`, the Quarkus `QuarkusMcpTools` where the capability exists,
  their adapter bean/producer methods, the CLI command path and manifest, and `docs/AI-AGENTS.md`, `docs/CLI.md`, and
  `docs/SPECIFICATION.md`.
- When a tool is unavailable on a stack, report that honestly through availability rather than removing it from the
  shared catalog.
- Keep `skills/bootui/SKILL.md` aligned when tools, panels, published coordinates, or install instructions change. It is
  a user-facing surface that duplicates that information and drifts silently otherwise.
- The CLI and client are published artifacts. Treat their command names, exit codes, and JSON output as public contract.
