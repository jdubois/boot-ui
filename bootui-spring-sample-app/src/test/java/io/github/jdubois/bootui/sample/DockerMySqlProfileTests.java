package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

class DockerMySqlProfileTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void mysqlComposeOnlyDeclaresMysqlAndRedis() {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new FileSystemResource("compose-mysql.yaml"));
        assertThat(yaml.getObject())
                .extractingByKey("services")
                .asInstanceOf(MAP)
                .containsOnlyKeys("mysql", "redis");
    }

    @Test
    void mysqlProfileUsesMysqlAndRedisWithoutKafkaOrOllama() {
        runner.withPropertyValues("spring.profiles.active=docker-mysql").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.docker.compose.enabled")).isEqualTo("true");
            assertThat(environment.getProperty("spring.docker.compose.file")).isEqualTo("compose-mysql.yaml");
            assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                    .isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration-mysql");
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
            assertThat(environment.getProperty("bootui.enabled-profiles")).contains("docker-mysql");
        });
    }

    @Test
    void existingDockerAndDockerFreeProfilesKeepTheirDatabaseConfiguration() {
        runner.withPropertyValues("spring.profiles.active=docker").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled"))
                    .isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("spring.docker.compose.file"))
                    .isNull();
            assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                    .isNull();
            assertThat(context.getEnvironment().getProperty("spring.kafka.bootstrap-servers"))
                    .isEqualTo("localhost:9092");
            assertThat(context.getEnvironment().getProperty("spring.ai.ollama.base-url"))
                    .isEqualTo("http://localhost:11434");
        });
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled"))
                    .isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                    .startsWith("jdbc:h2:");
            assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                    .isNull();
        });
    }
}
