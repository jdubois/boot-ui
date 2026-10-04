package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.async.ExceptionHandlingExecutor;
import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiReactiveAutoConfiguration;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** M4-22: the application's own executors run a request's tasks as its executions, without losing their decorator. */
class BootUiExecutorDecorationTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void aJHipsterShapedExecutorBeanThatWrapsItsPoolRunsTasksAsExecutionsOfTheirRequest() {
        runner.withBean("taskExecutor", Executor.class, () -> new ExceptionHandlingExecutor(pool()))
                .run(context -> {
                    Executor executor = context.getBean("taskExecutor", Executor.class);

                    CorrelationContext task = runAsRequest(executor, "r1");

                    assertThat(task.requestId()).isEqualTo("r1");
                    assertThat(task.executionId()).startsWith("task-");
                });
    }

    @Test
    void anApplicationsOwnPoolsAndSchedulerAreDecoratedAndItsDecoratorIsComposedNeverReplaced() {
        AtomicInteger applicationDecorations = new AtomicInteger();
        TaskDecorator application = runnable -> {
            applicationDecorations.incrementAndGet();
            return () -> runnable.run();
        };
        runner.withBean("plainPool", ThreadPoolTaskExecutor.class, BootUiExecutorDecorationTests::pool)
                .withBean("decoratedPool", ThreadPoolTaskExecutor.class, () -> {
                    ThreadPoolTaskExecutor pool = pool();
                    pool.setTaskDecorator(application);
                    // Applications often initialize the pool themselves; Spring reads the decorator per task.
                    pool.initialize();
                    return pool;
                })
                .withBean("appScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                .run(context -> {
                    assertThat(runAsRequest(context.getBean("plainPool", Executor.class), "r1")
                                    .executionId())
                            .startsWith("task-");

                    CorrelationContext composed = runAsRequest(context.getBean("decoratedPool", Executor.class), "r2");
                    assertThat(composed.requestId()).isEqualTo("r2");
                    assertThat(composed.executionId()).startsWith("task-");
                    assertThat(applicationDecorations.get())
                            .as("the application's decorator still wraps each task, once")
                            .isEqualTo(1);

                    ThreadPoolTaskScheduler scheduler = context.getBean("appScheduler", ThreadPoolTaskScheduler.class);
                    CompletableFuture<CorrelationContext> seen = new CompletableFuture<>();
                    try (BootUiCorrelation.Scope ignored =
                            BootUiCorrelation.open(CorrelationContext.forRequest("r3"))) {
                        scheduler.schedule(() -> seen.complete(BootUiCorrelation.current()), java.time.Instant.now());
                    }
                    assertThat(seen.get(5, TimeUnit.SECONDS).requestId()).isEqualTo("r3");

                    java.util.List<String> runs = new java.util.concurrent.CopyOnWriteArrayList<>();
                    java.util.concurrent.CountDownLatch twice = new java.util.concurrent.CountDownLatch(2);
                    java.util.concurrent.ScheduledFuture<?> periodic;
                    try (BootUiCorrelation.Scope ignored =
                            BootUiCorrelation.open(CorrelationContext.forRequest("r4"))) {
                        periodic = scheduler.scheduleAtFixedRate(
                                () -> {
                                    runs.add(String.valueOf(
                                            BootUiCorrelation.current().requestId()));
                                    twice.countDown();
                                },
                                java.time.Duration.ofMillis(10));
                    }
                    assertThat(twice.await(5, TimeUnit.SECONDS)).isTrue();
                    periodic.cancel(false);
                    assertThat(runs.subList(0, 2))
                            .as("a periodic task belongs to its request on its first run only")
                            .containsExactly("r4", "null");

                    // A trigger, as a cron @Scheduled or schedule(task, cronTrigger) uses: Spring reschedules and
                    // decorates each next run from the worker, inside the previous one.
                    java.util.List<String> triggered = new java.util.concurrent.CopyOnWriteArrayList<>();
                    java.util.concurrent.CountDownLatch thrice = new java.util.concurrent.CountDownLatch(3);
                    java.util.concurrent.ScheduledFuture<?> cron;
                    try (BootUiCorrelation.Scope ignored =
                            BootUiCorrelation.open(CorrelationContext.forRequest("r5"))) {
                        cron = scheduler.schedule(
                                () -> {
                                    triggered.add(String.valueOf(
                                            BootUiCorrelation.current().requestId()));
                                    thrice.countDown();
                                },
                                new org.springframework.scheduling.support.PeriodicTrigger(
                                        java.time.Duration.ofMillis(10)));
                    }
                    assertThat(thrice.await(5, TimeUnit.SECONDS)).isTrue();
                    cron.cancel(false);
                    assertThat(triggered.subList(0, 3))
                            .as("a trigger's second and third runs are not the request's")
                            .containsExactly("r5", "null", "null");
                });
    }

    @Test
    void bootsCompositeOfBootUisAndAnotherDecoratorComposedAgainPropagatesOnce() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        TaskDecorator counting = runnable -> () -> {
            executions.incrementAndGet();
            runnable.run();
        };
        ThreadPoolTaskExecutor pool = pool();
        pool.setTaskDecorator(new org.springframework.core.task.support.CompositeTaskDecorator(
                java.util.List.of(new BootUiTaskDecorator(), counting)));
        assertThat(BootUiExecutorDecoration.decorate(pool)).isEqualTo(BootUiExecutorDecoration.Outcome.COMPOSED);
        pool.initialize();
        try {
            CompletableFuture<CorrelationContext> seen = new CompletableFuture<>();
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
                pool.execute(() -> seen.complete(BootUiCorrelation.current()));
            }
            CorrelationContext task = seen.get(5, TimeUnit.SECONDS);
            assertThat(task.executionId()).startsWith("task-");
            assertThat(executions.get()).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void anExecutorThatAlreadyCarriesBootUisDecoratorOrIsUnreadableIsLeftAlone() throws Exception {
        ThreadPoolTaskExecutor pool = pool();
        assertThat(BootUiExecutorDecoration.decorate(pool)).isEqualTo(BootUiExecutorDecoration.Outcome.DECORATED);
        assertThat(BootUiExecutorDecoration.decorate(pool)).isEqualTo(BootUiExecutorDecoration.Outcome.ALREADY);

        ThreadPoolTaskExecutor decorated = pool();
        decorated.setTaskDecorator(runnable -> runnable);
        assertThat(BootUiExecutorDecoration.decorate(decorated)).isEqualTo(BootUiExecutorDecoration.Outcome.COMPOSED);
        assertThat(BootUiExecutorDecoration.decorate(decorated)).isEqualTo(BootUiExecutorDecoration.Outcome.ALREADY);

        ThreadPoolTaskExecutor initialized = pool();
        initialized.initialize();
        try {
            assertThat(BootUiExecutorDecoration.decorate(initialized))
                    .isEqualTo(BootUiExecutorDecoration.Outcome.DECORATED);
            assertThat(runAsRequest(initialized, "r9").executionId())
                    .as("Spring reads the decorator per task, so a pool the application already started gets it")
                    .startsWith("task-");
        } finally {
            initialized.shutdown();
        }

        assertThat(BootUiExecutorDecoration.decorate(new SimpleAsyncTaskExecutor()))
                .isEqualTo(BootUiExecutorDecoration.Outcome.DECORATED);
        assertThat(BootUiExecutorDecoration.pools(new Object())).isEmpty();
        assertThat(BootUiExecutorDecoration.pools((Executor) Runnable::run)).isEmpty();
    }

    @Test
    void outsideARequestATaskIsUntouchedAndWithBootUiOffNothingIsDecorated() {
        runner.withBean("plainPool", ThreadPoolTaskExecutor.class, BootUiExecutorDecorationTests::pool)
                .run(context -> {
                    CompletableFuture<CorrelationContext> seen = new CompletableFuture<>();
                    context.getBean("plainPool", Executor.class)
                            .execute(() -> seen.complete(BootUiCorrelation.current()));
                    assertThat(seen.get(5, TimeUnit.SECONDS).requestId()).isNull();
                });
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=OFF")
                .withBean("plainPool", ThreadPoolTaskExecutor.class, BootUiExecutorDecorationTests::pool)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BootUiExecutorDecoration.class);
                    assertThat(runAsRequest(context.getBean("plainPool", Executor.class), "r1")
                                    .requestId())
                            .isNull();
                });
    }

    @Test
    void webFluxDecoratesTheApplicationsExecutorsToo() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiReactiveAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .withBean("taskExecutor", Executor.class, () -> new ExceptionHandlingExecutor(pool()))
                .run(context -> assertThat(runAsRequest(context.getBean("taskExecutor", Executor.class), "r1")
                                .executionId())
                        .startsWith("task-"));
    }

    private static ThreadPoolTaskExecutor pool() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setThreadNamePrefix("app-task-");
        return pool;
    }

    private static CorrelationContext runAsRequest(Executor executor, String requestId) throws Exception {
        CompletableFuture<CorrelationContext> seen = new CompletableFuture<>();
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(requestId))) {
            executor.execute(() -> seen.complete(BootUiCorrelation.current()));
        }
        return seen.get(5, TimeUnit.SECONDS);
    }
}
