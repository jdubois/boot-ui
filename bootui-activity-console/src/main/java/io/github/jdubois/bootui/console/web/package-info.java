/**
 * Web layer (controllers, static resource serving, panels/overview manifests) for the BootUI Activity
 * Console.
 *
 * <p><strong>This module deliberately does not depend on {@code bootui-spring-autoconfigure} or {@code
 * bootui-spring-boot-starter-reactive}</strong>, even though both already ship a reactive (WebFlux)
 * adapter. Three reasons:
 *
 * <ol>
 *   <li>{@code ReactiveLocalhostOnlyFilter} and {@code ReactiveBootUiIndexController} both require
 *       {@code BootUiProperties}, a single {@code @ConfigurationProperties} class covering ~40 unrelated
 *       settings (every panel's enable/read-only toggle, vulnerabilities scanning, GitHub integration,
 *       MCP, etc.) that would be confusing to bind onto a narrowly-scoped, single-purpose aggregator.
 *   <li>Depending on the reactive starter would transitively wire ~40 panel controllers (Beans, Loggers,
 *       Mappings, Health, SQL Trace, and so on) that would all report "unavailable" here, adding dead
 *       surface area and confusing anyone inspecting the running application's beans.
 *   <li>Live Activity itself ({@code LiveActivityController} / {@code ActivityForwardingController}) is
 *       not yet ported to the reactive adapter at all, so the one capability the console actually needs
 *       is not available to reuse regardless of the above.
 * </ol>
 *
 * <p>Instead, this package contains small, self-contained WebFlux classes that reuse only genuinely
 * framework-neutral {@code bootui-core} / {@code bootui-engine} types (DTOs, {@code LocalhostGuard},
 * {@code BootUiPanels}) &mdash; the same reuse posture already established for {@link
 * io.github.jdubois.bootui.console.activity.R2dbcActivityStore} versus the engine's blocking {@code
 * JdbcActivityStore}.
 */
package io.github.jdubois.bootui.console.web;
