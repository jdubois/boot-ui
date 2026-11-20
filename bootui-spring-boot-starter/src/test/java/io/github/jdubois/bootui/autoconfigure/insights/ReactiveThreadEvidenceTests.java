package io.github.jdubois.bootui.autoconfigure.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.reactive.BootUiCorrelationThreadLocalAccessor;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveRequestCorrelationFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveThreadKinds;
import io.github.jdubois.bootui.autoconfigure.restclienttrace.RestClientTraceExchangeFilter;
import io.github.jdubois.bootui.autoconfigure.restclienttrace.RestClientTraceInterceptor;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.EventLoopBlocking;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.ProxyBypass;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.LoopResources;

class ReactiveThreadEvidenceTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()), RunIdentity.start());
    private final List<RuntimeEvent> captured = new CopyOnWriteArrayList<>();
    private final CountDownLatch completed = new CountDownLatch(6);
    private final ReactiveRequestCorrelationFilter correlation =
            new ReactiveRequestCorrelationFilter(new BootUiProperties());

    @BeforeEach
    void capture() {
        BootUiCorrelationThreadLocalAccessor.register();
        Hooks.enableAutomaticContextPropagation();
        correlation.setRuntimeEventSink(
                event -> {
                    captured.add(event);
                    boolean accepted = journal.offer(event);
                    if (event.payload() instanceof HttpPayload) {
                        completed.countDown();
                    }
                    return accepted;
                },
                1_000,
                null);
    }

    @AfterEach
    void close() {
        journal.close();
        Hooks.disableAutomaticContextPropagation();
        BootUiCorrelationThreadLocalAccessor.unregister();
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }

    @Test
    void realBlockingClientsOnANettyLoopDifferFromOffloadsAndReactiveCompletions() throws Exception {
        AtomicInteger remoteCalls = new AtomicInteger();
        HttpServer remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/remote", exchange -> {
            remoteCalls.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        remote.start();
        LoopResources loops = LoopResources.create("reactive-evidence", 1, true);
        var worker = Schedulers.newBoundedElastic(1, 10, "reactive-evidence-worker");
        try {
            String target = "http://127.0.0.1:" + remote.getAddress().getPort() + "/remote";
            RestClientTraceRecorder recorder =
                    new RestClientTraceRecorder(true, true, false, false, 20, 1_000, 2_000, 200, 5);
            recorder.setCorrelationContextProvider(BootUiCorrelation::current);
            recorder.setThreadKindClassifier(new ReactiveThreadKinds());
            recorder.setRuntimeEventSink(event -> {
                captured.add(event);
                return journal.offer(event);
            });
            SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
            requests.setConnectTimeout(2_000);
            requests.setReadTimeout(2_000);
            RestClient rest = RestClient.builder()
                    .requestFactory(requests)
                    .requestInterceptor(new RestClientTraceInterceptor(recorder, "RestClient"))
                    .build();
            RestTemplate template = new RestTemplate(requests);
            template.setInterceptors(List.of(new RestClientTraceInterceptor(recorder, "RestTemplate")));
            WebClient reactive = WebClient.builder()
                    .clientConnector(
                            new ReactorClientHttpConnector(HttpClient.create().runOn(loops)))
                    .filter(new RestClientTraceExchangeFilter(recorder))
                    .build();
            var handler = WebHttpHandlerBuilder.webHandler(exchange -> {
                        String path = exchange.getRequest().getPath().value();
                        Mono<String> call = path.startsWith("/reactive")
                                ? reactive.get().uri(target).retrieve().bodyToMono(String.class)
                                : Mono.fromCallable(() -> path.startsWith("/template")
                                        ? template.getForObject(target, String.class)
                                        : rest.get().uri(target).retrieve().body(String.class));
                        if (path.endsWith("/worker")) {
                            call = call.subscribeOn(worker);
                        }
                        return call.then(exchange.getResponse().setComplete());
                    })
                    .filter(correlation)
                    .build();
            var server = reactor.netty.http.server.HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .runOn(loops)
                    .handle(new ReactorHttpHandlerAdapter(correlation.apply(handler)))
                    .bindNow(TIMEOUT);
            try {
                for (String path : List.of(
                        "/rest/loop",
                        "/rest/worker",
                        "/template/loop",
                        "/template/worker",
                        "/reactive/loop",
                        "/reactive/worker")) {
                    Integer status = HttpClient.create()
                            .get()
                            .uri("http://127.0.0.1:" + server.port() + path)
                            .responseSingle((response, bytes) ->
                                    bytes.thenReturn(response.status().code()))
                            .block(TIMEOUT);
                    assertThat(status).isEqualTo(200);
                }
                assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(journal.awaitDrained(TIMEOUT)).isTrue();
                assertThat(remoteCalls).hasValue(6);
                assertThat(journal.entries().stream()
                                .filter(entry -> entry.event().payload() instanceof HttpPayload)
                                .toList())
                        .hasSize(6);
                assertThat(journal.entries().stream()
                                .filter(entry -> entry.event().payload() instanceof RestClientPayload)
                                .toList())
                        .hasSize(6);
                assertThat(captured.stream()
                                .filter(event -> event.payload() instanceof RestClientPayload)
                                .toList())
                        .hasSize(6)
                        .allSatisfy(event -> assertThat(event.requestId()).isNotBlank());
                for (RuntimeEvent request : captured.stream()
                        .filter(event -> event.payload() instanceof HttpPayload)
                        .toList()) {
                    String path = ((HttpPayload) request.payload()).path();
                    RuntimeEvent call = captured.stream()
                            .filter(event -> event.payload() instanceof RestClientPayload
                                    && request.requestId().equals(event.requestId()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(call.threadKind())
                            .isEqualTo(path.endsWith("/worker") ? ThreadKind.WORKER : ThreadKind.EVENT_LOOP);
                    if (path.startsWith("/reactive")) {
                        assertThat(call.thread()).contains("reactive-evidence").doesNotContain("worker");
                    }
                }
                RuntimeInsightsService service =
                        new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_WEBFLUX, null);
                var report = service.report();
                assertThat(report.observations())
                        .filteredOn(row -> row.kind().equals(EventLoopBlocking.KIND))
                        .extracting(row -> row.subject())
                        .containsExactlyInAnyOrder("GET /rest/loop", "GET /template/loop");
                service.report();
                assertThat(remoteCalls).as("advisor reads issue no HTTP calls").hasValue(6);
            } finally {
                server.disposeNow(TIMEOUT);
            }
        } finally {
            worker.dispose();
            loops.disposeLater().block(TIMEOUT);
            remote.stop(0);
        }
    }

    @Test
    void aRealAsyncProxyAndWebFluxCompletionCanShareANameWithoutSharingAThread() throws Exception {
        LoopResources loops = LoopResources.create("reactive-async-evidence", 1, true);
        AtomicReference<Thread> serving = new AtomicReference<>();
        AtomicReference<Thread> executing = new AtomicReference<>();
        AtomicReference<Thread> finishing = new AtomicReference<>();
        CountDownLatch requestRecorded = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor(
                task -> new Thread(task, serving.get().getName()));
        try {
            ProxyFactory factory = new ProxyFactory(new AsyncWork());
            factory.setProxyTargetClass(true);
            factory.addAdvisor(new AsyncAnnotationAdvisor(executor, null));
            AsyncWork proxy = (AsyncWork) factory.getProxy();
            correlation.setRuntimeEventSink(
                    event -> {
                        captured.add(event);
                        boolean accepted = journal.offer(event);
                        if (event.payload() instanceof HttpPayload) {
                            finishing.set(Thread.currentThread());
                            requestRecorded.countDown();
                        }

                        return accepted;
                    },
                    1_000,
                    null);
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/ship").build());
            var scheduler = Schedulers.fromExecutor(loops.onServer(false).next());
            try {
                correlation
                        .filter(
                                exchange,
                                ignored -> Mono.<Void>create(sink -> {
                                    serving.set(Thread.currentThread());
                                    proxy.run(journal, BootUiCorrelation.current(), executing, allowCompletion)
                                            .whenComplete((value, error) -> {
                                                if (error == null) {
                                                    sink.success();
                                                } else {
                                                    sink.error(error);
                                                }
                                            });
                                    allowCompletion.countDown();
                                }))
                        .subscribeOn(scheduler)
                        .block(TIMEOUT);
                assertThat(requestRecorded.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(journal.awaitDrained(TIMEOUT)).isTrue();
                assertThat(executing.get()).isNotSameAs(serving.get());
                assertThat(executing.get().getName()).isEqualTo(serving.get().getName());
                assertThat(finishing.get()).isSameAs(executing.get());
                assertThat(journal.entries())
                        .filteredOn(entry -> entry.event().payload() instanceof SqlPayload)
                        .singleElement()
                        .satisfies(entry -> {
                            assertThat(entry.event().requestId()).isNotBlank();
                            assertThat(((SqlPayload) entry.event().payload())
                                            .frames()
                                            .frames())
                                    .hasSize(1);
                            assertThat(new SpringProxyBoundaries(null)
                                            .resolve(((SqlPayload) entry.event().payload())
                                                    .frames()
                                                    .frames()
                                                    .get(0)))
                                    .isNotNull()
                                    .satisfies(boundary ->
                                            assertThat(boundary.async()).isTrue());
                        });
                RuntimeInsightsService service =
                        new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_WEBFLUX, null);
                service.setProxyBoundaries(new SpringProxyBoundaries(null));
                var report = service.report();
                assertThat(report.window().requests()).isEqualTo(1);
                assertThat(report.observations())
                        .as("async completion names do not prove missing advice")
                        .noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            } finally {
                scheduler.dispose();
            }
        } finally {
            allowCompletion.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            loops.disposeLater().block(TIMEOUT);
        }
    }

    @Test
    void r2RealMatrixSecretVariantsShareOneRecurringSiteAcrossExposureChanges() throws Exception {
        AtomicInteger remoteCalls = new AtomicInteger();
        HttpServer remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/", exchange -> {
            remoteCalls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        remote.start();
        LoopResources loops = LoopResources.create("reactive-matrix-evidence", 1, true);
        CountDownLatch requestsRecorded = new CountDownLatch(3);
        correlation.setRuntimeEventSink(
                event -> {
                    captured.add(event);
                    boolean accepted = journal.offer(event);
                    if (event.payload() instanceof HttpPayload) {
                        requestsRecorded.countDown();
                    }
                    return accepted;
                },
                1_000,
                null);
        try {
            RestClientTraceRecorder recorder =
                    new RestClientTraceRecorder(true, true, false, false, 10, 1_000, 2_000, 200, 5);
            recorder.setCorrelationContextProvider(BootUiCorrelation::current);
            recorder.setThreadKindClassifier(new ReactiveThreadKinds());
            recorder.setRuntimeEventSink(journal);
            SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
            requests.setConnectTimeout(2_000);
            requests.setReadTimeout(2_000);
            RestClient rest = RestClient.builder()
                    .requestFactory(requests)
                    .requestInterceptor(new RestClientTraceInterceptor(recorder, "RestClient"))
                    .build();
            AtomicInteger token = new AtomicInteger();
            String target = "http://127.0.0.1:" + remote.getAddress().getPort() + "/remote;token=synthetic-secret-";
            var handler = WebHttpHandlerBuilder.webHandler(exchange -> Mono.fromRunnable(() -> rest.get()
                                    .uri(target + token.incrementAndGet())
                                    .retrieve()
                                    .toBodilessEntity())
                            .then(exchange.getResponse().setComplete()))
                    .filter(correlation)
                    .build();
            var server = reactor.netty.http.server.HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .runOn(loops)
                    .handle(new ReactorHttpHandlerAdapter(correlation.apply(handler)))
                    .bindNow(TIMEOUT);
            try {
                for (int i = 0; i < 3; i++) {
                    Integer status = HttpClient.create()
                            .get()
                            .uri("http://127.0.0.1:" + server.port() + "/matrix")
                            .responseSingle((response, bytes) ->
                                    bytes.thenReturn(response.status().code()))
                            .block(TIMEOUT);
                    assertThat(status).isEqualTo(200);
                }
                assertThat(requestsRecorded.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(journal.awaitDrained(TIMEOUT)).isTrue();
                var calls = journal.entries().stream()
                        .map(entry -> entry.event())
                        .filter(event -> event.payload() instanceof RestClientPayload)
                        .toList();
                assertThat(calls).hasSize(3).allSatisfy(event -> {
                    assertThat(event.requestId()).isNotBlank();
                    assertThat(event.threadKind()).isEqualTo(ThreadKind.EVENT_LOOP);
                    assertThat(((RestClientPayload) event.payload()).frames()).isNull();
                });
                assertThat(calls)
                        .extracting(event -> ((RestClientPayload) event.payload()).path())
                        .containsExactlyInAnyOrder(
                                "/remote;token=synthetic-secret-1",
                                "/remote;token=synthetic-secret-2",
                                "/remote;token=synthetic-secret-3");
                assertThat(journal.entries().stream()
                                .filter(entry -> entry.event().payload() instanceof HttpPayload)
                                .toList())
                        .hasSize(3);
                AtomicReference<ValueExposure> mode = new AtomicReference<>(ValueExposure.MASKED);
                RuntimeInsightsService service =
                        new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_WEBFLUX, null);
                service.setExposure(new ExposurePolicy() {
                    @Override
                    public ValueExposure valueExposure() {
                        return mode.get();
                    }

                    @Override
                    public boolean maskSecrets() {
                        return true;
                    }
                });
                List<RuntimeInsightsReportDto> reports = new ArrayList<>();
                for (ValueExposure exposure : List.of(
                        ValueExposure.MASKED, ValueExposure.METADATA_ONLY, ValueExposure.FULL, ValueExposure.MASKED)) {
                    mode.set(exposure);
                    reports.add(service.report());
                }
                assertThat(remoteCalls)
                        .as("all exposure/report reads remain passive")
                        .hasValue(3);
                assertThat(reports)
                        .allSatisfy(report -> assertThat(report.observations())
                                .filteredOn(row -> row.kind().equals(EventLoopBlocking.KIND))
                                .singleElement()
                                .satisfies(row -> {
                                    assertThat(row.affected()).isEqualTo(3);
                                    assertThat(row.eligible()).isEqualTo(3);
                                    assertThat(row.sentence()).contains("it recurs");
                                }));
                assertThat(reports.stream()
                                .flatMap(report -> report.observations().stream())
                                .filter(row -> row.kind().equals(EventLoopBlocking.KIND))
                                .map(row -> row.id())
                                .distinct()
                                .toList())
                        .hasSize(1);
            } finally {
                server.disposeNow(TIMEOUT);
            }
        } finally {
            loops.disposeLater().block(TIMEOUT);
            remote.stop(0);
        }
    }

    public static class AsyncWork {

        @Async
        public CompletableFuture<Void> run(
                RuntimeJournal journal,
                CorrelationContext context,
                AtomicReference<Thread> executing,
                CountDownLatch allowCompletion) {
            try {
                if (!allowCompletion.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("The async callback was not registered.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(ex);
            }
            executing.set(Thread.currentThread());
            List<String> frames = new ArrayList<>();
            Arrays.stream(Thread.currentThread().getStackTrace())
                    .filter(frame -> frame.getClassName().equals(AsyncWork.class.getName()))
                    .map(StackTraceElement::toString)
                    .forEach(frames::add);
            journal.offer(RuntimeEvent.of(
                    JournalSource.SQL,
                    System.currentTimeMillis(),
                    0,
                    context,
                    Thread.currentThread().getName(),
                    null,
                    false,
                    new SqlPayload(
                            "select 1", null, "db", false, ApplicationFrames.of(frames), null, System.nanoTime())));
            return CompletableFuture.completedFuture(null);
        }
    }
}
