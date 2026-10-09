# `plugins/` — the portable agent plugin payload

`plugins/bootui` is the curated, user-facing plugin payload documented in the
[AI agents guide](https://www.julien-dubois.com/boot-ui/ai-agents). It carries two manifests because the same directory
serves two compatible plugin systems:

- [`plugin.json`](bootui/plugin.json) and [`mcp.json`](bootui/mcp.json) follow Agent Plugins 1.0.0. Cursor and other
  compatible clients discover the skill and Streamable HTTP server from these files.
- [`.claude-plugin/plugin.json`](bootui/.claude-plugin/plugin.json) uses Claude Code's client-specific manifest,
  including its `BOOTUI_MCP_URL` override.

The repository advertises that directory through both
[`.cursor-plugin/marketplace.json`](../.cursor-plugin/marketplace.json) and
[`.claude-plugin/marketplace.json`](../.claude-plugin/marketplace.json). Claude Code users install it with:

```
/plugin marketplace add jdubois/boot-ui
/plugin install bootui@bootui
```

## Do not hand-edit the shipped skill

`plugins/bootui/skills/bootui/SKILL.md` is a byte-for-byte copy of the canonical
[`skills/bootui/SKILL.md`](../skills/bootui/SKILL.md). Edit the canonical file and copy it over:

```bash
cp skills/bootui/SKILL.md plugins/bootui/skills/bootui/SKILL.md
```

`ClaudeCodePluginPayloadTests` in `bootui-engine` fails the build when the two drift, so a hand-edit
here turns the build red rather than quietly forking the instructions users get from the ones
`gh skill install` gives Copilot.

## Why a copy exists at all

A plugin marketplace installs a *directory*, and a plugin cannot follow a pointer out of its own
root — Claude Code copies the plugin directory into a per-version cache, so anything outside it is
simply absent.

The alternative is pointing the plugin at the repository root (`"source": "./"`). That works, and it
was measured: it copies the entire monorepo — 396 MB from a working tree with `node_modules` and
`bootui-ui` build output, tens of MB from a clean clone — into the user's plugin cache, **once per
version**. Since the plugin is versioned by commit SHA, every push to `main` would land another full
copy of the Java sources on every user's disk. The curated directory carries only the skill, manifests, connection
configuration, and plugin README; its size grows with that payload, not with the Java sources or build output.

## Plugin versions and release tags

The portable [`plugin.json`](bootui/plugin.json) carries the BootUI release version. The Release workflow updates it
before committing and signing the release tag, then verifies it again from the immutable tagged checkout before
publication. This lets directories such as Awesome Copilot compare their listing version with the reviewed payload.
Use a new release tag and its full commit SHA for a submission; existing signed tags are never changed to add metadata.

The Claude Code manifest deliberately omits `version`. `claude plugin validate` warns about this on purpose.
Without a `version`, Claude Code versions the plugin by the git commit SHA, so every push to `main` reaches installed
users as an update. The release workflow leaves that client-specific manifest untouched, so skill fixes do not have
to wait for the next Java release for Claude Code users.

## Awesome Copilot submission

Submit this payload as an external plugin, following the
[Awesome Copilot contribution guide](https://github.com/github/awesome-copilot/blob/main/CONTRIBUTING.md#adding-external-plugins).
This keeps the skill authoritative in this repository instead of introducing a separately maintained upstream copy.
An external-plugin listing distributes the bundled skill but does not imply a separate entry in the Skills directory.

1. Merge the plugin preparation changes and use the next normal release containing them. Do not modify an existing
   release tag. Confirm `plugins/bootui/plugin.json` at the new tag declares the same version as the release.
2. Resolve the tag's commit with `git rev-parse 'v<VERSION>^{commit}'` and verify installation from the released payload.
3. Open the [external-plugin submission form](https://github.com/github/awesome-copilot/issues/new?template=external-plugin.yml),
   not a PR adding `plugins/external.json`. Use name `bootui`, repository `jdubois/boot-ui`, path `plugins/bootui`,
   the release tag and full commit SHA, the matching version, and license `Apache-2.0`.
4. Supply the author, homepage, and keywords from `plugin.json`. Explain that the payload includes the consumer skill
   and a loopback MCP connection, requires a running application and explicit MCP enablement, and supports diagnostics
   through the CLI or HTTP without MCP.
5. Address metadata, Vally lint, install smoke-test, version-match, and ref/SHA consistency feedback. After fixes,
   update the submission's immutable locator as needed and request `/rerun-intake`.
6. Wait for maintainer approval and the generated listing PR to merge. Approval is not automatic after passing intake.

Suggested short description:

> Diagnose running Spring Boot and Quarkus applications with BootUI. Includes a setup and troubleshooting skill plus
> a local MCP connection for runtime diagnostics and evidence-based recommendations.

After acceptance, submit listing updates for newer reviewed releases through the upstream external-plugin update
process. Awesome Copilot also schedules maintainer re-review six months after approval.

## Why only the `bootui` skill

`.github/skills/bootui-java-development` is a maintainer skill: it teaches an agent how to work on
*this repository*. Shipping it to someone who just wants to scan their own application is noise at
best and misdirection at worst. It is not under `plugins/bootui/skills/`, and the second test in
`ClaudeCodePluginPayloadTests` fails if anything but `bootui` ever appears there.
