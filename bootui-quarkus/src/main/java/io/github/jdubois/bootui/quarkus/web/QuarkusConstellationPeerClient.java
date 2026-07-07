package io.github.jdubois.bootui.quarkus.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.engine.constellation.PeerClient;
import io.github.jdubois.bootui.engine.constellation.PeerSnapshot;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Quarkus {@link PeerClient} implementation: the Jackson 2 ({@code com.fasterxml.jackson.*}) analogue of the
 * Spring adapter's {@code ConstellationHttpPeerClient}, which uses Jackson 3 ({@code tools.jackson.*}).
 *
 * <p>The shared engine {@code ConstellationService} is JSON-library- and framework-free on purpose, so the
 * actual peer HTTP transport and JSON parsing live in each adapter. This class fetches a peer BootUI
 * instance's identity over loopback-only HTTP - {@code GET /bootui/api/overview} for identity and
 * {@code GET /bootui/api/panels} for the {@code platform} discriminator - parsing both tolerantly with
 * {@link JsonNode} so a missing or renamed field on an older/newer peer degrades to a blank value instead of
 * failing the whole node. The logic mirrors the Spring client byte-for-byte; the only differences are the
 * Jackson import family and Jackson 2's {@link JsonNode#isTextual()}/{@link JsonNode#asText()} in place of
 * Jackson 3's renamed {@code isString()}/{@code asString()}.</p>
 */
public final class QuarkusConstellationPeerClient implements PeerClient {

    private final HttpClient httpClient;

    private final ObjectMapper objectMapper;

    public QuarkusConstellationPeerClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public QuarkusConstellationPeerClient() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper());
    }

    @Override
    public PeerSnapshot fetch(String peerUrl, Duration timeout) {
        String baseUrl = trimTrailingSlash(peerUrl);
        if (!isLoopbackUrl(baseUrl)) {
            return PeerSnapshot.unreachable(
                    peerUrl, "Constellation peers must be loopback URLs (localhost/127.0.0.1/::1)");
        }
        try {
            JsonNode overview = getJson(baseUrl + "/bootui/api/overview", timeout);
            JsonNode panels = tryGetJson(baseUrl + "/bootui/api/panels", timeout);
            return new PeerSnapshot(
                    peerUrl,
                    true,
                    text(overview, "applicationName"),
                    platform(panels, overview),
                    text(overview, "frameworkVersion"),
                    text(overview, "javaVersion"),
                    activeProfiles(overview),
                    null);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return PeerSnapshot.unreachable(
                    peerUrl,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private String platform(JsonNode panels, JsonNode overview) {
        String platform = text(panels, "platform");
        if (platform != null) {
            return platform;
        }
        String frameworkName = text(overview, "frameworkName");
        return frameworkName != null ? frameworkName : "unknown";
    }

    private List<String> activeProfiles(JsonNode overview) {
        List<String> profiles = new ArrayList<>();
        JsonNode node = overview == null ? null : overview.get("activeProfiles");
        if (node != null && node.isArray()) {
            for (JsonNode profile : node) {
                if (profile.isTextual()) {
                    profiles.add(profile.asText());
                }
            }
        }
        return profiles;
    }

    private JsonNode getJson(String url, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected HTTP status " + response.statusCode() + " from " + url);
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode tryGetJson(String url, Duration timeout) {
        try {
            return getJson(url, timeout);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private boolean isLoopbackUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "[::1]".equals(host)
                    || "::1".equals(host);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String text(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }
}
