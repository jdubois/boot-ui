---
applyTo: "bootui-spring-boot-starter/**,bootui-spring-sample-app/**,bootui-spring-webflux-sample-app/**,docs/WEBFLUX-SUPPORT.md"
---

# Spring Boot adapter

- Use the `spring-boot.version` property in the root POM as the compatibility source of truth. Spring Boot 3
  compatibility is out of scope.
- `bootui-spring-boot-starter` is the one Spring artifact: the auto-configuration (packages
  `io.github.jdubois.bootui.autoconfigure.*`), its dependencies, and the bundled UI, for Spring MVC and Spring WebFlux
  alike. It never chooses the web stack: `spring-boot-starter-web` and `spring-boot-starter-webflux` stay
  `provided`, and the `no-web-stack` enforcer execution fails the build if any servlet or reactive web-server artifact
  reaches its compile or runtime dependencies. A WebFlux application must stay REACTIVE and a Spring MVC application
  SERVLET; the samples' `*ApplicationTypeIntegrationTest(s)` and the release consumer smoke tests pin both.
- The starter's tests keep the bundled UI and the OpenTelemetry tracing bridge off their classpath
  (`classpathDependencyExcludes`), as the auto-configuration tests always ran; the sample apps test the starter as
  shipped.
- Keep controllers thin. They inject engine services, translate Spring request types, and return stable core DTOs.
- Four autoconfigurations are registered in `AutoConfiguration.imports`: servlet and reactive BootUI configurations,
  plus their Spring Security companions. They are registered, not component-scanned. A fifth,
  `BootUiShellGuardAutoConfiguration`, is the inverse: it is gated by `BootUiInactiveCondition` and exists only while
  BootUI is off, to keep the packaged `/bootui` classpath shell a plain 404 on both stacks. A sixth,
  `BootUiApplicationEventMulticasterAutoConfiguration`, activates exactly with the servlet or reactive configuration and
  defines BootUI's `applicationEventMulticaster` last (after Spring Modulith's auto-configuration and at the lowest
  precedence), backing off from any other definition; never move that bean back into a configuration that sorts
  earlier, or an application defining the name, as Spring Modulith does, fails to start.
- Register shared controllers in both `BootUiAutoConfiguration` and `BootUiReactiveAutoConfiguration`. Put genuinely
  stack-specific bindings in the servlet or `...autoconfigure.reactive` package and its matching import list.
- Activation is fail-closed through the shared `BootUiActivationCondition`: `bootui.enabled=ON|OFF` wins; otherwise an
  active `bootui.enabled-profiles` entry (`dev`, `local` by default) or devtools enables BootUI; a
  `bootui.disabled-profiles` entry (`prod`, `production` by default) forces it off unless explicitly enabled.
- Consume optional Actuator endpoints through `ObjectProvider`; if an endpoint is unavailable, return the panel's empty DTO rather than failing.
- Keep all five Spring bootstrap `EnvironmentPostProcessor`s in the Spring adapter and register additions in
  `META-INF/spring.factories`. They have no Quarkus equivalent. The overrides EPP intentionally loads even while BootUI
  is inactive; do not add the activation gate used by the other four. `BootUiAgentClaimEnvironmentPostProcessor` is
  gated by BootUI's resolved activation like the startup EPP, but when BootUI or `bootui.agent.enabled` is off it
  releases the agent instead of claiming it.
- `LocalhostOnlyFilter`/`PanelAccessFilter` and their `ReactiveLocalhostOnlyFilter`/`ReactivePanelAccessFilter` siblings
  are thin bindings over shared engine policy. Change policy or canonical error text in the engine and keep MVC,
  WebFlux, and Quarkus bindings aligned.
- WebFlux uses the same activation and policy but has stack-specific resource serving, path composition, streaming, and
  capture bindings. Preserve the `spring-boot-reactive` platform manifest and the deliberate HTTP Sessions exception
  documented in `docs/WEBFLUX-SUPPORT.md`.
- Runtime configuration overrides must preserve the persisted `.bootui/application-bootui.properties` flow and restart-caveat message.
- Do not add `-am` to `spring-boot:run`; it applies the goal to parent and library modules without main classes.
- For a focused Spring build use:
  `./mvnw -pl bootui-engine,bootui-spring-boot-starter,bootui-spring-sample-app,bootui-spring-webflux-sample-app -am install`.
