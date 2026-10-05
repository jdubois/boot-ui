package io.github.jdubois.bootui.engine.sideeffects;

import java.util.List;

/**
 * Recognizes the client behind a network record ({@code docs/PLAN-v2.md} §5.16, M5-5b) from its frames: the first
 * frame outside the socket plumbing, then the first frame outside the JDK, then the first application frame, and last
 * the thread's family, as a Netty event loop's connect carries no frame of the library that asked for it. Each client
 * has a category, which decides which panel can capture its work: {@value #SQL}, {@value #MESSAGING} (with its broker),
 * {@value #MAIL}, {@value #HTTP}, {@value #INFRASTRUCTURE} (DNS resolvers, telemetry exporters, metrics and log
 * shippers, container tooling, which no panel is meant to show, recognized first, by frame, exporter thread, or
 * well-known port, since their transport is itself an HTTP client), or {@value #OTHER} (a client no panel captures, such
 * as a cache, a document store, or a cloud SDK).
 */
final class NetworkClients {

    static final String SQL = "sql";
    static final String MESSAGING = "messaging";
    static final String MAIL = "mail";
    static final String HTTP = "http";
    static final String INFRASTRUCTURE = "infrastructure";
    static final String OTHER = "other";

    /**
     * A recognized client.
     *
     * @param label its name, as rows show it
     * @param category its category
     * @param broker for a messaging client, its broker as the runtime journal names it ({@code kafka}, {@code
     *     rabbitmq}, {@code jms}), else {@code null}
     */
    record Client(String label, String category, String broker) {}

    private record Rule(String prefix, Client client) {}

    private static final List<Rule> FRAMES = List.of(
            // SQL: JDBC drivers, R2DBC, and reactive SQL clients.
            rule("org.postgresql.", "PostgreSQL JDBC", SQL),
            rule("com.mysql.", "MySQL JDBC", SQL),
            rule("org.mariadb.jdbc.", "MariaDB JDBC", SQL),
            rule("oracle.jdbc.", "Oracle JDBC", SQL),
            rule("oracle.net.", "Oracle JDBC", SQL),
            rule("com.microsoft.sqlserver.", "SQL Server JDBC", SQL),
            rule("org.h2.", "H2", SQL),
            rule("org.hsqldb.", "HSQLDB", SQL),
            rule("org.apache.derby.", "Derby", SQL),
            rule("com.ibm.db2.", "Db2 JDBC", SQL),
            rule("org.firebirdsql.", "Firebird JDBC", SQL),
            rule("io.r2dbc.", "R2DBC", SQL),
            rule("io.asyncer.r2dbc.", "R2DBC", SQL),
            rule("org.mariadb.r2dbc.", "R2DBC", SQL),
            rule("io.vertx.pgclient.", "Vert.x PostgreSQL client", SQL),
            rule("io.vertx.mysqlclient.", "Vert.x MySQL client", SQL),
            rule("io.vertx.oracleclient.", "Vert.x Oracle client", SQL),
            rule("io.vertx.mssqlclient.", "Vert.x SQL Server client", SQL),
            rule("io.vertx.db2client.", "Vert.x Db2 client", SQL),
            rule("io.vertx.sqlclient.", "Vert.x SQL client", SQL),
            rule("com.zaxxer.hikari.", "HikariCP", SQL),
            // Messaging.
            rule("org.apache.kafka.", "Kafka client", MESSAGING, "kafka"),
            rule("io.smallrye.reactive.messaging.kafka.", "Kafka client", MESSAGING, "kafka"),
            rule("com.rabbitmq.", "RabbitMQ client", MESSAGING, "rabbitmq"),
            rule("io.vertx.rabbitmq.", "RabbitMQ client", MESSAGING, "rabbitmq"),
            rule("org.apache.activemq.", "ActiveMQ client", MESSAGING, "jms"),
            rule("org.apache.qpid.jms.", "AMQP JMS client", MESSAGING, "jms"),
            rule("com.ibm.mq.", "IBM MQ client", MESSAGING, "jms"),
            rule("org.apache.pulsar.", "Pulsar client", OTHER),
            rule("io.nats.", "NATS client", OTHER),
            // Mail.
            rule("jakarta.mail.", "Jakarta Mail", MAIL),
            rule("javax.mail.", "JavaMail", MAIL),
            rule("com.sun.mail.", "JavaMail", MAIL),
            rule("org.eclipse.angus.mail.", "Jakarta Mail", MAIL),
            rule("io.vertx.ext.mail.", "Vert.x mail client", MAIL),
            // HTTP clients, which REST Client Trace may capture.
            rule("jdk.internal.net.http.", "JDK HttpClient", HTTP),
            rule("sun.net.www.", "JDK HttpURLConnection", HTTP),
            rule("sun.net.NetworkClient", "JDK HttpURLConnection", HTTP),
            rule("org.apache.hc.client5.", "Apache HttpClient", HTTP),
            rule("org.apache.hc.core5.", "Apache HttpClient", HTTP),
            rule("org.apache.http.", "Apache HttpClient", HTTP),
            rule("okhttp3.", "OkHttp", HTTP),
            rule("org.eclipse.jetty.client.", "Jetty HttpClient", HTTP),
            rule("reactor.netty.http.", "Reactor Netty HTTP client", HTTP),
            rule("io.vertx.core.http.", "Vert.x HTTP client", HTTP),
            rule("org.springframework.web.client.", "Spring RestClient", HTTP),
            rule("org.springframework.http.client.", "Spring HTTP client", HTTP),
            rule("org.springframework.web.reactive.function.client.", "Spring WebClient", HTTP),
            rule("org.jboss.resteasy.", "RESTEasy client", HTTP),
            rule("io.quarkus.rest.client.", "Quarkus REST client", HTTP),
            // Other clients no panel captures.
            rule("io.lettuce.", "Lettuce", OTHER),
            rule("redis.clients.jedis.", "Jedis", OTHER),
            rule("org.redisson.", "Redisson", OTHER),
            rule("io.vertx.redis.", "Vert.x Redis client", OTHER),
            rule("com.mongodb.", "MongoDB driver", OTHER),
            rule("com.datastax.", "Cassandra driver", OTHER),
            rule("co.elastic.clients.", "Elasticsearch client", OTHER),
            rule("org.elasticsearch.", "Elasticsearch client", OTHER),
            rule("org.opensearch.", "OpenSearch client", OTHER),
            rule("net.spy.memcached.", "Memcached client", OTHER),
            rule("com.hazelcast.", "Hazelcast", OTHER),
            rule("org.infinispan.", "Infinispan", OTHER),
            rule("org.jgroups.", "JGroups", OTHER),
            rule("io.grpc.", "gRPC", OTHER),
            rule("software.amazon.awssdk.", "AWS SDK", OTHER),
            rule("com.amazonaws.", "AWS SDK", OTHER),
            rule("com.azure.", "Azure SDK", OTHER),
            rule("com.microsoft.azure.", "Azure SDK", OTHER),
            rule("com.google.cloud.", "Google Cloud SDK", OTHER),
            rule("com.google.api.", "Google API client", OTHER),
            rule("io.minio.", "MinIO client", OTHER),
            rule("org.apache.zookeeper.", "ZooKeeper client", OTHER),
            rule("io.etcd.", "etcd client", OTHER),
            rule("org.apache.commons.net.", "Commons Net", OTHER),
            rule("com.jcraft.jsch.", "JSch", OTHER),
            rule("org.apache.sshd.", "Apache SSHD", OTHER));

    /** Transports many clients share: recognized only when no frame and no thread names a client. */
    private static final List<Rule> TRANSPORTS = List.of(
            rule("reactor.netty.", "Reactor Netty", OTHER),
            rule("io.vertx.core.", "Vert.x net client", OTHER),
            rule("io.netty.", "Netty", OTHER));

    /**
     * Infrastructure, checked before every other rule: its transport is a client of its own (OkHttp, the JDK's
     * clients), so the first frame outside the socket plumbing would otherwise name an HTTP client.
     */
    private static final List<Rule> INFRASTRUCTURE_FRAMES = List.of(
            rule("io.netty.resolver.dns.", "Netty DNS resolver", INFRASTRUCTURE),
            rule("com.sun.jndi.dns.", "JNDI DNS", INFRASTRUCTURE),
            rule("io.vertx.core.dns.", "Vert.x DNS client", INFRASTRUCTURE),
            rule("io.opentelemetry.", "OpenTelemetry exporter", INFRASTRUCTURE),
            rule("zipkin2.", "Zipkin reporter", INFRASTRUCTURE),
            rule("io.micrometer.statsd.", "StatsD registry", INFRASTRUCTURE),
            rule("io.micrometer.registry.", "Micrometer registry", INFRASTRUCTURE),
            rule("io.prometheus.", "Prometheus client", INFRASTRUCTURE),
            rule("ch.qos.logback.", "Logback appender", INFRASTRUCTURE),
            rule("org.apache.logging.log4j.", "Log4j appender", INFRASTRUCTURE),
            rule("biz.paluch.logging.", "GELF appender", INFRASTRUCTURE),
            rule("org.springframework.boot.docker.compose.", "Spring Boot Docker Compose", INFRASTRUCTURE),
            rule("org.springframework.boot.testcontainers.", "Spring Boot Testcontainers", INFRASTRUCTURE),
            rule("org.testcontainers.", "Testcontainers", INFRASTRUCTURE),
            rule("com.github.dockerjava.", "docker-java", INFRASTRUCTURE),
            rule("org.springframework.boot.devtools.", "Spring Boot DevTools", INFRASTRUCTURE),
            rule("io.quarkus.devservices.", "Quarkus Dev Services", INFRASTRUCTURE));

    /** Thread families of telemetry exporters, whose connects carry no exporter frame on an HTTP client's thread. */
    private static final List<Rule> INFRASTRUCTURE_THREADS = List.of(
            rule("BatchSpanProcessor", "OpenTelemetry exporter", INFRASTRUCTURE),
            rule("BatchLogRecordProcessor", "OpenTelemetry exporter", INFRASTRUCTURE),
            rule("PeriodicMetricReader", "OpenTelemetry exporter", INFRASTRUCTURE),
            rule("otlp", "OpenTelemetry exporter", INFRASTRUCTURE),
            rule("zipkin", "Zipkin reporter", INFRASTRUCTURE),
            rule("docker-java-", "docker-java", INFRASTRUCTURE),
            rule("testcontainers", "Testcontainers", INFRASTRUCTURE));

    /**
     * Well-known ports of infrastructure no panel is meant to show: OTLP gRPC and HTTP, Zipkin, Jaeger, Loki, StatsD,
     * GELF, and DNS.
     */
    private static final java.util.Map<Integer, String> INFRASTRUCTURE_PORTS = java.util.Map.of(
            4317, "OpenTelemetry exporter",
            4318, "OpenTelemetry exporter",
            9411, "Zipkin reporter",
            14250, "Jaeger exporter",
            14268, "Jaeger exporter",
            3100, "Loki appender",
            8125, "StatsD client",
            12201, "GELF appender",
            53, "DNS");

    /** Thread families whose connects carry no frame of the library that asked for them. */
    private static final List<Rule> THREADS = List.of(
            rule("lettuce-", "Lettuce", OTHER),
            rule("redisson-netty", "Redisson", OTHER),
            rule("reactor-http-", "Reactor Netty HTTP client", HTTP),
            rule("reactor-tcp-", "Reactor Netty", OTHER),
            rule("HttpClient-", "JDK HttpClient", HTTP),
            rule("OkHttp", "OkHttp", HTTP),
            rule("kafka-", "Kafka client", MESSAGING, "kafka"),
            rule("AMQP Connection", "RabbitMQ client", MESSAGING, "rabbitmq"),
            rule("cluster-", "MongoDB driver", OTHER),
            rule("grpc-", "gRPC", OTHER),
            rule("aws-java-sdk-", "AWS SDK", OTHER),
            rule("sdk-async-response", "AWS SDK", OTHER),
            rule("docker-java-", "docker-java", INFRASTRUCTURE),
            rule("testcontainers", "Testcontainers", INFRASTRUCTURE));

    private NetworkClients() {}

    private static Rule rule(String prefix, String label, String category) {
        return new Rule(prefix, new Client(label, category, null));
    }

    private static Rule rule(String prefix, String label, String category, String broker) {
        return new Rule(prefix, new Client(label, category, broker));
    }

    /**
     * The client of a record whose frames are {@code client}, {@code outside}, and {@code application} ({@code
     * Class#method} each, or {@code null}), on a thread of family {@code thread}; {@code null} when none is recognized.
     * A datagram to port 53 is a DNS resolver's.
     */
    static Client recognize(String client, String outside, String application, String thread, String target) {
        // Infrastructure first: its frames, its exporters' threads, then its well-known ports.
        for (String frame : new String[] {client, outside, application}) {
            Client known = byFrame(frame, INFRASTRUCTURE_FRAMES);
            if (known != null) {
                return known;
            }
        }
        if (thread != null) {
            for (Rule rule : INFRASTRUCTURE_THREADS) {
                if (thread.startsWith(rule.prefix())) {
                    return rule.client();
                }
            }
            if (thread.startsWith("OkHttp ") && OTLP_PATH.matcher(thread).find()) {
                return new Client("OpenTelemetry exporter", INFRASTRUCTURE, null);
            }
        }
        String port = port(target);
        if (port != null) {
            String label = INFRASTRUCTURE_PORTS.get(Integer.valueOf(port));
            if (label != null) {
                return new Client(label, INFRASTRUCTURE, null);
            }
        }
        for (String frame : new String[] {client, outside, application}) {
            Client known = byFrame(frame);
            if (known != null) {
                return known;
            }
        }
        if (thread != null) {
            for (Rule rule : THREADS) {
                if (thread.startsWith(rule.prefix())) {
                    return rule.client();
                }
            }
        }
        for (String frame : new String[] {client, outside, application}) {
            Client known = byFrame(frame, TRANSPORTS);
            if (known != null) {
                return known;
            }
        }
        return null;
    }

    /** An OTLP exporter's path, as OkHttp names its thread after the URL it calls. */
    private static final java.util.regex.Pattern OTLP_PATH =
            java.util.regex.Pattern.compile("/v\\{n\\}/(traces|metrics|logs)|/v1/(traces|metrics|logs)");

    /** The port of a {@code host:port} target, or {@code null}. */
    private static String port(String target) {
        if (target == null) {
            return null;
        }
        int colon = target.lastIndexOf(':');
        if (colon < 0 || colon == target.length() - 1) {
            return null;
        }
        String port = target.substring(colon + 1);
        return port.length() <= 5 && port.chars().allMatch(Character::isDigit) ? port : null;
    }

    private static Client byFrame(String frame) {
        return byFrame(frame, FRAMES);
    }

    private static Client byFrame(String frame, List<Rule> rules) {
        if (frame == null) {
            return null;
        }
        for (Rule rule : rules) {
            if (frame.startsWith(rule.prefix())) {
                return rule.client();
            }
        }
        return null;
    }
}
