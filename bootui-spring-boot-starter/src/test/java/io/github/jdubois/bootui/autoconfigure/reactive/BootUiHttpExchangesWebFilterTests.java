package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.web.BootUiHttpExchangeRepository;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.Include;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

@ExtendWith(OutputCaptureExtension.class)
class BootUiHttpExchangesWebFilterTests {

    private static BootUiHttpExchangesWebFilter recordingFilter(
            HttpExchangeRepository repository, Set<Include> includes) {
        return new BootUiHttpExchangesWebFilter(
                repository, includes, new BootUiProperties(), BootUiSelfDataFilter.defaults());
    }

    private static HttpExchangeRepository failingRepository() {
        return new HttpExchangeRepository() {
            @Override
            public List<HttpExchange> findAll() {
                return List.of();
            }

            @Override
            public void add(HttpExchange exchange) {
                throw new IllegalStateException("secret-repository-message");
            }
        };
    }

    private static Mono<Void> writeBody(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.ACCEPTED);
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap("complete".getBytes(StandardCharsets.UTF_8))));
    }

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().setComplete();
    };

    private static void perform(BootUiHttpExchangesWebFilter filter, MockServerHttpRequest request) {
        filter.filter(MockServerWebExchange.from(request), OK_CHAIN).block(Duration.ofSeconds(5));
    }

    private static List<String> recordedPaths(BootUiHttpExchangeRepository repository) {
        return repository.findAll().stream()
                .map(HttpExchange::getRequest)
                .map(request -> request.getUri().getRawPath()
                        + (request.getUri().getRawQuery() == null
                                ? ""
                                : "?" + request.getUri().getRawQuery()))
                .toList();
    }

    @Test
    void skipsBootUiRequestsBelowTheBasePathButRecordsApplicationRequests() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesWebFilter filter = new BootUiHttpExchangesWebFilter(
                repository, Set.of(Include.REQUEST_HEADERS), new BootUiProperties(), BootUiSelfDataFilter.defaults());

        perform(
                filter,
                MockServerHttpRequest.get("/app/bootui/api/http-exchanges")
                        .contextPath("/app")
                        .build());
        perform(
                filter,
                MockServerHttpRequest.get("/app/api/orders?next=/bootui/api")
                        .contextPath("/app")
                        .build());
        perform(filter, MockServerHttpRequest.get("/bootuix").build());

        assertThat(recordedPaths(repository)).containsExactly("/bootuix", "/app/api/orders?next=/bootui/api");
    }

    @Test
    void recordsBootUiRequestsWhenExcludeSelfIsOffOrTheApplicationRecords() {
        BootUiHttpExchangeRepository owned = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        perform(
                new BootUiHttpExchangesWebFilter(
                        owned, Set.of(), new BootUiProperties(), BootUiSelfDataFilter.disabled()),
                MockServerHttpRequest.get("/bootui/api/panels").build());
        BootUiHttpExchangeRepository applicationRecorded = new BootUiHttpExchangeRepository(10, 25, 1_000L, true);
        perform(
                new BootUiHttpExchangesWebFilter(
                        applicationRecorded, Set.of(), new BootUiProperties(), BootUiSelfDataFilter.defaults()),
                MockServerHttpRequest.get("/bootui/api/panels").build());

        assertThat(recordedPaths(owned)).containsExactly("/bootui/api/panels");
        assertThat(recordedPaths(applicationRecorded)).containsExactly("/bootui/api/panels");
    }

    @Test
    void passesSkippedRequestsThroughTheChain() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesWebFilter filter = new BootUiHttpExchangesWebFilter(
                repository, Set.of(), new BootUiProperties(), BootUiSelfDataFilter.defaults());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/bootui/api/panels"));

        filter.filter(exchange, served -> {
                    served.getResponse().setStatusCode(HttpStatus.ACCEPTED);
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void skipsNullResponseHeaderNamesAndRecordsValidHeadersWithoutMutatingTheResponse() {
        var repository = new InMemoryHttpExchangeRepository();
        var base = MockServerWebExchange.from(MockServerHttpRequest.get("/download"));
        var values = new LinkedMultiValueMap<String, String>();
        values.add(null, "ignored");
        values.add("X-Values", "one");
        values.add("X-Values", "two");
        values.add("x-values", "other-case");
        values.add("Set-Cookie", "session=secret");
        HttpHeaders headers = new HttpHeaders(values);
        var response = new ServerHttpResponseDecorator(base.getResponse()) {
            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
        var exchange = base.mutate().response(response).build();

        recordingFilter(repository, Set.of(Include.RESPONSE_HEADERS))
                .filter(exchange, BootUiHttpExchangesWebFilterTests::writeBody)
                .block(Duration.ofSeconds(5));

        assertThat(base.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(base.getResponse().getBodyAsString().block()).isEqualTo("complete");
        assertThat(values).containsKey(null);
        assertThat(repository.findAll())
                .singleElement()
                .satisfies(recorded -> assertThat(recorded.getResponse().getHeaders())
                        .containsEntry("X-Values", List.of("one", "two"))
                        .doesNotContainKeys(null, "x-values", "Set-Cookie"));
    }

    @Test
    void skipsNullRequestHeaderNamesBeforeCaseInsensitiveNormalization() {
        var repository = new InMemoryHttpExchangeRepository();
        var base = MockServerWebExchange.from(MockServerHttpRequest.get("/download"));
        var values = new LinkedMultiValueMap<String, String>();
        values.add(null, "ignored");
        values.add("X-Values", "one");
        values.add("x-values", "other-case");
        values.add("Authorization", "secret");
        var request = new ServerHttpRequestDecorator(base.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders(values);
            }
        };
        recordingFilter(repository, Set.of(Include.REQUEST_HEADERS))
                .filter(base.mutate().request(request).build(), OK_CHAIN)
                .block(Duration.ofSeconds(5));

        assertThat(values).containsKey(null);
        assertThat(repository.findAll())
                .singleElement()
                .satisfies(recorded -> assertThat(recorded.getRequest().getHeaders())
                        .containsEntry("X-Values", List.of("one"))
                        .doesNotContainKeys(null, "x-values", "Authorization"));
    }

    @Test
    void metadataFailureDoesNotPreventResponseCommitment(CapturedOutput output) {
        var repository = new InMemoryHttpExchangeRepository();
        var base = MockServerWebExchange.from(MockServerHttpRequest.get("/download?token=secret-query"));
        var response = new ServerHttpResponseDecorator(base.getResponse()) {
            @Override
            public HttpHeaders getHeaders() {
                throw new IllegalStateException("secret-header-message");
            }
        };
        var exchange = base.mutate().response(response).build();

        recordingFilter(repository, Set.of(Include.RESPONSE_HEADERS))
                .filter(exchange, BootUiHttpExchangesWebFilterTests::writeBody)
                .block(Duration.ofSeconds(5));

        assertThat(base.getResponse().isCommitted()).isTrue();
        assertThat(base.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(base.getResponse().getBodyAsString().block()).isEqualTo("complete");
        assertThat(repository.findAll()).isEmpty();
        assertThat(output)
                .contains("BootUI skipped an HTTP exchange capture (IllegalStateException)")
                .doesNotContain("secret-header-message", "secret-query", "/download");
    }

    @Test
    void repositoryFailureDoesNotPreventResponseCommitment(CapturedOutput output) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/download"));

        recordingFilter(failingRepository(), Set.of())
                .filter(exchange, BootUiHttpExchangesWebFilterTests::writeBody)
                .block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().isCommitted()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("complete");
        assertThat(output)
                .contains("BootUI skipped an HTTP exchange capture (IllegalStateException)")
                .doesNotContain("secret-repository-message");
    }

    @Test
    void preservesApplicationErrorsAndLetsTheErrorHandlerCommitDespiteARecordingFailure() {
        var failure = new IllegalArgumentException("application");
        for (HttpExchangeRepository repository : List.of(new InMemoryHttpExchangeRepository(), failingRepository())) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/fail"));
            var filter = recordingFilter(repository, Set.of());

            assertThatThrownBy(() -> filter.filter(exchange, served -> Mono.error(failure))
                            .block(Duration.ofSeconds(5)))
                    .isSameAs(failure);
        }
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/fail"));
        recordingFilter(failingRepository(), Set.of())
                .filter(exchange, served -> Mono.error(failure))
                .onErrorResume(error -> {
                    assertThat(error).isSameAs(failure);
                    return writeBody(exchange);
                })
                .block(Duration.ofSeconds(5));
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("complete");
    }

    @Test
    void doesNotSwallowSynchronousApplicationOrApplicationCommitFailures() {
        var failure = new IllegalArgumentException("application");
        var filter = recordingFilter(failingRepository(), Set.of());
        var sync = MockServerWebExchange.from(MockServerHttpRequest.get("/api/fail"));
        assertThatThrownBy(() -> filter.filter(sync, served -> {
                            throw failure;
                        })
                        .block(Duration.ofSeconds(5)))
                .isSameAs(failure);

        var commit = MockServerWebExchange.from(MockServerHttpRequest.get("/api/fail"));
        commit.getResponse().beforeCommit(() -> Mono.error(failure));
        assertThatThrownBy(() -> filter.filter(commit, OK_CHAIN).block(Duration.ofSeconds(5)))
                .isSameAs(failure);
    }

    @Test
    void preparationFailuresStillInvokeTheApplicationExactlyOnce() {
        var base = MockServerWebExchange.from(MockServerHttpRequest.get("/download"));
        List<ServerWebExchange> exchanges = List.of(
                new ServerWebExchangeDecorator(base) {
                    @Override
                    public <T extends Principal> Mono<T> getPrincipal() {
                        throw new IllegalStateException("principal");
                    }
                },
                new ServerWebExchangeDecorator(base) {
                    @Override
                    public <T extends Principal> Mono<T> getPrincipal() {
                        return Mono.error(new IllegalStateException("principal"));
                    }
                },
                new ServerWebExchangeDecorator(base) {
                    @Override
                    public Mono<WebSession> getSession() {
                        return Mono.error(new IllegalStateException("session"));
                    }
                });
        for (var exchange : exchanges) {
            var calls = new AtomicInteger();
            var repository = new InMemoryHttpExchangeRepository();
            recordingFilter(repository, Set.of())
                    .filter(exchange, served -> {
                        calls.incrementAndGet();
                        return Mono.empty();
                    })
                    .block(Duration.ofSeconds(5));
            assertThat(calls).hasValue(1);
            assertThat(repository.findAll()).isEmpty();
        }
    }

    @Test
    void callbackRegistrationFailureStillServesTheResponse() {
        var base = MockServerWebExchange.from(MockServerHttpRequest.get("/download"));
        var response = new ServerHttpResponseDecorator(base.getResponse()) {
            @Override
            public void beforeCommit(java.util.function.Supplier<? extends Mono<Void>> action) {
                throw new IllegalStateException("registration");
            }
        };
        var repository = new InMemoryHttpExchangeRepository();

        recordingFilter(repository, Set.of())
                .filter(base.mutate().response(response).build(), BootUiHttpExchangesWebFilterTests::writeBody)
                .block(Duration.ofSeconds(5));

        assertThat(base.getResponse().getBodyAsString().block()).isEqualTo("complete");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void preservesIncludeRulesRemoteAddressPrincipalAndStartedSession() {
        var repository = new InMemoryHttpExchangeRepository();
        var base = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders?q=two words")
                .remoteAddress(new InetSocketAddress("127.0.0.2", 1234))
                .header("Authorization", "secret")
                .header("Cookie", "session=secret")
                .header("X-Request", "one"));
        var session = base.getSession().block();
        session.start();
        var exchange =
                base.mutate().principal(Mono.just((Principal) () -> "alice")).build();

        recordingFilter(
                        repository,
                        Set.of(
                                Include.REQUEST_HEADERS,
                                Include.REMOTE_ADDRESS,
                                Include.PRINCIPAL,
                                Include.SESSION_ID,
                                Include.TIME_TAKEN))
                .filter(exchange, OK_CHAIN)
                .block(Duration.ofSeconds(5));

        assertThat(repository.findAll()).singleElement().satisfies(recorded -> {
            assertThat(recorded.getRequest().getUri().getRawQuery()).isEqualTo("q=two%20words");
            assertThat(recorded.getRequest().getHeaders())
                    .containsEntry("X-Request", List.of("one"))
                    .doesNotContainKeys("Authorization", "Cookie");
            assertThat(recorded.getRequest().getRemoteAddress()).isEqualTo("/127.0.0.2");
            assertThat(recorded.getPrincipal().getName()).isEqualTo("alice");
            assertThat(recorded.getSession().getId()).isEqualTo(session.getId());
            assertThat(recorded.getTimeTaken()).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);
        });
    }

    @Test
    void defaultsTo200AndDoesNotRecordAnUnstartedSessionOrExcludedFields() {
        var repository = new InMemoryHttpExchangeRepository();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders"));
        recordingFilter(repository, Set.of())
                .filter(exchange, served -> served.getResponse().setComplete())
                .block(Duration.ofSeconds(5));
        assertThat(repository.findAll()).singleElement().satisfies(recorded -> {
            assertThat(recorded.getResponse().getStatus()).isEqualTo(200);
            assertThat(recorded.getRequest().getHeaders()).isEmpty();
            assertThat(recorded.getRequest().getRemoteAddress()).isNull();
            assertThat(recorded.getPrincipal()).isNull();
            assertThat(recorded.getSession()).isNull();
            assertThat(recorded.getTimeTaken()).isNull();
        });
        assertThat(exchange.getSession().block().isStarted()).isFalse();
    }
}
