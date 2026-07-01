package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Shared, black-box HTTP conformance contract for the BootUI {@code /bootui/api/**} surface.
 *
 * <p>This is the behavior safety net the Quarkus port is built on: both the Spring Boot adapter and
 * the Quarkus adapter run this exact suite against a booted sample app, so the shared Vue UI keeps
 * binding to one stable API shape. A concrete subclass boots its app, exposes the base URL via
 * {@link #baseUrl()}, and (optionally) overrides {@link #expectedPanelsResource()} to declare the
 * panel manifest its platform ships.
 *
 * <p>The assertions here are deliberately framework-neutral: they verify the panel manifest contract
 * and that every panel the manifest reports as available answers its primary GET with JSON, plus the
 * transport-level CSRF defense. Fine-grained per-endpoint payloads stay covered by each adapter's own
 * unit/controller tests; this suite locks the cross-adapter contract.
 */
public abstract class AbstractBootUiApiConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_EXPECTED_PANELS =
            "/io/github/jdubois/bootui/conformance/expected-panels-spring.json";

    /**
     * Panels whose primary data lives at a plain {@code GET /bootui/api/<id>} and must answer 200 with
     * JSON whenever the manifest reports them available — this includes the advisor panels that serve a
     * meaningful unscanned root report (hibernate, security, spring). Panels whose primary data lives at a
     * sub-path (database-connection-pools, data, ai, log-tail, flyway, liquibase), scan-first advisor panels
     * whose root GET is exercised through a {@code POST /scan} (architecture, rest-api, pentesting,
     * vulnerabilities) or that are not applicable on every adapter (graalvm, crac), and action-first panels
     * (heap-dump, http-probe) are intentionally excluded: they are exercised by adapter-specific tests
     * instead of this cross-adapter contract.
     */
    private static final Set<String> DATA_PANEL_ROOT_GETS = Set.of(
            "overview",
            "health",
            "metrics",
            "live-memory",
            "jvm-tuning",
            "threads",
            "memory",
            "startup",
            "config",
            "profile-diff",
            "loggers",
            "beans",
            "conditions",
            "mappings",
            "scheduled",
            "hibernate",
            "security",
            "spring",
            "cache",
            "http-exchanges",
            "security-logs",
            "http-sessions",
            "traces",
            "dev-services",
            "devtools",
            "github",
            "mcp-server",
            "activity");

    /** Base URL of the booted app under test, e.g. {@code http://localhost:54321} (no trailing slash). */
    protected abstract String baseUrl();

    /** Classpath resource of the expected panel manifest for this platform. */
    protected String expectedPanelsResource() {
        return DEFAULT_EXPECTED_PANELS;
    }

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl());
    }

    @Test
    void panelsManifestMatchesExpectedContract() {
        List<ExpectedPanel> expected = loadExpectedPanels();

        Response response = probe().get("/bootui/api/panels");
        assertThat(response.status()).as("GET /bootui/api/panels status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/panels content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();

        String expectedPlatform = loadExpectedPlatform();
        JsonNode livePlatform = root.path("platform");
        assertThat(livePlatform.isTextual())
                .as("$.platform must be a non-null string (got %s)", livePlatform)
                .isTrue();
        assertThat(livePlatform.asText())
                .as("manifest platform must match the expected fixture")
                .isEqualTo(expectedPlatform);

        JsonNode panels = root.get("panels");
        assertThat(panels).as("$.panels array").isNotNull();
        assertThat(panels.isArray()).as("$.panels is an array").isTrue();

        List<String> actualIds = new ArrayList<>();
        panels.forEach(panel -> actualIds.add(panel.path("id").asText(null)));
        assertThat(actualIds)
                .as("panel ids and ordering must match the expected manifest exactly")
                .containsExactlyElementsOf(
                        expected.stream().map(ExpectedPanel::id).toList());

        Map<String, JsonNode> byId = new java.util.LinkedHashMap<>();
        panels.forEach(panel -> byId.put(panel.path("id").asText(null), panel));

        for (ExpectedPanel expectedPanel : expected) {
            JsonNode panel = byId.get(expectedPanel.id());
            assertThat(panel.path("title").asText(null))
                    .as("panel %s title", expectedPanel.id())
                    .isEqualTo(expectedPanel.title());
            assertPanelShape(expectedPanel, panel);
        }
    }

    @Test
    void availablePanelsAnswerTheirPrimaryGet() {
        Response manifest = probe().get("/bootui/api/panels");
        assertThat(manifest.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        List<String> failures = new ArrayList<>();
        for (JsonNode panel : manifest.json().get("panels")) {
            String id = panel.path("id").asText(null);
            if (!DATA_PANEL_ROOT_GETS.contains(id) || !panel.path("available").asBoolean(false)) {
                continue;
            }
            Response response = probe().get("/bootui/api/" + id);
            if (response.status() != 200) {
                failures.add(id + " -> HTTP " + response.status());
            } else if (!response.isJson()) {
                failures.add(id + " -> non-JSON content-type '" + response.contentType() + "'");
            }
        }

        if (!failures.isEmpty()) {
            fail("Available panels whose primary GET regressed: " + failures);
        }
    }

    @Test
    void crossSiteStateChangingRequestIsRejected() {
        // Black-box safety floor: a state-changing request whose Origin host differs from the request
        // host must be rejected (CSRF / DNS-rebind defense), on every platform, before it can mutate
        // anything. Both adapters are thin bindings over the shared engine LocalhostGuard, so they must
        // return the *same* 403: a JSON body of {"error":"<canonical message>"} with an application/json
        // content-type. Fine-grained safety semantics that cannot be reproduced over loopback HTTP
        // (trusted source, non-loopback peer, Host allow-list/rebinding, the host-only Origin compare)
        // are pinned separately as pure-function LocalhostGuard contract tests plus per-adapter binding
        // tests. Uses only non-restricted headers so it behaves identically across JDKs and across the
        // Spring/Quarkus transports.
        //
        // The expected message is asserted as a literal (not imported from the engine) on purpose: this
        // is the black-box wire contract the SPA/e2e may key on, so a change to the constant must show up
        // here as a deliberate contract change rather than passing silently.
        Response rejected = probe().post(
                        "/bootui/api/overview",
                        Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));
        assertThat(rejected.status())
                .as("cross-site POST to /bootui/api/overview must be rejected with 403")
                .isEqualTo(403);
        assertThat(rejected.isJson())
                .as("cross-site 403 content-type must be JSON (%s)", rejected.contentType())
                .isTrue();
        assertThat(rejected.json().path("error").asText())
                .as("cross-site 403 body must carry the canonical LocalhostGuard message")
                .isEqualTo("BootUI rejected a cross-site request to a state-changing endpoint.");
    }

    @Test
    void overviewEndpointServesShellContract() {
        // GET /bootui/api/overview is the shared shell's framework-neutral chrome source: it powers the
        // header subtitle/status and primes the CSRF cookie, so it must answer on every platform
        // regardless of the Overview dashboard *panel* (which is a purely client-side aggregation that
        // never calls this endpoint). This is a shape contract:
        // it pins the fields the shell binds to, not their platform-varying values (so it asserts that
        // frameworkVersion is present, not its value, and never asserts the activation.localhostOnly
        // flag, which differs by platform).
        Response response = probe().get("/bootui/api/overview");
        assertThat(response.status()).as("GET /bootui/api/overview status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/overview content-type (%s)", response.contentType())
                .isTrue();

        JsonNode overview = response.json();
        assertThat(overview.path("applicationName").isTextual())
                .as("$.applicationName must be a string")
                .isTrue();
        assertThat(overview.path("frameworkName").isTextual())
                .as("$.frameworkName must be a string (e.g. 'Spring Boot' or 'Quarkus')")
                .isTrue();
        assertThat(!overview.path("frameworkVersion").isMissingNode())
                .as("$.frameworkVersion must be present (its value is platform-specific)")
                .isTrue();
        assertThat(overview.path("javaVersion").isTextual())
                .as("$.javaVersion must be a string")
                .isTrue();
        assertThat(overview.path("activeProfiles").isArray())
                .as("$.activeProfiles must be an array")
                .isTrue();

        JsonNode activation = overview.path("activation");
        assertThat(activation.path("enabled").isBoolean())
                .as("$.activation.enabled must be a boolean")
                .isTrue();
        assertThat(activation.path("reason").isTextual())
                .as("$.activation.reason must be a string")
                .isTrue();
    }

    @Test
    void loggerLevelCanBeSetAndResetThroughTheWritePath() {
        // Cross-adapter WRITE contract: POST /bootui/api/loggers/{name} sets one logger's level and
        // returns its refreshed view; a null level resets it. Both adapters route this through the shared
        // engine LoggersService over their own backend (Actuator's LoggersEndpoint on Spring Boot, the
        // JBoss LogManager on Quarkus), so a canonical level name set on one platform round-trips to the
        // same name on the other. This is the first mutating endpoint exercised on both adapters, so it
        // also proves a same-origin write reaches the backend through each adapter's safety stack:
        // mirroring the BootUI SPA, a priming GET makes the Spring adapter mint its XSRF-TOKEN cookie
        // (via CsrfCookieFilter), which is echoed back as the X-XSRF-TOKEN header; the Quarkus adapter
        // sets no such cookie and lets the same-origin write through, so the identical flow runs on both.
        assertThat(loggersAvailable())
                .as("both adapters ship the Loggers panel, so its write path must be exercisable")
                .isTrue();

        BootUiHttpProbe probe = probe();
        String logger = "com.example.bootui.conformanceprobe";
        Map<String, String> headers = stateChangingHeaders(probe);

        Response set = probe.request("POST", "/bootui/api/loggers/" + logger, headers, "{\"level\":\"DEBUG\"}");
        assertThat(set.status()).as("POST set-level status").isEqualTo(200);
        assertThat(set.isJson())
                .as("POST set-level content-type (%s)", set.contentType())
                .isTrue();
        JsonNode updated = set.json();
        assertThat(updated.path("name").asText()).as("returned logger name").isEqualTo(logger);
        assertThat(updated.path("configuredLevel").asText())
                .as("configured level after set")
                .isEqualTo("DEBUG");
        assertThat(updated.path("effectiveLevel").asText())
                .as("effective level after set")
                .isEqualTo("DEBUG");

        Response reset = probe.request("POST", "/bootui/api/loggers/" + logger, headers, "{\"level\":null}");
        assertThat(reset.status()).as("POST reset-level status").isEqualTo(200);
        assertThat(isNull(reset.json().path("configuredLevel")))
                .as("configured level must be null after a reset")
                .isTrue();
    }

    /**
     * Headers for a same-origin state-changing request, built exactly as the BootUI SPA does. A priming
     * GET lets the Spring adapter set its {@code XSRF-TOKEN} cookie, which Spring's SPA CSRF contract
     * expects echoed back verbatim as the {@code X-XSRF-TOKEN} header. The Quarkus adapter sets no CSRF
     * cookie, so only {@code Content-Type} is sent and its Origin-based defense allows the write.
     */
    private Map<String, String> stateChangingHeaders(BootUiHttpProbe probe) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        probe.get("/bootui/api/overview");
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        return headers;
    }

    private boolean loggersAvailable() {
        for (JsonNode panel : probe().get("/bootui/api/panels").json().get("panels")) {
            if ("loggers".equals(panel.path("id").asText(null))) {
                return panel.path("available").asBoolean(false);
            }
        }
        return false;
    }

    private void assertPanelShape(ExpectedPanel expectedPanel, JsonNode panel) {
        String id = expectedPanel.id();
        assertThat(panel.path("id").isTextual())
                .as("panel %s id is a string", id)
                .isTrue();
        assertThat(panel.path("title").isTextual())
                .as("panel %s title is a string", id)
                .isTrue();
        assertThat(panel.path("available").isBoolean())
                .as("panel %s available is a boolean", id)
                .isTrue();
        assertThat(panel.path("enabled").isBoolean())
                .as("panel %s enabled is a boolean", id)
                .isTrue();
        assertThat(panel.path("readOnly").isBoolean())
                .as("panel %s readOnly is a boolean", id)
                .isTrue();

        boolean available = panel.path("available").asBoolean();
        JsonNode unavailableReason = panel.path("unavailableReason");
        if (available) {
            assertThat(isNull(unavailableReason))
                    .as("panel %s is available so unavailableReason must be null", id)
                    .isTrue();
        } else {
            assertThat(unavailableReason.isTextual())
                    .as("panel %s is unavailable so unavailableReason must be a non-null string", id)
                    .isTrue();
        }

        boolean readOnly = panel.path("readOnly").asBoolean();
        JsonNode readOnlyReason = panel.path("readOnlyReason");
        if (readOnly) {
            assertThat(expectedPanel.actionCapable())
                    .as("panel %s is read-only so it must be action-capable", id)
                    .isTrue();
            assertThat(readOnlyReason.isTextual())
                    .as("panel %s is read-only so readOnlyReason must be a non-null string", id)
                    .isTrue();
        } else {
            assertThat(isNull(readOnlyReason))
                    .as("panel %s is not read-only so readOnlyReason must be null", id)
                    .isTrue();
        }
    }

    private static boolean isNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private List<ExpectedPanel> loadExpectedPanels() {
        String resource = expectedPanelsResource();
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Expected-panels resource not found on the classpath: " + resource);
            }
            JsonNode root = MAPPER.readTree(in);
            List<ExpectedPanel> panels = new ArrayList<>();
            for (JsonNode panel : root.get("panels")) {
                panels.add(new ExpectedPanel(
                        panel.get("id").asText(),
                        panel.get("title").asText(),
                        panel.get("actionCapable").asBoolean()));
            }
            return panels;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read expected-panels resource: " + resource, ex);
        }
    }

    private String loadExpectedPlatform() {
        String resource = expectedPanelsResource();
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Expected-panels resource not found on the classpath: " + resource);
            }
            JsonNode platform = MAPPER.readTree(in).path("platform");
            assertThat(platform.isTextual())
                    .as("expected-panels fixture %s must declare a string 'platform'", resource)
                    .isTrue();
            return platform.asText();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read expected-panels resource: " + resource, ex);
        }
    }

    /** Expected manifest entry: the contract a platform promises for one panel. */
    protected record ExpectedPanel(String id, String title, boolean actionCapable) {}
}
