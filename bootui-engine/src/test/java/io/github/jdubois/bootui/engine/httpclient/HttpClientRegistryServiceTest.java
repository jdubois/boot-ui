package io.github.jdubois.bootui.engine.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.HttpClientDto;
import io.github.jdubois.bootui.core.dto.HttpClientRegistryReport;
import io.github.jdubois.bootui.core.dto.HttpClientSettingDto;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.spi.DiscoveredHttpClient;
import io.github.jdubois.bootui.spi.DiscoveredHttpClientSetting;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.HttpClientProvider;
import io.github.jdubois.bootui.spi.HttpClientVocabulary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for the shared HTTP Clients engine. They assert the guarantees the panel promises on
 * every runtime — safe values, honest status, stable identity and ordering, and attribution that refuses to
 * guess — rather than merely exercising the code paths.
 */
class HttpClientRegistryServiceTest {

    private static final String NO_PROVIDER_REASON =
            "Not available: this runtime has no declarative HTTP client registry wired.";

    @Test
    @DisplayName("no provider fails closed with a stable empty report")
    void failsClosedWithoutProvider() {
        HttpClientRegistryService service = new HttpClientRegistryService(null, policy(ValueExposure.MASKED), null);

        assertThat(service.available()).isFalse();
        HttpClientRegistryReport report = service.report();
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo(NO_PROVIDER_REASON);
        assertThat(report.total()).isZero();
        assertThat(report.clients()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(report.valueExposure()).isEqualTo("MASKED");
    }

    @Test
    @DisplayName("a provider that reports itself unavailable keeps its framework-specific hint")
    void keepsProviderUnavailableReason() {
        HttpClientRegistryService service = new HttpClientRegistryService(
                new StubProvider(false, "Add a @RegisterRestClient interface.", List.of()),
                policy(ValueExposure.MASKED),
                null);

        assertThat(service.unavailableReason()).isEqualTo("Add a @RegisterRestClient interface.");
        assertThat(service.report().unavailableReason()).isEqualTo("Add a @RegisterRestClient interface.");
    }

    @Test
    @DisplayName("an available provider that discovers nothing still reports unavailable, not an empty table")
    void reportsUnavailableWhenNoClientsDiscovered() {
        HttpClientRegistryService service = new HttpClientRegistryService(
                new StubProvider(true, "none", List.of()), policy(ValueExposure.MASKED), null);

        assertThat(service.report().available()).isFalse();
    }

    @Test
    @DisplayName("declarative clients sort before framework builders, then by name")
    void ordersDeclarativeClientsFirst() {
        HttpClientRegistryService service = new HttpClientRegistryService(
                new StubProvider(
                        true,
                        null,
                        List.of(
                                client("zeta", HttpClientVocabulary.KIND_WEB_CLIENT_BUILDER, null),
                                client("beta", HttpClientVocabulary.KIND_HTTP_INTERFACE, "https://beta.example.com"),
                                client("Alpha", HttpClientVocabulary.KIND_HTTP_INTERFACE, "https://alpha.example.com"),
                                client("gamma", HttpClientVocabulary.KIND_OPEN_FEIGN, "https://gamma.example.com"))),
                policy(ValueExposure.MASKED),
                null);

        assertThat(service.report().clients())
                .extracting(HttpClientDto::name)
                .containsExactly("Alpha", "beta", "gamma", "zeta");
    }

    @Test
    @DisplayName("clients sharing a kind and a name still get distinct stable ids")
    void assignsUniqueIds() {
        HttpClientRegistryService service = new HttpClientRegistryService(
                new StubProvider(
                        true,
                        null,
                        List.of(
                                client("orders", HttpClientVocabulary.KIND_HTTP_INTERFACE, "https://a.example.com"),
                                client("orders", HttpClientVocabulary.KIND_HTTP_INTERFACE, "https://b.example.com"),
                                client("orders", HttpClientVocabulary.KIND_HTTP_INTERFACE, "https://c.example.com"))),
                policy(ValueExposure.MASKED),
                null);

        assertThat(service.report().clients())
                .extracting(HttpClientDto::id)
                .containsExactly("http_interface:orders", "http_interface:orders#2", "http_interface:orders#3");
    }

    @Test
    @DisplayName("an unresolved placeholder is reported honestly and surfaced as a warning")
    void reportsUnresolvedBaseUrl() {
        DiscoveredHttpClient unresolved = new DiscoveredHttpClient(
                "orders",
                HttpClientVocabulary.KIND_HTTP_INTERFACE,
                "com.example.OrdersClient",
                "orders",
                "https://${orders.host}/v1",
                "https://${orders.host}/v1",
                HttpClientVocabulary.PROVENANCE_CLIENT,
                "spring.http.serviceclient.orders.base-url",
                List.of());

        HttpClientRegistryReport report = new HttpClientRegistryService(
                        new StubProvider(true, null, List.of(unresolved)), policy(ValueExposure.FULL), null)
                .report();

        HttpClientDto client = report.clients().get(0);
        assertThat(client.baseUrlStatus()).isEqualTo(HttpClientVocabulary.BASE_URL_UNRESOLVED);
        assertThat(client.configuredBaseUrl()).isEqualTo("https://${orders.host}/v1");
        assertThat(client.resolvedBaseUrl()).isNull();
        assertThat(client.baseUrlSource()).isEqualTo("spring.http.serviceclient.orders.base-url");
        assertThat(report.warnings())
                .anySatisfy(
                        warning -> assertThat(warning).contains("1 client has a base URL that could not be resolved"));
    }

    @Test
    @DisplayName("a client with no declared base URL is NOT_DECLARED with unavailable provenance and no source")
    void reportsMissingBaseUrl() {
        HttpClientDto client = new HttpClientRegistryService(
                        new StubProvider(
                                true,
                                null,
                                List.of(client("builder", HttpClientVocabulary.KIND_REST_CLIENT_BUILDER, null))),
                        policy(ValueExposure.FULL),
                        null)
                .report()
                .clients()
                .get(0);

        assertThat(client.baseUrlStatus()).isEqualTo(HttpClientVocabulary.BASE_URL_NOT_DECLARED);
        assertThat(client.baseUrlProvenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_UNAVAILABLE);
        assertThat(client.baseUrlSource()).isNull();
    }

    @Test
    @DisplayName("credentials in a base URL never reach the browser, even under FULL exposure")
    void sanitizesBaseUrlUnderFullExposure() {
        HttpClientDto client = new HttpClientRegistryService(
                        new StubProvider(
                                true,
                                null,
                                List.of(client(
                                        "orders",
                                        HttpClientVocabulary.KIND_HTTP_INTERFACE,
                                        "https://admin:hunter2@orders.example.com/v1?token=abc"))),
                        policy(ValueExposure.FULL),
                        null)
                .report()
                .clients()
                .get(0);

        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com/v1?token=******");
        assertThat(client.configuredBaseUrl()).doesNotContain("hunter2");
    }

    @Test
    @DisplayName("a setting with no value stays explicitly unavailable rather than pretending to be a default")
    void keepsUnknownSettingsUnavailable() {
        DiscoveredHttpClient client = withSettings(
                DiscoveredHttpClientSetting.unavailable(HttpClientVocabulary.CATEGORY_TIMEOUT, "Connect timeout"));

        HttpClientSettingDto setting = new HttpClientRegistryService(
                        new StubProvider(true, null, List.of(client)), policy(ValueExposure.FULL), null)
                .report()
                .clients()
                .get(0)
                .settings()
                .get(0);

        assertThat(setting.value()).isNull();
        assertThat(setting.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_UNAVAILABLE);
        assertThat(setting.source()).isNull();
    }

    @Test
    @DisplayName("a credential-bearing setting is masked by its name and by its source key")
    void masksSettingsDefensively() {
        DiscoveredHttpClient client = withSettings(
                new DiscoveredHttpClientSetting(
                        HttpClientVocabulary.CATEGORY_PROXY,
                        "Proxy password",
                        "hunter2",
                        HttpClientVocabulary.PROVENANCE_CLIENT,
                        "spring.http.clients.proxy.password"),
                new DiscoveredHttpClientSetting(
                        HttpClientVocabulary.CATEGORY_TLS,
                        "Trust store",
                        "changeit",
                        HttpClientVocabulary.PROVENANCE_CLIENT,
                        "quarkus.rest-client.orders.trust-store-password"));

        List<HttpClientSettingDto> settings = new HttpClientRegistryService(
                        new StubProvider(true, null, List.of(client)), policy(ValueExposure.FULL), null)
                .report()
                .clients()
                .get(0)
                .settings();

        assertThat(settings).extracting(HttpClientSettingDto::value).containsExactly("******", "******");
    }

    @Test
    @DisplayName("an unknown provenance value is normalized instead of being echoed to the browser")
    void normalizesUnknownProvenance() {
        DiscoveredHttpClient client = withSettings(new DiscoveredHttpClientSetting(
                null, "Connect timeout", "2s", "SOMETHING-ELSE", "spring.http.clients.connect-timeout"));

        HttpClientSettingDto setting = new HttpClientRegistryService(
                        new StubProvider(true, null, List.of(client)), policy(ValueExposure.FULL), null)
                .report()
                .clients()
                .get(0)
                .settings()
                .get(0);

        assertThat(setting.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_UNAVAILABLE);
        assertThat(setting.category()).isEqualTo(HttpClientVocabulary.CATEGORY_TRANSPORT);
    }

    @Test
    @DisplayName("without a trace recorder every client reports observed calls as unavailable")
    void reportsObservedCallsUnavailableWithoutRecorder() {
        HttpClientRegistryReport report = new HttpClientRegistryService(
                        new StubProvider(
                                true,
                                null,
                                List.of(client(
                                        "orders",
                                        HttpClientVocabulary.KIND_HTTP_INTERFACE,
                                        "https://orders.example.com"))),
                        policy(ValueExposure.MASKED),
                        null)
                .report();

        assertThat(report.observedCallsAvailable()).isFalse();
        assertThat(report.observedCallsUnavailableReason()).isNotBlank();
        assertThat(report.clients().get(0).observedCallsStatus()).isEqualTo(HttpClientVocabulary.OBSERVED_UNAVAILABLE);
        assertThat(report.clients().get(0).observedCalls()).isEmpty();
    }

    @Test
    @DisplayName("retained calls are linked only to the single client that unambiguously owns the host")
    void linksObservedCallsToTheOwningClient() {
        RestClientTraceRecorder recorder = recorderWith("orders.example.com", "/v1/orders", 4);

        HttpClientRegistryReport report = new HttpClientRegistryService(
                        new StubProvider(
                                true,
                                null,
                                List.of(
                                        client(
                                                "orders",
                                                HttpClientVocabulary.KIND_HTTP_INTERFACE,
                                                "https://orders.example.com"),
                                        client(
                                                "billing",
                                                HttpClientVocabulary.KIND_HTTP_INTERFACE,
                                                "https://billing.example.com"))),
                        policy(ValueExposure.MASKED),
                        recorder)
                .report();

        assertThat(report.observedCallsAvailable()).isTrue();
        HttpClientDto orders = report.clients().get(1);
        assertThat(orders.name()).isEqualTo("orders");
        assertThat(orders.observedCallsStatus()).isEqualTo(HttpClientVocabulary.OBSERVED_LINKED);
        assertThat(orders.observedCalls()).singleElement().satisfies(link -> {
            assertThat(link.path()).isEqualTo("/v1/orders");
            assertThat(link.executions()).isEqualTo(4);
        });

        HttpClientDto billing = report.clients().get(0);
        assertThat(billing.observedCallsStatus()).isEqualTo(HttpClientVocabulary.OBSERVED_NO_CALLS);
    }

    @Test
    @DisplayName("a host claimed by two clients is never attributed to either of them")
    void refusesToAttributeAmbiguousHosts() {
        RestClientTraceRecorder recorder = recorderWith("shared.example.com", "/v1/things", 2);

        HttpClientRegistryReport report = new HttpClientRegistryService(
                        new StubProvider(
                                true,
                                null,
                                List.of(
                                        client(
                                                "a",
                                                HttpClientVocabulary.KIND_HTTP_INTERFACE,
                                                "https://shared.example.com/a"),
                                        client(
                                                "b",
                                                HttpClientVocabulary.KIND_HTTP_INTERFACE,
                                                "https://shared.example.com/b"))),
                        policy(ValueExposure.MASKED),
                        recorder)
                .report();

        assertThat(report.clients()).allSatisfy(client -> {
            assertThat(client.observedCallsStatus()).isEqualTo(HttpClientVocabulary.OBSERVED_NOT_ATTRIBUTABLE);
            assertThat(client.observedCalls()).isEmpty();
        });
        assertThat(report.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("1 host is shared by more than one client"));
    }

    @Test
    @DisplayName("at most five linked calls are retained per client, most-executed first")
    void boundsLinkedCallsPerClient() {
        RestClientTraceRecorder recorder = enabledRecorder();
        for (int index = 0; index < 8; index++) {
            for (int repeat = 0; repeat <= index; repeat++) {
                record(recorder, "orders.example.com", "/v1/resource-" + index);
            }
        }

        HttpClientDto orders = new HttpClientRegistryService(
                        new StubProvider(
                                true,
                                null,
                                List.of(client(
                                        "orders",
                                        HttpClientVocabulary.KIND_HTTP_INTERFACE,
                                        "https://orders.example.com"))),
                        policy(ValueExposure.MASKED),
                        recorder)
                .report()
                .clients()
                .get(0);

        assertThat(orders.observedCalls()).hasSize(HttpClientRegistryService.MAX_LINKED_CALLS_PER_CLIENT);
        assertThat(orders.observedCalls().get(0).path()).isEqualTo("/v1/resource-7");
        assertThat(orders.observedCalls())
                .isSortedAccordingTo((left, right) -> Long.compare(right.executions(), left.executions()));
    }

    @Test
    @DisplayName("a client whose base URL is unresolved is never linked to retained calls")
    void doesNotLinkUnresolvedClients() {
        RestClientTraceRecorder recorder = recorderWith("orders.example.com", "/v1/orders", 3);
        DiscoveredHttpClient unresolved = new DiscoveredHttpClient(
                "orders",
                HttpClientVocabulary.KIND_HTTP_INTERFACE,
                null,
                null,
                "https://${orders.host}",
                "https://${orders.host}",
                HttpClientVocabulary.PROVENANCE_CLIENT,
                "spring.http.serviceclient.orders.base-url",
                List.of());

        HttpClientDto client = new HttpClientRegistryService(
                        new StubProvider(true, null, List.of(unresolved)), policy(ValueExposure.MASKED), recorder)
                .report()
                .clients()
                .get(0);

        assertThat(client.observedCallsStatus()).isEqualTo(HttpClientVocabulary.OBSERVED_NOT_ATTRIBUTABLE);
    }

    @Test
    @DisplayName("an unknown kind degrades to a neutral label instead of leaking a raw provider string")
    void normalizesUnknownKind() {
        DiscoveredHttpClient client =
                new DiscoveredHttpClient("mystery", "SOME_NEW_KIND", null, null, null, null, null, null, List.of());

        HttpClientDto dto = new HttpClientRegistryService(
                        new StubProvider(true, null, List.of(client)), policy(ValueExposure.MASKED), null)
                .report()
                .clients()
                .get(0);

        assertThat(dto.kind()).isEqualTo("UNKNOWN");
        assertThat(dto.kindLabel()).isEqualTo("HTTP client");
        assertThat(dto.framework()).isEqualTo("Unknown");
        assertThat(dto.id()).isEqualTo("unknown:mystery");
    }

    @Test
    @DisplayName("nameless or null registrations are dropped rather than rendered as blank rows")
    void dropsNamelessClients() {
        HttpClientRegistryReport report = new HttpClientRegistryService(
                        new StubProvider(
                                true,
                                null,
                                java.util.Arrays.asList(
                                        null,
                                        new DiscoveredHttpClient(
                                                "  ",
                                                HttpClientVocabulary.KIND_HTTP_INTERFACE,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of()),
                                        client("orders", HttpClientVocabulary.KIND_HTTP_INTERFACE, null))),
                        policy(ValueExposure.MASKED),
                        null)
                .report();

        assertThat(report.total()).isEqualTo(1);
        assertThat(report.clients()).extracting(HttpClientDto::name).containsExactly("orders");
    }

    @Test
    @DisplayName("a null exposure policy degrades to MASKED rather than to FULL")
    void defaultsToMaskedExposure() {
        HttpClientRegistryReport report = new HttpClientRegistryService(
                        new StubProvider(
                                true,
                                null,
                                List.of(client(
                                        "orders",
                                        HttpClientVocabulary.KIND_HTTP_INTERFACE,
                                        "https://orders.example.com/v1?token=abc"))),
                        null,
                        null)
                .report();

        assertThat(report.valueExposure()).isEqualTo("MASKED");
        assertThat(report.clients().get(0).resolvedBaseUrl()).isEqualTo("https://orders.example.com/v1?token=******");
    }

    private static DiscoveredHttpClient client(String name, String kind, String baseUrl) {
        return new DiscoveredHttpClient(
                name,
                kind,
                null,
                name,
                baseUrl,
                baseUrl,
                baseUrl == null ? null : HttpClientVocabulary.PROVENANCE_CLIENT,
                baseUrl == null ? null : "config." + name + ".base-url",
                List.of());
    }

    private static DiscoveredHttpClient withSettings(DiscoveredHttpClientSetting... settings) {
        return new DiscoveredHttpClient(
                "orders",
                HttpClientVocabulary.KIND_HTTP_INTERFACE,
                null,
                "orders",
                "https://orders.example.com",
                "https://orders.example.com",
                HttpClientVocabulary.PROVENANCE_CLIENT,
                "config.orders.base-url",
                List.of(settings));
    }

    private static RestClientTraceRecorder enabledRecorder() {
        RestClientTraceRecorder recorder = new RestClientTraceRecorder(true, true, false, false, 100, 500, 200, 200, 3);
        recorder.registerClientCustomization("RestClient");
        return recorder;
    }

    private static RestClientTraceRecorder recorderWith(String host, String path, int executions) {
        RestClientTraceRecorder recorder = enabledRecorder();
        for (int index = 0; index < executions; index++) {
            record(recorder, host, path);
        }
        return recorder;
    }

    private static void record(RestClientTraceRecorder recorder, String host, String path) {
        recorder.record(
                "GET", "https://" + host + path, host, path, 200, 12L, true, null, "RestClient", Map.of(), "main");
    }

    @Test
    @DisplayName("METADATA_ONLY hides every setting value but keeps its name, provenance and key")
    void hidesSettingValuesUnderMetadataOnly() {
        HttpClientRegistryService service = new HttpClientRegistryService(
                new StubProvider(
                        true,
                        "none",
                        List.of(withSettings(new DiscoveredHttpClientSetting(
                                HttpClientVocabulary.CATEGORY_TIMEOUT,
                                "Connect timeout",
                                "2s",
                                HttpClientVocabulary.PROVENANCE_CLIENT,
                                "spring.http.serviceclient.orders.connect-timeout")))),
                policy(ValueExposure.METADATA_ONLY),
                null);

        HttpClientSettingDto setting =
                service.report().clients().get(0).settings().get(0);
        assertThat(setting.value()).isNull();
        assertThat(setting.name()).isEqualTo("Connect timeout");
        assertThat(setting.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_CLIENT);
        assertThat(setting.source()).isEqualTo("spring.http.serviceclient.orders.connect-timeout");
    }

    @Test
    @DisplayName("a secret-looking client name does not mask that client's harmless settings")
    void masksOnTheSettingRatherThanTheClientName() {
        HttpClientRegistryService service = new HttpClientRegistryService(
                new StubProvider(
                        true,
                        "none",
                        List.of(withSettings(new DiscoveredHttpClientSetting(
                                HttpClientVocabulary.CATEGORY_TIMEOUT,
                                "Connect timeout",
                                "2s",
                                HttpClientVocabulary.PROVENANCE_CLIENT,
                                "spring.http.serviceclient.api-key-service.connect-timeout")))),
                policy(ValueExposure.MASKED),
                null);

        assertThat(service.report().clients().get(0).settings().get(0).value()).isEqualTo("2s");
    }

    @Test
    @DisplayName("a provider that returns null instead of an empty list fails closed")
    void toleratesNullClientList() {
        HttpClientRegistryService service =
                new HttpClientRegistryService(new StubProvider(true, "none", null), policy(ValueExposure.MASKED), null);

        assertThat(service.report().available()).isFalse();
    }

    @Test
    @DisplayName("trace evidence still works when no exposure policy is wired")
    void toleratesMissingExposurePolicyWithARecorder() {
        HttpClientRegistryService service = new HttpClientRegistryService(
                new StubProvider(
                        true,
                        "none",
                        List.of(client(
                                "orders", HttpClientVocabulary.KIND_HTTP_INTERFACE, "https://orders.example.com"))),
                null,
                recorderWith("orders.example.com", "/v1/orders", 2));

        HttpClientDto dto = service.report().clients().get(0);
        assertThat(dto.observedCallsStatus()).isEqualTo(HttpClientVocabulary.OBSERVED_LINKED);
    }

    private static ExposurePolicy policy(ValueExposure exposure) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return exposure != ValueExposure.FULL;
            }
        };
    }

    private record StubProvider(boolean available, String unavailableReason, List<DiscoveredHttpClient> clients)
            implements HttpClientProvider {}
}
