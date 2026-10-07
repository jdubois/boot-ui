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
| [GitHub Agent Finder](https://agentfinder.github.com) | GitHub Copilot users searching for skills in natural language | Pull request to [`github/agentfinder-catalog`](https://github.com/github/agentfinder-catalog) | submitted — [`github/agentfinder-catalog#66`](https://github.com/github/agentfinder-catalog/pull/66) |
| Claude Code plugin marketplace (this repository) | Claude Code users | Nothing to submit — the marketplace is self-hosted here and works as soon as this PR merges | shipped |
| GitHub repository topics | GitHub search, and auto-updating trackers that discover projects by topic | Repository Settings → About → Topics | shipped |
| [mcpservers.org](https://mcpservers.org) | People browsing MCP servers | Web form only, no pull request path | todo — owner |
| [Cursor Marketplace](https://cursor.com/marketplace) | Cursor users installing plugins from Customize | Agent Plugin in this repository, then repository submission at `cursor.com/marketplace/publish` | todo — owner, plugin ready after merge |
| [cursor.directory](https://cursor.directory) | Cursor users browsing community plugins | Plugin submission form at `cursor.directory/plugins/new` | todo — owner, plugin ready after merge |
| [hesreallyhim/awesome-claude-code](https://github.com/hesreallyhim/awesome-claude-code) | Claude Code users | Open an issue on that repository | todo — delegable |
| [ComposioHQ/awesome-claude-skills](https://github.com/ComposioHQ/awesome-claude-skills) | Agent skill users | Open a pull request on that repository | todo — delegable |
| [skills.sh](https://skills.sh) | Agent skill users | No submission form — a repository is listed once people install its skills with `npx skills add` | nothing to submit; the docs carry the command, the listing follows real installs |
| [Official MCP Registry](https://registry.modelcontextprotocol.io) | MCP clients that read the registry | — | not eligible — no `server.json`, see below |
| [Glama](https://glama.ai) | Broad MCP audience | — | not eligible — no `glama.json`, nothing to containerise |
| GitHub MCP Registry / VS Code MCP gallery | VS Code and Copilot users | — | out of reach — both are fed by the Official MCP Registry |

## GitHub Agent Finder

[GitHub Agent Finder](https://github.blog/changelog/2026-06-17-agent-finder-for-github-copilot-now-available/)
is now the highest-priority discovery channel for the BootUI skill. It lets Copilot search a curated public catalog for
skills, MCP servers and other agent resources from a natural-language task. This is separate from the Official MCP
Registry: BootUI's embedded localhost MCP server still does not fit that registry, but the user-facing agent skill is
eligible for Agent Finder.

Checked on 2026-10-07:

- `gh skill search bootui` already found the skill directly in this repository, and
  `gh skill preview jdubois/boot-ui skills/bootui` loaded the canonical consumer skill successfully.
- Agent Finder itself did not return BootUI for either an exact `BootUI` search or a task asking to diagnose a running
  Spring Boot or Quarkus application. It returned generic Spring Boot and MCP skills instead.
- [`github/agentfinder-catalog#66`](https://github.com/github/agentfinder-catalog/pull/66) was opened to add the
  canonical `skills/bootui/SKILL.md` as an `application/ai-skill`. The entry deliberately points to the canonical
  skill rather than the byte-for-byte plugin copy under `plugins/`, so Agent Finder has one authoritative result.

Once that pull request merges, query <https://agentfinder.github.com/api/v1/search> for `BootUI` and for a representative
runtime-diagnostics task. Update the table above to `shipped` only after the public search result resolves to
`https://github.com/jdubois/boot-ui/blob/main/skills/bootui/SKILL.md`.

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

### 3. Submit the plugin to Cursor

Checked on 2026-10-07:

- Cursor loads the open Agent Plugins format from a root `plugin.json`, including skills under `skills/` and MCP
  servers from `mcp.json`.
- A multi-plugin repository can advertise payload directories through `.cursor-plugin/marketplace.json`; this
  repository points its `bootui` entry at `plugins/bootui`.
- Official marketplace submissions go through <https://cursor.com/marketplace/publish>, take a repository link, and are
  manually reviewed by the Cursor team.
- The separate community directory accepts plugin submissions at <https://cursor.directory/plugins/new>.
- Both submission forms were behind Vercel bot protection, so their current fields could not be inspected or submitted
  by an agent. Submission remains a manual owner action.

The repository is ready for both submissions after this plugin pull request merges. Use:

- Name: `bootui`
- Display name, if requested: `BootUI`
- Repository: `https://github.com/jdubois/boot-ui`
- Plugin source, if requested: `plugins/bootui`
- Website: `https://www.julien-dubois.com/boot-ui/`
- License: `Apache-2.0`
- Summary and longer description: reuse the text in the mcpservers.org section above

The Agent Plugins manifest cannot carry a logo field. A logo is optional in Cursor's documented checklist; if either
form requests one, the repository already contains `docs/.vuepress/public/favicon.svg`, so no new artwork is needed.
Do not submit the maintainer-only `.github/skills/bootui-java-development` skill as a separate user plugin.

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

- `.claude-plugin/marketplace.json` and `plugins/bootui/.claude-plugin/plugin.json` — the self-hosted Claude Code
  marketplace and manifest, so `/plugin marketplace add jdubois/boot-ui` followed by
  `/plugin install bootui@bootui` works from the default branch, with no external submission anywhere.
- `.cursor-plugin/marketplace.json`, `plugins/bootui/plugin.json`, and `plugins/bootui/mcp.json` — the repository
  marketplace pointer and portable Agent Plugins manifest. They make the same payload loadable by Cursor; public
  discovery still requires the manual submissions above.
- `plugins/bootui/skills/bootui/SKILL.md` — the shipped copy of the canonical `skills/bootui/SKILL.md`, and only that
  skill. The maintainer-only `bootui-java-development` skill is deliberately kept out of the plugin payload.
- `ClaudeCodePluginPayloadTests` in `bootui-engine` — the drift guard. It fails the build if the shipped skill stops
  matching the canonical one, and if any skill other than `bootui` ever appears in the plugin payload.
- `AgentSkillDiscoverabilityTests` in `bootui-engine` — the discoverability guard. It fails the build if the canonical
  skill loses its installable frontmatter, if a documented `gh skill` or `npx skills add` command stops naming the
  canonical `skills/bootui` path, if a document outside the checked set grows its own install command, or if the
  advertised marketplace plugin stops carrying the skill. It runs offline, so it never asks whether a registry is up.
- `docs/AI-AGENTS.md`, `plugins/bootui/README.md`, and `plugins/README.md` — the user-facing installation instructions
  and the maintainer note explaining why the plugin payload is a curated copy rather than the repository root.
- The exact-path
  `npx skills add https://github.com/jdubois/boot-ui/tree/main/skills/bootui` command in the documentation, which is
  the whole skills.sh mechanism: that site lists a repository once people install its skill that way, and there is
  nothing else to submit.

## Keeping this honest

Every status in the table above was true when its row was written, and nothing in this file has been submitted
anywhere. Submission forms, listing policies and registry eligibility rules change. If you update a row, re-check the
channel first and date the change in the same edit, so that a stale `todo` is never mistaken for a fresh one. If a row
turns out to be obsolete, delete it rather than leave it to rot.
