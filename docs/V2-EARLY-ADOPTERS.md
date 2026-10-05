# Try BootUI 2.0 early

BootUI 2.0 is built on the long-lived `v2` branch ([v2 plan](PLAN-v2.md)) and nothing from it is published before
2.0.0. To try it on your own application, build the branch into a Maven repository of its own and point your
application at that repository. Your regular `~/.m2` repository and the released BootUI versions stay untouched.

## What 2.0 adds

- **Exact correlation**: every SQL statement, exception, security event, cache access, message, and log line knows its
  request or execution, with or without tracing, on Spring MVC, WebFlux, and Quarkus.
- **The runtime journal**: one bounded, in-memory record of what the run did, behind Live Activity, request profiles,
  and run summaries that survive restarts.
- **Runtime Insights**: observations such as repeated selects, writes behind a `GET`, errors behind a 2xx, transactions
  held across a remote call, and anonymous reach of data, each with its counterexample, its evidence, and what to check.
- **The change loop**: run comparison against the previous run, change impact for a bean, class, table, cache, or host,
  and the `get_runtime_insights`, `get_runtime_insight`, `get_runtime_impact`, and `get_runtime_run_comparison` agent
  tools, also available as `bootui insights` CLI commands ([AI agents](AI-AGENTS.md#runtime-insights-for-agents)).

What 2.0 does not do yet, per stack and for the BootUI Java agent, is listed in
[Known limitations](KNOWN-LIMITATIONS.md).

## Build the `v2` branch

You need JDK 17 or newer and Git. The Maven Wrapper downloads Maven, and the build downloads Node.js for the UI.

```bash
git clone https://github.com/jdubois/boot-ui.git
cd boot-ui
git checkout v2
./mvnw -B -ntp -Dmaven.repo.local="$HOME/.m2/bootui-v2" -DskipTests install
```

The branch still carries the 1.x version number, so the separate repository is what keeps a 2.0 build from replacing
the released artifact of the same version in `~/.m2`. To pick up later changes, run `git pull` and the same command.

## Use it in your application

Add BootUI as the [setup guide](SETUP.md) describes, with the version the `v2` build installed. A WebFlux application
uses the same `bootui-spring-boot-starter` as a Spring MVC one: the `v2` build has no
`bootui-spring-boot-starter-reactive`, and the starter leaves the choice of web stack to the application's own starter.
To find the version:

```bash
./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version
```

Then build and run your application with the same repository:

```bash
./mvnw -Dmaven.repo.local="$HOME/.m2/bootui-v2" spring-boot:run
./mvnw -Dmaven.repo.local="$HOME/.m2/bootui-v2" quarkus:dev
```

The first run downloads your application's other dependencies into that repository. With Gradle, declare it ahead of
Maven Central, so the BootUI artifacts resolve from it:

```kotlin
repositories {
    maven { url = uri("${System.getProperty("user.home")}/.m2/bootui-v2") }
    mavenCentral()
}
```

## Get a first observation

1. Start the application with BootUI on, as in 1.x, with no extra property.
2. Run your integration or browser tests against it, or click through its main flows, so it serves realistic traffic.
3. Open **Runtime Insights**, or run `bootui insights list`. Read the coverage strip and the checks that did not run
   first: an empty list never means healthy.
4. After a change, restart, run the same traffic, and open the run comparison.

## Give feedback

Feedback on 2.0 goes to [GitHub Discussions](https://github.com/jdubois/boot-ui/discussions): reply to the pinned v2
feedback discussion, or, until it is pinned, open one in the **Ideas** category with a title starting with `v2:`. Useful reports name the stack (Spring MVC, WebFlux, or Quarkus), the observation's kind and
status, and whether it was actionable, informative, noise, or misleading on your application. Never paste data you
would not publish: Copy for AI and the agent tools mask what BootUI masks, but your application's routes and table
names are still yours.

To judge observations the way the 2.0 release does, use the [validation report template](V2-VALIDATION-REPORT.md).
