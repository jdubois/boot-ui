package io.github.jdubois.bootui.spi;

import java.util.List;
import java.util.Optional;

/**
 * Framework-neutral seam behind the Flyway panel: it reports the host application's Flyway-managed databases
 * (their migration history) and performs the {@code migrate}/{@code clean} primitives, while the engine
 * {@code FlywayService} owns the framework-neutral concerns — counting applied/pending migrations and the
 * current version, sorting and totalling databases, and orchestrating the actions (Spring-Modulith block,
 * target resolution, confirmation gating, clean-disabled gating and {@code FlywayActionResult} shaping).
 *
 * <p>The optional Flyway library ({@code org.flywaydb.*}) and any framework-specific discovery live only in
 * the adapter implementation: {@code SpringFlywayProvider} discovers {@code Flyway} beans from the
 * {@code ListableBeanFactory} (and keeps the Spring-Modulith module-aware behaviour), while
 * {@code QuarkusFlywayProvider} enumerates the active Quarkus {@code FlywayContainer} beans. The engine stays
 * Flyway-free.</p>
 */
public interface FlywayProvider {

    /**
     * Whether Flyway is present. {@code false} means no Flyway infrastructure is available, in which case the
     * engine serves an empty report with {@code flywayPresent=false}. On Spring this is {@code true} whenever
     * the provider exists (it is wired only when {@code org.flywaydb.core.Flyway} is on the classpath, even
     * with zero beans, preserving the historical {@code flywayPresent=true} contract); on Quarkus it tracks
     * whether the Flyway extension is wired.
     */
    boolean available();

    /**
     * The Flyway-managed databases and their migrations as framework-neutral, <em>unsorted</em> snapshots.
     * Drives {@code GET /bootui/api/flyway/migrations}. On Spring, when Spring Modulith module-aware Flyway is
     * active, this returns read-only per-module snapshots; the engine sorts them and computes the counts.
     */
    List<FlywayDatabaseSnapshot> report();

    /**
     * The framework-specific reason all Flyway actions are blocked, or {@link Optional#empty()} when they are
     * permitted. The engine checks this first (before target resolution) and, when present, returns an HTTP
     * 403 {@code blocked} result carrying this message. Used by the Spring adapter to keep the Spring-Modulith
     * "module-aware migrations are read-only" wording out of the engine; always empty on Quarkus.
     */
    Optional<String> actionsBlockedReason();

    /**
     * The names of the writable Flyway beans that an action can target, used by the engine for target
     * resolution: a blank request name resolves to the single bean when exactly one exists (otherwise the
     * action is not found); a non-blank request name must match one of these exactly. On Spring these are the
     * underlying {@code Flyway} bean names (never the module-aware report names); on Quarkus the datasource
     * names.
     */
    List<String> actionTargets();

    /**
     * The reason the {@code clean} action is disabled for the named database (Flyway's
     * {@code Configuration.isCleanDisabled()}), or {@link Optional#empty()} when clean is permitted. Checked by
     * the engine — before the confirmation gate — for the {@code clean} action only.
     *
     * @param name an {@link #actionTargets()} name
     */
    Optional<String> cleanDisabledReason(String name);

    /**
     * Runs {@code flyway.migrate()} for the named database and returns the neutral outcome, catching any
     * {@code FlywayException} into {@link FlywayMigrateOutcome#failed()}. The engine only calls this after its
     * block/target/confirmation gates pass.
     *
     * @param name an {@link #actionTargets()} name
     */
    FlywayMigrateOutcome migrate(String name);

    /**
     * Runs {@code flyway.clean()} for the named database and returns the neutral outcome, catching any
     * {@code FlywayException} into {@link FlywayCleanOutcome#failed()}. The engine only calls this after its
     * block/target/clean-disabled/confirmation gates pass.
     *
     * @param name an {@link #actionTargets()} name
     */
    FlywayCleanOutcome clean(String name);
}
