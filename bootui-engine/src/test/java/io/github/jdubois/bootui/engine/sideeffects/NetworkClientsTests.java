package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NetworkClientsTests {

    @Test
    void aJdbcDriversFrameIsAnSqlClient() {
        NetworkClients.Client client = NetworkClients.recognize(
                "org.postgresql.core.PGStream#createSocket",
                "org.postgresql.core.PGStream#createSocket",
                null,
                "main",
                "db:5432");

        assertThat(client.label()).isEqualTo("PostgreSQL JDBC");
        assertThat(client.category()).isEqualTo(NetworkClients.SQL);
        assertThat(SideEffectsService.captureKey(client)).isEqualTo(SideEffectsStore.CAPTURE_SQL);
    }

    @Test
    void theJdksOwnClientsAreHttpClientsWhoseConnectsWaitForARestCall() {
        NetworkClients.Client client = NetworkClients.recognize(
                "jdk.internal.net.http.PlainHttpConnection#lambda$connectAsync$0",
                null,
                null,
                "HttpClient-{n}-Worker-{n}",
                "api.example.com:443");

        assertThat(client.label()).isEqualTo("JDK HttpClient");
        assertThat(SideEffectsService.captureKey(client)).isEqualTo(SideEffectsStore.REST_WAITING_HTTP);
        assertThat(NetworkClients.recognize("sun.net.NetworkClient#doConnect", null, null, "main", "x:80")
                        .label())
                .isEqualTo("JDK HttpURLConnection");
    }

    @Test
    void aNettyEventLoopsConnectIsRecognizedByItsThreadBeforeTheSharedTransport() {
        NetworkClients.Client lettuce = NetworkClients.recognize(
                null, "io.netty.util.internal.SocketUtils$3#run", null, "lettuce-nioEventLoop-{n}-{n}", "cache:6379");
        NetworkClients.Client netty =
                NetworkClients.recognize(null, "io.netty.util.internal.SocketUtils$3#run", null, "worker", "x:1");

        assertThat(lettuce.label()).isEqualTo("Lettuce");
        assertThat(netty.label()).isEqualTo("Netty");
    }

    @Test
    void messagingClientsCarryTheirBrokerAndInfrastructureIsNoPanels() {
        NetworkClients.Client kafka = NetworkClients.recognize(
                "org.apache.kafka.common.network.Selector#connect",
                null,
                null,
                "kafka-producer-network-thread",
                "broker:9092");
        NetworkClients.Client dns = NetworkClients.recognize(null, null, null, "worker", "10.0.0.2:53");

        assertThat(SideEffectsService.captureKey(kafka)).isEqualTo(SideEffectsStore.CAPTURE_MESSAGING + "kafka");
        assertThat(SideEffectsService.captureKey(dns)).isEqualTo(SideEffectsStore.CAPTURE_INFRASTRUCTURE);
        assertThat(SideEffectsService.captureKey(null)).isEqualTo(SideEffectsStore.REST_WAITING);
    }

    @Test
    void anApplicationsOwnSocketIsUnrecognized() {
        assertThat(NetworkClients.recognize(
                        "com.example.sdk.LicenseClient#check",
                        "com.example.sdk.LicenseClient#check",
                        "com.example.sdk.LicenseClient#check",
                        "http-nio-{n}-exec-{n}",
                        "localhost:8080"))
                .isNull();
    }

    @Test
    void anOpenTelemetryExportersOkHttpConnectToItsConfiguredEndpointIsInfrastructure() {
        java.util.Set<String> endpoints = NetworkClients.endpoints(java.util.Map.of(
                "management.otlp.tracing.endpoint", "http://Otel-Collector:4318/v1/traces",
                "quarkus.otel.exporter.otlp.endpoint", "https://collector.example.com")::get);
        // OkHttp names its threads after the redacted URL: "OkHttp " + url.redact().
        NetworkClients.Client okHttp = NetworkClients.recognize(
                "okhttp3.internal.connection.RealConnection#connectSocket",
                "okhttp3.internal.connection.RealConnection#connectSocket",
                null,
                "OkHttp http://otel-collector:{n}/...",
                "otel-collector:4318",
                endpoints);
        NetworkClients.Client byFrame = NetworkClients.recognize(
                "io.opentelemetry.exporter.sender.okhttp.internal.OkHttpHttpSender#send",
                "okhttp3.internal.connection.RealConnection#connectSocket",
                null,
                "worker",
                "collector:80");

        assertThat(endpoints).containsExactlyInAnyOrder("otel-collector:4318", "collector.example.com:443");
        assertThat(okHttp.category()).isEqualTo(NetworkClients.INFRASTRUCTURE);
        assertThat(byFrame.label()).isEqualTo("OpenTelemetry exporter");
        assertThat(SideEffectsService.captureKey(okHttp)).isEqualTo(SideEffectsStore.CAPTURE_INFRASTRUCTURE);
    }

    @Test
    void openTelemetryInstrumentationAroundAnApplicationsCallsLeavesThemTheirClients() {
        NetworkClients.Client rest = NetworkClients.recognize(
                "sun.net.www.protocol.http.HttpURLConnection#connect",
                "io.opentelemetry.instrumentation.spring.web.v3_1.RestTemplateInterceptor#intercept",
                "com.example.OrderService#price",
                "http-nio-{n}-exec-{n}",
                "prices.example.com:443");
        NetworkClients.Client jdbc = NetworkClients.recognize(
                "org.postgresql.core.PGStream#createSocket",
                "org.postgresql.core.PGStream#createSocket",
                "com.example.OrderRepository#find",
                "http-nio-{n}-exec-{n}",
                "db:5432");
        NetworkClients.Client throughDataSource = NetworkClients.recognize(
                "org.postgresql.core.PGStream#createSocket",
                "io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource#getConnection",
                null,
                "main",
                "db:5432");

        assertThat(rest.category()).isEqualTo(NetworkClients.HTTP);
        assertThat(jdbc.category()).isEqualTo(NetworkClients.SQL);
        assertThat(throughDataSource.category()).isEqualTo(NetworkClients.SQL);
    }

    @Test
    void theJdkSenderAndTheMicrometerOtlpRegistryAreInfrastructureByThreadOrFrame() {
        assertThat(NetworkClients.recognize(
                                "jdk.internal.net.http.PlainHttpConnection#connectAsync",
                                null,
                                null,
                                "BatchSpanProcessor_WorkerThread-{n}",
                                "collector:80")
                        .category())
                .isEqualTo(NetworkClients.INFRASTRUCTURE);
        assertThat(NetworkClients.recognize(
                                "io.micrometer.registry.otlp.OtlpMeterRegistry#publish",
                                null,
                                null,
                                "otlp-metrics-publisher",
                                "collector:80")
                        .category())
                .isEqualTo(NetworkClients.INFRASTRUCTURE);
    }

    @Test
    void anApplicationsOwnHttpCallToAWellKnownPortStaysItsHttpClients() {
        assertThat(NetworkClients.recognize(
                                "jdk.internal.net.http.PlainHttpConnection#connectAsync",
                                null,
                                "com.example.Metrics#push",
                                "HttpClient-{n}-Worker-{n}",
                                "localhost:8125")
                        .category())
                .isEqualTo(NetworkClients.HTTP);
        assertThat(NetworkClients.recognize(null, null, null, "worker", "10.0.0.2:53")
                        .label())
                .isEqualTo("DNS");
    }

    @Test
    void zipkinJaegerLokiStatsdAndGelfPortsAreInfrastructure() {
        for (String target : java.util.List.of(
                "zipkin:9411", "jaeger:14250", "jaeger:14268", "loki:3100", "statsd:8125", "graylog:12201")) {
            assertThat(NetworkClients.recognize("com.example.Unknown#call", null, null, "main", target))
                    .as(target)
                    .satisfies(client -> assertThat(client.category()).isEqualTo(NetworkClients.INFRASTRUCTURE));
        }
    }

    @Test
    void springBootDockerComposeAndTestcontainersReadinessChecksAreInfrastructure() {
        assertThat(NetworkClients.recognize(
                                "org.springframework.boot.docker.compose.lifecycle.TcpConnectServiceReadinessCheck#check",
                                "org.springframework.boot.docker.compose.lifecycle.TcpConnectServiceReadinessCheck#check",
                                "com.example.Application#main",
                                "main",
                                "127.0.0.1:5432")
                        .label())
                .isEqualTo("Spring Boot Docker Compose");
        assertThat(NetworkClients.recognize(
                                "org.springframework.boot.testcontainers.lifecycle.TestcontainersLifecycleBeanPostProcessor#start",
                                null,
                                null,
                                "main",
                                "localhost:32768")
                        .category())
                .isEqualTo(NetworkClients.INFRASTRUCTURE);
    }
}
