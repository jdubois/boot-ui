# Configuration & safety

BootUI is intended for **local development only**. By default it:

- activates in `AUTO` mode only for `dev` / `local` profiles or DevTools
- rejects non-loopback requests
- permits `/bootui/**` through Spring Security when Spring Security is present, with a startup warning, so the local
  console remains directly reachable while the loopback-only filter still applies
- masks secret-like configuration values
- exposes the local Actuator endpoints used by BootUI panels when BootUI is active
- captures local application spans for the Traces panel when telemetry and the panel are enabled
- disables itself for `prod` / `production` profiles
- stores runtime configuration overrides in `.bootui/application-bootui.properties`, not in your source config files

Every visible panel can be disabled with `bootui.panels.<panel-id>.enabled=false`. Panels with mutating browser actions
can also be made read-only with `bootui.panels.<panel-id>.read-only=true`, and `bootui.read-only=true` makes the whole
BootUI application read-only. See the [property reference](./properties) for the full panel list.

## Common properties

| Property                               | Default                                 | Description                                                                              |
| -------------------------------------- | --------------------------------------- | ---------------------------------------------------------------------------------------- |
| `bootui.enabled`                       | `AUTO`                                  | `AUTO`, `ON`, or `OFF`.                                                                  |
| `bootui.enabled-profiles`              | `dev,local`                             | Profiles that activate BootUI in auto mode.                                              |
| `bootui.disabled-profiles`             | `prod,production`                       | Profiles that disable BootUI unless forced on.                                           |
| `bootui.allow-non-localhost`           | `false`                                 | Explicit opt-out of loopback-only protection.                                            |
| `bootui.expose-values`                 | `MASKED`                                | `MASKED`, `METADATA_ONLY`, or `FULL`; `FULL` can disclose secrets and should stay local. |
| `bootui.read-only`                     | `false`                                 | Disable all browser-triggered actions while keeping read-only panel data visible.         |
| `bootui.overrides-file`                | `.bootui/application-bootui.properties` | Runtime override persistence file.                                                       |
| `bootui.startup.enabled`               | `true`                                  | Auto-install startup buffering for the Startup Timeline panel while BootUI is active.     |
| `bootui.startup.capacity`              | `4096`                                  | Maximum startup steps retained by BootUI's auto-installed startup buffer.                 |
| `bootui.cache.clear-enabled`           | `true`                                  | Enables Spring Cache clear actions after explicit browser confirmation.                  |
| `bootui.dev-services.restart-enabled`  | `false`                                 | Enables restart controls for bean-backed Testcontainers services. Disabled by default.   |
| `bootui.dev-services.log-tail-bytes`   | `65536`                                 | Maximum bytes returned by one Dev Services log request.                                  |
| `bootui.telemetry.enabled`             | `true`                                  | Enables local in-memory trace capture and the OTLP receiver used by Traces and AI Usage. |
| `bootui.copilot.enabled`               | `AUTO`                                  | Enable the Copilot panel. `AUTO` activates when `~/.copilot/session-state/` exists.      |
| `bootui.copilot.session-state-dir`     | `~/.copilot/session-state`              | Directory scanned for Copilot CLI session directories and `events.jsonl` files.          |
| `bootui.copilot.max-sessions`          | `100`                                   | Maximum recent sessions returned by the Copilot session explorer.                        |
| `bootui.copilot.max-parsed-sessions`   | `100`                                   | Maximum recent Copilot session files parsed and retained in memory.                      |
| `bootui.copilot.allow-raw-reveal`      | `true`                                  | When `false`, the opt-in raw-event reveal endpoint returns 404 even on loopback.         |
| `bootui.claude-code.enabled`           | `AUTO`                                  | Enable the Claude Code panel. `AUTO` activates when `~/.claude/projects/` exists.        |
| `bootui.claude-code.session-state-dir` | `~/.claude/projects`                    | Directory scanned for Claude Code project JSONL logs.                                    |
| `bootui.claude-code.max-sessions`      | `100`                                   | Maximum recent sessions returned by the Claude Code session explorer.                    |
| `bootui.claude-code.max-parsed-sessions` | `100`                                 | Maximum recent Claude Code JSONL files parsed and retained in memory.                    |
| `bootui.claude-code.allow-raw-reveal`  | `false`                                 | Explicitly enable raw JSONL reveal; raw Claude Code logs can include prompts and output. |

For the complete list of global, per-panel, and action-gating properties, see the [property reference](./properties).

## Runtime overrides

The Configuration panel can create, update, and delete local runtime overrides. Overrides are stored in
`.bootui/application-bootui.properties` by default, loaded at high precedence on the next startup, and never modify your
application source configuration. Already-bound `@ConfigurationProperties` beans may keep their previous value until the
app restarts; BootUI returns that warning with every override mutation.

## Read-only examples

Make the whole application read-only:

```properties
bootui.read-only=true
```

Hide one panel entirely:

```properties
bootui.panels.devtools.enabled=false
```

Keep one panel visible but disable its actions:

```properties
bootui.panels.config.read-only=true
```

Require both an action gate and panel read-only state to allow an action:

```properties
bootui.panels.dev-services.read-only=false
bootui.dev-services.restart-enabled=true
```
