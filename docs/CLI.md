# Command line

BootUI's diagnostics are reachable from a terminal. The `bootui` CLI asks a running Spring Boot or Quarkus
application one question and prints the answer — no browser, no MCP client, no hand-written `curl`.

```console
$ bootui beans --query dataSource
$ bootui hibernate scan --json | jq '.findings[] | select(.severity == "HIGH")'
$ bootui http exchanges --limit 20
```

Every command is one BootUI [MCP](AI-AGENTS.md) tool, projected mechanically from the same registry. The CLI
cannot offer a diagnostic the MCP server does not, and cannot lack one it does: the command table is generated
from the registry at build time and a test fails when the two disagree.

## Install

The CLI is a single runnable jar. It needs a JDK 17 or later — the machine that builds your application
already has one.

**On Linux and macOS:**

```bash
curl -fsSL https://www.julien-dubois.com/boot-ui/install.sh | sh
```

**On Windows, in PowerShell:**

```powershell
irm https://www.julien-dubois.com/boot-ui/install.ps1 | iex
```

That asks Maven Central for the newest release, checks the download against the checksum published beside
it, and leaves a `bootui` command in `~/.local/bin` — `%LOCALAPPDATA%\BootUI\bin` on Windows, which it adds
to your user `PATH`. It needs no administrator rights, does not edit your shell profile, and contacts
nothing but the Maven repository. Running it again upgrades in place, and `--uninstall` — `-Uninstall` in
PowerShell — reverses it.

To pin a version or install somewhere else, pass options after `sh -s --`:

```bash
curl -fsSL https://www.julien-dubois.com/boot-ui/install.sh | sh -s -- --version <version> --bin-dir ~/bin
```

The PowerShell script takes `-Version` and `-BinDir`, but has to be run rather than piped to read them:

```powershell
& ([scriptblock]::Create((irm https://www.julien-dubois.com/boot-ui/install.ps1))) -Version <version>
```

The shell script explains itself with `--help`. Both read `BOOTUI_VERSION`, `BOOTUI_INSTALL_DIR`,
`BOOTUI_BIN_DIR` and `BOOTUI_MAVEN_REPO`, so a build agent behind a mirror can be pointed at its own
repository.

If you would rather not pipe a script into a shell — a fair position, and both are short enough to read
first — there are two other ways in.

**With [JBang](https://www.jbang.dev), which downloads and runs it for you:**

```bash
jbang bootui@jdubois/boot-ui --url http://localhost:8080 beans
```

Install it as a real command with `jbang app install bootui@jdubois/boot-ui`, and then just `bootui beans`.

**Or download the jar and run it directly:**

```bash
VERSION=1.16.0
BASE=https://repo1.maven.org/maven2/com/julien-dubois/bootui/bootui-cli
curl -fLO "${BASE}/${VERSION}/bootui-cli-${VERSION}-all.jar"
java -jar "bootui-cli-${VERSION}-all.jar" beans
```

An alias keeps that readable:

```bash
alias bootui='java -jar ~/tools/bootui-cli-all.jar'
```

### Staying up to date

Once a newer release reaches Maven Central, the CLI says so — on a terminal, after the answer, on standard
error:

```console
bootui: version 1.17.0 is available; you have 1.16.0.
        Update with: curl -fsSL https://www.julien-dubois.com/boot-ui/install.sh | sh
        Silence this with BOOTUI_NO_UPDATE_CHECK=1.
```

The run that prints it makes no network call of its own: it reports what a previous check left in
`~/.bootui` — `%LOCALAPPDATA%\BootUI` on Windows, or `BOOTUI_INSTALL_DIR` wherever you pointed it. That check
reads the same `maven-metadata.xml` the installer does, at most once a day, on a background thread that the
command never waits for and that is abandoned rather than delayed. Nothing is written to standard output and
the exit code is untouched, so a pipeline sees exactly what it saw before.

A piped run says nothing and asks nothing, so CI does no unexpected network traffic. `BOOTUI_NO_UPDATE_CHECK=1`
turns it off everywhere, and `BOOTUI_MAVEN_REPO` points it at a mirror.

## Turn it on

The CLI talks to `GET /bootui/api/cli` and `POST /bootui/api/cli/tools/{name}`, which are enabled by default.
Nothing needs configuring for a normal local application, and `bootui.mcp.enabled` is **not** required — the
command-line endpoint is separate from the MCP one.

To turn it off:

```properties
bootui.cli.enabled=false
```

See [Properties](PROPERTIES.md) for `bootui.cli.max-results`, `bootui.cli.execution-timeout`, and
`bootui.cli.max-concurrent-calls`.

## Global options

| Option | Environment variable | Default | Purpose |
| --- | --- | --- | --- |
| `--url <url>` | `BOOTUI_URL` | `http://localhost:8080` | Where the application is listening. A bare `host:port` is accepted. |
| `--api-path <path>` | `BOOTUI_API_PATH` | `/bootui/api` | Only needed when `bootui.api-path` is customised. |
| `--token <token>` | `BOOTUI_TOKEN` | none | Sent as an `Authorization` header when `bootui.authentication.token` is set. |
| `--timeout <seconds>` | — | `60` | How long to wait for an answer. Raise it for a slow scan. |
| `--json` | — | auto | Print the application's JSON verbatim. Implied when output is not a terminal. |
| `--no-color` | `NO_COLOR` | — | Disable ANSI colour. |
| `-v, --verbose` | — | — | Show the underlying failure when a request does not complete. |

Options work before or after the command, so both `bootui --url :9000 beans` and `bootui beans --url :9000`
do the same thing.

## Output

On a terminal, BootUI renders the payload for a human: arrays of like-shaped records become tables, everything
else becomes an indented key/value tree.

When output is piped, or with `--json`, the CLI prints exactly the bytes the application sent — the same JSON
the MCP tool returns, unmodified. That is the form to parse:

```bash
bootui security scan --json | jq -r '.results[] | "\(.severity)\t\(.name)"'
```

Advisor scans differ in how they name that array — `pentest scan` reports `findings`, the rule-based
advisors report `results` — so check the shape with `bootui <command> --json | jq keys` before writing a
filter. What every scan does share is `severityCounts`, which is what the [CI](#in-ci) gate below uses.

Search commands such as `bootui config --query` share a `page` envelope instead, where `total` counts everything
the panel can see *before* the query is applied and `matched` counts what the query kept. A large `total` beside
`matched: 0` therefore means the query found nothing, not that the data is missing — retry with a shorter query.
See [Reading a bounded result](AI-AGENTS.md#reading-a-bounded-result) for the full envelope, including the relaxed
name matching that lets `bootui config --query bootui.mcp.enabled` find a value supplied as `BOOTUI_MCP_ENABLED`.

Auto-detection is a convenience, not a contract: on a JDK 22 or later runtime a redirected stream can still
report a console. Pass `--json` explicitly in scripts.

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | The tool ran and answered. |
| `1` | Usage error, a rejected argument, authentication was rejected, or the application could not be reached or did not answer. |
| `2` | BootUI declined to run the tool: its panel is disabled, or read-only and the tool is an action. |
| `3` | Reserved for a future severity threshold. |

A tool that runs and rejects what you asked for — `bootui exceptions show` on an id that is not in the
buffer, say — exits `1` and prints the application's own message. That is the same code as a usage error
because it is the same kind of mistake: the command exists and BootUI was willing to run it, but the
request was wrong, so retrying it unchanged cannot help.

`2` is deliberately distinct. A read-only panel refusing a scan is a statement about how the target is
configured, not a broken request, and a CI job should be able to tell those apart without reading stderr:

```bash
bootui hibernate scan --json > report.json
case $? in
  0) echo "scanned" ;;
  2) echo "the Hibernate panel is disabled on this application; skipping" ;;
  *) exit 1 ;;
esac
```

## Discovering what an application exposes

The command table below is what this CLI was *built* with. What a *specific* application answers depends on
its stack and its panel settings, and `bootui tools` reports that:

```console
$ bootui tools
command                tool                   panel        arguments      status
---------------------  ---------------------  -----------  -------------  --------------
beans                  get_beans              beans        query, limit   ready
sql clear              clear_sql_traces       sql-trace    -              read-only
sql traces             get_sql_traces         sql-trace    -              ready
```

`status` is `ready` for a readable tool, `action` for one that changes state, `read-only` when the panel would
refuse the action, and `panel disabled` when the panel is off. This is also how to see stack differences: a
Quarkus application advertises fewer tools than Spring MVC, and some Spring tools appear only when the
corresponding library is on the classpath.

## The MCP server

The MCP server is a panel like any other, so the CLI can inspect and toggle it — subject to that panel's own
enable and read-only settings:

```bash
bootui mcp status
bootui mcp enable
bootui mcp disable
```

## The Command Line panel

![BootUI Command Line panel](./images/bootui-cli.webp)

The browser console mirrors this endpoint back at you. **Developer tools → Command Line** shows whether the
endpoint answers, the install snippet, how many calls it has served and how long they took, and every command
this instance exposes — the `bootui` command to type, the arguments it accepts, the MCP tool it maps to, and
which commands the target's panel settings would currently refuse. It is a read-only view: the endpoint is
governed by `bootui.cli.enabled` in configuration, not by a button, because a CI job's access should not be
revocable from a browser tab. See [Developer tools](features/developer-tools.md).

## In CI

The CLI is designed for a job that starts the application, asks it something, and stops it:

```yaml
- name: Fail on high-severity Hibernate findings
  run: |
    ./mvnw -B spring-boot:start

    status=0
    bootui hibernate scan --json > hibernate.json || status=$?

    # Stop the application whether or not the scan answered, so a failure never leaks a JVM.
    ./mvnw -B spring-boot:stop

    case $status in
      0) ;;
      2) echo "the Hibernate panel is off or read-only on this application; skipping"; exit 0 ;;
      *) exit 1 ;;
    esac

    jq -e '[.severityCounts[]
           | select(.severity == "CRITICAL" or .severity == "HIGH")
           | .count] | add == 0' hibernate.json
```

Three details make that work as a gate rather than as a job that merely looks green.

**Gate on `severityCounts`, not on the finding array.** Every scan command reports `severityCounts` as
`[{"severity": …, "count": …}]`, so one expression works for all of them. The array of findings themselves is
*not* uniform — `pentest scan` calls it `findings`, the rule-based advisors call it `results` — so a filter
written against the wrong name does not report zero findings, it aborts with `Cannot iterate over null` and
fails the build for a reason that has nothing to do with the application.

**Capture the exit code instead of letting it abort the step.** A step runs under `bash -e`, so a bare
`bootui …` that exits non-zero skips the rest of the script, including the shutdown. The `|| status=$?` form
keeps the failure from aborting the step so the application still gets stopped.

**Treat `2` as a skip.** A read-only or disabled panel exits `2` with nothing on stdout. That is a statement
about how the target is configured, not a finding and not a failure — see [Exit codes](#exit-codes).

Two things make this safe rather than a new exposure. BootUI is still local-only: the endpoint stays behind the
loopback, `Host` allow-list, cross-site-write, and authentication-token protections that guard every other
route — so the application has to be running on the same runner as the job, not in a deployed environment. And
no tool becomes reachable that was not already reachable — the CLI is a second spelling of the same panel data,
gated by the same per-panel policy.

## Every command

MCP tool names are listed so a mapping between an agent conversation and a shell script stays obvious. `all`
means every stack advertises the tool; anything else names the stacks that do. Whether an application really
exposes a tool is still what `bootui tools` says.

| Command | MCP tool | Arguments | Kind | Stacks |
| --- | --- | --- | --- | --- |
| `bootui activity` | `get_live_activity` | `--limit` | read | all |
| `bootui ai overview` | `get_ai_overview` | — | read | all |
| `bootui architecture report` | `get_architecture_report` | — | read | all |
| `bootui architecture scan` | `architecture_scan` | — | action | all |
| `bootui beans` | `get_beans` | `--query`, `--limit` | read | all |
| `bootui cache` | `get_cache_stats` | — | read | all |
| `bootui conditions` | `get_conditions` | `--query`, `--limit` | read | Spring MVC, WebFlux |
| `bootui config` | `get_config` | `--query`, `--limit` | read | all |
| `bootui crac report` | `get_crac_report` | — | read | Spring MVC, WebFlux |
| `bootui crac scan` | `crac_scan` | — | action | Spring MVC, WebFlux |
| `bootui db flyway` | `get_flyway_migrations` | — | read | all |
| `bootui db liquibase` | `get_liquibase_changesets` | — | read | all |
| `bootui db pools` | `get_database_connection_pools` | — | read | all |
| `bootui db report` | `get_database_advisor_report` | — | read | all |
| `bootui db scan` | `database_advisor_scan` | — | action | all |
| `bootui dev-services` | `get_dev_services` | — | read | all |
| `bootui devtools livereload` | `trigger_devtools_livereload` | — | action | Spring MVC, WebFlux |
| `bootui devtools status` | `get_devtools_status` | — | read | Spring MVC, WebFlux |
| `bootui exceptions clear` | `clear_exceptions` | — | action | all |
| `bootui exceptions list` | `get_exceptions` | — | read | all |
| `bootui exceptions show` | `get_exception_detail` | `<id>` | read | all |
| `bootui fault-tolerance` | `get_fault_tolerance` | — | read | all |
| `bootui github` | `get_github_dashboard` | — | read | all |
| `bootui graalvm report` | `get_graalvm_report` | — | read | Spring MVC, WebFlux |
| `bootui graalvm scan` | `graalvm_scan` | — | action | Spring MVC, WebFlux |
| `bootui health` | `get_health` | — | read | all |
| `bootui hibernate report` | `get_hibernate_report` | — | read | all |
| `bootui hibernate scan` | `hibernate_scan` | — | action | all |
| `bootui http exchanges` | `get_http_exchanges` | `--limit` | read | all |
| `bootui http sessions` | `get_http_sessions` | — | read | Spring MVC |
| `bootui jms` | `get_jms_activity` | — | read | Spring MVC, WebFlux |
| `bootui jvm tuning` | `get_jvm_tuning` | — | read | all |
| `bootui kafka` | `get_kafka_activity` | — | read | all |
| `bootui loggers` | `get_loggers` | `--query`, `--limit` | read | all |
| `bootui logs tail` | `get_log_tail` | — | read | all |
| `bootui mail` | `get_emails` | — | read | all |
| `bootui mappings` | `get_mappings` | `--query`, `--limit` | read | all |
| `bootui memory heap analyze` | `analyze_heap_dump` | — | action | all |
| `bootui memory heap report` | `get_heap_dump_report` | — | read | all |
| `bootui memory live` | `get_live_memory` | — | read | all |
| `bootui memory report` | `get_memory_report` | — | read | all |
| `bootui memory scan` | `memory_scan` | — | action | all |
| `bootui metrics` | `get_metrics` | `--query`, `--limit` | read | all |
| `bootui overview` | `get_overview` | — | read | all |
| `bootui pentest report` | `get_pentest_report` | — | read | all |
| `bootui pentest scan` | `pentest_scan` | — | action | all |
| `bootui profile diff` | `get_profile_diff` | — | read | all |
| `bootui rabbitmq` | `get_rabbitmq_activity` | — | read | all |
| `bootui repositories` | `get_spring_data_repositories` | — | read | Spring MVC, WebFlux |
| `bootui rest-api report` | `get_rest_api_report` | — | read | all |
| `bootui rest-api scan` | `rest_api_scan` | — | action | all |
| `bootui rest-client clear` | `clear_rest_client_traces` | — | action | all |
| `bootui rest-client pause` | `pause_rest_client_recording` | — | action | all |
| `bootui rest-client resume` | `resume_rest_client_recording` | — | action | all |
| `bootui rest-client traces` | `get_rest_client_traces` | — | read | all |
| `bootui scheduled` | `get_scheduled_tasks` | — | read | all |
| `bootui security config` | `get_spring_security` | — | read | Spring MVC, WebFlux |
| `bootui security logs` | `get_security_logs` | `--limit` | read | all |
| `bootui security report` | `get_security_report` | — | read | all |
| `bootui security scan` | `security_scan` | — | action | all |
| `bootui sessions claude` | `get_claude_code_sessions` | — | read | all |
| `bootui sessions copilot` | `get_copilot_sessions` | — | read | all |
| `bootui spring report` | `get_spring_report` | — | read | all |
| `bootui spring scan` | `spring_scan` | — | action | all |
| `bootui sql clear` | `clear_sql_traces` | — | action | all |
| `bootui sql pause` | `pause_sql_trace_recording` | — | action | all |
| `bootui sql resume` | `resume_sql_trace_recording` | — | action | all |
| `bootui sql traces` | `get_sql_traces` | — | read | all |
| `bootui startup` | `get_startup_timeline` | — | read | Spring MVC, WebFlux |
| `bootui threads` | `get_threads` | `--query`, `--limit` | read | all |
| `bootui traces clear` | `clear_traces` | — | action | all |
| `bootui traces list` | `get_traces` | `--limit` | read | all |
| `bootui tx clear` | `clear_transactions` | — | action | Spring MVC, WebFlux |
| `bootui tx list` | `get_transactions` | — | read | Spring MVC, WebFlux |
| `bootui tx pause` | `pause_transaction_recording` | — | action | Spring MVC, WebFlux |
| `bootui tx resume` | `resume_transaction_recording` | — | action | Spring MVC, WebFlux |
| `bootui vulnerabilities report` | `get_vulnerabilities_report` | — | read | all |
| `bootui vulnerabilities scan` | `vulnerabilities_scan` | — | action | all |

## Building on it

The transport lives in `bootui-client`, a small library with no dependencies at all — no Jackson, no HTTP
client beyond the JDK's — that handles the URL, the token, the request, and the outcome mapping. It treats
payloads as opaque JSON on purpose, so a client built against one BootUI version keeps working against an
application running another. That is what a future Maven plugin, or your own tooling, would build on.
