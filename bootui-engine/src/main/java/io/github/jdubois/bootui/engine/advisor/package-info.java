/**
 * Framework-neutral advisor support shared by every BootUI advisor panel.
 *
 * <p>Plain Java (BootUI core DTOs + {@code java.nio} only); adapters wire these helpers via an
 * {@code @Bean} factory / {@code @Produces} method. {@link io.github.jdubois.bootui.engine.advisor
 * .DismissedRulesStore} persists the developer's dismissed advisor rule IDs to a local
 * {@code .bootui/boot-ui.yml} file so dismissals survive restarts on both Spring Boot and Quarkus.
 */
package io.github.jdubois.bootui.engine.advisor;
