package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

class DockerPostgreSqlProfileTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void postgresqlComposeOnlyDeclaresPostgresAndRedisFromTheMainCompose() {
        Map<String, Object> services = services("compose-postgresql.yaml");
        assertThat(services).containsOnlyKeys("postgres", "redis");
        assertThat(services)
                .extractingByKey("postgres")
                .asInstanceOf(MAP)
                .extractingByKey("extends")
                .isEqualTo(Map.of("file", "compose.yaml", "service", "postgres"));
        assertThat(services)
                .extractingByKey("redis")
                .asInstanceOf(MAP)
                .extractingByKey("extends")
                .isEqualTo(Map.of("file", "compose.yaml", "service", "redis"));
    }

    @Test
    void inheritedPostgresServiceEnablesPgStatStatements() throws Exception {
        Map<String, Object> postgres =
                (Map<String, Object>) services("compose.yaml").get("postgres");
        assertThat(postgres)
                .extractingByKey("command")
                .asInstanceOf(LIST)
                .contains("shared_preload_libraries=pg_stat_statements");
        assertThat(postgres)
                .extractingByKey("volumes")
                .asInstanceOf(LIST)
                .contains("./docker/postgres/init.sql:/docker-entrypoint-initdb.d/01-pg-stat-statements.sql:ro");
        assertThat(Files.readString(Path.of("docker/postgres/init.sql")))
                .contains("CREATE EXTENSION IF NOT EXISTS pg_stat_statements;");
    }

    @Test
    void postgresqlProfileUsesPostgresAndRedisWithoutKafkaOrOllama() {
        runner.withPropertyValues("spring.profiles.active=docker-postgresql").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.docker.compose.enabled")).isEqualTo("true");
            assertThat(environment.getProperty("spring.docker.compose.file")).isEqualTo("compose-postgresql.yaml");
            assertThat(environment.getProperty("spring.datasource.url")).isNull();
            assertThat(environment.getProperty("spring.flyway.locations")).isNull();
            assertThat(environment.getProperty("spring.cache.type")).isEqualTo("redis");
            assertThat(environment.getProperty("spring.cache.redis.enable-statistics"))
                    .isEqualTo("true");
            assertThat(environment.getProperty("spring.kafka.bootstrap-servers"))
                    .isNull();
            assertThat(environment.getProperty("spring.ai.ollama.base-url")).isNull();
            assertThat(environment.getProperty("spring.autoconfigure.exclude", String[].class))
                    .containsExactly(
                            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
                            "org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration",
                            "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
                            "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration",
                            "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration");
            assertThat(environment.getProperty("bootui.enabled-profiles")).contains("docker-postgresql");
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services(String composeFile) {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new FileSystemResource(composeFile));
        return (Map<String, Object>) yaml.getObject().get("services");
    }
}
