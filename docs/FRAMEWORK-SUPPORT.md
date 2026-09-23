# Framework support

BootUI runs on three stacks from one codebase: **Spring MVC**, **Spring WebFlux**, and **Quarkus**. They share the same
engine, the same console, and the same JSON contract, so what you learn on one carries over to the others.

| Stack             | Panel availability                               | Setup                              |
| ----------------- | ------------------------------------------------ | ---------------------------------- |
| Spring MVC        | Every panel. This is the reference stack.        | [Setup](./SETUP.md)                |
| Spring WebFlux    | Every panel except **HTTP Sessions**.            | [WebFlux setup](./setup/webflux.md) |
| Quarkus           | Most panels — see [below](#what-is-not-on-quarkus). | [Quarkus setup](./setup/quarkus.md) |

## Your app is the real answer

This page describes the stacks. **BootUI describes your application**, which is what you actually need, because most
panels also depend on what you have on the classpath — no Kafka, no Kafka panel.

Open the console and almost every panel that cannot run tells you so directly: it stays in the sidebar with a tooltip,
and opening it shows a banner with the specific reason. The same information is on `/bootui/api/panels`, so agents and
scripts read it too.

The PostgreSQL and MySQL panels behave differently, because they are vendor-specific. Without that vendor's JDBC driver
on the runtime classpath they do not appear at all, so a manifest can legitimately carry 58 panels rather than the 60
in the catalog.

That is always current for your app and your dependencies. Prefer it over any list in the documentation.

### JDBC diagnostics are datasource-specific

An HTTP stack does not determine a database client's capabilities. PostgreSQL needs its corresponding JDBC
datasource; the **[MySQL operational panel](features/database.md#mysql)** provides the same JDBC-backed
behavior on MVC, WebFlux, and Quarkus. Default and named JDBC datasources are in scope. A WebFlux app with only R2DBC,
or a Quarkus app with only a reactive MySQL client, cannot use this panel.

Oracle MySQL 8.4 LTS is the tested server line, with live coverage on 8.4.6. MariaDB is a separate unsupported
follow-up; the Database advisor's existing MySQL/MariaDB checks do not establish operational-panel support.
No other MySQL line or compatible flavor is certified by this test target; the feature guide lists the tested
driver/pool combinations.

## Spring WebFlux

Everything works, including every action — setting log levels, running migrations, capturing heap dumps, and every
advisor scan — behind the same safety rules as the servlet stack.

The single exception is **HTTP Sessions**, which inventories servlet sessions through Spring Session's registry.
WebFlux is stateless by default and has no equivalent registry to list.

## What is not on Quarkus

Nine panels are permanently not applicable. In every case the capability is either absent from the stack or already
covered by something else:

| Panel                                  | Why, and what to use instead                                                      |
| -------------------------------------- | --------------------------------------------------------------------------------- |
| **GraalVM**, **CRaC**                   | Quarkus is native-first and starts fast by design. Use its own native build.        |
| **Conditions**, **Startup Timeline**    | Quarkus resolves wiring at build time, so there is no runtime graph or step timeline. |
| **Spring Security**, **Spring Data**    | Quarkus uses Elytron/OIDC and Panache. Use the Quarkus Security advisor and the Hibernate advisor. |
| **Spring DevTools**                     | Quarkus dev mode already owns live reload.                                          |
| **HTTP Sessions**                       | Reactive and stateless by default, as on WebFlux.                                   |
| **Transactions**                        | Boundary capture needs a Spring hook that Narayana and CDI do not expose.            |

One panel, **JMS**, is not yet available: the capture targets Spring JMS. Use the **Kafka** or **RabbitMQ** panels for
Quarkus Reactive Messaging.

Two smaller gaps are worth knowing. The Hibernate advisor runs its mapping and configuration rules, but its query rules
need Spring Data repository metadata that Panache does not expose. And some capture panels reconstruct their data from
Vert.x rather than a servlet thread, so request correlation works through the OpenTelemetry trace id instead of thread
identity — add `quarkus-opentelemetry` to get it.

Everything else ships, including the whole advisor and scoring surface and the MCP server, which is where BootUI adds
the most on a stack that already has a dev UI.

## Going deeper

Per-panel behavior lives with the panel, in [Features](./features/README.md).

If you want the engineering rationale — how the adapters share code, why each panel was ported, adapted, rebuilt, or
dropped, and what remains open — that is in the design notes for
[Quarkus](./QUARKUS-SUPPORT.md) and [Spring WebFlux](./WEBFLUX-SUPPORT.md). They are written for people working on
BootUI itself.
