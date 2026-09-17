# `plugins/` — the Claude Code plugin payload

`plugins/bootui` is the [Claude Code plugin](https://www.julien-dubois.com/boot-ui/ai-agents)
published by the marketplace manifest at [`.claude-plugin/marketplace.json`](../.claude-plugin/marketplace.json).
Users install it with:

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
copy of the Java sources on every user's disk. The curated directory installs 36 KB.

## Why `plugin.json` carries no `version`

`claude plugin validate` warns about this on purpose — please don't "fix" it. Without a `version`,
Claude Code versions the plugin by the git commit SHA, so every push to `main` reaches installed
users as an update. Pinning a version would mean the plugin only refreshes when someone remembers to
bump it, which would leave a one-line skill fix waiting for the next Java release, and would couple
the release workflow to an artefact it otherwise knows nothing about.

## Why only the `bootui` skill

`.github/skills/bootui-java-development` is a maintainer skill: it teaches an agent how to work on
*this repository*. Shipping it to someone who just wants to scan their own application is noise at
best and misdirection at worst. It is not under `plugins/bootui/skills/`, and the second test in
`ClaudeCodePluginPayloadTests` fails if anything but `bootui` ever appears there.
