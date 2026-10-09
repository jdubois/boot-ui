# 01 - Setup and tooling

**Time in the session:** 15 minutes for verification, not first-time installation.

Complete this **whole chapter before the event**, including startup, client/skill configuration, and a successful
diagnostic read. In the live slot, confirm application identity, journal state, absent Java agent, and one real
transport read. Pair if these checks fail; do not spend the later capstone slot downloading or configuring accounts.

## Before the session

Install JDK 17 or later, Git 2.23 or later, and an editor. Confirm `java -version` uses the intended JDK. Install and authenticate
your coding agent; a GitHub account alone does not establish Copilot access.

Clone the released sample into a new directory:

```bash
git clone --branch v2.0.0 --depth 1 https://github.com/jdubois/boot-ui.git workshop
cd workshop
git switch -c workshop-exercises
./mvnw -B -ntp -Dmaven.repo.local=.m2 -pl bootui-spring-sample-app -am install -DskipTests
```

On Windows PowerShell, use the same clone/branch commands, then the Windows wrapper with quoted Maven properties:

```powershell
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.m2" -pl bootui-spring-sample-app -am install "-DskipTests"
if ($LASTEXITCODE -ne 0) { throw "Warm build failed; resolve it before the workshop" }
```

Run commands from the clone's repository root, not a module directory. The first build downloads dependencies and
the frontend toolchain. It skips tests to warm the build, not
to establish correctness. It also builds the `bootui-agent` jar needed in Chapter 05. Allow that download time outside
the three-hour slot.

Use two terminals with their working directory at this **clone's root**, the outer `workshop` directory containing
`mvnw`. Terminal 1 runs the app; terminal 2 runs diagnostics, tests, and the coding agent. A later path such as
`workshop/fixtures/...` refers to the repository's inner materials directory, not another `cd workshop`.
On Windows, check `$LASTEXITCODE` after native commands such as Git, Maven, `bootui`, and `curl.exe`; stop on nonzero
unless the chapter explicitly expects the acceptance test's red result.

Use the clone only for workshop work. BootUI's deliberately inefficient sample fixtures will be changed in your
exercise branch; do not send those changes as fixes to the BootUI project.

### Install the published CLI

Use the [CLI guide](../CLI.md) to review the installer. Pin it to the workshop release, rather than whatever is newest.

```bash
curl -fsSL https://www.julien-dubois.com/boot-ui/install.sh | sh -s -- --version 2.0.0
```

On Windows PowerShell, after reviewing the script:

```powershell
& ([scriptblock]::Create((Invoke-RestMethod https://www.julien-dubois.com/boot-ui/install.ps1))) -Version 2.0.0
```

Open a new terminal if PATH changed. Do not mix a released 1.x CLI with the workshop's 2.0.0 sample/agent.

### Install the consumer skill

Review `skills/bootui/SKILL.md` in your release-tagged clone. Copy the **whole `skills/bootui` directory** into the
project skill location supported by your coding agent. For GitHub Copilot:

```bash
mkdir -p .github/skills/bootui
cp -R skills/bootui/. .github/skills/bootui/
```

PowerShell:

```powershell
New-Item -ItemType Directory -Force .github/skills/bootui -ErrorAction Stop | Out-Null
Get-ChildItem skills/bootui -Force -ErrorAction Stop |
  Copy-Item -Destination .github/skills/bootui -Recurse -Force -ErrorAction Stop
```

This installs the canonical consumer skill, not the contributor `bootui-java-development` skill already in this
repository. It does not enable MCP or attach Java instrumentation. Restart/reload the coding-agent session if its
skill discovery needs that, and verify it can identify the `bootui` skill.
Copying the contents makes repeating setup safe without nesting another `bootui` directory. These project-local skill
files are expected untracked setup files; do not include `.github/skills/bootui/` in the approved application patch.

## Start without the Java agent

On macOS/Linux:

```bash
./bootui-spring-sample-app/run-local.sh \
  '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

On Windows, use the warmed repository explicitly:

```powershell
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.m2" "-Dmaven.test.skip=true" `
  -pl bootui-spring-sample-app spring-boot:run "-Dspring-boot.run.profiles=dev" `
  "-Dspring-boot.run.arguments=--server.address=127.0.0.1"
```

Do not add `-am` to `spring-boot:run`; it would try to run library modules too. The `dev` profile uses H2 and Caffeine,
with Docker/Kafka/application AI disabled. Keep this terminal running.

Open <http://localhost:8080/> and <http://localhost:8080/bootui>. Wait for the sample to finish startup and for its
welcome page to be usable; the overview endpoint alone can answer before seed tables finish.

If another app uses 8080, select a free port. On the Unix launcher add
`'-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8085'` instead of its existing arguments value;
on Windows use double quotes around that same value. Space-separate application options inside **one** Maven option.
Use that port in the browser, MCP configuration, `BOOTUI_URL`, and every curl command thereafter.

In terminal 2, set the URL once to match that process; substitute your chosen port:

```bash
export BOOTUI_URL=http://localhost:8080
bootui overview --json
```

PowerShell:

```powershell
$env:BOOTUI_URL = "http://localhost:8080"
bootui overview --json
if ($LASTEXITCODE -ne 0) { throw "BootUI overview failed; check the app and URL" }
```

Reuse this terminal/environment in later chapters rather than resetting an alternate port to 8080.
The explicit `server.address` binds the **host application** to loopback. BootUI's own localhost guards cover its
console/API, not the sample's deliberately unsafe application routes.

## Activation and safety

The sample already has the starter. In a different Spring Boot app, the published dependency is:

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>2.0.0</version>
</dependency>
```

MVC and WebFlux use that same starter; the application's own web dependency chooses its request stack.
Quarkus uses the [extension setup](../setup/quarkus.md).

BootUI activates in local development, including `dev`/`local` or Spring DevTools, and stays off by default in
production. Do not force it on in production to reproduce a workshop step.

In the console:

1. Confirm the application/framework/JDK/profile information.
2. Open **Live Activity -> Recording** and note the run id and journal state.
3. Open **Java Agent**: it should report `NOT_ATTACHED`, with setup guidance.
4. Find a missing integration such as application AI and read its unavailable reason.
5. In **Configuration**, search `spring.datasource.password`. The sample password is blank, so also find a
   sensitive-named property if present; its value must not be exposed. Read the exposure policy rather than inventing
   a real password to test masking.

Keep `bootui.expose-values` at `MASKED` or `METADATA_ONLY`. Panel enable/read-only settings constrain browser and
tool actions too. Do not disable localhost, Host, or cross-site protections.

## Connect the coding agent

In **MCP Server**, enable the opt-in server for this process and copy the configuration for **your actual client**.
The loopback endpoint is `http://localhost:8080/bootui/api/mcp`.

For Copilot CLI, use `/mcp add`, select HTTP transport, and supply that URL using the installed client's prompts.
For VS Code, the panel provides `.vscode/mcp.json` with a `servers` block. These client configurations are not
interchangeable; [Appendix A](appendix-a-prompts.md#connection-options) has the alternatives.

Ask the agent:

```text
Use BootUI to read only the overview and Java agent status of my workshop app.
Confirm its identity and that the Java agent is not attached.
Do not scan, generate traffic, change policy, or edit files.
```

A real returned tool read is the checkpoint, not merely a saved MCP configuration. If MCP fails, use local CLI
commands as the agent's transport:

```bash
bootui overview --json
bootui agent status --json
```

The CLI endpoint is independent of MCP and does not need the MCP toggle. Remote/container callers cross a different
trust boundary; use the local host for this workshop rather than turning off guards.

## Prepare the worksheet

Copy the [worksheet](participant-worksheet.md) into a local note outside tracked source. Record the release,
`git rev-parse HEAD`, your port, initial run id, agent client, exposure mode, and successful transport.

## Checkpoint

Both application and console work, the CLI or MCP returns real diagnostic data, the journal is active, the Java
agent is not attached, and you can explain one unavailable panel. If not, use
[troubleshooting](appendix-b-troubleshooting.md) or pair before continuing.

**Next:** [02 - Configuration and wiring](02-configuration-and-wiring.md).
