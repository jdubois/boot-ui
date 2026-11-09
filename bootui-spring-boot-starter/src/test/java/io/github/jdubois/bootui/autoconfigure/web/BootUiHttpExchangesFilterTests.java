package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

@ExtendWith(OutputCaptureExtension.class)
class BootUiHttpExchangesFilterTests {

    @Test
    void skipsNullRequestHeaderNamesWithoutLosingValidHeaders() throws Exception {
        var repository = new InMemoryHttpExchangeRepository();
        var request = new MockHttpServletRequest("GET", "/api/orders") {
            @Override
            public Enumeration<String> getHeaderNames() {
                return Collections.enumeration(Arrays.asList(null, "X-Request"));
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                assertThat(name).isNotNull();
                return super.getHeaders(name);
            }
        };
        request.addHeader("X-Request", "one");

        perform(recordingFilter(repository, Set.of(Include.REQUEST_HEADERS)), request);

        assertThat(repository.findAll())
                .singleElement()
                .satisfies(exchange -> assertThat(exchange.getRequest().getHeaders())
                        .containsOnlyKeys("X-Request")
                        .containsEntry("X-Request", List.of("one")));
    }

    private static BootUiHttpExchangesFilter recordingFilter(HttpExchangeRepository repository, Set<Include> includes) {
        return new BootUiHttpExchangesFilter(
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

    private static MockHttpServletRequest request(String contextPath, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", contextPath + path);
        request.setContextPath(contextPath);
        request.setQueryString(query);
        return request;
    }

    private static List<String> recordedUris(HttpExchangeRepository repository) {
        return repository.findAll().stream()
                .map(HttpExchange::getRequest)
                .map(request -> request.getUri().toString())
                .toList();
    }

    private static void perform(BootUiHttpExchangesFilter filter, MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private static BootUiHttpExchangesFilter filter(
            HttpExchangeRepository repository, BootUiProperties properties, BootUiSelfDataFilter selfDataFilter) {
        return new BootUiHttpExchangesFilter(repository, Set.of(Include.REQUEST_HEADERS), properties, selfDataFilter);
    }

    @Test
    void skipsBootUiRequestsBelowTheContextPathButRecordsApplicationRequests() throws Exception {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesFilter filter = filter(repository, new BootUiProperties(), BootUiSelfDataFilter.defaults());

        perform(filter, request("/app", "/bootui/api/http-exchanges", null));
        perform(filter, request("/app", "/bootui", null));
        perform(filter, request("/app", "/bootuix", null));
        perform(filter, request("/app", "/api/orders", "next=/bootui/api"));

        assertThat(recordedUris(repository))
                .containsExactly("http://localhost/app/api/orders?next=/bootui/api", "http://localhost/app/bootuix");
    }

    @Test
    void honorsCustomUiAndApiMounts() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/console");
        properties.setApiPath("/internal/console-api");
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesFilter filter = filter(repository, properties, BootUiSelfDataFilter.defaults());

        perform(filter, request("", "/console/index.html", null));
        perform(filter, request("", "/internal/console-api/http-exchanges", null));
        perform(filter, request("", "/bootui/api/panels", null));

        assertThat(recordedUris(repository)).containsExactly("http://localhost/bootui/api/panels");
    }

    @Test
    void recordsBootUiRequestsWhenExcludeSelfIsOff() throws Exception {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesFilter filter = filter(repository, new BootUiProperties(), BootUiSelfDataFilter.disabled());

        perform(filter, request("", "/bootui/api/panels", null));

        assertThat(recordedUris(repository)).containsExactly("http://localhost/bootui/api/panels");
    }

    @Test
    void recordsEverythingIntoAnApplicationRepositoryOrAnApplicationRecordedRepository() throws Exception {
        InMemoryHttpExchangeRepository applicationRepository = new InMemoryHttpExchangeRepository();
        perform(
                filter(applicationRepository, new BootUiProperties(), BootUiSelfDataFilter.defaults()),
                request("", "/bootui/api/panels", null));
        BootUiHttpExchangeRepository applicationRecorded = new BootUiHttpExchangeRepository(10, 25, 1_000L, true);
        perform(
                filter(applicationRecorded, new BootUiProperties(), BootUiSelfDataFilter.defaults()),
                request("", "/bootui/api/panels", null));

        assertThat(recordedUris(applicationRepository)).containsExactly("http://localhost/bootui/api/panels");
        assertThat(recordedUris(applicationRecorded)).containsExactly("http://localhost/bootui/api/panels");
    }

    @Test
    void skipsNullResponseHeaderNamesWithoutLosingValidHeadersOrTheServedBody() throws Exception {
        var repository = new InMemoryHttpExchangeRepository();
        var response = new MockHttpServletResponse() {
            @Override
            public Collection<String> getHeaderNames() {
                return Arrays.asList(null, "Content-Type", "X-Values", "Set-Cookie");
            }

            @Override
            public List<String> getHeaders(String name) {
                assertThat(name).isNotNull();
                return super.getHeaders(name);
            }
        };
        var filter = recordingFilter(repository, Set.of(Include.RESPONSE_HEADERS));

        filter.doFilter(request("", "/download", null), response, (req, res) -> {
            response.setContentType("application/pdf");
            response.addHeader("X-Values", "one");
            response.addHeader("X-Values", "two");
            response.addHeader("Set-Cookie", "session=secret");
            response.getWriter().write("%PDF-stream");
            response.flushBuffer();
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("%PDF-stream");
        assertThat(response.isCommitted()).isTrue();
        assertThat(repository.findAll()).singleElement().satisfies(exchange -> {
            assertThat(exchange.getResponse().getHeaders())
                    .containsEntry("Content-Type", List.of("application/pdf"))
                    .containsEntry("X-Values", List.of("one", "two"))
                    .doesNotContainKeys(null, "Set-Cookie");
        });
    }

    @Test
    void metadataFailureDoesNotAlterACommittedResponse(CapturedOutput output) throws Exception {
        var repository = new InMemoryHttpExchangeRepository();
        var response = new MockHttpServletResponse() {
            @Override
            public Collection<String> getHeaderNames() {
                throw new IllegalStateException("secret-header-message");
            }
        };
        recordingFilter(repository, Set.of(Include.RESPONSE_HEADERS))
                .doFilter(request("", "/download", "token=secret-query"), response, (req, res) -> {
                    response.setStatus(206);
                    response.getWriter().write("complete");
                    response.flushBuffer();
                });

        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentAsString()).isEqualTo("complete");
        assertThat(repository.findAll()).isEmpty();
        assertThat(output)
                .contains("BootUI skipped an HTTP exchange capture (IllegalStateException)")
                .doesNotContain("secret-header-message", "secret-query", "/download");
    }

    @Test
    void repositoryFailureDoesNotAlterTheServedResponse(CapturedOutput output) throws Exception {
        var response = new MockHttpServletResponse();
        recordingFilter(failingRepository(), Set.of())
                .doFilter(request("", "/download", null), response, (req, res) -> {
                    response.setStatus(201);
                    response.getWriter().write("complete");
                });

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("complete");
        assertThat(output)
                .contains("BootUI skipped an HTTP exchange capture (IllegalStateException)")
                .doesNotContain("secret-repository-message");
    }

    @Test
    void preservesEveryApplicationExceptionEvenWhenRecordingAlsoFails() {
        for (Exception failure : List.of(
                new IOException("application"),
                new ServletException("application"),
                new IllegalStateException("app"))) {
            for (HttpExchangeRepository repository :
                    List.of(new InMemoryHttpExchangeRepository(), failingRepository())) {
                var filter = recordingFilter(repository, Set.of());
                assertThatThrownBy(() -> filter.doFilter(
                                request("", "/api/fail", null), new MockHttpServletResponse(), (q, s) -> {
                                    if (failure instanceof IOException io) {
                                        throw io;
                                    }
                                    if (failure instanceof ServletException servlet) {
                                        throw servlet;
                                    }
                                    throw (RuntimeException) failure;
                                }))
                        .isSameAs(failure);
                if (repository instanceof InMemoryHttpExchangeRepository) {
                    assertThat(repository.findAll())
                            .singleElement()
                            .satisfies(exchange -> assertThat(
                                            exchange.getResponse().getStatus())
                                    .isEqualTo(500));
                }
            }
        }
    }

    @Test
    void setupFailureStillInvokesTheApplicationExactlyOnce() throws Exception {
        var repository = new InMemoryHttpExchangeRepository();
        var request = new MockHttpServletRequest("GET", "/api/orders") {
            @Override
            public StringBuffer getRequestURL() {
                throw new IllegalStateException("metadata");
            }
        };
        var calls = new AtomicInteger();
        recordingFilter(repository, Set.of())
                .doFilter(request, new MockHttpServletResponse(), (q, s) -> calls.incrementAndGet());

        assertThat(calls).hasValue(1);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void preservesActuatorsQuietSkipForAnUnrecordableRequestUrl(CapturedOutput output) throws Exception {
        var repository = new InMemoryHttpExchangeRepository();
        var calls = new AtomicInteger();
        recordingFilter(repository, Set.of())
                .doFilter(
                        request("", "/bad path", null),
                        new MockHttpServletResponse(),
                        (q, s) -> calls.incrementAndGet());

        assertThat(calls).hasValue(1);
        assertThat(repository.findAll()).isEmpty();
        assertThat(output).doesNotContain("BootUI skipped");
    }

    @Test
    void preservesIncludeRulesQueryEncodingAndSessionSemantics() throws Exception {
        var repository = new InMemoryHttpExchangeRepository();
        var request = request("/app", "/api/orders", "q=two words");
        request.setRemoteAddr("127.0.0.2");
        request.setUserPrincipal(() -> "alice");
        request.setSession(new MockHttpSession());
        request.addHeader("Authorization", "secret");
        request.addHeader("Cookie", "session=secret");
        request.addHeader("X-Request", "one");
        var filter = recordingFilter(
                repository,
                Set.of(
                        Include.REQUEST_HEADERS,
                        Include.REMOTE_ADDRESS,
                        Include.PRINCIPAL,
                        Include.SESSION_ID,
                        Include.TIME_TAKEN));

        perform(filter, request);

        assertThat(repository.findAll()).singleElement().satisfies(exchange -> {
            assertThat(exchange.getRequest().getUri().toString()).endsWith("/app/api/orders?q=two%20words");
            assertThat(exchange.getRequest().getHeaders())
                    .containsEntry("X-Request", List.of("one"))
                    .doesNotContainKeys("Authorization", "Cookie");
            assertThat(exchange.getRequest().getRemoteAddress()).isEqualTo("127.0.0.2");
            assertThat(exchange.getPrincipal().getName()).isEqualTo("alice");
            assertThat(exchange.getSession().getId())
                    .isEqualTo(request.getSession(false).getId());
            assertThat(exchange.getTimeTaken()).isNotNull().isGreaterThanOrEqualTo(java.time.Duration.ZERO);
        });
        var noSession = request("", "/api/no-session", null);
        perform(filter, noSession);
        assertThat(noSession.getSession(false)).isNull();
    }

    @Test
    void retainsInheritedAsyncAndErrorDispatchExclusion() throws Exception {
        var repository = new InMemoryHttpExchangeRepository();
        var filter = recordingFilter(repository, Set.of());
        for (DispatcherType dispatcher : List.of(DispatcherType.ASYNC, DispatcherType.ERROR)) {
            var request = request("", "/api/download", null);
            request.setDispatcherType(dispatcher);
            if (dispatcher == DispatcherType.ERROR) {
                request.setAttribute("jakarta.servlet.error.request_uri", "/api/download");
            }
            perform(filter, request);
        }
        assertThat(repository.findAll()).isEmpty();
    }
}
