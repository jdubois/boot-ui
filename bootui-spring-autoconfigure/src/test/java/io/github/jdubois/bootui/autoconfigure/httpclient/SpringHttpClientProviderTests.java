package io.github.jdubois.bootui.autoconfigure.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.DiscoveredHttpClient;
import io.github.jdubois.bootui.spi.DiscoveredHttpClientSetting;
import io.github.jdubois.bootui.spi.HttpClientVocabulary;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Discovery tests for the Spring adapter. They pin the two guarantees that matter most: registrations are
 * read from bean definitions without ever instantiating a client, and an unresolved property placeholder
 * stays visible instead of being silently interpolated away or throwing.
 */
class SpringHttpClientProviderTests {

    @Test
    @DisplayName("an empty bean factory yields no clients and a Spring-specific setup hint")
    void reportsNothingWhenNoClientIsRegistered() {
        SpringHttpClientProvider provider =
                new SpringHttpClientProvider(new DefaultListableBeanFactory(), new MockEnvironment());

        assertThat(provider.clients()).isEmpty();
        assertThat(provider.available()).isFalse();
        assertThat(provider.unavailableReason()).contains("@HttpExchange").contains("@ImportHttpServices");
    }

    @Test
    @DisplayName("a null bean factory degrades to an empty registry instead of throwing")
    void toleratesMissingBeanFactory() {
        SpringHttpClientProvider provider = new SpringHttpClientProvider(null, new MockEnvironment());

        assertThat(provider.clients()).isEmpty();
        assertThat(provider.available()).isFalse();
    }

    @Test
    @DisplayName("an HTTP Interface group is discovered from its bean definition, never by resolving the proxy")
    void discoversHttpInterfaceGroups() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "orders#com.example.OrdersClient", httpInterfaceDefinition("orders", "com.example.OrdersClient"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.http.serviceclient.orders.base-url", "https://orders.example.com")
                .withProperty("spring.http.serviceclient.orders.connect-timeout", "2s")
                .withProperty("spring.http.clients.read-timeout", "30s");

        DiscoveredHttpClient client =
                new SpringHttpClientProvider(beanFactory, environment).clients().get(0);

        assertThat(client.name()).isEqualTo("orders");
        assertThat(client.kind()).isEqualTo(HttpClientVocabulary.KIND_HTTP_INTERFACE);
        assertThat(client.declaredInterface()).isEqualTo("com.example.OrdersClient");
        assertThat(client.configKey()).isEqualTo("orders");
        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
        assertThat(client.baseUrlProvenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_CLIENT);
        assertThat(client.baseUrlSource()).isEqualTo("spring.http.serviceclient.orders.base-url");
    }

    @Test
    @DisplayName("a client-specific value wins over the application default, and each keeps its own provenance")
    void distinguishesClientSpecificFromInheritedSettings() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "orders#com.example.OrdersClient", httpInterfaceDefinition("orders", "com.example.OrdersClient"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.http.serviceclient.orders.connect-timeout", "2s")
                .withProperty("spring.http.clients.read-timeout", "30s");

        List<DiscoveredHttpClientSetting> settings = new SpringHttpClientProvider(beanFactory, environment)
                .clients()
                .get(0)
                .settings();

        assertThat(setting(settings, "Connect timeout")).hasValueSatisfying(value -> {
            assertThat(value.value()).isEqualTo("2s");
            assertThat(value.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_CLIENT);
            assertThat(value.source()).isEqualTo("spring.http.serviceclient.orders.connect-timeout");
        });
        assertThat(setting(settings, "Read timeout")).hasValueSatisfying(value -> {
            assertThat(value.value()).isEqualTo("30s");
            assertThat(value.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_APPLICATION);
            assertThat(value.source()).isEqualTo("spring.http.clients.read-timeout");
        });
        assertThat(setting(settings, "Redirects"))
                .hasValueSatisfying(value -> assertThat(value.value()).isNull());
    }

    @Test
    @DisplayName("an unresolved placeholder survives into the report instead of being interpolated away")
    void keepsUnresolvedPlaceholderVisible() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "orders#com.example.OrdersClient", httpInterfaceDefinition("orders", "com.example.OrdersClient"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.http.serviceclient.orders.base-url", "https://${orders.host}/v1");

        DiscoveredHttpClient client =
                new SpringHttpClientProvider(beanFactory, environment).clients().get(0);

        assertThat(client.configuredBaseUrl()).isEqualTo("https://${orders.host}/v1");
        assertThat(client.resolvedBaseUrl()).isEqualTo("https://${orders.host}/v1");
    }

    @Test
    @DisplayName("a resolvable placeholder is shown raw and resolved side by side")
    void resolvesPlaceholdersWhenPossible() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "orders#com.example.OrdersClient", httpInterfaceDefinition("orders", "com.example.OrdersClient"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("orders.host", "orders.example.com")
                .withProperty("spring.http.serviceclient.orders.base-url", "https://${orders.host}/v1");

        DiscoveredHttpClient client =
                new SpringHttpClientProvider(beanFactory, environment).clients().get(0);

        assertThat(client.configuredBaseUrl()).isEqualTo("https://${orders.host}/v1");
        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com/v1");
    }

    @Test
    @DisplayName("a group name containing dots is still resolved through the indexed property form")
    void supportsIndexedPropertyForm() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "com.example.OrdersClient#com.example.OrdersClient",
                httpInterfaceDefinition("com.example.OrdersClient", "com.example.OrdersClient"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "spring.http.serviceclient[\"com.example.OrdersClient\"].base-url",
                        "https://orders.example.com");

        DiscoveredHttpClient client =
                new SpringHttpClientProvider(beanFactory, environment).clients().get(0);

        assertThat(client.resolvedBaseUrl()).isEqualTo("https://orders.example.com");
    }

    @Test
    @DisplayName(
            "an OpenFeign registration is read from the factory bean definition without Spring Cloud on the classpath")
    void discoversOpenFeignClientsWithoutSpringCloud() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setBeanClassName("org.springframework.cloud.openfeign.FeignClientFactoryBean");
        definition.getPropertyValues().add("name", "billing");
        definition.getPropertyValues().add("contextId", "billing");
        definition.getPropertyValues().add("type", "com.example.BillingClient");
        definition.getPropertyValues().add("url", "https://billing.example.com");
        definition.getPropertyValues().add("path", "/api");
        beanFactory.registerBeanDefinition("billing", definition);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.cloud.openfeign.client.config.billing.connectTimeout", "1500")
                .withProperty("spring.cloud.openfeign.client.config.default.readTimeout", "9000");

        DiscoveredHttpClient client =
                new SpringHttpClientProvider(beanFactory, environment).clients().get(0);

        assertThat(client.kind()).isEqualTo(HttpClientVocabulary.KIND_OPEN_FEIGN);
        assertThat(client.name()).isEqualTo("billing");
        assertThat(client.declaredInterface()).isEqualTo("com.example.BillingClient");
        assertThat(client.resolvedBaseUrl()).isEqualTo("https://billing.example.com");
        assertThat(client.baseUrlProvenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_ANNOTATION);
        assertThat(client.baseUrlSource()).isEqualTo("@FeignClient(url)");
        assertThat(setting(client.settings(), "Connect timeout"))
                .hasValueSatisfying(
                        value -> assertThat(value.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_CLIENT));
        assertThat(setting(client.settings(), "Read timeout"))
                .hasValueSatisfying(
                        value -> assertThat(value.provenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_APPLICATION));
        assertThat(setting(client.settings(), "Path prefix"))
                .hasValueSatisfying(value -> assertThat(value.value()).isEqualTo("/api"));
    }

    @Test
    @DisplayName("a configured OpenFeign url overrides the annotation url and says so")
    void prefersConfiguredOpenFeignUrl() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setBeanClassName("org.springframework.cloud.openfeign.FeignClientFactoryBean");
        definition.getPropertyValues().add("contextId", "billing");
        // Spring Cloud lets the property override the annotation, so an application that overrides its
        // target per environment must not be shown the hard-coded annotation value.
        definition.getPropertyValues().add("url", "https://billing.example.com");
        beanFactory.registerBeanDefinition("billing", definition);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.cloud.openfeign.client.config.billing.url", "https://billing.internal");

        DiscoveredHttpClient client =
                new SpringHttpClientProvider(beanFactory, environment).clients().get(0);

        assertThat(client.resolvedBaseUrl()).isEqualTo("https://billing.internal");
        assertThat(client.baseUrlProvenance()).isEqualTo(HttpClientVocabulary.PROVENANCE_CLIENT);
        assertThat(client.baseUrlSource()).isEqualTo("spring.cloud.openfeign.client.config.billing.url");
    }

    @Test
    @DisplayName("OpenFeign millisecond timeouts render like every other adapter's timeout")
    void normalizesOpenFeignTimeouts() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setBeanClassName("org.springframework.cloud.openfeign.FeignClientFactoryBean");
        definition.getPropertyValues().add("contextId", "billing");
        beanFactory.registerBeanDefinition("billing", definition);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.cloud.openfeign.client.config.billing.connectTimeout", "1500");

        DiscoveredHttpClient client =
                new SpringHttpClientProvider(beanFactory, environment).clients().get(0);

        assertThat(setting(client.settings(), "Connect timeout"))
                .hasValueSatisfying(value -> assertThat(value.value()).isEqualTo("1500ms"));
    }

    @Test
    @DisplayName("a group declaring several interfaces is one client, not one card per interface")
    void reportsAMultiInterfaceGroupOnce() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "orders#com.example.OrdersClient", httpInterfaceDefinition("orders", "com.example.OrdersClient"));
        beanFactory.registerBeanDefinition(
                "orders#com.example.OrdersAdminClient",
                httpInterfaceDefinition("orders", "com.example.OrdersAdminClient"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.http.serviceclient.orders.base-url", "https://orders.example.com");

        List<DiscoveredHttpClient> clients = new SpringHttpClientProvider(beanFactory, environment).clients();

        assertThat(clients).hasSize(1);
        assertThat(clients.get(0).name()).isEqualTo("orders");
        assertThat(clients.get(0).declaredInterface()).isNull();
        assertThat(setting(clients.get(0).settings(), "HTTP service interfaces"))
                .hasValueSatisfying(value -> assertThat(value.value())
                        .contains("com.example.OrdersClient")
                        .contains("com.example.OrdersAdminClient"));
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    @DisplayName("a deprecated client factory key is still reported instead of looking unset")
    void readsLegacyClientFactoryKey() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "orders#com.example.OrdersClient", httpInterfaceDefinition("orders", "com.example.OrdersClient"));
        MockEnvironment environment = new MockEnvironment().withProperty("spring.http.client.factory", "jdk");

        DiscoveredHttpClient client =
                new SpringHttpClientProvider(beanFactory, environment).clients().get(0);

        assertThat(setting(client.settings(), "Client factory")).hasValueSatisfying(value -> {
            assertThat(value.value()).isEqualTo("jdk");
            assertThat(value.source()).isEqualTo("spring.http.client.factory");
        });
    }

    @Test
    @DisplayName("builder beans are reported without a guessed base URL, and never eagerly initialized")
    void discoversBuilderBeansWithoutInstantiatingThem() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition restClientBuilder = new RootBeanDefinition(RestClient.Builder.class, RestClient::builder);
        restClientBuilder.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        beanFactory.registerBeanDefinition("restClientBuilder", restClientBuilder);
        beanFactory.registerBeanDefinition(
                "appWebClientBuilder", new RootBeanDefinition(WebClient.Builder.class, WebClient::builder));

        List<DiscoveredHttpClient> clients = new SpringHttpClientProvider(beanFactory, new MockEnvironment()).clients();

        assertThat(clients).hasSize(2);
        assertThat(clients).allSatisfy(client -> {
            assertThat(client.configuredBaseUrl()).isNull();
            assertThat(client.resolvedBaseUrl()).isNull();
            assertThat(client.baseUrlProvenance()).isNull();
        });
        assertThat(clients)
                .extracting(DiscoveredHttpClient::kind)
                .containsExactlyInAnyOrder(
                        HttpClientVocabulary.KIND_REST_CLIENT_BUILDER, HttpClientVocabulary.KIND_WEB_CLIENT_BUILDER);
        assertThat(setting(clients.get(0).settings(), "Ownership"))
                .hasValueSatisfying(value -> assertThat(value.value()).isEqualTo("Framework-managed builder"));
        assertThat(setting(clients.get(1).settings(), "Ownership"))
                .hasValueSatisfying(value -> assertThat(value.value()).isEqualTo("Application-defined builder"));
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    @DisplayName("the reactive builder reads the reactive legacy prefix, not the imperative one")
    void usesTheStackSpecificLegacyPrefix() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "webClientBuilder", new RootBeanDefinition(WebClient.Builder.class, WebClient::builder));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.http.reactiveclient.connect-timeout", "4s")
                .withProperty("spring.http.client.connect-timeout", "99s");

        DiscoveredHttpClient client =
                new SpringHttpClientProvider(beanFactory, environment).clients().get(0);

        assertThat(setting(client.settings(), "Connect timeout"))
                .hasValueSatisfying(value -> assertThat(value.value()).isEqualTo("4s"));
    }

    private static RootBeanDefinition httpInterfaceDefinition(String group, String declaredInterface) {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setBeanClassName(declaredInterface);
        definition.setAttribute("httpServiceGroupName", group);
        // The real registrar sets an instance supplier; failing to resolve it here would prove that the
        // provider never asks the bean factory to create the proxy.
        definition.setInstanceSupplier(() -> {
            throw new IllegalStateException("BootUI must never instantiate a declared HTTP client");
        });
        return definition;
    }

    private static Optional<DiscoveredHttpClientSetting> setting(
            List<DiscoveredHttpClientSetting> settings, String name) {
        return settings.stream().filter(setting -> name.equals(setting.name())).findFirst();
    }
}
