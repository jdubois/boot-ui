package io.github.jdubois.bootui.engine.devservices;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DevServiceTypeInferenceTests {

    @Test
    void classifiesEveryDocumentedServiceType() {
        assertThat(DevServiceTypeInference.inferType("postgres", "postgres:16", Map.of()))
                .isEqualTo("PostgreSQL");
        assertThat(DevServiceTypeInference.inferType("mariadb", "mariadb:11", Map.of()))
                .isEqualTo("MariaDB");
        assertThat(DevServiceTypeInference.inferType("mysql", "mysql:8", Map.of()))
                .isEqualTo("MySQL");
        assertThat(DevServiceTypeInference.inferType("redis", "redis:7", Map.of()))
                .isEqualTo("Redis");
        assertThat(DevServiceTypeInference.inferType("mongo", "mongo:7", Map.of()))
                .isEqualTo("MongoDB");
        assertThat(DevServiceTypeInference.inferType("rabbit", "rabbitmq:3", Map.of()))
                .isEqualTo("RabbitMQ");
        assertThat(DevServiceTypeInference.inferType("kafka", "kafka:3", Map.of()))
                .isEqualTo("Kafka");
        assertThat(DevServiceTypeInference.inferType("redpanda", "redpanda:latest", Map.of()))
                .isEqualTo("Kafka");
        assertThat(DevServiceTypeInference.inferType("es", "elasticsearch:8", Map.of()))
                .isEqualTo("Elasticsearch");
        assertThat(DevServiceTypeInference.inferType("neo4j", "neo4j:5", Map.of()))
                .isEqualTo("Neo4j");
        assertThat(DevServiceTypeInference.inferType("zipkin", "zipkin:latest", Map.of()))
                .isEqualTo("Zipkin");
        assertThat(DevServiceTypeInference.inferType("custom", "custom:1", Map.of()))
                .isEqualTo("Service");
    }

    @Test
    void mariaDbIsNotFoldedIntoMySql() {
        assertThat(DevServiceTypeInference.inferType("db", "mariadb:11", Map.of()))
                .isNotEqualTo("MySQL");
    }

    @Test
    void classifiesFromLabelsWhenNameAndImageAreGeneric() {
        assertThat(DevServiceTypeInference.inferType("db", "generic:latest", Map.of("engine", "postgres")))
                .isEqualTo("PostgreSQL");
    }
}
