package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.HttpHeaderDto;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.test.web.servlet.MockMvc;

class HttpExchangesControllerTests {

    private static final Instant START = Instant.parse("2026-06-03T09:15:00Z");

    @SuppressWarnings("unchecked")
    private static ObjectProvider<HttpExchangeRepository> providerOf(HttpExchangeRepository repository) {
        ObjectProvider<HttpExchangeRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<HttpExchangeRepository> emptyProvider() {
        ObjectProvider<HttpExchangeRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static HttpExchangeRepository repositoryWith(HttpExchange... exchanges) {
        HttpExchangeRepository repository = mock(HttpExchangeRepository.class);
        when(repository.findAll()).thenReturn(List.of(exchanges));
        return repository;
    }

    private static HttpExchange exchange(String method, String uri, int status) {
        return exchange(method, uri, status, Map.of(), Map.of());
    }

    private static HttpExchange exchange(
            String method,
            String uri,
            int status,
            Map<String, List<String>> requestHeaders,
            Map<String, List<String>> responseHeaders) {
        return new HttpExchange(
                START,
                new HttpExchange.Request(URI.create(uri), "127.0.0.1", method, requestHeaders),
                new HttpExchange.Response(status, responseHeaders),
                new HttpExchange.Principal("alice"),
                new HttpExchange.Session("session-123"),
                Duration.ofMillis(37));
    }

    @Test
    void returnsUnavailableReportWhenRepositoryIsAbsent() throws Exception {
        MockMvc mvc = standaloneSetup(new HttpExchangesController(emptyProvider(), new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/http-exchanges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.exchanges.length()").value(0))
                .andExpect(jsonPath("$.unavailableReason").value("HTTP exchange repository not available"));
    }

    @Test
    void mapsExchangesWithMaskingTraceExtractionResponseSizeAndSelfFiltering() {
        HttpExchange appExchange = exchange(
                "POST",
                "http://localhost/api/orders?token=s3cr3t&page=1",
                201,
                Map.of(
                        "Accept", List.of("application/json"),
                        "Authorization", List.of("Bearer clear"),
                        "Cookie", List.of("SESSION=clear"),
                        "traceparent", List.of("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00")),
                Map.of(
                        "Content-Length", List.of("42"),
                        "Set-Cookie", List.of("SESSION=clear; Path=/")));
        HttpExchange bootUiExchange = exchange("GET", "http://localhost/bootui/api/panels", 200);
        HttpExchangesController controller = new HttpExchangesController(
                providerOf(repositoryWith(appExchange, bootUiExchange)), new BootUiProperties());

        HttpExchangesReport report = controller.exchanges(null, null, null, null, null);

        assertThat(report.total()).isEqualTo(1);
        assertThat(report.recorded()).isEqualTo(2);
        assertThat(report.hiddenSelf()).isEqualTo(1);
        HttpExchangeDto dto = report.exchanges().get(0);
        assertThat(dto.method()).isEqualTo("POST");
        assertThat(dto.path()).isEqualTo("/api/orders");
        assertThat(dto.query()).isEqualTo("token=******&page=1");
        assertThat(dto.uri()).isEqualTo("http://localhost/api/orders?token=******&page=1");
        assertThat(dto.status()).isEqualTo(201);
        assertThat(dto.statusFamily()).isEqualTo("2xx");
        assertThat(dto.durationMs()).isEqualTo(37);
        assertThat(dto.responseSizeBytes()).isEqualTo(42);
        assertThat(dto.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertHeader(dto.requestHeaders(), "Accept", false, "application/json");
        assertHeader(dto.requestHeaders(), "Authorization", true, SecretMasker.MASKED_VALUE);
        assertHeader(dto.requestHeaders(), "Cookie", true, SecretMasker.MASKED_VALUE);
        assertHeader(dto.responseHeaders(), "Set-Cookie", true, SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksBareSensitiveQueryParameterWithoutFabricatingEquals() {
        HttpExchange appExchange = exchange("GET", "http://localhost/api/orders?token&page=1", 200);
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repositoryWith(appExchange)), new BootUiProperties());

        HttpExchangesReport report = controller.exchanges(null, null, null, null, null);

        HttpExchangeDto dto = report.exchanges().get(0);
        assertThat(dto.query()).isEqualTo(SecretMasker.MASKED_VALUE + "&page=1");
        assertThat(dto.uri()).isEqualTo("http://localhost/api/orders?" + SecretMasker.MASKED_VALUE + "&page=1");
    }

    @Test
    void filtersAndPagesOnServer() throws Exception {
        HttpExchange alpha = exchange("GET", "http://localhost/api/alpha", 200);
        HttpExchange beta = exchange("POST", "http://localhost/api/beta", 404);
        HttpExchange gamma = exchange("POST", "http://localhost/api/gamma", 500);
        MockMvc mvc = standaloneSetup(new HttpExchangesController(
                        providerOf(repositoryWith(alpha, beta, gamma)), new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/http-exchanges")
                        .param("q", "beta")
                        .param("method", "POST")
                        .param("statusClass", "4xx")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.exchanges.length()").value(1))
                .andExpect(jsonPath("$.exchanges[0].path").value("/api/beta"))
                .andExpect(jsonPath("$.page.matched").value(1))
                .andExpect(jsonPath("$.page.returned").value(1));
    }

    @Test
    void metadataOnlyHidesHeadersAndQueryValues() {
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.METADATA_ONLY);
        HttpExchange appExchange = exchange(
                "GET",
                "http://localhost/api/profile?email=user@example.com&token=s3cr3t",
                200,
                Map.of("Accept", List.of("application/json"), "Authorization", List.of("Bearer clear")),
                Map.of());
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repositoryWith(appExchange)), properties);

        HttpExchangeDto dto =
                controller.exchanges(null, null, null, null, null).exchanges().get(0);

        assertThat(dto.query()).isNull();
        assertThat(dto.uri()).isEqualTo("http://localhost/api/profile");
        assertHeader(dto.requestHeaders(), "Accept", false);
        assertHeader(dto.requestHeaders(), "Authorization", true);
    }

    private static void assertHeader(List<HttpHeaderDto> headers, String name, boolean masked, String... values) {
        HttpHeaderDto header = headers.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
        assertThat(header.masked()).isEqualTo(masked);
        assertThat(header.values()).containsExactly(values);
    }
}
