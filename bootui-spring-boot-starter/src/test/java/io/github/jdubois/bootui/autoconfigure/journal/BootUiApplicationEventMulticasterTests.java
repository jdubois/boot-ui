package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.events.OrderPlaced;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** M4-8: Spring's application events and their listeners' runs, transactional ones included, reach the journal. */
class BootUiApplicationEventMulticasterTests {

    @Test
    void recordsPublicationsImmediateListenersAndWhatTheTransactionMadeOfTransactionalOnes() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            RuntimeJournal journal = new RuntimeJournal(
                    new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
                    RunIdentity.start());
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(
                    "applicationEventMulticaster",
                    BootUiApplicationEventMulticaster.class,
                    () -> new BootUiApplicationEventMulticaster(context.getBeanFactory(), () -> journal));
            context.register(Listeners.class);
            context.refresh();
            ApplicationEventPublisher publisher = context;
            TransactionTemplate transactions =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            publisher.publishEvent(new OrderPlaced(1));
            transactions.executeWithoutResult(status -> publisher.publishEvent(new OrderPlaced(2)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

            List<AppEventPayload> events = journal.entries().stream()
                    .map(entry -> entry.event().payload())
                    .filter(AppEventPayload.class::isInstance)
                    .map(AppEventPayload.class::cast)
                    .toList();
            assertThat(events)
                    .extracting(
                            AppEventPayload::kind,
                            AppEventPayload::listener,
                            AppEventPayload::phase,
                            AppEventPayload::outcome)
                    .containsExactlyInAnyOrder(
                            tuple(AppEventPayload.PUBLISHED, null, null, null),
                            tuple(AppEventPayload.LISTENER, "Listeners#onPlaced", "IMMEDIATE", "RAN"),
                            tuple(
                                    AppEventPayload.LISTENER,
                                    "Listeners#afterCommit",
                                    "AFTER_COMMIT",
                                    "SKIPPED_NO_TRANSACTION"),
                            tuple(AppEventPayload.LISTENER, "Listeners#fallback", "AFTER_COMMIT", "RAN"),
                            tuple(AppEventPayload.PUBLISHED, null, null, null),
                            tuple(AppEventPayload.LISTENER, "Listeners#onPlaced", "IMMEDIATE", "RAN"),
                            tuple(AppEventPayload.LISTENER, "Listeners#afterCommit", "AFTER_COMMIT", "DEFERRED"),
                            tuple(AppEventPayload.LISTENER, "Listeners#fallback", "AFTER_COMMIT", "DEFERRED"),
                            tuple(AppEventPayload.LISTENER, "Listeners#afterCommit", "AFTER_COMMIT", "RAN"),
                            tuple(AppEventPayload.LISTENER, "Listeners#fallback", "AFTER_COMMIT", "RAN"));
            assertThat(events)
                    .allSatisfy(event -> assertThat(event.eventType()).isEqualTo(OrderPlaced.class.getName()));
            assertThat(context.getBean(Listeners.class).afterCommitRuns)
                    .as("delivery is unchanged")
                    .isEqualTo(1);
            journal.close();
        }
    }

    @Test
    void frameworkEventsAreNotRecorded() {
        assertThat(BootUiApplicationEventMulticaster.applicationType(
                        new org.springframework.context.event.ContextRefreshedEvent(
                                new AnnotationConfigApplicationContext())))
                .isNull();
        assertThat(BootUiApplicationEventMulticaster.applicationType(
                        new org.springframework.context.PayloadApplicationEvent<>(this, "a string")))
                .isNull();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Listeners {

        int afterCommitRuns;

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @EventListener
        void onPlaced(OrderPlaced event) {}

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void afterCommit(OrderPlaced event) {
            afterCommitRuns++;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        void fallback(OrderPlaced event) {}
    }
}
