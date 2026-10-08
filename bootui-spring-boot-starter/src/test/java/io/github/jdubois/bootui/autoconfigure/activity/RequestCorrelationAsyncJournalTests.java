package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Spring MVC: a request whose handler goes asynchronous is journalled once, when its response really completes, with
 * the status it answered and the time it took, on a real servlet container whose async listeners fire as in
 * production.
 */
@SpringBootTest(
        classes = RequestCorrelationAsyncJournalTests.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bootui.enabled=OFF", "spring.main.web-application-type=servlet"})
class RequestCorrelationAsyncJournalTests {

    static final long DELAY_MILLIS = 300;

    static final List<RuntimeEvent> PUBLISHED = new CopyOnWriteArrayList<>();

    static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor();

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @AfterAll
    static void stopScheduler() {
        LATER.shutdownNow();
    }

    @BeforeEach
    void clear() {
        PUBLISHED.clear();
    }

    @Test
    void aDelayedSuccessIsJournalledWhenItsResponseCompletes() throws Exception {
        assertThat(get("/async/ok")).isEqualTo(200);

        RuntimeEvent event = onlyEventFor("/async/ok");
        assertThat(status(event)).isEqualTo(200);
        assertThat(event.durationNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS));
        assertThat(event.failedOrSlow()).isFalse();
        assertThat(((HttpPayload) event.payload()).asyncStarted()).isTrue();
        assertThat(((HttpPayload) event.payload()).routeTemplate()).isEqualTo("/async/ok");
        assertThat(event.requestId()).isNotNull();
    }

    @Test
    void aDelayedNonSuccessStatusIsTheOneJournalled() throws Exception {
        assertThat(get("/async/unavailable")).isEqualTo(503);

        RuntimeEvent event = onlyEventFor("/async/unavailable");
        assertThat(status(event)).isEqualTo(503);
        assertThat(event.durationNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS));
        assertThat(event.failedOrSlow()).isTrue();
    }

    @Test
    void aDelayedFailureIsJournalledAsAServerError() throws Exception {
        assertThat(get("/async/fail")).isEqualTo(500);

        RuntimeEvent event = onlyEventFor("/async/fail");
        assertThat(status(event)).isEqualTo(500);
        assertThat(event.durationNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS));
        assertThat(event.failedOrSlow()).isTrue();
    }

    @Test
    void anAsyncTimeoutIsJournalledWithTheStatusItAnswered() throws Exception {
        assertThat(get("/async/timeout")).isEqualTo(503);

        RuntimeEvent event = onlyEventFor("/async/timeout");
        assertThat(status(event)).isEqualTo(503);
        assertThat(event.durationNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS));
        assertThat(event.failedOrSlow()).isTrue();
    }

    @Test
    void aRequestThatGoesAsyncTwiceIsJournalledOnceAtItsEnd() throws Exception {
        assertThat(get("/async/twice")).isEqualTo(202);

        RuntimeEvent event = onlyEventFor("/async/twice");
        assertThat(status(event)).isEqualTo(202);
        assertThat(event.durationNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(2 * DELAY_MILLIS));
    }

    @Test
    void aSynchronousRequestIsStillJournalledOnceWithoutTheAsyncMark() throws Exception {
        assertThat(get("/sync")).isEqualTo(200);

        RuntimeEvent event = onlyEventFor("/sync");
        assertThat(status(event)).isEqualTo(200);
        assertThat(((HttpPayload) event.payload()).asyncStarted()).isFalse();
    }

    private int get(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /** Waits for the path's event, then for any second one a later dispatch would publish. */
    private static RuntimeEvent onlyEventFor(String path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (eventsFor(path).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(2 * DELAY_MILLIS);
        List<RuntimeEvent> events = eventsFor(path);
        assertThat(events).as("one journal row for " + path).hasSize(1);
        return events.get(0);
    }

    private static List<RuntimeEvent> eventsFor(String path) {
        return PUBLISHED.stream()
                .filter(event -> event.source() == JournalSource.HTTP)
                .filter(event -> event.payload() instanceof HttpPayload http && path.equals(http.path()))
                .toList();
    }

    private static int status(RuntimeEvent event) {
        return ((HttpPayload) event.payload()).status();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
            })
    static class TestApplication {

        @Bean
        FilterRegistrationBean<RequestCorrelationFilter> requestCorrelationFilter() {
            RequestCorrelationFilter filter = new RequestCorrelationFilter(
                    new RequestCorrelationRegistry(50),
                    new HttpExchangeTraceRegistry(50),
                    "/bootui",
                    null,
                    5_000,
                    new RequestPhases());
            filter.setRuntimeEventSink(PUBLISHED::add);
            FilterRegistrationBean<RequestCorrelationFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setAsyncSupported(true);
            return registration;
        }

        @Bean
        AsyncController asyncController() {
            return new AsyncController();
        }
    }

    @RestController
    static class AsyncController {

        @GetMapping("/sync")
        String sync() {
            return "ok";
        }

        @GetMapping("/async/ok")
        DeferredResult<String> ok() {
            DeferredResult<String> result = new DeferredResult<>();
            LATER.schedule(() -> result.setResult("ok"), DELAY_MILLIS, TimeUnit.MILLISECONDS);
            return result;
        }

        @GetMapping("/async/unavailable")
        DeferredResult<ResponseEntity<String>> unavailable() {
            DeferredResult<ResponseEntity<String>> result = new DeferredResult<>();
            LATER.schedule(
                    () -> result.setResult(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body("busy")),
                    DELAY_MILLIS,
                    TimeUnit.MILLISECONDS);
            return result;
        }

        @GetMapping("/async/fail")
        DeferredResult<String> fail() {
            DeferredResult<String> result = new DeferredResult<>();
            LATER.schedule(
                    () -> result.setErrorResult(new IllegalStateException("failed later")),
                    DELAY_MILLIS,
                    TimeUnit.MILLISECONDS);
            return result;
        }

        @GetMapping("/async/timeout")
        DeferredResult<String> timeout() {
            return new DeferredResult<>(DELAY_MILLIS);
        }

        @GetMapping("/async/twice")
        Callable<DeferredResult<ResponseEntity<String>>> twice() {
            return () -> {
                Thread.sleep(DELAY_MILLIS);
                DeferredResult<ResponseEntity<String>> result = new DeferredResult<>();
                LATER.schedule(
                        () -> result.setResult(
                                ResponseEntity.status(HttpStatus.ACCEPTED).body("later")),
                        DELAY_MILLIS,
                        TimeUnit.MILLISECONDS);
                return result;
            };
        }
    }
}
