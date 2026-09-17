# Distribution channels

This file records where BootUI can be listed so that people looking for a Spring Boot or Quarkus MCP server, agent
skill or Claude Code plugin actually find it, which of those listings are already handled, and which ones only
@jdubois can do because they need his GitHub account or repository admin rights.

It is a maintainer note, not user documentation. It lives in `.github/` deliberately: the VuePress site only builds
`docs/`, so nothing here is published to https://www.julien-dubois.com/boot-ui/. Keep user-facing installation
instructions in `docs/AI-AGENTS.md`.

Context: this is the hand-off from [issue #1067](https://github.com/jdubois/boot-ui/issues/1067).

## At a glance

| Channel | Audience | How to submit | Status |
| --- | --- | --- | --- |
| Claude Code plugin marketplace (this repository) | Claude Code users | Nothing to submit — the marketplace is self-hosted here and works as soon as this PR merges | shipped |
| GitHub repository topics | GitHub search, and auto-updating trackers that discover projects by topic | Repository Settings → About → Topics | todo — owner |
| [mcpservers.org](https://mcpservers.org) | People browsing MCP servers | Web form only, no pull request path | todo — owner |
| [cursor.directory](https://cursor.directory) | Cursor users looking for MCP servers and skills | Web form — **Manual tab**, never the GitHub auto-scan | todo — owner |
| [hesreallyhim/awesome-claude-code](https://github.com/hesreallyhim/awesome-claude-code) | Claude Code users | Open an issue on that repository | todo — delegable |
| [ComposioHQ/awesome-claude-skills](https://github.com/ComposioHQ/awesome-claude-skills) | Agent skill users | Open a pull request on that repository | todo — delegable |
| [skills.sh](https://skills.sh) | Agent skill users | No submission form — a repository is listed once people install its skills with `npx skills add` | nothing to submit; the docs carry the command, the listing follows real installs |
| [Official MCP Registry](https://registry.modelcontextprotocol.io) | MCP clients that read the registry | — | not eligible — no `server.json`, see below |
| [Glama](https://glama.ai) | Broad MCP audience | — | not eligible — no `glama.json`, nothing to containerise |
| GitHub MCP Registry / VS Code MCP gallery | VS Code and Copilot users | — | out of reach — both are fed by the Official MCP Registry |

## Why this repository has no `server.json` or `glama.json`

Projects that publish an MCP server usually carry two manifests that BootUI deliberately does not: `server.json` for
the [Official MCP Registry](https://registry.modelcontextprotocol.io), and `glama.json` telling
[Glama](https://glama.ai) how to build a container that runs the server. Both describe a server that can be **started
as its own process**. BootUI's cannot: it is an HTTP endpoint inside the user's own Spring Boot or Quarkus
application, it starts with that application, it is off by default (`bootui.mcp.enabled=ON`), and it listens on
loopback.

Checked rather than assumed, on 2026-09-17:

- The registry's API was queried for both `bootui` and `boot-ui`: HTTP 200, zero results. BootUI is not listed today.
- Its [schema](https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json) offers a server two ways
  to describe itself. A `packages` entry names a `registryType` — the schema's examples are `npm`, `pypi`, `oci`,
  `nuget` and `mcpb`, with no Maven or JBang among them — so there is no way to point at BootUI's Maven coordinates.
  A `remotes` entry names a URL a client connects to.
- In a 100-server sample of the live registry, 24 entries advertised a package and 77 a remote URL. **None** advertised
  a `localhost` or `127.0.0.1` remote.

So BootUI fits neither shape. The GitHub MCP Registry and the VS Code MCP gallery are populated from the Official MCP
Registry, so the same reasoning carries to both, and Glama has nothing it could build. Recorded here as a closed
question, so nobody spends an afternoon rediscovering it.

What would change this: a standalone MCP process that speaks stdio and proxies to a running application, published to
a registry type the schema recognises. `bootui-cli` already talks to a running application over HTTP, so the gap is a
packaging question rather than a protocol one. That is a product decision for @jdubois, not something this pull
request assumes — and it is not needed for the Claude Code or Copilot paths, which already install in one command.

## Actions only @jdubois can take

**These are the registry and catalog submissions.** The plugin ships in this pull request and needs nothing further,
but BootUI stays invisible to the catalogs below until someone with your account and repository rights submits it.
Each one takes a few minutes: the topic list, the descriptions and the URLs are prepared and meant to be pasted as
they are. Nothing has been submitted on your behalf.

### 1. Add the missing repository topics

Where: https://github.com/jdubois/boot-ui, then Settings, or the gear icon next to **About** on the repository home
page, then **Topics**.

Why only you: editing topics requires repository admin rights.

Why it matters: several auto-updating lists and MCP trackers discover projects purely by scanning GitHub for topics
such as `mcp-server`. Without the topic, BootUI is invisible to them whatever the README says. The three missing ones
are `mcp`, `mcp-server` and `agent-skills`.

Full list to paste (the current seven plus the three new ones):

```
developer-tools, java, spring-boot, spring-boot-4, spring-boot-starter, quarkus, quarkus-extension, mcp, mcp-server, agent-skills
```

### 2. Submit to mcpservers.org

Where: https://mcpservers.org. The site accepts submissions through a web form only; there is no pull request path.

Why only you: the form asks for a submitter, and the listing should be owned by the project owner rather than by a
contributor.

Ready to paste:

- Name: `BootUI`
- Repository: `https://github.com/jdubois/boot-ui`
- Website: `https://www.julien-dubois.com/boot-ui/`
- License: `Apache-2.0`
- Endpoint, if the form asks for one: `POST http://127.0.0.1:8080/bootui/api/mcp`, local and inside the user's own
  application, on whichever port that application uses
- One-line summary:

```
A local-only developer console for Spring Boot 4 and Quarkus, with an MCP server that lets an agent ask a running application about itself.
```

- Longer description:

```
BootUI embeds a developer console in your own Spring Boot 4 or Quarkus application. Its MCP server runs inside that
application, on loopback, and is disabled by default; set bootui.mcp.enabled=ON to turn it on. The tools cover the
architecture, REST API, Spring, Hibernate, JVM memory, Spring Security, pentest, GraalVM and CRaC advisors, plus live
runtime diagnostics: health, effective configuration with secrets masked, beans, request mappings, exceptions, SQL
traces and HTTP exchanges. An agent reading source code can only guess at runtime behaviour; BootUI lets it consult
the running application before proposing a fix, and verify the fix afterwards. Nothing is hosted, and no data leaves
the machine.
```

### 3. Submit to cursor.directory, using the Manual tab

Where: https://cursor.directory. The submission form has a GitHub tab and a Manual tab.

Why only you: as above, the listing should be owned by the project owner.

**Use the Manual tab.** The GitHub tab runs an auto-scan that indexes every `SKILL.md` in the repository, including
files under `.github/skills`. That would advertise `.github/skills/bootui-java-development/SKILL.md` to users. That
skill teaches an agent how to work on BootUI itself; it is maintainer-only and must not be offered to people who only
want to scan their own application. The Manual tab lets you list exactly one skill and nothing else.

The only skill to list is the user-facing one, `skills/bootui/SKILL.md`. Reuse the name, repository, website, license
and descriptions from the mcpservers.org section above. If an install command is requested:

```
/plugin marketplace add jdubois/boot-ui
/plugin install bootui@bootui
```

### 4. The two community lists, which are delegable

These need a submission from a GitHub account, which is why they sit in this section, but they do not need yours. The
contributor behind #1067 has offered to send both on your word; a comment on the issue is enough, and nothing will be
submitted before that.

- [hesreallyhim/awesome-claude-code](https://github.com/hesreallyhim/awesome-claude-code) — submissions are made by
  opening an issue on that repository.
- [ComposioHQ/awesome-claude-skills](https://github.com/ComposioHQ/awesome-claude-skills) — submissions are made by
  opening a pull request on that repository.

Suggested entry text for both:

```
BootUI — a local-only developer console for Spring Boot 4 and Quarkus. Its Claude Code plugin and agent skill let an
agent scan a running application (architecture, Spring, Hibernate, memory, security, pentest, GraalVM and CRaC
advisors) and read live runtime diagnostics over a local MCP server.
```

## What this pull request already does

None of this needs repeating.

- `.claude-plugin/marketplace.json` and `plugins/bootui/.claude-plugin/plugin.json` — the self-hosted marketplace and
  the plugin manifest, so `/plugin marketplace add jdubois/boot-ui` followed by `/plugin install bootui@bootui` works
  from the default branch, with no external submission anywhere.
- `plugins/bootui/skills/bootui/SKILL.md` — the shipped copy of the canonical `skills/bootui/SKILL.md`, and only that
  skill. The maintainer-only `bootui-java-development` skill is deliberately kept out of the plugin payload.
- `ClaudeCodePluginPayloadTests` in `bootui-engine` — the drift guard. It fails the build if the shipped skill stops
  matching the canonical one, and if any skill other than `bootui` ever appears in the plugin payload.
- `docs/AI-AGENTS.md` and `plugins/README.md` — the user-facing installation instructions for the plugin, and the
  maintainer note explaining why the plugin payload is a curated copy rather than the repository root.
- The `npx skills add jdubois/boot-ui` command in the documentation, which is the whole skills.sh mechanism: that site
  lists a repository once people install its skills that way, and there is nothing else to submit.

## Keeping this honest

Every status in the table above was true when its row was written, and nothing in this file has been submitted
anywhere. Submission forms, listing policies and registry eligibility rules change. If you update a row, re-check the
channel first and date the change in the same edit, so that a stale `todo` is never mistaken for a fresh one. If a row
turns out to be obsolete, delete it rather than leave it to rot.
