package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.events.OrderPlaced;
import io.github.jdubois.bootui.autoconfigure.transactions.BootUiTransactionExecutionListener;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.AfterCommitWrites;
import io.github.jdubois.bootui.engine.insights.Finding;
import io.github.jdubois.bootui.engine.insights.InsightsSnapshot;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalApplicationListener;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager;
import org.springframework.transaction.reactive.GenericReactiveTransaction;
import org.springframework.transaction.reactive.TransactionContextManager;
import org.springframework.transaction.reactive.TransactionalEventPublisher;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/** M4-8: Spring's application events and their listeners' runs, transactional ones included, reach the journal. */
class BootUiApplicationEventMulticasterTests {

    @Test
    void fallbackJdbcWriteWithoutATransactionCommitsImmediatelyAndIsNotAnAfterCommitFinding() throws Exception {
        try (JdbcFixture fixture = new JdbcFixture()) {
            fixture.request(() -> fixture.context.publishEvent(new OrderPlaced(1)));

            assertThat(fixture.jdbc.queryForObject("select count(*) from event_audit", Integer.class))
                    .isEqualTo(1);
            assertThat(fixture.findings())
                    .as("a committed fallback write is not a write after commit")
                    .isEmpty();
            assertThat(fixture.listeners())
                    .extracting(AppEventPayload::listener, AppEventPayload::phase, AppEventPayload::outcome)
                    .contains(tuple("JdbcListeners#fallback", AppEventPayload.IMMEDIATE, AppEventPayload.RAN));
        }
    }

    @Test
    void genuineAfterCommitJdbcWriteStillProducesTheFinding() throws Exception {
        try (JdbcFixture fixture = new JdbcFixture()) {
            fixture.request(() -> fixture.transactions.executeWithoutResult(
                    status -> fixture.context.publishEvent(new OrderPlaced(2))));

            assertThat(fixture.listeners())
                    .extracting(AppEventPayload::listener, AppEventPayload::phase, AppEventPayload::outcome)
                    .contains(
                            tuple("JdbcListeners#fallback", "AFTER_COMMIT", AppEventPayload.DEFERRED),
                            tuple("JdbcListeners#fallback", "AFTER_COMMIT", AppEventPayload.RAN));
            assertThat(fixture.findings())
                    .anySatisfy(finding -> assertThat(finding.sentence())
                            .contains("AFTER_COMMIT listener `JdbcListeners#fallback` ran 1 write"));
        }
    }

    @Test
    void fallbackDisabledListenerDoesNotExecuteOrProduceAnAfterCommitFindingWithoutATransaction() throws Exception {
        try (JdbcFixture fixture = new JdbcFixture()) {
            fixture.request(() -> fixture.context.publishEvent(new OrderPlaced(3)));

            assertThat(fixture.context.getBean(JdbcListeners.class).strictRuns).isZero();
            assertThat(fixture.listeners())
                    .extracting(AppEventPayload::listener, AppEventPayload::phase, AppEventPayload::outcome)
                    .contains(tuple("JdbcListeners#strict", "AFTER_COMMIT", AppEventPayload.SKIPPED_NO_TRANSACTION));
            assertThat(fixture.findings())
                    .noneSatisfy(finding -> assertThat(finding.sentence()).contains("JdbcListeners#strict"));
        }
    }

    @Test
    void reactiveEventSourceDefersBothListenersAndRecordsTheirActualCommitCallbacks() throws Exception {
        try (JdbcFixture fixture = new JdbcFixture()) {
            TransactionalOperator transactions = TransactionalOperator.create(new ReactiveTransactions());
            fixture.request(() -> transactions
                    .execute(status -> new TransactionalEventPublisher(fixture.context)
                            .publishEvent(new OrderPlaced(4))
                            .then(Mono.fromRunnable(() -> {
                                assertThat(fixture.context.getBean(JdbcListeners.class).strictRuns)
                                        .isZero();
                                assertThat(fixture.jdbc.queryForObject(
                                                "select count(*) from event_audit", Integer.class))
                                        .isZero();
                            })))
                    .blockLast(Duration.ofSeconds(5)));

            assertThat(fixture.listeners())
                    .extracting(AppEventPayload::listener, AppEventPayload::phase, AppEventPayload::outcome)
                    .containsExactlyInAnyOrder(
                            tuple("JdbcListeners#fallback", "AFTER_COMMIT", AppEventPayload.DEFERRED),
                            tuple("JdbcListeners#strict", "AFTER_COMMIT", AppEventPayload.DEFERRED),
                            tuple("JdbcListeners#fallback", "AFTER_COMMIT", AppEventPayload.RAN),
                            tuple("JdbcListeners#strict", "AFTER_COMMIT", AppEventPayload.RAN));
            assertThat(fixture.context.getBean(JdbcListeners.class).strictRuns).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("select count(*) from event_audit", Integer.class))
                    .isEqualTo(1);
            assertThat(fixture.findings())
                    .singleElement()
                    .satisfies(finding ->
                            assertThat(finding.whatToCheck().get(0)).contains("timing alone does not prove"));
        }
    }

    @Test
    void anInactiveReactiveEventSourceDoesNotDeferFallbackOrRunTheStrictListener() throws Exception {
        try (JdbcFixture fixture = new JdbcFixture()) {
            fixture.request(() -> TransactionContextManager.currentContext()
                    .doOnNext(source ->
                            fixture.context.publishEvent(new PayloadApplicationEvent<>(source, new OrderPlaced(7))))
                    .contextWrite(TransactionContextManager.createTransactionContext())
                    .block(Duration.ofSeconds(5)));

            assertThat(fixture.listeners())
                    .extracting(AppEventPayload::listener, AppEventPayload::phase, AppEventPayload::outcome)
                    .containsExactlyInAnyOrder(
                            tuple("JdbcListeners#fallback", AppEventPayload.IMMEDIATE, AppEventPayload.RAN),
                            tuple("JdbcListeners#strict", "AFTER_COMMIT", AppEventPayload.SKIPPED_NO_TRANSACTION));
            assertThat(fixture.jdbc.queryForObject("select count(*) from event_audit", Integer.class))
                    .isEqualTo(1);
            assertThat(fixture.findings()).isEmpty();
        }
    }

    @Test
    void imperativeTransactionTakesPrecedenceEvenWhenTheEventCarriesAnActiveReactiveTransaction() throws Exception {
        try (JdbcFixture fixture = new JdbcFixture()) {
            fixture.request(() -> TransactionalOperator.create(new ReactiveTransactions())
                    .execute(status -> TransactionContextManager.currentContext()
                            .doOnNext(source -> {
                                fixture.transactions.executeWithoutResult(ignored -> {
                                    fixture.context.publishEvent(
                                            new PayloadApplicationEvent<>(source, new OrderPlaced(8)));
                                    assertThat(fixture.context.getBean(JdbcListeners.class).strictRuns)
                                            .isZero();
                                });
                                assertThat(fixture.context.getBean(JdbcListeners.class).strictRuns)
                                        .as("the listener ran at imperative completion, before reactive completion")
                                        .isEqualTo(1);
                            }))
                    .blockLast(Duration.ofSeconds(5)));

            assertThat(fixture.listeners())
                    .extracting(AppEventPayload::listener, AppEventPayload::phase, AppEventPayload::outcome)
                    .containsExactlyInAnyOrder(
                            tuple("JdbcListeners#fallback", "AFTER_COMMIT", AppEventPayload.DEFERRED),
                            tuple("JdbcListeners#strict", "AFTER_COMMIT", AppEventPayload.DEFERRED),
                            tuple("JdbcListeners#fallback", "AFTER_COMMIT", AppEventPayload.RAN),
                            tuple("JdbcListeners#strict", "AFTER_COMMIT", AppEventPayload.RAN));
        }
    }

    @ParameterizedTest
    @EnumSource(TransactionPhase.class)
    void preservesEveryImperativeTransactionPhase(TransactionPhase phase) throws Exception {
        verifyPhase(phase, false, false);
        verifyPhase(phase, false, true);
    }

    @ParameterizedTest
    @EnumSource(TransactionPhase.class)
    void preservesEveryReactiveTransactionPhase(TransactionPhase phase) throws Exception {
        verifyPhase(phase, true, false);
        verifyPhase(phase, true, true);
    }

    private void verifyPhase(TransactionPhase phase, boolean reactive, boolean rollback) throws Exception {
        try (JdbcFixture fixture = new JdbcFixture()) {
            AtomicInteger runs = new AtomicInteger();
            fixture.context.addApplicationListener(
                    TransactionalApplicationListener.<OrderPlaced>forPayload(phase, event -> runs.incrementAndGet()));
            fixture.request(() -> {
                if (reactive) {
                    TransactionalOperator.create(new ReactiveTransactions())
                            .execute(status -> {
                                if (rollback) {
                                    status.setRollbackOnly();
                                }
                                return new TransactionalEventPublisher(fixture.context)
                                        .publishEvent(new OrderPlaced(5));
                            })
                            .blockLast(Duration.ofSeconds(5));
                } else {
                    fixture.transactions.executeWithoutResult(status -> {
                        if (rollback) {
                            status.setRollbackOnly();
                        }
                        fixture.context.publishEvent(new OrderPlaced(5));
                    });
                }
            });
            boolean ran = rollback
                    ? phase == TransactionPhase.AFTER_ROLLBACK || phase == TransactionPhase.AFTER_COMPLETION
                    : phase != TransactionPhase.AFTER_ROLLBACK;
            assertThat(runs.get()).isEqualTo(ran ? 1 : 0);
            assertThat(fixture.listeners())
                    .filteredOn(event -> "TransactionalApplicationListenerAdapter".equals(event.listener()))
                    .extracting(AppEventPayload::phase, AppEventPayload::outcome)
                    .containsExactlyInAnyOrderElementsOf(
                            ran
                                    ? List.of(
                                            tuple(phase.name(), AppEventPayload.DEFERRED),
                                            tuple(phase.name(), AppEventPayload.RAN))
                                    : List.of(tuple(phase.name(), AppEventPayload.DEFERRED)));
        }
    }

    @Test
    void nestedPublicationKeepsImmediateFallbackRunsAndTheirCorrelation() throws Exception {
        try (JdbcFixture fixture = new JdbcFixture()) {
            fixture.context.addApplicationListener(ApplicationListener.forPayload((OrderPlaced event) -> {
                if (event.id() == 10) {
                    fixture.context.publishEvent(new OrderPlaced(11));
                }
            }));
            fixture.request(() -> fixture.context.publishEvent(new OrderPlaced(10)));

            assertThat(fixture.jdbc.queryForObject("select count(*) from event_audit", Integer.class))
                    .isEqualTo(2);
            assertThat(fixture.listeners())
                    .filteredOn(event -> "JdbcListeners#fallback".equals(event.listener()))
                    .extracting(AppEventPayload::phase, AppEventPayload::outcome)
                    .containsExactly(
                            tuple(AppEventPayload.IMMEDIATE, AppEventPayload.RAN),
                            tuple(AppEventPayload.IMMEDIATE, AppEventPayload.RAN));
            assertThat(fixture.findings()).isEmpty();
            assertThat(fixture.journal.entries())
                    .filteredOn(entry -> entry.event().source() == JournalSource.APP_EVENT)
                    .allSatisfy(entry -> assertThat(entry.event().requestId()).isEqualTo("event-request"));
        }
    }

    @Test
    void executorDeliveryKeepsImmediateRunsAndThePropagatedRequest() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (JdbcFixture fixture = new JdbcFixture()) {
            fixture.context.getBean(BootUiApplicationEventMulticaster.class).setTaskExecutor(command -> {
                CorrelationContext correlation = BootUiCorrelation.current();
                executor.execute(() -> {
                    try (BootUiCorrelation.Scope ignored = BootUiCorrelation.openPropagated(correlation)) {
                        command.run();
                    }
                });
            });
            fixture.context.addApplicationListener(ApplicationListener.forPayload((OrderPlaced event) ->
                    fixture.jdbc.update("insert into event_audit values (?)", event.id() + 100)));
            fixture.request(() -> fixture.context.publishEvent(new OrderPlaced(6)));
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(fixture.journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

            assertThat(fixture.jdbc.queryForObject("select count(*) from event_audit", Integer.class))
                    .isEqualTo(2);
            assertThat(fixture.listeners())
                    .filteredOn(event -> AppEventPayload.RAN.equals(event.outcome()))
                    .extracting(AppEventPayload::phase)
                    .containsOnly(AppEventPayload.IMMEDIATE);
            assertThat(fixture.journal.entries())
                    .filteredOn(entry -> entry.event().source() == JournalSource.APP_EVENT)
                    .allSatisfy(entry -> assertThat(entry.event().requestId()).isEqualTo("event-request"));
            assertThat(fixture.findings()).isEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

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
                            tuple(AppEventPayload.LISTENER, "Listeners#fallback", "IMMEDIATE", "RAN"),
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

    private static final class JdbcFixture implements AutoCloseable {

        final RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
                RunIdentity.start());
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;

        JdbcFixture() {
            SqlTraceRecorder sql = new SqlTraceRecorder(true, true, false, false, 100, 100, 256, 128, 5);
            sql.setRuntimeEventSink(journal);
            DataSource traced = SqlTracingProxies.wrap(database, sql);
            jdbc = new JdbcTemplate(traced);
            jdbc.execute("create table event_audit (id bigint)");
            context.registerBean(DataSource.class, () -> traced);
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(
                    "applicationEventMulticaster",
                    BootUiApplicationEventMulticaster.class,
                    () -> new BootUiApplicationEventMulticaster(context.getBeanFactory(), () -> journal));
            context.register(JdbcListeners.class);
            context.refresh();
            DataSourceTransactionManager manager = context.getBean(DataSourceTransactionManager.class);
            TransactionRecorder recorder = new TransactionRecorder(true, true, 100, 100, 100, sql);
            recorder.setRuntimeEventSink(journal);
            manager.setTransactionExecutionListeners(List.of(new BootUiTransactionExecutionListener(recorder)));
            transactions = new TransactionTemplate(manager);
        }

        void request(Runnable action) throws InterruptedException {
            CorrelationContext correlation = CorrelationContext.forRequest("event-request");
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(correlation)) {
                long start = System.nanoTime();
                action.run();
                journal.offer(RuntimeEvent.of(
                        JournalSource.HTTP,
                        System.currentTimeMillis(),
                        System.nanoTime() - start,
                        correlation,
                        Thread.currentThread().getName(),
                        null,
                        false,
                        new HttpPayload("POST", "/events", "/events", null, 200)));
            }
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        }

        List<AppEventPayload> listeners() {
            return journal.entries().stream()
                    .map(entry -> entry.event().payload())
                    .filter(AppEventPayload.class::isInstance)
                    .map(AppEventPayload.class::cast)
                    .filter(event -> AppEventPayload.LISTENER.equals(event.kind()))
                    .toList();
        }

        List<Finding> findings() {
            return new AfterCommitWrites()
                    .evaluate(InsightsSnapshot.of(
                            journal.entries(),
                            journal.status(),
                            null,
                            source -> true,
                            source -> true,
                            InsightsStack.SPRING_MVC,
                            null))
                    .findings();
        }

        @Override
        public void close() {
            context.close();
            database.shutdown();
            journal.close();
        }
    }

    private static final class ReactiveTransactions extends AbstractReactiveTransactionManager {

        @Override
        protected Object doGetTransaction(
                org.springframework.transaction.reactive.TransactionSynchronizationManager manager) {
            return new Object();
        }

        @Override
        protected Mono<Void> doBegin(
                org.springframework.transaction.reactive.TransactionSynchronizationManager manager,
                Object transaction,
                TransactionDefinition definition) {
            return Mono.empty();
        }

        @Override
        protected Mono<Void> doCommit(
                org.springframework.transaction.reactive.TransactionSynchronizationManager manager,
                GenericReactiveTransaction status) {
            return Mono.empty();
        }

        @Override
        protected Mono<Void> doRollback(
                org.springframework.transaction.reactive.TransactionSynchronizationManager manager,
                GenericReactiveTransaction status) {
            return Mono.empty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class JdbcListeners {

        private final JdbcTemplate jdbc;
        int strictRuns;

        JdbcListeners(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        void fallback(OrderPlaced event) {
            jdbc.update("insert into event_audit values (?)", event.id());
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void strict(OrderPlaced event) {
            strictRuns++;
        }
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
