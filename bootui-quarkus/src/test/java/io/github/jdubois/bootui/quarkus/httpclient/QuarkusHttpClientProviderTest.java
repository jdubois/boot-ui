package io.github.jdubois.bootui.quarkus.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.spi.DiscoveredHttpClient;
import io.github.jdubois.bootui.spi.DiscoveredHttpClientSetting;
import io.github.jdubois.bootui.spi.HttpClientVocabulary;
import io.smallrye.config.FallbackConfigSourceInterceptor;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the Quarkus adapter's mapping from a build-time-captured {@code @RegisterRestClient} registration plus
 * live SmallRye configuration onto the shared SPI record. It needs no Quarkus runtime: the provider is a pure
 * function of the captured holder and the config.
 */
class QuarkusHttpClientProviderTest {

    @Test
    @DisplayName("an unsatisfied holder means no REST Client extension, so the panel fails closed")
    void failsClosedWithoutTheCapturedHolder() {
        QuarkusHttpClientProvider provider = new QuarkusHttpClientProvider(unsatisfied(), config(Map.of()));

        assertThat(provider.available()).isFalse();
        assertThat(provider.clients()).isEmpty();
        assertThat(provider.unavailableReason()).contains("@RegisterRestClient");
    }

    @Test
    @DisplayName("a satisfied but empty holder is still unavailable rather than an empty table")
    void failsClosedWithoutCapturedClients() {
        QuarkusHttpClientProvider provider = new QuarkusHttpClientProvider(holder(), config(Map.of()));

        assertThat(provider.available()).isFalse();
    }

    @Test
    @DisplayName("the per-client url wins, and the reported source is the key it actually came from")
    void readsPerClientUrl() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "orders", ""),
                Map.of("quarkus.rest-client.orders.url", "https://orders.example.com"));

        assertThat(client.name()).isEqualTo("orders");
        assertThat(client.kind()).isEqualTo(HttpClientVocabulary.KIND_MICROPROFILE_REST_CLIENT);
        assertThat(client.declaredInterface()).isEqualTo("com.example.OrdersClient");
        assertThat(client.configKey()).isEqualTo("orders");
        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
        assertThat(client.baseUrlProvenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_CLIENT);
        assertThat(client.baseUrlSource()).isEqualTo("quarkus.rest-client.orders.url");
    }

    @Test
    @DisplayName("the MicroProfile standard form is honored when the Quarkus namespace is not used")
    void readsMicroProfileUrl() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "orders", ""),
                Map.of("orders/mp-rest/url", "https://orders.example.com"));

        assertThat(client.baseUrlSource()).isEqualTo("orders/mp-rest/url");
        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
    }

    @Test
    @DisplayName("the annotation baseUri is the last resort, and says so")
    void fallsBackToTheAnnotationBaseUri() {
        DiscoveredHttpClient client =
                single(new RawHttpClient("com.example.OrdersClient", "", "https://annotated.example.com"), Map.of());

        assertThat(client.name()).isEqualTo("OrdersClient");
        assertThat(client.configKey()).isNull();
        assertThat(client.resolvedBaseUrl()).isEqualTo("https://annotated.example.com");
        assertThat(client.baseUrlProvenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_ANNOTATION);
        assertThat(client.baseUrlSource()).isEqualTo("@RegisterRestClient(baseUri)");
    }

    @Test
    @DisplayName("a client with no configured or annotated target reports no base URL at all")
    void reportsNoBaseUrlWhenNoneIsDeclared() {
        DiscoveredHttpClient client = single(new RawHttpClient("com.example.OrdersClient", "orders", ""), Map.of());

        assertThat(client.configuredBaseUrl()).isNull();
        assertThat(client.resolvedBaseUrl()).isNull();
        assertThat(client.baseUrlProvenance()).isNull();
    }

    @Test
    @DisplayName("a resolvable expression is shown raw and expanded side by side")
    void expandsResolvableExpressions() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "orders", ""),
                Map.of(
                        "orders.host", "orders.example.com",
                        "quarkus.rest-client.orders.url", "https://${orders.host}"));

        assertThat(client.configuredBaseUrl()).isEqualTo("https://${orders.host}");
        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
    }

    @Test
    @DisplayName("an expression that cannot expand keeps its raw form instead of throwing")
    void keepsUnresolvableExpressionsRaw() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "orders", ""),
                Map.of("quarkus.rest-client.orders.url", "https://${orders.missing.host}"));

        assertThat(client.configuredBaseUrl()).isEqualTo("https://${orders.missing.host}");
        assertThat(client.resolvedBaseUrl()).isEqualTo("https://${orders.missing.host}");
    }

    @Test
    @DisplayName("a client-specific setting wins over the global default, and each keeps its own provenance")
    void distinguishesClientSpecificFromGlobalSettings() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "orders", ""),
                Map.of(
                        "quarkus.rest-client.orders.connect-timeout", "2000",
                        "quarkus.rest-client.read-timeout", "30000"));

        assertThat(setting(client, "Connect timeout")).hasValueSatisfying(value -> {
            assertThat(value.value()).isEqualTo("2000ms");
            assertThat(value.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_CLIENT);
            assertThat(value.source()).isEqualTo("quarkus.rest-client.orders.connect-timeout");
        });
        assertThat(setting(client, "Read timeout")).hasValueSatisfying(value -> {
            assertThat(value.value()).isEqualTo("30000ms");
            assertThat(value.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_APPLICATION);
            assertThat(value.source()).isEqualTo("quarkus.rest-client.read-timeout");
        });
    }

    @Test
    @DisplayName("an unset setting stays explicitly unavailable rather than showing an invented default")
    void keepsUnsetSettingsUnavailable() {
        DiscoveredHttpClient client = single(new RawHttpClient("com.example.OrdersClient", "orders", ""), Map.of());

        assertThat(setting(client, "Connect timeout")).hasValueSatisfying(value -> {
            assertThat(value.value()).isNull();
            assertThat(value.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_UNAVAILABLE);
        });
    }

    @Test
    @DisplayName("key and trust stores are reported as presence only, and their passwords are never read")
    void reportsStoresAsPresenceOnly() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "orders", ""),
                Map.of(
                        "quarkus.rest-client.orders.trust-store", "/etc/ssl/truststore.p12",
                        "quarkus.rest-client.orders.trust-store-password", "hunter2"));

        assertThat(setting(client, "Trust store"))
                .hasValueSatisfying(value -> assertThat(value.value()).isEqualTo("Configured"));
        assertThat(client.settings())
                .noneSatisfy(value -> assertThat(String.valueOf(value.value())).contains("hunter2"));
        assertThat(client.settings())
                .noneSatisfy(value -> assertThat(String.valueOf(value.source())).contains("password"));
    }

    @Test
    @DisplayName("a quoted config key is resolved, which is the form Quarkus documents for dotted keys")
    void supportsQuotedConfigKeys() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "com.example.OrdersClient", ""),
                Map.of("quarkus.rest-client.\"com.example.OrdersClient\".url", "https://orders.example.com"));

        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
    }

    @Test
    @DisplayName("a client-scoped MicroProfile member beats the global Quarkus default")
    void prefersClientScopedMicroProfileMemberOverGlobalDefault() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "orders", "https://orders.example.com"),
                Map.of(
                        "orders/mp-rest/connectTimeout", "2000",
                        "quarkus.rest-client.connect-timeout", "10000"));

        assertThat(setting(client, "Connect timeout")).hasValueSatisfying(value -> {
            assertThat(value.value()).isEqualTo("2000ms");
            assertThat(value.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_CLIENT);
            assertThat(value.source()).isEqualTo("orders/mp-rest/connectTimeout");
        });
    }

    @Test
    @DisplayName("the fully-qualified interface key is honoured even when a config key is declared")
    void readsTheFullyQualifiedKeyAlongsideAConfigKey() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "orders", ""),
                Map.of("quarkus.rest-client.\"com.example.OrdersClient\".url", "https://orders.example.com"));

        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
        assertThat(client.baseUrlSource()).isEqualTo("quarkus.rest-client.\"com.example.OrdersClient\".url");
    }

    @Test
    @DisplayName("the simple interface name Quarkus documents is a valid key too")
    void readsTheSimpleNameKey() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "", ""),
                Map.of("quarkus.rest-client.OrdersClient.url", "https://orders.example.com"));

        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
        assertThat(client.baseUrlSource()).isEqualTo("quarkus.rest-client.OrdersClient.url");
    }

    @Test
    @DisplayName("an unresolvable expression under an active profile stays visible instead of vanishing")
    void keepsUnresolvedProfiledExpressionVisible() {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withProfile("test")
                .withSources(new PropertiesConfigSource(
                        Map.of("%test.quarkus.rest-client.orders.url", "https://${missing.inventory.host}/v1"),
                        "test",
                        100))
                .build();
        QuarkusHttpClientProvider provider = new QuarkusHttpClientProvider(
                holder(new RawHttpClient("com.example.OrdersClient", "orders", "")), config);

        DiscoveredHttpClient client = provider.clients().get(0);
        assertThat(client.configuredBaseUrl()).contains("${missing.inventory.host}");
    }

    @Test
    @DisplayName("the fully-qualified key wins when the application declares both forms, as Quarkus resolves it")
    void prefersTheFullyQualifiedKeyWhenBothFormsAreDeclared() {
        DiscoveredHttpClient client = single(
                new RawHttpClient("com.example.OrdersClient", "orders", ""),
                Map.of(
                        "quarkus.rest-client.orders.url", "https://orders-by-config-key.example.com",
                        "quarkus.rest-client.\"com.example.OrdersClient\".url", "https://orders.example.com"));

        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
        assertThat(client.baseUrlSource()).isEqualTo("quarkus.rest-client.\"com.example.OrdersClient\".url");
    }

    @Test
    @DisplayName("the reported source is the key the application wrote, not the one Quarkus falls back from")
    void reportsTheDeclaredKeyRatherThanAFallbackForm() {
        // Quarkus registers a fallback so a client identified by its interface name still picks up the value
        // written under its config key. A naive probe resolves the interface-name key and reports a key that
        // appears nowhere in the application's configuration, which is useless to the developer.
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withInterceptors(new FallbackConfigSourceInterceptor(
                        name -> name.startsWith("quarkus.rest-client.\"com.example.OrdersClient\".")
                                ? "quarkus.rest-client.orders."
                                        + name.substring("quarkus.rest-client.\"com.example.OrdersClient\".".length())
                                : name))
                .withSources(new PropertiesConfigSource(
                        Map.of("quarkus.rest-client.orders.url", "https://orders.example.com"), "test", 100))
                .build();

        DiscoveredHttpClient client = new QuarkusHttpClientProvider(
                        holder(new RawHttpClient("com.example.OrdersClient", "orders", "")), config)
                .clients()
                .get(0);

        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
        assertThat(client.baseUrlSource()).isEqualTo("quarkus.rest-client.orders.url");
    }

    private static DiscoveredHttpClient single(RawHttpClient raw, Map<String, String> properties) {
        QuarkusHttpClientProvider provider = new QuarkusHttpClientProvider(holder(raw), config(properties));
        assertThat(provider.available()).isTrue();
        return provider.clients().get(0);
    }

    private static Optional<DiscoveredHttpClientSetting> setting(DiscoveredHttpClient client, String name) {
        return client.settings().stream()
                .filter(setting -> name.equals(setting.name()))
                .findFirst();
    }

    private static SmallRyeConfig config(Map<String, String> properties) {
        // Default interceptors are what give SmallRye its ${...} expression expansion, exactly as Quarkus
        // builds it at runtime.
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Instance<QuarkusHttpClients> unsatisfied() {
        Instance<QuarkusHttpClients> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private static Instance<QuarkusHttpClients> holder(RawHttpClient... clients) {
        Instance<QuarkusHttpClients> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(new QuarkusHttpClients(List.of(clients)));
        return instance;
    }
}
