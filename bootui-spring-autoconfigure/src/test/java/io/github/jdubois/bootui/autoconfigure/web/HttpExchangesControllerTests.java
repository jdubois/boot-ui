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
import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.HttpHeaderDto;
import io.github.jdubois.bootui.core.dto.HttpRoutesReport;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
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
    void reportsApplicationManagedRetentionForAnApplicationRepository() {
        HttpExchangesController controller = new HttpExchangesController(
                providerOf(repositoryWith(
                        exchange("GET", "http://localhost/api/a", 200),
                        exchange("GET", "http://localhost/api/b", 500))),
                new BootUiProperties());

        HttpExchangesReport report = controller.exchanges(null, null, null, null, null);

        assertThat(report.retention()).isEqualTo(CaptureRetentionDto.applicationManaged(2));
    }

    @Test
    void reportsBootUiRetentionFromTheSameSnapshotAsTheExchanges() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(3, 34, 1_000L, false);
        repository.add(exchange("GET", "http://localhost/api/failing", 500));
        for (int i = 0; i < 4; i++) {
            repository.add(exchange("GET", "http://localhost/api/ok-" + i, 200));
        }
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repository), new BootUiProperties());

        HttpExchangesReport report = controller.exchanges(null, null, "5xx", null, null);

        assertThat(report.recorded()).isEqualTo(3);
        assertThat(report.exchanges()).extracting(HttpExchangeDto::path).containsExactly("/api/failing");
        assertThat(report.retention()).isEqualTo(new CaptureRetentionDto(false, 3, 1, 3, 1, 2L, 1_000L));
    }

    @Test
    void doesNotHideApplicationExchangesUnderAContextPathThatContainsTheBootUiMount() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(5, 25, 1_000L, false);
        // Recorded by BootUI's filter under server.servlet.context-path=/bootui: the path within the application is
        // /api/orders, so it is application traffic even though the absolute URL starts with the BootUI mount.
        repository.add(exchange("GET", "http://localhost/bootui/api/orders", 500));
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repository), new BootUiProperties());

        HttpExchangesReport report = controller.exchanges(null, null, null, null, null);

        assertThat(report.hiddenSelf()).isZero();
        assertThat(report.exchanges()).extracting(HttpExchangeDto::path).containsExactly("/bootui/api/orders");
    }

    @Test
    void stillHidesBootUiExchangesAtReadTimeForAnApplicationRecordedRepository() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(5, 25, 1_000L, true);
        repository.add(exchange("GET", "http://localhost/bootui/api/panels", 200));
        repository.add(exchange("GET", "http://localhost/api/orders", 200));
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repository), new BootUiProperties());

        HttpExchangesReport report = controller.exchanges(null, null, null, null, null);

        assertThat(report.hiddenSelf()).isEqualTo(1);
        assertThat(report.exchanges()).extracting(HttpExchangeDto::path).containsExactly("/api/orders");
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

    @Test
    void stampsTraceIdFromRegistryWhenInstalled() {
        HttpExchange appExchange = exchange("GET", "http://localhost/api/notes", 200);
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repositoryWith(appExchange)), new BootUiProperties());
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        long start = START.toEpochMilli();
        registry.record(new HttpExchangeTraceRegistry.HttpExchangeTrace(
                start, start + 37, "GET", "/api/notes", "trace-registry-abc"));
        controller.setTraceRegistry(registry);

        HttpExchangeDto dto =
                controller.exchanges(null, null, null, null, null).exchanges().get(0);

        assertThat(dto.traceId()).isEqualTo("trace-registry-abc");
    }

    @Test
    void registryTraceIdTakesPrecedenceOverHeaderDerivedTraceId() {
        HttpExchange appExchange = exchange(
                "GET",
                "http://localhost/api/notes",
                200,
                Map.of("traceparent", List.of("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00")),
                Map.of());
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repositoryWith(appExchange)), new BootUiProperties());
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        long start = START.toEpochMilli();
        registry.record(new HttpExchangeTraceRegistry.HttpExchangeTrace(
                start, start + 37, "GET", "/api/notes", "trace-registry-wins"));
        controller.setTraceRegistry(registry);

        HttpExchangeDto dto =
                controller.exchanges(null, null, null, null, null).exchanges().get(0);

        assertThat(dto.traceId()).isEqualTo("trace-registry-wins");
    }

    @Test
    void fallsBackToHeaderDerivedTraceIdWhenRegistryHasNoMatch() {
        HttpExchange appExchange = exchange(
                "GET",
                "http://localhost/api/notes",
                200,
                Map.of("traceparent", List.of("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00")),
                Map.of());
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repositoryWith(appExchange)), new BootUiProperties());
        controller.setTraceRegistry(new HttpExchangeTraceRegistry(10));

        HttpExchangeDto dto =
                controller.exchanges(null, null, null, null, null).exchanges().get(0);

        assertThat(dto.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void labelsExchangesWithTheFrameworkTemplateRecordedBesideTheTraceId() {
        HttpExchangesController controller = new HttpExchangesController(
                providerOf(repositoryWith(exchange("GET", "http://localhost/api/orders/42", 200))),
                new BootUiProperties());
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        long start = START.toEpochMilli();
        registry.record(new HttpExchangeTraceRegistry.HttpExchangeTrace(
                start, start + 37, "GET", "/api/orders/42", null, "/api/orders/{orderId}"));
        controller.setTraceRegistry(registry);
        controller.setMappingProvider(mappingsOf(new MappingDto("GET", "/api/orders/{id}", "h", null, null)));

        HttpExchangeDto dto =
                controller.exchanges(null, null, null, null, null).exchanges().get(0);

        assertThat(dto.route()).isEqualTo("/api/orders/{orderId}");
        assertThat(dto.routeSource()).isEqualTo("FRAMEWORK_TEMPLATE");
    }

    @Test
    void fallsBackToDeclaredMappingsThenToAMaskedPath() {
        HttpExchangesController controller = new HttpExchangesController(
                providerOf(repositoryWith(
                        exchange("GET", "http://localhost/api/orders/42", 200),
                        exchange("GET", "http://localhost/files/3f2b8c1e-0a4d-4e8b-9c55-1d2e3f4a5b6c?token=x", 200))),
                new BootUiProperties());
        controller.setTraceRegistry(new HttpExchangeTraceRegistry(10));
        controller.setMappingProvider(mappingsOf(new MappingDto("GET", "/api/orders/{id}", "h", null, null)));

        List<HttpExchangeDto> exchanges =
                controller.exchanges(null, null, null, null, null).exchanges();

        assertThat(exchanges.get(0).route()).isEqualTo("/api/orders/{id}");
        assertThat(exchanges.get(0).routeSource()).isEqualTo("DECLARED_MAPPING");
        assertThat(exchanges.get(1).route()).isEqualTo("/files/{value}");
        assertThat(exchanges.get(1).routeSource()).isEqualTo("MASKED_PATH");
    }

    @Test
    void servesRouteRankingsAndFiltersTheExchangeListToOneRoute() throws Exception {
        HttpExchangesController controller = new HttpExchangesController(
                providerOf(repositoryWith(
                        exchange("GET", "http://localhost/api/orders/1", 200),
                        exchange("GET", "http://localhost/api/orders/2", 500),
                        exchange("GET", "http://localhost/api/health", 200),
                        exchange("GET", "http://localhost/bootui/api/panels", 200))),
                new BootUiProperties());
        controller.setMappingProvider(mappingsOf(new MappingDto("GET", "/api/orders/{id}", "h", null, null)));
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/http-exchanges/routes").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.topPerCriterion").value(5))
                .andExpect(jsonPath("$.distinctRoutes").value(2))
                .andExpect(jsonPath("$.routes[0].id").value("GET /api/orders/{id}"))
                .andExpect(jsonPath("$.routes[0].routeSource").value("DECLARED_MAPPING"))
                .andExpect(jsonPath("$.routes[0].requests").value(2))
                .andExpect(jsonPath("$.routes[0].status5xx").value(1))
                .andExpect(jsonPath("$.routes[0].p95DurationMs").value(37))
                .andExpect(jsonPath("$.window.retainedExchanges").value(4))
                .andExpect(jsonPath("$.window.hiddenSelfExchanges").value(1))
                .andExpect(jsonPath("$.window.summarizedExchanges").value(3))
                .andExpect(jsonPath("$.window.evicted").doesNotExist());

        mvc.perform(get("/bootui/api/http-exchanges").param("route", "GET /api/orders/{id}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page.matched").value(2))
                .andExpect(jsonPath("$.exchanges.length()").value(2))
                .andExpect(jsonPath("$.exchanges[0].route").value("/api/orders/{id}"));
    }

    @Test
    void pinsALinkedRouteAndSaysWhenNoFrameworkTemplateWasRecorded() throws Exception {
        HttpExchangesController controller = new HttpExchangesController(
                providerOf(repositoryWith(
                        exchange("GET", "http://localhost/a", 200),
                        exchange("GET", "http://localhost/a", 200),
                        exchange("GET", "http://localhost/b", 200))),
                new BootUiProperties());
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/http-exchanges/routes").param("limit", "1").param("route", "GET /b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes.length()").value(2))
                .andExpect(jsonPath("$.routes[1].id").value("GET /b"))
                .andExpect(jsonPath("$.routes[1].topFor.length()").value(0))
                .andExpect(jsonPath("$.notes[?(@ =~ /.*OpenTelemetry.*/)]").exists());

        controller.setTraceRegistry(new HttpExchangeTraceRegistry(10));
        assertThat(controller.routes(1).notes()).noneMatch(note -> note.contains("OpenTelemetry"));
    }

    @Test
    void readsTheDeclaredMappingsOnceAcrossRequests() {
        MappingProvider provider = mock(MappingProvider.class);
        when(provider.available()).thenReturn(true);
        when(provider.mappings()).thenReturn(List.of(new MappingDto("GET", "/api/orders/{id}", "h", null, null)));
        @SuppressWarnings("unchecked")
        ObjectProvider<MappingProvider> objectProvider = mock(ObjectProvider.class);
        when(objectProvider.getIfAvailable()).thenReturn(provider);
        HttpExchangesController controller = new HttpExchangesController(
                providerOf(repositoryWith(exchange("GET", "http://localhost/api/orders/42", 200))),
                new BootUiProperties());
        controller.setMappingProvider(objectProvider);

        controller.exchanges(null, null, null, null, null);
        controller.routes(null);
        controller.exchanges(null, null, null, null, null);

        org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(1)).mappings();
    }

    @Test
    void routeRankingsAreUnavailableWithoutARepository() {
        HttpRoutesReport report = new HttpExchangesController(emptyProvider(), new BootUiProperties()).routes(null);

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("HTTP exchange repository not available");
    }

    @Test
    void reportsTheBufferSizeOnlyForBootUisOwnRepository() {
        HttpExchangeRepository own = repositoryWith(exchange("GET", "http://localhost/a", 200));
        BootUiProperties properties = new BootUiProperties();
        properties.getHttpExchanges().setMaxExchanges(42);
        HttpExchangesController controller = new HttpExchangesController(providerOf(own), properties);
        BeanFactory beanFactory = mock(BeanFactory.class);
        when(beanFactory.containsBean("bootUiHttpExchangeRepository")).thenReturn(true);
        when(beanFactory.getBean("bootUiHttpExchangeRepository")).thenReturn(own);
        controller.setBeanFactory(beanFactory);

        assertThat(controller.routes(null).window().bufferSize()).isEqualTo(42);
        assertThat(controller.routes(null).window().evicted()).isNull();

        HttpExchangesController applicationOwned = new HttpExchangesController(
                providerOf(repositoryWith(exchange("GET", "http://localhost/a", 200))), properties);
        applicationOwned.setBeanFactory(beanFactory);
        assertThat(applicationOwned.routes(null).window().bufferSize()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MappingProvider> mappingsOf(MappingDto... mappings) {
        MappingProvider provider = mock(MappingProvider.class);
        when(provider.available()).thenReturn(true);
        when(provider.mappings()).thenReturn(List.of(mappings));
        ObjectProvider<MappingProvider> objectProvider = mock(ObjectProvider.class);
        when(objectProvider.getIfAvailable()).thenReturn(provider);
        return objectProvider;
    }

    private static void assertHeader(List<HttpHeaderDto> headers, String name, boolean masked, String... values) {
        HttpHeaderDto header = headers.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
        assertThat(header.masked()).isEqualTo(masked);
        assertThat(header.values()).containsExactly(values);
    }

    @Test
    void routeWindowReportsTheCapacityAndEvictionsOfBootUisOwnRepository() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(3, 34, 1_000L, false);
        repository.add(exchange("GET", "http://localhost/api/failing", 500));
        for (int i = 0; i < 5; i++) {
            repository.add(exchange("GET", "http://localhost/api/ok", 200));
        }
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repository), new BootUiProperties());

        HttpRoutesReport report = controller.routes(null);

        assertThat(report.window().bufferSize()).isEqualTo(3);
        assertThat(report.window().evicted()).isEqualTo(3L);
        assertThat(report.window().retainedExchanges()).isEqualTo(3);
        assertThat(report.notes()).noneMatch(note -> note.contains("does not count evictions"));
        assertThat(report.window().evicted())
                .isEqualTo(controller
                        .exchanges(null, null, null, null, null)
                        .retention()
                        .evicted());
    }

    @Test
    void routeWindowLeavesEvictionsUnreportedWhenAnApplicationFilterRecordsIntoBootUisRepository() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(1, 25, 1_000L, true);
        repository.add(exchange("GET", "http://localhost/api/one", 200));
        repository.add(exchange("GET", "http://localhost/api/two", 200));
        HttpExchangesController controller =
                new HttpExchangesController(providerOf(repository), new BootUiProperties());

        assertThat(controller.routes(null).window().evicted()).isNull();
    }
}
