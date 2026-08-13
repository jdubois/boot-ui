package io.github.jdubois.bootui.engine.devservices;

import java.util.Locale;
import java.util.Map;

/**
 * Framework-neutral service-type classification shared by every {@link io.github.jdubois.bootui.spi.DevServicesProvider}
 * adapter, so the same underlying image/name/labels always classify to the same {@code type} regardless of whether the
 * service was discovered through Spring's Docker Compose/Testcontainers/connection-details sources or Quarkus Dev
 * Services. Matches the type list in {@code docs/SPECIFICATION.md} &sect;5.19 (PostgreSQL, MySQL, MariaDB, Redis,
 * MongoDB, RabbitMQ, Kafka, Elasticsearch, Neo4j), plus Zipkin as an additional well-known type.
 */
public final class DevServiceTypeInference {

    private DevServiceTypeInference() {}

    public static String inferType(String name, String image, Map<?, ?> labels) {
        String combined = (name + " " + image + " " + labels).toLowerCase(Locale.ROOT);
        if (combined.contains("postgres")) {
            return "PostgreSQL";
        }
        if (combined.contains("mariadb")) {
            return "MariaDB";
        }
        if (combined.contains("mysql")) {
            return "MySQL";
        }
        if (combined.contains("redis")) {
            return "Redis";
        }
        if (combined.contains("kafka") || combined.contains("redpanda")) {
            return "Kafka";
        }
        if (combined.contains("mongo")) {
            return "MongoDB";
        }
        if (combined.contains("rabbit")) {
            return "RabbitMQ";
        }
        if (combined.contains("elasticsearch")) {
            return "Elasticsearch";
        }
        if (combined.contains("neo4j")) {
            return "Neo4j";
        }
        if (combined.contains("zipkin")) {
            return "Zipkin";
        }
        return "Service";
    }
}
