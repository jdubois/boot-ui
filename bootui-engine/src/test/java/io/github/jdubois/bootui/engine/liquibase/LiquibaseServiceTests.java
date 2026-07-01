package io.github.jdubois.bootui.engine.liquibase;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.LiquibaseActionRequest;
import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.core.dto.LiquibaseChangeSetDto;
import io.github.jdubois.bootui.core.dto.LiquibaseReport;
import io.github.jdubois.bootui.spi.LiquibaseDatabaseSnapshot;
import io.github.jdubois.bootui.spi.LiquibaseProvider;
import io.github.jdubois.bootui.spi.LiquibaseTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the framework-neutral {@link LiquibaseService}, driven by a package-private fake
 * {@link LiquibaseProvider}. They pin the byte-identical assembly (applied + pending merge, counts, name
 * ordering, total, {@code updateEnabled}) and the update orchestration (null provider, not-found,
 * single-database default, blocked-disabled, blocked-confirm, success and thrown {@code ->} 500) that the
 * pre-extraction Spring {@code LiquibaseController} owned.
 */
class LiquibaseServiceTests {

    private static LiquibaseChangeSetDto changeSet(String id, String execType) {
        return new LiquibaseChangeSetDto(
                id,
                "author",
                "db/changelog.xml",
                "desc",
                null,
                execType,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    @Test
    void reportIsAbsentWhenProviderIsNull() {
        LiquibaseReport report = new LiquibaseService(null).report();

        assertThat(report.liquibasePresent()).isFalse();
        assertThat(report.total()).isZero();
        assertThat(report.databases()).isEmpty();
    }

    @Test
    void reportIsAbsentWhenProviderUnavailable() {
        FakeProvider provider = new FakeProvider();
        provider.available = false;

        LiquibaseReport report = new LiquibaseService(provider).report();

        assertThat(report.liquibasePresent()).isFalse();
        assertThat(report.databases()).isEmpty();
    }

    @Test
    void reportIsPresentButEmptyWhenNoDatabases() {
        LiquibaseReport report = new LiquibaseService(new FakeProvider()).report();

        assertThat(report.liquibasePresent()).isTrue();
        assertThat(report.total()).isZero();
        assertThat(report.databases()).isEmpty();
    }

    @Test
    void reportMergesAppliedAndPendingCountsAndOrdersByName() {
        FakeProvider provider = new FakeProvider();
        provider.databases.add(new LiquibaseDatabaseSnapshot(
                "zeta", List.of(changeSet("z1", "EXECUTED")), List.of(changeSet("z2", "PENDING")), null));
        provider.databases.add(new LiquibaseDatabaseSnapshot(
                "alpha",
                List.of(changeSet("a1", "EXECUTED"), changeSet("a2", "EXECUTED")),
                List.of(),
                "no change log"));

        LiquibaseReport report = new LiquibaseService(provider).report();

        assertThat(report.liquibasePresent()).isTrue();
        assertThat(report.total()).isEqualTo(4);
        assertThat(report.databases()).extracting(db -> db.name()).containsExactly("alpha", "zeta");

        var alpha = report.databases().get(0);
        assertThat(alpha.applied()).isEqualTo(2);
        assertThat(alpha.pending()).isZero();
        assertThat(alpha.total()).isEqualTo(2);
        assertThat(alpha.changeSets()).hasSize(2);
        assertThat(alpha.updateEnabled()).isFalse();
        assertThat(alpha.updateDisabledReason()).isEqualTo("no change log");

        var zeta = report.databases().get(1);
        assertThat(zeta.applied()).isEqualTo(1);
        assertThat(zeta.pending()).isEqualTo(1);
        assertThat(zeta.total()).isEqualTo(2);
        // Applied change sets come first, then pending.
        assertThat(zeta.changeSets()).extracting(LiquibaseChangeSetDto::id).containsExactly("z1", "z2");
        assertThat(zeta.updateEnabled()).isTrue();
        assertThat(zeta.updateDisabledReason()).isNull();
    }

    @Test
    void updateIsUnavailableWhenProviderIsNull() {
        LiquibaseActionResponse response = new LiquibaseService(null).update(new LiquibaseActionRequest("db", true));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().status()).isEqualTo("unavailable");
        assertThat(response.body().message()).isEqualTo("No Liquibase integration is available.");
    }

    @Test
    void updateReturnsNotFoundWhenTargetUnmatched() {
        FakeProvider provider = new FakeProvider();
        provider.targets.add(new LiquibaseTarget("db", null));

        LiquibaseActionResponse response =
                new LiquibaseService(provider).update(new LiquibaseActionRequest("other", true));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().status()).isEqualTo("unavailable");
        assertThat(response.body().message()).isEqualTo("No Liquibase bean matched the requested datasource.");
    }

    @Test
    void updateReturnsNotFoundWhenNoBeanNameAndMultipleDatabases() {
        FakeProvider provider = new FakeProvider();
        provider.targets.add(new LiquibaseTarget("db1", null));
        provider.targets.add(new LiquibaseTarget("db2", null));

        LiquibaseActionResponse response =
                new LiquibaseService(provider).update(new LiquibaseActionRequest(null, true));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().status()).isEqualTo("unavailable");
    }

    @Test
    void updateDefaultsToTheSoleDatabaseWhenNoBeanNameGiven() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.targets.add(new LiquibaseTarget("only", null));
        provider.updateResult =
                new LiquibaseActionResult("success", "Liquibase applied 1 change set(s).", "only", 1, 0, 1, List.of());

        LiquibaseActionResponse response =
                new LiquibaseService(provider).update(new LiquibaseActionRequest(null, true));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("success");
        assertThat(provider.updatedName).isEqualTo("only");
    }

    @Test
    void updateIsBlockedWhenDisabled() {
        FakeProvider provider = new FakeProvider();
        provider.targets.add(
                new LiquibaseTarget("db", "Liquibase update cannot run because this bean has no DataSource."));

        LiquibaseActionResponse response =
                new LiquibaseService(provider).update(new LiquibaseActionRequest("db", true));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().status()).isEqualTo("blocked");
        assertThat(response.body().beanName()).isEqualTo("db");
        assertThat(response.body().message())
                .isEqualTo("Liquibase update cannot run because this bean has no DataSource.");
    }

    @Test
    void updateRequiresConfirmation() {
        FakeProvider provider = new FakeProvider();
        provider.targets.add(new LiquibaseTarget("db", null));

        LiquibaseActionResponse response =
                new LiquibaseService(provider).update(new LiquibaseActionRequest("db", null));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("blocked");
        assertThat(response.body().beanName()).isEqualTo("db");
        assertThat(response.body().message()).isEqualTo(LiquibaseService.CONFIRMATION_REQUIRED);
    }

    @Test
    void updateRunsTheSelectedDatabase() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.targets.add(new LiquibaseTarget("inventory", null));
        provider.updateResult = new LiquibaseActionResult(
                "success", "Liquibase applied 2 change set(s).", "inventory", 3, 1, 2, List.of());

        LiquibaseActionResponse response =
                new LiquibaseService(provider).update(new LiquibaseActionRequest("inventory", true));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("success");
        assertThat(response.body().changeSetsApplied()).isEqualTo(2);
        assertThat(provider.updatedName).isEqualTo("inventory");
    }

    @Test
    void updateMapsAThrownExceptionToFailed() {
        FakeProvider provider = new FakeProvider();
        provider.targets.add(new LiquibaseTarget("db", null));
        provider.updateException = new IllegalStateException("boom");

        LiquibaseActionResponse response =
                new LiquibaseService(provider).update(new LiquibaseActionRequest("db", true));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.body().beanName()).isEqualTo("db");
        assertThat(response.body().message()).isEqualTo("boom");
    }

    private static final class FakeProvider implements LiquibaseProvider {

        private boolean available = true;
        private final List<LiquibaseDatabaseSnapshot> databases = new ArrayList<>();
        private final List<LiquibaseTarget> targets = new ArrayList<>();
        private LiquibaseActionResult updateResult;
        private RuntimeException updateException;
        private String updatedName;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<LiquibaseDatabaseSnapshot> databases() {
            return databases;
        }

        @Override
        public List<LiquibaseTarget> targets() {
            return targets;
        }

        @Override
        public LiquibaseActionResult update(String name) {
            this.updatedName = name;
            if (updateException != null) {
                throw updateException;
            }
            return updateResult;
        }
    }
}
