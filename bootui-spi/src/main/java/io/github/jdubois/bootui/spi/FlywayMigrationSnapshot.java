package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.FlywayMigrationDto;

/**
 * Framework-neutral snapshot of one Flyway migration known to a {@link FlywayProvider}: the already-mapped
 * {@link FlywayMigrationDto} together with the two state classifications the engine needs to count migrations.
 *
 * <p>Mapping a Flyway {@code MigrationInfo} to the neutral {@link FlywayMigrationDto} requires the
 * {@code org.flywaydb.*} API, so it is the provider's job; the {@code applied}/{@code pending} flags are the
 * provider's reading of {@code MigrationState.isApplied()} and {@code MigrationState == PENDING} respectively
 * (independent checks, as in Flyway). The engine {@code FlywayService} then computes each database's applied
 * count, pending count and current version purely from these neutral flags — never touching a Flyway type.</p>
 *
 * @param migration the neutral migration DTO (the wire shape, unchanged across adapters)
 * @param applied whether the migration is in an applied state ({@code MigrationState.isApplied()})
 * @param pending whether the migration is pending ({@code MigrationState == PENDING})
 */
public record FlywayMigrationSnapshot(FlywayMigrationDto migration, boolean applied, boolean pending) {}
