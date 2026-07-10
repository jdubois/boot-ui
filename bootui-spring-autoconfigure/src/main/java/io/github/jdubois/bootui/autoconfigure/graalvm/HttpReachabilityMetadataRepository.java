package io.github.jdubois.bootui.autoconfigure.graalvm;

import io.github.jdubois.bootui.engine.graalvm.Coordinates;
import io.github.jdubois.bootui.engine.graalvm.ReachabilityMetadataIndex;
import io.github.jdubois.bootui.engine.graalvm.ReachabilityMetadataRepository;
import io.github.jdubois.bootui.engine.web.BoundedBodyReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Boot adapter implementation of the engine's {@link ReachabilityMetadataRepository} transport
 * seam: fetches a dependency's reachability-metadata {@code index.json} from Oracle's GraalVM
 * reachability metadata repository over a single bounded HTTP GET and parses it with Jackson (Jackson 3
 * on Spring Boot 4). The engine owns all coverage policy, gating, caching, and note formatting; this
 * class performs only transport + deserialization and never throws — every failure maps to
 * {@link ReachabilityMetadataIndex#unavailable(String)} (or an empty index for HTTP 404). Keeping the
 * JSON library here (rather than in {@code bootui-engine}) lets the future Quarkus adapter use its own
 * Jackson 2 without forcing Spring Boot 4's {@code tools.jackson} stack onto the neutral engine.
 *
 * <p>Response bodies are bounded at {@link BoundedBodyReader#GRAALVM_METADATA_MAX_BYTES}: if the
 * response exceeds that limit the fetch is treated as unavailable rather than silently accepting a
 * truncated JSON document that a downstream parser would then accept as valid.
 */
public final class HttpReachabilityMetadataRepository implements ReachabilityMetadataRepository {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final String DEFAULT_INDEX_BASE_URL =
            "https://raw.githubusercontent.com/oracle/graalvm-reachability-metadata/master/metadata/";

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final String indexBaseUrl;
    private final Duration requestTimeout;
    private final int maxBodyBytes;

    public HttpReachabilityMetadataRepository() {
        this(DEFAULT_TIMEOUT);
    }

    public HttpReachabilityMetadataRepository(Duration requestTimeout) {
        this(
                HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
                new ObjectMapper(),
                DEFAULT_INDEX_BASE_URL,
                requestTimeout);
    }

    HttpReachabilityMetadataRepository(HttpClient client, ObjectMapper objectMapper, String indexBaseUrl) {
        this(client, objectMapper, indexBaseUrl, DEFAULT_TIMEOUT);
    }

    HttpReachabilityMetadataRepository(
            HttpClient client, ObjectMapper objectMapper, String indexBaseUrl, Duration requestTimeout) {
        this(client, objectMapper, indexBaseUrl, requestTimeout, BoundedBodyReader.GRAALVM_METADATA_MAX_BYTES);
    }

    /** Package-private constructor for testing with a custom byte limit. */
    HttpReachabilityMetadataRepository(
            HttpClient client,
            ObjectMapper objectMapper,
            String indexBaseUrl,
            Duration requestTimeout,
            int maxBodyBytes) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.indexBaseUrl = indexBaseUrl;
        this.requestTimeout = requestTimeout;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public ReachabilityMetadataIndex fetch(Coordinates coordinates) {
        try {
            HttpRequest request = HttpRequest.newBuilder(indexUri(coordinates))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("User-Agent", "BootUI GraalVM readiness")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, BoundedBodyReader.strictBodyHandler(maxBodyBytes));
            if (response.statusCode() == 404) {
                return ReachabilityMetadataIndex.of(List.of());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ReachabilityMetadataIndex.unavailable("HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ReachabilityMetadataIndex.unavailable("interrupted");
        } catch (Exception ex) {
            String message = ex.getMessage();
            return ReachabilityMetadataIndex.unavailable(
                    message == null || message.isBlank() ? ex.getClass().getSimpleName() : message);
        }
    }

    private URI indexUri(Coordinates coordinates) {
        return URI.create(
                indexBaseUrl + encode(coordinates.groupId()) + "/" + encode(coordinates.artifactId()) + "/index.json");
    }

    private ReachabilityMetadataIndex parse(String body) {
        JsonNode root = objectMapper.readTree(body);
        if (!root.isArray()) {
            return ReachabilityMetadataIndex.unavailable("unexpected index format");
        }
        List<ReachabilityMetadataIndex.Entry> entries = new ArrayList<>();
        for (JsonNode entry : root) {
            entries.add(new ReachabilityMetadataIndex.Entry(
                    text(entry, "metadata-version"),
                    stringList(entry.get("tested-versions")),
                    entry.path("latest").asBoolean(false)));
        }
        return ReachabilityMetadataIndex.of(entries);
    }

    private static String encode(String value) {
        return value.replace(" ", "%20");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (value != null && !value.isNull()) {
                values.add(value.asString());
            }
        }
        return List.copyOf(values);
    }
}
