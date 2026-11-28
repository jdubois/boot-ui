# BootUI on Quarkus

BootUI uses a generation-specific Quarkus extension over the same framework-neutral engine. Both generations retain
the shared Vue UI, DTO, REST, MCP, and CLI contracts and the platform discriminator `quarkus`.

<!-- bootui-quarkus-4:next-release:start -->
> **Next release:** Releases before the first Quarkus 4-carrying BootUI release do not include the new coordinates. Select a published release that contains this adapter; until then, using Quarkus 4 requires an explicit source build.
<!-- bootui-quarkus-4:next-release:end -->

## Prerequisites

| Application | Java minimum | BootUI runtime artifact | Build baseline / declared range |
| --- | --- | --- | --- |
| Quarkus 3 | 17 | `bootui-quarkus` | `quarkus.platform.version=3.40.1.2` LTS; core 3.33+ within 3.x |
| Quarkus 4 | 21 | `bootui-quarkus-4` | `quarkus4.platform.version=4.0.1`; core 4.0.0+ within 4.x |

- Maven or Gradle (or their local wrappers)

Quarkus 4 is initially a non-LTS line. Its independent baseline does not change the Quarkus 3 LTS policy or Java 17
support. A build patch is not an exact-patch compatibility allowlist.

BootUI checks the platform during augmentation, independently of console activation. See
[Startup compatibility](../FRAMEWORK-SUPPORT.md#startup-compatibility) for blocking diagnostics and capability warnings.

The Quarkus 3 extension also supports the previous LTS line, 3.33. Every build runs the published build of the extension through
all of its Quarkus integration fixtures on Quarkus **3.33.4 LTS**, and runs the extension's own tests compiled against
it. Its core, REST client, production-guard, and Hibernate JVM fixtures have also passed with Quarkus 3.39.5;
[that older non-LTS line's upstream support ended when 3.40 shipped](https://quarkus.io/releases/).
For the separate Quarkus 4 JVM evidence and pending acceptance, see [Quarkus support](../QUARKUS-SUPPORT.md#quarkus-4).

## Add the extension

### Quarkus 3

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-quarkus</artifactId>
  <version>2.6.0</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
implementation 'com.julien-dubois.bootui:bootui-quarkus:2.6.0'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
implementation("com.julien-dubois.bootui:bootui-quarkus:2.6.0")
```

:::

Declare only `bootui-quarkus`. The Quarkus extension mechanism resolves the matching `bootui-quarkus-deployment`
artifact for you.

### Quarkus 4

<!-- bootui-quarkus-4:next-release:start -->
The snippets deliberately use `NEXT_BOOTUI_RELEASE`, **not a released version**. Replace it only with a published
BootUI release that actually contains the Quarkus 4 artifacts. Verify that release's artifact set; a version or
release commit alone does not establish publication. To evaluate the source implementation before publication,
[build the matching modules from source](https://github.com/jdubois/boot-ui/blob/main/CONTRIBUTING.md#build).
<!-- bootui-quarkus-4:next-release:end -->

::: tabs#build

@tab Maven

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-quarkus-4</artifactId>
  <version>NEXT_BOOTUI_RELEASE</version>
</dependency>
```

@tab Gradle

```groovy
// Groovy DSL (build.gradle)
implementation 'com.julien-dubois.bootui:bootui-quarkus-4:NEXT_BOOTUI_RELEASE'
```

```kotlin
// Kotlin DSL (build.gradle.kts)
implementation("com.julien-dubois.bootui:bootui-quarkus-4:NEXT_BOOTUI_RELEASE")
```

:::

Declare only the runtime extension. Quarkus resolves its matching
`com.julien-dubois.bootui:bootui-quarkus-4-deployment` artifact. Do not add either deployment artifact directly,
install both generations, or run a Quarkus 3 adapter on Quarkus 4.

## Run your app in development mode

Start Quarkus in dev mode. BootUI activates automatically, with no profile or flag to set:

::: tabs#build

@tab Maven

```bash
./mvnw quarkus:dev
```

@tab Gradle

```bash
./gradlew quarkusDev
```

:::

BootUI is then available at <http://localhost:8080/bootui>.

The `bootui.path` and `bootui.api-path` settings described in [Use a custom path](../SETUP.md#use-a-custom-path) work
in dev and test mode and compose with `quarkus.http.root-path`.

## Activation and safety

For JVM applications, the Quarkus launch mode decides activation; there is no Spring-style profile or
`bootui.enabled` override:

| Runtime | Behavior |
| --- | --- |
| JVM `dev` (`quarkus:dev`) and `test` (`@QuarkusTest`), either generation | The console, API, CDI services, and safety filters are wired. |
| Packaged JVM `NORMAL`, either generation | Data-bearing console wiring is absent. A guard returns 404 for the configured UI/API mounts and private `/bootui/**` assets. |
| Quarkus 3 native executable | BootUI is excluded and its surface remains dark. |

No flag enables a packaged JVM production console. **Quarkus 4 native-console support has not yet been verified**:
prototypes do not establish real BootUI capability or safety. Actual compile-and-run acceptance is still required on
Linux x86-64, Linux ARM64, and macOS ARM64; no local-native installation recipe is offered here yet.

BootUI uses the application's listener and does not overwrite `quarkus.http.host` or create a separate listener.

The request-time safety model matches Spring Boot. The shared `LocalhostGuard` applies loopback-source trust, a `Host`
allow-list against DNS rebinding, and cross-site-write protection. API callers from untrusted sources must also
present the BootUI bearer token; loopback callers and sources you have explicitly trusted through
`bootui.trusted-proxies` or `bootui.trust-container-gateway` are exempt. The same keys apply, read from MicroProfile `Config`:

```properties
# Default: reject non-loopback callers.
bootui.allow-non-localhost=false
# Extra Host header values to accept.
bootui.allowed-hosts=localhost
# Extra source ranges, for example a Docker gateway.
bootui.trusted-proxies=172.17.0.1/32
# Auto-trust the container gateway in dev containers.
bootui.trust-container-gateway=AUTO
# Optional stable token; otherwise generated at startup.
# bootui.authentication.token=...
```

The [Docker container guidance](environments.md#running-inside-a-docker-container) applies to Quarkus too, since dev
mode is already active there. Per-panel `bootui.panels.*` toggles and the `bootui.read-only` master switch behave
identically on both frameworks.

Runtime configuration overrides are Spring-only today, so the Configuration panel is read-only on Quarkus.

## Move from Quarkus 3 to Quarkus 4

Upgrade the application platform and JDK first, following the
[Quarkus 4 migration guide](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-4.0/ad3207a2f1a93ba07ec04a862f888701455b624f).
Replace `bootui-quarkus` with `bootui-quarkus-4`; keep shared BootUI settings and client URLs.
Wrong-generation or dual-adapter graphs are unsupported and can fail linkage before BootUI can emit a diagnostic.

Important application changes include Jackson 3, Vert.x 5, CDI 5, Jakarta REST 4, and Hibernate ORM 8 / Persistence 4.
Quarkus 4 removes `quarkus.hibernate-orm.database.generation` and `quarkus.hibernate-orm.log.bind-param`; use
`schema-management.strategy` and `log.bind-parameters`, including for named persistence units. Removed keys are
ignored, not fallback values.

Likewise, removed OIDC/OIDC-client `tls.verification` declarations do not describe effective Quarkus 4 TLS behavior.
Use the TLS registry, and use `quarkus.http.cors.enabled` rather than the removed `quarkus.http.cors` root.

The stable Quarkus **4.0.1** platform manages Hibernate **8.0.0.CR2** and Persistence **4.0.0-M8**. These components
are candidates/milestones, not final ORM/Persistence releases. The sample's optional Quarkiverse LangChain4j
**2.0.0.CR1** is also a candidate. See the [version-backed assessment](../QUARKUS-SUPPORT.md#quarkus-4).

## Quarkus Dev UI

BootUI stays at its own mount; it is not embedded in `/q/dev`. Quarkus 4's new Observability dashboard is
development-only and depends on contributing telemetry extensions. Dev MCP is experimental and separate from
BootUI MCP: there is no automatic bridge or exporter replacement.
Clean 4.0.1 applications passed all four telemetry/BootUI presence combinations in real Chromium, without CSP bypass
or server-policy changes. The dashboard/export controls and Prometheus followed the telemetry extensions, not BootUI.
Upstream Dev UI fetched its Inter font from Google Fonts; this is not a zero-external-traffic claim.
See [the results and verification limits](../QUARKUS-SUPPORT.md#dev-ui-assessment).

## Panel availability

Most panels are live. Nine target Spring-specific runtime concepts and are permanently marked _not applicable_, and
JMS is not available yet. See [what is not on Quarkus](../FRAMEWORK-SUPPORT.md#what-is-not-on-quarkus) for the list and
the reason for each. To try a fully wired application, see
[Try the sample app](../TRY-SAMPLE-APP.md#quarkus-image).

The [MySQL panel](../features/database.md#mysql) needs an existing default or named JDBC datasource with the
`io.quarkus:quarkus-jdbc-mysql` extension and a MySQL declaration: `quarkus.datasource.db-kind=mysql` for the default
datasource, or `quarkus.datasource.<name>.db-kind=mysql` for a named one. A reactive MySQL client alone is not enough,
and BootUI creates no monitoring datasource of its own.
