package io.github.jdubois.bootui.autoconfigure.web;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring-side {@link PeerClient}: fetches a peer BootUI instance's identity over loopback-only HTTP,
 * mirroring {@link GitHubApiClient}'s JDK {@code HttpClient} + Jackson pattern. Reads
 * {@code GET /bootui/api/overview} for identity and {@code GET /bootui/api/panels} for the
 * {@code platform} discriminator, parsing both tolerantly with {@link JsonNode} so a missing or renamed
 * field on an older/newer peer degrades to a blank value instead of failing the whole node.
 */
final class ConstellationHttpPeerClient implements PeerClient {

    private final HttpClient httpClient;

    private final ObjectMapper objectMapper;

    ConstellationHttpPeerClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    ConstellationHttpPeerClient() {
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
                if (profile.isString()) {
                    profiles.add(profile.asString());
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
        if (value == null || value.isNull() || !value.isString()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text;
    }
}
