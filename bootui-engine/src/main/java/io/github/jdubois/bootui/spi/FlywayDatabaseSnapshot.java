package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral snapshot of one Flyway-managed database (one {@code Flyway} bean / history table) known to
 * a {@link FlywayProvider}: its name, its migrations and the action-availability flags. The engine
 * {@code FlywayService} computes the applied/pending counts, current version and totals from
 * {@link #migrations()} and sorts databases by name — never touching a {@code org.flywaydb.*} type.
 *
 * <p>On Spring the name is the (stripped) Flyway bean name, and when Spring Modulith module-aware migrations
 * are active the provider returns one read-only snapshot per module ({@code beanName:identifier}) with
 * {@code migrateEnabled=false}. On Quarkus the name is the Flyway datasource name and there is one snapshot
 * per active {@code FlywayContainer}.</p>
 *
 * @param name the database / Flyway bean name (Spring bean name or Quarkus datasource name)
 * @param migrations the migrations as neutral snapshots, in Flyway's reported order
 * @param migrateEnabled whether the migrate action is permitted for this database
 * @param migrateDisabledReason the reason migrate is disabled, or {@code null} when it is enabled
 * @param cleanEnabled whether the clean action is permitted for this database
 * @param cleanDisabledReason the reason clean is disabled, or {@code null} when it is enabled
 */
public record FlywayDatabaseSnapshot(
        String name,
        List<FlywayMigrationSnapshot> migrations,
        boolean migrateEnabled,
        String migrateDisabledReason,
        boolean cleanEnabled,
        String cleanDisabledReason) {

    public FlywayDatabaseSnapshot {
        migrations = migrations == null ? List.of() : List.copyOf(migrations);
    }
}
