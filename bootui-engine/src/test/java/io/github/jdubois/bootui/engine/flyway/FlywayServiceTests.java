package io.github.jdubois.bootui.engine.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.FlywayActionRequest;
import io.github.jdubois.bootui.core.dto.FlywayDatabaseDto;
import io.github.jdubois.bootui.core.dto.FlywayMigrationDto;
import io.github.jdubois.bootui.core.dto.FlywayReport;
import io.github.jdubois.bootui.spi.FlywayCleanOutcome;
import io.github.jdubois.bootui.spi.FlywayDatabaseSnapshot;
import io.github.jdubois.bootui.spi.FlywayMigrateOutcome;
import io.github.jdubois.bootui.spi.FlywayMigrationSnapshot;
import io.github.jdubois.bootui.spi.FlywayProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the framework-neutral {@link FlywayService}. They pin the behavior that must stay
 * byte-identical to the pre-extraction Spring {@code FlywayController}: the report assembly
 * (applied/pending/current-version counting, name sorting, total summing), and the migrate/clean action gate
 * order and status/HTTP-code matrix (actions-blocked → 403, target resolution → 404, confirmation → 400,
 * clean-disabled → 403 before confirmation, FlywayException → 500, and the 200 "failed"-result vs 500-exception
 * distinction).
 */
class FlywayServiceTests {

    private static FlywayMigrationDto migration(String version, String state) {
        return new FlywayMigrationDto(
                "SQL", version, "desc " + version, "V" + version + "__x.sql", state, "sa", null, 1, 5, 123);
    }

    private static FlywayMigrationSnapshot applied(String version) {
        return new FlywayMigrationSnapshot(migration(version, "Success"), true, false);
    }

    private static FlywayMigrationSnapshot pending(String version) {
        return new FlywayMigrationSnapshot(migration(version, "Pending"), false, true);
    }

    @Test
    void reportIsUnavailableWhenProviderIsNull() {
        FlywayReport report = new FlywayService(null).report();
        assertThat(report.flywayPresent()).isFalse();
        assertThat(report.total()).isZero();
        assertThat(report.databases()).isEmpty();
    }

    @Test
    void reportIsUnavailableWhenProviderNotAvailable() {
        FlywayReport report = new FlywayService(new FakeProvider().available(false)).report();
        assertThat(report.flywayPresent()).isFalse();
        assertThat(report.databases()).isEmpty();
    }

    @Test
    void reportCountsAppliedPendingCurrentVersionAndSortsBySumTotals() {
        FakeProvider provider = new FakeProvider()
                .database(new FlywayDatabaseSnapshot(
                        "zeta", List.of(applied("1"), applied("2"), pending("3")), true, null, true, null))
                .database(new FlywayDatabaseSnapshot(
                        "alpha", List.of(applied("1")), true, null, false, "clean disabled"));

        FlywayReport report = new FlywayService(provider).report();

        assertThat(report.flywayPresent()).isTrue();
        assertThat(report.total()).isEqualTo(4);
        assertThat(report.databases()).extracting(FlywayDatabaseDto::name).containsExactly("alpha", "zeta");

        FlywayDatabaseDto zeta = report.databases().get(1);
        assertThat(zeta.applied()).isEqualTo(2);
        assertThat(zeta.pending()).isEqualTo(1);
        assertThat(zeta.total()).isEqualTo(3);
        assertThat(zeta.currentVersion()).isEqualTo("2");
        assertThat(zeta.cleanEnabled()).isTrue();

        FlywayDatabaseDto alpha = report.databases().get(0);
        assertThat(alpha.cleanEnabled()).isFalse();
        assertThat(alpha.cleanDisabledReason()).isEqualTo("clean disabled");
    }

    @Test
    void reportSortsNullNamesLast() {
        FakeProvider provider = new FakeProvider()
                .database(new FlywayDatabaseSnapshot(null, List.of(), true, null, true, null))
                .database(new FlywayDatabaseSnapshot("a", List.of(), true, null, true, null));
        FlywayReport report = new FlywayService(provider).report();
        assertThat(report.databases()).extracting(FlywayDatabaseDto::name).containsExactly("a", null);
    }

    @Test
    void migrateBlockedReasonReturns403WithRequestedBeanName() {
        FakeProvider provider =
                new FakeProvider().blockedReason("modulith read-only").target("flyway");
        FlywayActionResponse response = new FlywayService(provider).migrate(new FlywayActionRequest("flyway", true));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().status()).isEqualTo("blocked");
        assertThat(response.body().message()).isEqualTo("modulith read-only");
        assertThat(response.body().beanName()).isEqualTo("flyway");
    }

    @Test
    void migrateBlockedReasonWithBlankRequestHasNullBeanName() {
        FakeProvider provider = new FakeProvider().blockedReason("modulith read-only");
        FlywayActionResponse response = new FlywayService(provider).migrate(new FlywayActionRequest("  ", true));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().beanName()).isNull();
    }

    @Test
    void migrateWithBlankNameAndMultipleTargetsIsNotFound() {
        FakeProvider provider = new FakeProvider().target("a").target("b");
        FlywayActionResponse response = new FlywayService(provider).migrate(new FlywayActionRequest(null, true));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().status()).isEqualTo("unavailable");
        assertThat(response.body().beanName()).isNull();
    }

    @Test
    void migrateWithBlankNameAndSingleTargetResolvesIt() {
        FakeProvider provider = new FakeProvider()
                .target("only")
                .migrateOutcome(name -> new FlywayMigrateOutcome(true, 2, List.of(), false, null));
        FlywayActionResponse response = new FlywayService(provider).migrate(new FlywayActionRequest(null, true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("success");
        assertThat(response.body().message()).isEqualTo("Flyway applied 2 migration(s).");
        assertThat(response.body().beanName()).isEqualTo("only");
        assertThat(provider.migratedNames).containsExactly("only");
    }

    @Test
    void migrateUnknownNameIsNotFound() {
        FakeProvider provider = new FakeProvider().target("a");
        FlywayActionResponse response = new FlywayService(provider).migrate(new FlywayActionRequest("nope", true));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().beanName()).isNull();
    }

    @Test
    void migrateRequiresConfirmation() {
        FakeProvider provider = new FakeProvider().target("flyway");
        FlywayActionResponse response = new FlywayService(provider).migrate(new FlywayActionRequest("flyway", null));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("blocked");
        assertThat(response.body().beanName()).isEqualTo("flyway");
        assertThat(provider.migratedNames).isEmpty();
    }

    @Test
    void migrateUpToDateMessageWhenZeroExecuted() {
        FakeProvider provider = new FakeProvider()
                .target("flyway")
                .migrateOutcome(name -> new FlywayMigrateOutcome(true, 0, List.of(), false, null));
        FlywayActionResponse response = new FlywayService(provider).migrate(new FlywayActionRequest("flyway", true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().message()).isEqualTo("Flyway schema is already up to date.");
    }

    @Test
    void migrateFailedResultIs200WithFailedStatus() {
        FakeProvider provider = new FakeProvider()
                .target("flyway")
                .migrateOutcome(name -> new FlywayMigrateOutcome(false, 0, List.of("w"), false, null));
        FlywayActionResponse response = new FlywayService(provider).migrate(new FlywayActionRequest("flyway", true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.body().warnings()).containsExactly("w");
    }

    @Test
    void migrateExceptionIs500EvenWithNullMessage() {
        FakeProvider provider = new FakeProvider()
                .target("flyway")
                .migrateOutcome(name -> new FlywayMigrateOutcome(false, 0, List.of(), true, null));
        FlywayActionResponse response = new FlywayService(provider).migrate(new FlywayActionRequest("flyway", true));
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.body().message()).isNull();
    }

    @Test
    void cleanChecksDisabledBeforeConfirmation() {
        FakeProvider provider = new FakeProvider().target("flyway").cleanDisabled("flyway", "clean off");
        FlywayActionResponse response = new FlywayService(provider).clean(new FlywayActionRequest("flyway", null));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().status()).isEqualTo("blocked");
        assertThat(response.body().message()).isEqualTo("clean off");
        assertThat(provider.cleanedNames).isEmpty();
    }

    @Test
    void cleanRequiresConfirmationWhenEnabled() {
        FakeProvider provider = new FakeProvider().target("flyway");
        FlywayActionResponse response = new FlywayService(provider).clean(new FlywayActionRequest("flyway", false));
        assertThat(response.status()).isEqualTo(400);
        assertThat(provider.cleanedNames).isEmpty();
    }

    @Test
    void cleanSuccessReturnsSchemas() {
        FakeProvider provider = new FakeProvider()
                .target("flyway")
                .cleanOutcome(
                        name -> new FlywayCleanOutcome(List.of("public"), List.of("audit"), List.of(), false, null));
        FlywayActionResponse response = new FlywayService(provider).clean(new FlywayActionRequest("flyway", true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("success");
        assertThat(response.body().message()).isEqualTo("Flyway cleaned schema(s) for flyway.");
        assertThat(response.body().schemasCleaned()).containsExactly("public");
        assertThat(response.body().schemasDropped()).containsExactly("audit");
    }

    @Test
    void cleanExceptionIs500() {
        FakeProvider provider = new FakeProvider()
                .target("flyway")
                .cleanOutcome(name -> new FlywayCleanOutcome(List.of(), List.of(), List.of(), true, "boom"));
        FlywayActionResponse response = new FlywayService(provider).clean(new FlywayActionRequest("flyway", true));
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.body().message()).isEqualTo("boom");
    }

    @Test
    void migrateOnNullProviderIsNotFound() {
        FlywayActionResponse response = new FlywayService(null).migrate(new FlywayActionRequest("x", true));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().status()).isEqualTo("unavailable");
    }

    private static final class FakeProvider implements FlywayProvider {

        private boolean available = true;
        private final List<FlywayDatabaseSnapshot> databases = new ArrayList<>();
        private final List<String> targets = new ArrayList<>();
        private String blockedReason;
        private final java.util.Map<String, String> cleanDisabled = new java.util.HashMap<>();
        private Function<String, FlywayMigrateOutcome> migrateOutcome =
                name -> new FlywayMigrateOutcome(true, 1, List.of(), false, null);
        private Function<String, FlywayCleanOutcome> cleanOutcome =
                name -> new FlywayCleanOutcome(List.of(), List.of(), List.of(), false, null);
        private final List<String> migratedNames = new ArrayList<>();
        private final List<String> cleanedNames = new ArrayList<>();

        FakeProvider available(boolean value) {
            this.available = value;
            return this;
        }

        FakeProvider database(FlywayDatabaseSnapshot snapshot) {
            this.databases.add(snapshot);
            return this;
        }

        FakeProvider target(String name) {
            this.targets.add(name);
            return this;
        }

        FakeProvider blockedReason(String reason) {
            this.blockedReason = reason;
            return this;
        }

        FakeProvider cleanDisabled(String name, String reason) {
            this.cleanDisabled.put(name, reason);
            return this;
        }

        FakeProvider migrateOutcome(Function<String, FlywayMigrateOutcome> outcome) {
            this.migrateOutcome = outcome;
            return this;
        }

        FakeProvider cleanOutcome(Function<String, FlywayCleanOutcome> outcome) {
            this.cleanOutcome = outcome;
            return this;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<FlywayDatabaseSnapshot> report() {
            return databases;
        }

        @Override
        public Optional<String> actionsBlockedReason() {
            return Optional.ofNullable(blockedReason);
        }

        @Override
        public List<String> actionTargets() {
            return targets;
        }

        @Override
        public Optional<String> cleanDisabledReason(String name) {
            return Optional.ofNullable(cleanDisabled.get(name));
        }

        @Override
        public FlywayMigrateOutcome migrate(String name) {
            migratedNames.add(name);
            return migrateOutcome.apply(name);
        }

        @Override
        public FlywayCleanOutcome clean(String name) {
            cleanedNames.add(name);
            return cleanOutcome.apply(name);
        }
    }
}
