package io.github.jdubois.bootui.engine.liquibase;

import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;

/**
 * Framework-neutral outcome of a {@link LiquibaseService#update} call: the HTTP status the adapter should
 * render together with the {@link LiquibaseActionResult} body. The engine owns the status decision so both
 * adapters report identical codes; each adapter maps {@link #status()} onto its native response type
 * ({@code ResponseEntity} on Spring, {@code Response} on Quarkus).
 *
 * @param status the HTTP status code (200, 400, 403, 404 or 500)
 * @param body the result body to serialize
 */
public record LiquibaseActionResponse(int status, LiquibaseActionResult body) {}
