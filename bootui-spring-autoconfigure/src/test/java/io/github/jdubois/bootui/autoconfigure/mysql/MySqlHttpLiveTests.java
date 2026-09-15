package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.MySqlReportContract;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class MySqlHttpLiveTests {

    @Container
    static final MySQLContainer MYSQL = MySqlLiveFixture.container();

    @BeforeAll
    static void initialize() throws Exception {
        MySqlLiveFixture.initialize(MYSQL);
    }

    @ParameterizedTest
    @CsvSource({"SERVLET,false", "SERVLET,true", "REACTIVE,false", "REACTIVE,true"})
    void genuineMysqlHasTheSameContractThroughEverySpringTransport(WebApplicationType type, boolean customPath) {
        try (HikariDataSource pool = MySqlLiveFixture.pool(MYSQL, "reader")) {
            CountingDataSource source = new CountingDataSource(pool);
            SpringApplication application = new SpringApplication(HttpConfiguration.class);
            application.setWebApplicationType(type);
            application.setRegisterShutdownHook(false);
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("server.port", "0");
            properties.put("spring.main.web-application-type", type.name().toLowerCase(java.util.Locale.ROOT));
            properties.put("spring.config.location", "optional:classpath:/mysql-http-fixture.properties");
            properties.put("spring.main.banner-mode", "off");
            properties.put("spring.flyway.enabled", "false");
            properties.put("spring.liquibase.enabled", "false");
            properties.put("spring.jpa.hibernate.ddl-auto", "none");
            properties.put("bootui.enabled", "ON");
            properties.put("bootui.show-banner", "false");
            properties.put("bootui.mcp.enabled", "ON");
            properties.put("bootui.cli.enabled", "ON");
            properties.put("bootui.sql-trace.enabled", "false");
            properties.put("bootui.authentication.token", "mysql-http-synthetic-token");
            properties.put("bootui.overrides-file", "target/mysql-http/" + type + customPath + "/overrides.properties");
            properties.put("logging.level.org.springframework.boot.security", "ERROR");
            properties.put("logging.level.org.hibernate", "ERROR");
            if (customPath) {
                properties.put("bootui.path", "/console");
                properties.put("bootui.api-path", "/diagnostics");
                properties.put("server.servlet.context-path", "/host");
                properties.put("spring.webflux.base-path", "/host");
            }
            application.setDefaultProperties(properties);
            application.addInitializers(context -> context.getBeanFactory().registerSingleton("mysql", source));
            try (var context = application.run()) {
                int port =
                        ((WebServerApplicationContext) context).getWebServer().getPort();
                String origin = "http://127.0.0.1:" + port;
                String api = customPath ? "/host/diagnostics" : "/bootui/api";
                BootUiHttpProbe probe = new BootUiHttpProbe(origin);
                int before = source.borrows.get();
                assertThat(probe.get(api + "/panels").status()).isEqualTo(200);
                assertThat(probe.get(api + "/mysql").status()).isEqualTo(200);
                assertThat(source.borrows).hasValue(before);
                source.threads.clear();
                MySqlReportContract.verify(probe, origin, api);
                assertThat(source.borrows.get()).isEqualTo(before + 3);
                if (type == WebApplicationType.REACTIVE) {
                    assertThat(source.threads).anyMatch(thread -> thread.startsWith("boundedElastic-"));
                    assertThat(source.threads)
                            .allMatch(thread ->
                                    thread.startsWith("boundedElastic-") || thread.startsWith("bootui-mcp-tool-"));
                }
                BootUiProperties policy = context.getBean(BootUiProperties.class);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("Origin", origin);
                probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
                policy.setReadOnly(true);
                assertReadOnly(probe, api, headers);
                policy.setReadOnly(false);
                policy.panel("mysql").setReadOnly(true);
                assertReadOnly(probe, api, headers);
                policy.panel("mysql").setReadOnly(false);
                context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource(
                                "mysql-exposure", Map.of("bootui.expose-values", "METADATA_ONLY")));
                assertThat(probe.get(api + "/mysql").json().path("status").asText())
                        .isEqualTo("NOT_READ");
                policy.panel("mysql").setEnabled(false);
                assertThat(probe.get(api + "/mysql").status()).isEqualTo(403);
                assertThat(probe.post(api + "/mysql/read", headers).status()).isEqualTo(403);
                assertThat(source.borrows).hasValue(before + 3);
                if (customPath) {
                    assertThat(probe.get("/bootui/api/mysql").status()).isEqualTo(404);
                }
            }
        }
    }

    private static void assertReadOnly(BootUiHttpProbe probe, String api, Map<String, String> headers) {
        assertThat(probe.get(api + "/mysql").status()).isEqualTo(200);
        assertThat(probe.post(api + "/mysql/read", headers).status()).isEqualTo(403);
        assertThat(probe.request("POST", api + "/cli/tools/get_mysql_report", headers, "{}")
                        .status())
                .isEqualTo(200);
        assertThat(probe.request("POST", api + "/cli/tools/mysql_read", headers, "{}")
                        .status())
                .isEqualTo(403);
        var mcp = probe.request(
                "POST",
                api + "/mcp",
                headers,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"mysql_read\",\"arguments\":{}}}");
        assertThat(mcp.json().path("result").path("isError").asBoolean()).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class HttpConfiguration {}

    public static final class CountingDataSource extends AbstractDataSource {
        private final HikariDataSource delegate;
        final AtomicInteger borrows = new AtomicInteger();
        final Set<String> threads = ConcurrentHashMap.newKeySet();

        CountingDataSource(HikariDataSource delegate) {
            this.delegate = delegate;
        }

        public String getJdbcUrl() {
            return delegate.getJdbcUrl();
        }

        @Override
        public Connection getConnection() throws SQLException {
            borrows.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("Fixture credentials cannot be overridden");
        }
    }
}
