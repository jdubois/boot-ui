package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral outcome of a {@link FlywayProvider#migrate(String)} call. The provider performs the
 * {@code flyway.migrate()} primitive and catches any {@code FlywayException} itself, so the engine
 * {@code FlywayService} never sees a {@code org.flywaydb.*} type.
 *
 * <p>The {@code failed} flag is the engine's 500-vs-200 discriminator and is deliberately distinct from
 * {@code success}: {@code failed} is {@code true} only when a {@code FlywayException} was thrown (the engine
 * renders HTTP 500 with {@link #errorMessage()}), whereas a non-exception {@code MigrateResult} with
 * {@code success == false} is still HTTP 200 with a body status of {@code "failed"}. A thrown exception may
 * carry a {@code null} message, so {@code errorMessage} alone is not a reliable signal.</p>
 *
 * @param success the {@code MigrateResult.success} flag (meaningful only when {@code failed} is false)
 * @param migrationsExecuted the number of migrations applied (meaningful only when {@code failed} is false)
 * @param warnings any warnings reported by Flyway, never {@code null}
 * @param failed whether a {@code FlywayException} was thrown (renders HTTP 500)
 * @param errorMessage the exception message when {@code failed}, otherwise {@code null}
 */
public record FlywayMigrateOutcome(
        boolean success, int migrationsExecuted, List<String> warnings, boolean failed, String errorMessage) {

    public FlywayMigrateOutcome {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
