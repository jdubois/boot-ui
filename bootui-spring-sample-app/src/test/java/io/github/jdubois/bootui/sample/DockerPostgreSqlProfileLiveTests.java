package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Tests the lightweight PostgreSQL profile against the same PostgreSQL configuration as its Compose file,
 * using a local cache instead of Redis. Kafka and AI are disabled by the profile itself, not by test-only
 * exclusions.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=docker-postgresql",
            "spring.docker.compose.enabled=false",
            "spring.cache.type=simple",
            "management.health.redis.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/docker-postgresql-profile-test/overrides.properties"
        })
class DockerPostgreSqlProfileLiveTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("bootui_sample")
            .withUsername("bootui")
            .withPassword("bootui")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("docker/postgres/init.sql")),
                    "/docker-entrypoint-initdb.d/01-pg-stat-statements.sql");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void primaryPostgresqlServesTheSampleAndRanksStatements() throws Exception {
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(KafkaTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(context.getBeansOfType(EmbeddingModel.class)).isEmpty();

        String origin = "http://127.0.0.1:" + port;
        BootUiHttpProbe probe = new BootUiHttpProbe(origin);
        var panels = probe.get("/bootui/api/panels").json().path("panels");
        assertThat(panels).anySatisfy(panel -> {
            assertThat(panel.path("id").asText()).isEqualTo("postgresql");
            assertThat(panel.path("available").asBoolean()).isTrue();
        });
        assertThat(panels).anySatisfy(panel -> {
            assertThat(panel.path("id").asText()).isEqualTo("mysql");
            assertThat(panel.path("available").asBoolean()).isFalse();
        });
        assertThat(probe.get("/bootui/api/postgresql").json().path("status").asText())
                .isEqualTo("NOT_READ");
        var products = probe.get("/api/sample/products");
        assertThat(products.status()).isEqualTo(200);
        assertThat(products.body()).contains("BootUI Starter");

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", origin);
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        var response = probe.post("/bootui/api/postgresql/read", headers);
        assertThat(response.status()).isEqualTo(200);
        var database = response.json().path("databases").get(0);
        assertThat(database.path("databaseName").asText()).isEqualTo("bootui_sample");
        assertThat(database.path("sections"))
                .filteredOn(section -> "statements".equals(section.path("id").asText()))
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.path("status").asText())
                            .as("statements section: %s", section)
                            .isEqualTo("AVAILABLE");
                    assertThat(section.path("reason").isNull()).isTrue();
                });
        assertThat(database.path("statements"))
                .anyMatch(statement -> statement.path("query").asText().contains("sample_products"));
    }
}
