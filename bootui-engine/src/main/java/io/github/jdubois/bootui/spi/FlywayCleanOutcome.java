package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral outcome of a {@link FlywayProvider#clean(String)} call. The provider performs the
 * {@code flyway.clean()} primitive and catches any {@code FlywayException} itself, so the engine
 * {@code FlywayService} never sees a {@code org.flywaydb.*} type.
 *
 * <p>As with {@link FlywayMigrateOutcome}, the {@code failed} flag (a thrown {@code FlywayException}) is the
 * engine's HTTP-500 discriminator and is independent of the (possibly {@code null}) {@link #errorMessage()}.
 * A successful clean renders HTTP 200 with the cleaned/dropped schema lists.</p>
 *
 * @param schemasCleaned the schemas Flyway cleaned, never {@code null}
 * @param schemasDropped the schemas Flyway dropped, never {@code null}
 * @param warnings any warnings reported by Flyway, never {@code null}
 * @param failed whether a {@code FlywayException} was thrown (renders HTTP 500)
 * @param errorMessage the exception message when {@code failed}, otherwise {@code null}
 */
public record FlywayCleanOutcome(
        List<String> schemasCleaned,
        List<String> schemasDropped,
        List<String> warnings,
        boolean failed,
        String errorMessage) {

    public FlywayCleanOutcome {
        schemasCleaned = schemasCleaned == null ? List.of() : List.copyOf(schemasCleaned);
        schemasDropped = schemasDropped == null ? List.of() : List.copyOf(schemasDropped);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
