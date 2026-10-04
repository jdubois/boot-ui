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
                });
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
