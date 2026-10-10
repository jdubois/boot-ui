# BootUI JetBrains companion prototype

This standalone Kotlin plugin adds a native **BootUI** tool window to IntelliJ-based IDEs. It connects to one local
BootUI-enabled application and reads only `GET <api-path>/overview` and `GET <api-path>/panels`. It does not start a
scan, invoke a mutation, embed the BootUI browser UI, add an AI chat/provider, or send diagnostics to an AI service.

## Use

For installation from the built ZIP, the end-to-end workflow, and troubleshooting, see the
[JetBrains companion guide](../../docs/JETBRAINS.md).

Open the **BootUI** tool window and set the application URL (including any application context path), API path, and UI
path. The defaults are `http://localhost:8080`, `/bootui/api`, and `/bootui`. **Connect** and **Refresh** are the only
actions that send HTTP requests. **Open Console** opens the configured local UI only when clicked.

**Run application** runs the configuration currently selected in IntelliJ's Run toolbar through the IDE's standard
execution workflow. Choose your application's configuration first (for this repository, the Spring sample application
is a convenient demonstration). If none is selected or it cannot be run, the tool window explains what to select.
The button does not install BootUI, change profiles, choose a port, or automatically connect. Watch the IDE's Run
window for startup or execution errors, then click **Connect** with the application's actual URL.

### Add BootUI to the current project

Click **Add BootUI dependency**, select the application's module/build file, and choose **Spring Boot 4 MVC**,
**Spring Boot 4 WebFlux**, or **Quarkus**. The IDE-native dialog lists only `pom.xml`, `build.gradle`, and
`build.gradle.kts` from the current project's indexed content roots; it excludes generated sources, build output,
vendor directories, and common caches. Discovery happens in a background read action only after clicking the button.
Wait for indexing to finish if the project is in dumb mode. Untrusted projects cannot use the action.

The editable stable version defaults to **1.20.0**, the repository's current release. Numeric `major.minor.patch`
versions are accepted; properties, dynamic versions, prereleases, and snapshots are not. The coordinates are:

| Integration | Dependency | Gradle configuration |
| --- | --- | --- |
| Spring Boot 4 MVC | `com.julien-dubois.bootui:bootui-spring-boot-starter:1.20.0` | `runtimeOnly` |
| Spring Boot 4 WebFlux | `com.julien-dubois.bootui:bootui-spring-boot-starter-reactive:1.20.0` | `runtimeOnly` |
| Quarkus | `com.julien-dubois.bootui:bootui-quarkus:1.20.0` | `implementation` |

Choose **Preview exact change** to see the full current document (including unsaved edits) and exact proposed result.
**Add dependency** is disabled until a successful preview and is the only confirmation that edits the file.
**Cancel** changes nothing. Editing the file after preview invalidates confirmation; preview and review again.
The edit is one IDE write command and supports normal **Undo/Redo**. The IDE's writable-file checks still apply.
The plugin leaves the edited document unsaved and does not issue a build reload, download, build, run, or connection.

This adds **only a dependency**. It does not create a dev-only build profile, remove the dependency from production
artifacts, or change profiles/production activation. Existing BootUI activation rules still apply: use the
[MVC setup](../../docs/SETUP.md), [WebFlux setup](../../docs/setup/webflux.md), or
[Quarkus setup](../../docs/setup/quarkus.md) for runtime requirements and development activation.
Reload Maven/Gradle through the IDE normally when ready; that reload may contact artifact repositories.
IDE auto-reload settings may independently initiate a reload after a document edit; the plugin never requests one.
Then select your application run configuration, click **Run application**, and explicitly **Connect** after startup.

#### Deliberate editing limits

- Maven is parsed with XML PSI, supporting the standard default/prefixed Maven namespace or no namespace.
  Only the direct `project/dependencies` element is edited (or created). Comments and unrelated content are preserved;
  `dependencyManagement`, profiles, and plugin dependencies are never insertion targets.
- Gradle is syntax-checked with the IDE's Groovy/Kotlin parsers. The editor appends a **separate top-level**
  `dependencies { ... }` block after the entire existing script; it never guesses an existing nested scope.
  Existing dependency blocks must use simple literal `configuration("group:artifact[:version]")` or Groovy
  `configuration 'group:artifact[:version]'` declarations. Comments and unrelated strings do not count as dependencies.
- Existing BootUI declarations, even with another version, managed/versionless coordinates, profiles, or a different
  integration, cause a no-change explanation. The editor does not upgrade, overwrite, or combine MVC/WebFlux.
  Property-based Maven coordinates and Gradle catalogs, variables, map notation, constraints, dependency closures,
  applied scripts, dynamic APIs, interpolated/slashy strings, and unrecognized configurations require manual review instead.
- Syntax errors, ambiguous XML dependency containers, unsupported namespaces/DOCTYPEs, and files over 512K characters are
  rejected explicitly. This is a conservative local-file editor, not a build evaluator: it cannot inspect inherited
  parent POMs, convention plugins, transitive dependencies, or dependencies injected from other build files.
  Check those manually before adding BootUI. No framework or dependency version is inferred from a running app.

The optional bearer token is saved only in the IDE's Password Safe; URL and mount paths are saved in the project-local
workspace settings. A blank token field reuses the saved token. **Forget token** removes it from Password Safe.

The AI-context action copies a small JSON object to the clipboard. It uses only allowlisted overview values and
available panel IDs/status bits, strips values outside conservative label syntax, omits unavailable reasons and raw
response bodies, and has an 8,000-character ceiling. It is copy-only and does not call an AI provider.

Connections are restricted to `localhost`, `127.0.0.0/8`, or numeric IPv6 loopback, and reject URL credentials,
queries, fragments, traversal, encoded path segments, and non-HTTP(S) schemes. Requests bypass configured proxies,
never follow redirects, require JSON, have an 8-second total timeout, and stop reading any response after 256 KiB.
Connection and HTTP errors use fixed redacted messages.

## Build and verify

Requirements: JDK 21 and network access for Gradle/IDE dependency resolution. The wrapper pins Gradle 9.3.0.
The project uses JetBrains' official IntelliJ Platform Gradle Plugin 2.19.0 with Kotlin Gradle Plugin 2.3.20. Kotlin's
published compatibility table fully supports Gradle through 9.3.0 for that compiler line; JetBrains' 2.x platform
plugin requires Gradle 9.0 or newer. The plugin targets IntelliJ Platform 2025.3 (build 253) and compiles for Java 21.

From this directory:

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPluginStructure
./gradlew verifyPlugin
./gradlew runIde
```

`test` runs focused URL, transport (including timeout), API parsing, error-redaction, and context-export tests, plus
IntelliJ platform service tests and actual dependency document-edit tests (XML namespaces/containers, Groovy/Kotlin,
duplicates/comments, cancellation, stale confirmation, unsaved edits, discovery filtering, trust, read-only files, and undo).
`buildPlugin` creates an installable ZIP under `build/distributions/`.
`verifyPlugin` is the JetBrains Plugin Verifier task against the configured 2025.3 baseline. `runIde` launches a
disposable IDE sandbox with the plugin installed; open any project and select **View > Tool Windows > BootUI**. No
request is made until you click **Connect** or **Refresh**.

The plugin declares XML platform support and requires the bundled Groovy and Kotlin plugins to parse Gradle scripts.
Compatibility verification targets IntelliJ IDEA 2025.3; other IntelliJ-based IDEs have not been verified.

## Official compatibility references

- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [Gradle Plugin Portal: IntelliJ Platform Gradle Plugin 2.19.0](https://plugins.gradle.org/plugin/org.jetbrains.intellij.platform/2.19.0)
- [Kotlin Gradle and Java compatibility](https://kotlinlang.org/docs/gradle-configure-project.html#gradle-java-compatibility)
- [IntelliJ Platform build-number ranges and runtime JDKs](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
- [JetBrains plugin compatibility verification](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)

The companion is experimental, independently built, and not a Marketplace publication or a Maven reactor module.
