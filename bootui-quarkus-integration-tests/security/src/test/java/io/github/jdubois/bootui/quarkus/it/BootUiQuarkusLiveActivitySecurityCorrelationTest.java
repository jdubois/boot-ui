package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot proof of both fixes this module adds on top of {@link BootUiQuarkusSecurityLogsCaptureTest}:
 *
 * <ul>
 *   <li><strong>Part A</strong> ({@code QuarkusHttpExchangeCaptureFilter}): an authenticated call to
 *       {@code /secure} must capture the real principal on the HTTP exchange instead of a hardcoded
 *       {@code null}; an unauthenticated call must capture {@code null}.
 *   <li><strong>Part B</strong> ({@code LiveActivityAssembler} + {@code QuarkusSecurityEventCapture}): with
 *       real OpenTelemetry context propagation, the authentication event fired by {@code /secure} must
 *       surface as a standalone {@code SECURITY} entry in the Live Activity feed, nested under the
 *       {@code REQUEST} entry that shares its trace id, which must in turn carry the correlated
 *       {@code securedPrincipal}.
 * </ul>
 *
 * <p>The pure nesting/ambiguity algorithm is already pinned by the engine's
 * {@code LiveActivityAssemblerTests}; this test is the end-to-end proof that the trace id actually
 * survives capture on a real Quarkus request and that the two adapters (capture filter + CDI event
 * observer) agree on it.
 */
@QuarkusTest
class BootUiQuarkusLiveActivitySecurityCorrelationTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    private static String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void authenticatedRequestCapturesPrincipalAndCorrelatesSecurityEntry() {
        Response secure = probe().get("/secure", Map.of("Authorization", basicAuth("admin", "admin")));
        assertThat(secure.status()).as("/secure with valid credentials").isEqualTo(200);

        // Part A: the captured HTTP exchange itself must carry the resolved principal.
        Response exchanges = probe().get("/bootui/api/http-exchanges");
        assertThat(exchanges.status())
                .as("GET /bootui/api/http-exchanges status")
                .isEqualTo(200);
        JsonNode secureExchange = firstByPath(exchanges.json().path("exchanges"), "/secure");
        assertThat(secureExchange).as("captured /secure exchange").isNotNull();
        assertThat(secureExchange.path("principal").asText(null))
                .as("Part A: an authenticated request's captured exchange must carry the real principal")
                .isEqualTo("admin");

        // Part B: the Live Activity feed must produce a standalone SECURITY entry, nested by trace id
        // under the REQUEST entry, which must also carry the correlated securedPrincipal.
        Response activity = probe().get("/bootui/api/activity");
        assertThat(activity.status()).as("GET /bootui/api/activity status").isEqualTo(200);
        JsonNode entries = activity.json().path("entries");

        JsonNode request = firstByTypeAndPath(entries, "REQUEST", "/secure");
        assertThat(request)
                .as("the /secure call must surface as a REQUEST entry")
                .isNotNull();
        String requestId = request.path("id").asText(null);
        String requestTrace = request.path("correlationId").asText(null);
        assertThat(requestTrace)
                .as("with OpenTelemetry present the request entry must carry the server span's trace id")
                .isNotBlank();
        assertThat(request.path("securedPrincipal").asText(null))
                .as("the REQUEST entry must carry the authenticated principal")
                .isEqualTo("admin");

        JsonNode security = firstOfType(entries, "SECURITY");
        assertThat(security)
                .as("an authentication event must surface as a SECURITY entry")
                .isNotNull();
        assertThat(security.path("summary").asText())
                .as("the security entry summary should name the authentication event")
                .startsWith("Authentication");
        assertThat(security.path("correlationId").asText(null))
                .as("the SECURITY entry must carry the same trace id as its request")
                .isEqualTo(requestTrace);
        assertThat(security.path("parentId").asText(null))
                .as("the SECURITY entry must nest under its owning request")
                .isEqualTo(requestId);

        boolean securitySourceListed = false;
        for (JsonNode source : activity.json().path("sources")) {
            securitySourceListed = securitySourceListed || "security".equals(source.asText());
        }
        assertThat(securitySourceListed)
                .as("$.sources must include \"security\" once the source is wired and available")
                .isTrue();
    }

    @Test
    void unauthenticatedRequestCapturesNoPrincipal() {
        Response unauthorized = probe().get("/secure");
        assertThat(unauthorized.status()).as("/secure without credentials").isEqualTo(401);

        Response exchanges = probe().get("/bootui/api/http-exchanges");
        assertThat(exchanges.status())
                .as("GET /bootui/api/http-exchanges status")
                .isEqualTo(200);
        JsonNode secureExchange = firstByPath(exchanges.json().path("exchanges"), "/secure");
        assertThat(secureExchange).as("captured /secure exchange").isNotNull();
        assertThat(secureExchange.path("principal").isNull())
                .as("Part A: an unauthenticated request's captured exchange must carry a null principal")
                .isTrue();
    }

    /**
     * Returns the first (newest, per {@code HttpExchangeBuffer}'s newest-first ordering) exchange whose
     * path matches, so each test observes only the call it just made even though the buffer is shared
     * across test methods in this class.
     */
    private static JsonNode firstByPath(JsonNode exchanges, String path) {
        for (JsonNode exchange : exchanges) {
            if (path.equals(exchange.path("path").asText())) {
                return exchange;
            }
        }
        return null;
    }

    /** Same newest-first-wins lookup as {@link #firstByPath}, scoped to a specific entry type + path. */
    private static JsonNode firstByTypeAndPath(JsonNode entries, String type, String path) {
        for (JsonNode entry : entries) {
            if (type.equals(entry.path("type").asText())
                    && path.equals(entry.path("path").asText())) {
                return entry;
            }
        }
        return null;
    }

    private static JsonNode firstOfType(JsonNode entries, String type) {
        for (JsonNode entry : entries) {
            if (type.equals(entry.path("type").asText())) {
                return entry;
            }
        }
        return null;
    }
}
