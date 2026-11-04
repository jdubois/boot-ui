package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalCompleteness;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class StartupJournalAggregationTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON", "bootui.runtime-journal.sources=sql")
            .withUserConfiguration(EarlySql.class);

    @Test
    void sqlDispatchedDuringBeanCreationIsAggregatedExactlyOnce() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertCaptured(context.getBean(RuntimeJournal.class), context.getBean(JournalAggregates.class));
        });
    }

    @Test
    void anApplicationSuppliedJournalAlsoBindsBeforePublishersReceiveIt() {
        runner.withBean(
                        RuntimeJournal.class,
                        () -> new RuntimeJournal(
                                RuntimeJournalSettings.of(true, 100, null, 100, "sql"), RunIdentity.start()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertCaptured(context.getBean(RuntimeJournal.class), context.getBean(JournalAggregates.class));
                });
    }

    @Test
    void applicationSuppliedAggregatesAreTheOnlyAggregateListener() {
        JournalAggregates supplied = new JournalAggregates();
        runner.withBean(JournalAggregates.class, () -> supplied).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JournalAggregates.class)).isSameAs(supplied);
            assertCaptured(context.getBean(RuntimeJournal.class), supplied);
        });
    }

    @Test
    void applicationSuppliedJournalAlreadyBoundToItsAggregatesDoesNotCountTwice() {
        JournalAggregates supplied = new JournalAggregates();
        runner.withBean(JournalAggregates.class, () -> supplied)
                .withBean(RuntimeJournal.class, () -> {
                    RuntimeJournal journal = new RuntimeJournal(
                            RuntimeJournalSettings.of(true, 100, null, 100, "sql"), RunIdentity.start());
                    journal.addListener(supplied);
                    return journal;
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertCaptured(context.getBean(RuntimeJournal.class), supplied);
                });
    }

    @Test
    void aDisabledJournalDoesNotAcquireSqlOrInventMissedEvents() {
        runner.withPropertyValues("bootui.runtime-journal.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            RuntimeJournal journal = context.getBean(RuntimeJournal.class);
            var snapshot = context.getBean(JournalAggregates.class).snapshot();
            assertThat(journal.status().accepted()).isEmpty();
            assertThat(snapshot.run().events()).isEmpty();
            assertThat(JournalCompleteness.missed(snapshot, JournalSource.SQL)).isZero();
            assertThat(JournalCompleteness.windowComplete(snapshot, JournalSource.SQL))
                    .isFalse();
        });
    }

    @Test
    void earlyAggregateCreationDoesNotResolveTheMappingProvider() {
        runner.withBean(
                        "unresolvedMappings",
                        MappingProvider.class,
                        () -> {
                            throw new AssertionError("Startup SQL must not initialize route mappings");
                        },
                        definition -> definition.setLazyInit(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertCaptured(context.getBean(RuntimeJournal.class), context.getBean(JournalAggregates.class));
                    assertThat(context.getBeanFactory().containsSingleton("unresolvedMappings"))
                            .isFalse();
                });
    }

    @Test
    void failedAggregateInitializationClosesTheJournalBeforeSpringCanExposeIt() {
        AtomicReference<RuntimeJournal> created = new AtomicReference<>();
        runner.withBean(RuntimeJournal.class, () -> {
                    RuntimeJournal journal = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start());
                    created.set(journal);
                    return journal;
                })
                .withBean(JournalAggregates.class, () -> {
                    throw new IllegalStateException("aggregate initialization failed");
                })
                .run(context -> assertThat(context).hasFailed());
        assertThat(created.get()).isNotNull();
        assertThat(created.get().isOpen()).isFalse();
        assertThat(created.get().resourceSamplerRunning()).isFalse();
    }

    private static void assertCaptured(RuntimeJournal journal, JournalAggregates aggregates) {
        assertThat(journal.status().accepted()).containsEntry(JournalSource.SQL, 2L);
        assertThat(journal.status().dropped()).isEmpty();
        var snapshot = aggregates.snapshot();
        assertThat(JournalCompleteness.missed(snapshot, JournalSource.SQL)).isZero();
        assertThat(JournalCompleteness.wholeRunComplete(snapshot, JournalSource.SQL))
                .isTrue();
        assertThat(snapshot.run().events()).containsEntry(JournalSource.SQL, 2L);
        assertThat(snapshot.statements())
                .hasSize(2)
                .allSatisfy(statement -> assertThat(statement.executions()).isEqualTo(1));
    }

    @Configuration(proxyBeanMethods = false)
    static class EarlySql {

        @Bean
        DataSource dataSource() {
            JdbcDataSource pool = new JdbcDataSource();
            pool.setURL("jdbc:h2:mem:startup_" + UUID.randomUUID());
            return pool;
        }

        @Bean
        String initializeSchema(DataSource dataSource, RuntimeJournal journal) throws Exception {
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("create table startup_marker (id integer)");
                statement.execute("select * from startup_marker");
            }
            assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();
            return "ready";
        }
    }
}
