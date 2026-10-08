# Set up the BootUI Java agent

The BootUI Java agent is an optional `-javaagent` that adds evidence BootUI cannot see from a framework alone: which
application methods a request ran ([Code Paths](../features/java-agent.md#code-paths)), whether the code you just
changed executed ([Code Inventory](../features/java-agent.md#code-inventory)), what the application did outside the JVM
([Side Effects](../features/java-agent.md#side-effects)), and one method's next invocations
([method probes](../features/java-agent.md#method-probes)). It works on Spring MVC, Spring WebFlux, and Quarkus, in
development and tests only. An application started without it behaves exactly as before.

This page takes an application that already runs BootUI ([Setup](../SETUP.md)) to a first "did my change run?" answer.
The [Java Agent](../features/java-agent.md) reference covers every sensor, state, and property.

## 1. Get the agent jar

Open BootUI's **Java agent** panel, or run `bootui agent status`. Without the agent, both report `NOT_ATTACHED` and
show the jar's expected path in your local Maven repository, with a **Download the agent** snippet when it is missing:

```bash
mvn dependency:get -Dartifact=com.julien-dubois.bootui:bootui-agent:<version>
```

Use the same version as BootUI.

## 2. Add `-javaagent` to the development JVM

Copy the snippet for your build from the panel or from `bootui agent status --json` (`setup.snippets`). For example,
for one Spring Boot run:

```bash
JAVA_TOOL_OPTIONS='-javaagent:<path to bootui-agent.jar>' ./mvnw spring-boot:run
```

or for Quarkus dev mode:

```bash
./mvnw quarkus:dev -Djvm.args="-javaagent:<path to bootui-agent.jar>"
```

Scope `JAVA_TOOL_OPTIONS` to one command; never export it in a shell. Never add the agent to a production,
native-image, or AOT-cached JVM: Quarkus production mode reports it `DISABLED` and never claims it, and neither does
Spring when `bootui.enabled=ON` forces BootUI on in a disabled profile such as `prod`, unless
`bootui.agent.allow-in-disabled-profiles=true`.

## 3. Check that it is armed

Restart the application, then run `bootui agent status` or reopen the panel. `ARMED` means this application claimed the
agent and its sensors record; the report lists each sensor. Any other state comes with its reason; see
[Status states](../features/java-agent.md#status-states). The default sensors are `executors`, `inventory`,
`code-paths`, `processes`, `network`, `files`, `blocking`, and `resources`; `bootui.agent.sensors` adds the opt-in ones.

## 4. Answer "did my change run?"

Code Inventory compares this run with the previous one, so it needs a DevTools restart or a Quarkus live reload after
the edit:

1. Change a method and let the application restart or reload.
2. `bootui code inventory` lists the changed methods, each `EXECUTED` or `NEVER_EXECUTED` in this run, with the first
   request and route that ran it.
3. Run the test or send the request that should reach a `NEVER_EXECUTED` method, then read the inventory again.
4. Still `NEVER_EXECUTED`? Start a method probe on it (`bootui probe start <method>`, or **Probe in Code Paths** on the
   method in the Code Inventory panel), wait until `bootui probe show <id>` says `active`, rerun the same test or
   request within its 60-second window, and read it again. With the probe active before the rerun and still active
   after it, or ended only after the rerun finished, `invocations` at 0 means that path never reaches the method: the
   wrong route, the wrong bean, or never wired; a probe that never became active, failed, or ended before the rerun
   finished is inconclusive. A
   probe is an
   action: read-only policy refuses it, and it records metadata only, never argument or return values; argument and
   return shapes are opt-in, in the panel only.

## 5. Let a coding agent use it

AI agents reach the same evidence through BootUI's [MCP server](../AI-AGENTS.md#connect-an-agent-to-the-bootui-mcp-server)
or the [CLI](../CLI.md). The server's instructions, the `verify_after_change` prompt, and the
[agent skill](../AI-AGENTS.md#install-the-bootui-agent-skill) teach them two rules:

- Call `get_agent_status` once before relying on an agent-only tool. An observation `NOT_APPLICABLE` because it
  requires the BootUI agent was not measured: it is never healthy.
- Verify, then probe: when an edited method is still not executed after the test that should reach it, ask the user
  before starting `start_method_probe`, then read `get_method_probe`.

See [Did my change run?](../AI-AGENTS.md#did-my-change-run) for the tools and their answers.
