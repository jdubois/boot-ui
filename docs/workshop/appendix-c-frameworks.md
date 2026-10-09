# Appendix C - Frameworks

The three-hour core uses **Spring MVC** because it supplies the full reference fixture and transaction evidence.
BootUI serves the same Vue console/JSON contracts on **Spring WebFlux** and **Quarkus**, but availability is
capability-specific. Do not execute MVC-only fixture URLs on another sample and call a 404 missing evidence.

## Spring MVC reference

Use Chapters 01 and 05's launchers. JDBC SQL durations, transaction capture, and eager JPA fixtures support the
capstone. BootUI does not supply your application's authentication or database permissions.

See [Spring setup](../SETUP.md) and [activation](../setup/activation.md).

## Spring WebFlux

In a separate terminal/port and disposable release clone:

```bash
./bootui-spring-webflux-sample-app/run-local.sh \
  '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8086'
```

For instrumentation, stop that JVM first, then:

```bash
./bootui-spring-webflux-sample-app/run-local-agent.sh \
  '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8086'
```

Use `http://localhost:8086/bootui`. The starter is the same; WebFlux is selected by the application.
The launcher warms its own module/agent dependencies.

On Windows, warm and launch with the wrapper:

```powershell
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.m2" `
  -pl "bootui-spring-webflux-sample-app,bootui-agent" -am install "-DskipTests"
if ($LASTEXITCODE -ne 0) { throw "WebFlux warm build failed" }
$agent = (Resolve-Path "bootui-agent/target/bootui-agent-2.0.0.jar" -ErrorAction Stop).Path
if ($agent.Contains(",")) { throw "Use an agent jar path without commas" }
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.m2" "-Dmaven.test.skip=true" `
  -pl bootui-spring-webflux-sample-app spring-boot:run "-Dspring-boot.run.profiles=dev" `
  "-Dspring-boot.run.agents=$agent" `
  "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8086"
```

Choose a route from this sample's **Mappings** panel, send three bounded requests, and read its journal/profile.
Document handler/response phase coverage rather than expecting the MVC breakdown. BootUI does not capture R2DBC SQL;
JDBC evidence is available only for actual JDBC integrations. Probes/Code Paths on reactive methods can be
assembly-only, not the subscription's end-to-end work.

Read [WebFlux setup](../setup/webflux.md), [support notes](../WEBFLUX-SUPPORT.md), and the live panel reasons.

## Quarkus

Use a JDK supported by the workshop release's Quarkus LTS platform. Java 17 is the baseline; a new early-access JDK
is not automatically a supported augmentation toolchain.

**Additional prerequisite for this appendix:** the Quarkus sample uses PostgreSQL Dev Services and requires a
running Docker or compatible Podman runtime. It is not the Docker-free MVC core. Alternatively, prepare and
configure a local PostgreSQL database as described in the sample README; merely disabling Dev Services does not
supply a database. Resolve this before the optional lab, not during the three-hour workshop.

```bash
./bootui-quarkus-sample-app/run-local.sh -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=8087
```

Stop it before the agent version:

```bash
./bootui-quarkus-sample-app/run-local-agent.sh -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=8087
```

The console is `http://localhost:8087/bootui`. For Windows:

```powershell
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=.m2" `
  -pl "bootui-quarkus-sample-app,bootui-agent" -am install "-DskipTests"
if ($LASTEXITCODE -ne 0) { throw "Quarkus warm build failed" }
$repo = Join-Path (Get-Location) ".m2"
$agent = (Resolve-Path "bootui-agent/target/bootui-agent-2.0.0.jar" -ErrorAction Stop).Path
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=$repo" -f bootui-quarkus-sample-app/pom.xml `
  quarkus:dev "-Djvm.args=-javaagent:$agent" "-Dquarkus.http.host=127.0.0.1" "-Dquarkus.http.port=8087"
```

Use a clone path without spaces for Quarkus's JVM-argument splitting. Do not replace `jvm.args` on the agent launcher
with another value; that can remove the agent. JVM dev/test mode supports instrumentation; no native-mode agent
support is implied.

Choose a route from this sample, observe its journal, and inspect a supported code path. Quarkus has **no transaction
capture**. ORM SQL preparation can be recorded with **unknown execution duration**, so do not grade its result using
MVC JDBC timing assertions. Live reload supplies the same-JVM history workflow, with native Quarkus limits.

See [Quarkus setup](../setup/quarkus.md) and [support notes](../QUARKUS-SUPPORT.md).

## Portability checkpoint

For your chosen stack, write one available source, one unsupported source, one assembly/unknown-duration limit, and
the evidence needed for a comparable run. Availability is a product contract, not something to hide by enabling
unrelated dependencies.

**Return:** [Workshop overview](README.md).
