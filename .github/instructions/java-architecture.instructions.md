---
applyTo: "**/*.java,pom.xml,**/pom.xml"
---

# Java and shared architecture

- Compile for Java 17 with `-parameters`. Use the Maven Wrapper; do not require a system Maven.
- Published Maven coordinates use `com.julien-dubois.bootui:*`; Java packages remain
  `io.github.jdubois.bootui.*`.
- Preserve the package direction `io.github.jdubois.bootui.core..` <- `io.github.jdubois.bootui.engine..` and
  `io.github.jdubois.bootui.spi..` <- adapters. The core package lives in `bootui-engine` and depends only on the JDK
  and itself; `CoreBoundaryArchitectureTests` enforces this. Core, engine, conformance, and UI must not depend on
  Spring, Quarkus, or servlet/JAX-RS APIs. Core and engine must not depend on either Jackson generation;
  `bootui-conformance` deliberately uses Jackson 2, JUnit and AssertJ in compile scope because its black-box
  HTTP test-support classes live in `src/main` for the sample and integration-test runners.
- DTOs are annotation-free immutable records in `io.github.jdubois.bootui.core.dto`, one record per file. They must serialize identically with Spring Boot's Jackson 3 and Quarkus' Jackson 2.
- Keep the engine framework-, DI-, and JSON-free. Parse external JSON in an adapter and pass neutral records to the engine.
- Put reusable behavior in an engine feature package and expose framework integration through neutral SPI ports in `io.github.jdubois.bootui.spi`.
- Engine services have explicit adapter factories. Spring uses `@Bean`; Quarkus uses `@Produces`. When Quarkus injects an SPI implementation, inject its concrete class to avoid ambiguous CDI resolution.
- For live-overridable configuration, define an SPI policy that is reread per request. For static configuration, pass an immutable settings record.
- Optional dependencies must be safe when absent. Keep presence checks in the adapter and avoid static imports of optional APIs from always-loaded engine classes. On Quarkus, use capability-gated build steps and `ExcludedTypeBuildItem` where classloading requires it.
- Package roots are `io.github.jdubois.bootui.<module>`. Use feature subpackages in the engine, `...autoconfigure.web` or feature packages in Spring, and `...quarkus.web` for Quarkus JAX-RS resources.
- Use four-space Java/XML indentation, UTF-8, LF, and trailing newlines. Formatting is enforced by Spotless with Palantir Java Format.
- Tests move with extracted behavior: pure service tests belong in the engine; adapter tests cover only native wiring; factory tests pin property-to-settings mapping.
- Run the smallest targeted Maven test set first. Run Spring MVC, Spring WebFlux, and Quarkus conformance after
  cross-stack API or extraction changes.
