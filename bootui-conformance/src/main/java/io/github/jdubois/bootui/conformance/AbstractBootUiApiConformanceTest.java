package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';"
            + " img-src 'self' data:; font-src 'self' data:; connect-src 'self';"
            + " object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    private static final Map<String, String> COMMON_SECURITY_HEADERS = Map.of(
            CONTENT_SECURITY_POLICY,
            CSP,
            "X-Content-Type-Options",
            "nosniff",
            "X-Frame-Options",
            "DENY",
            "Referrer-Policy",
            "strict-origin-when-cross-origin",
            "Permissions-Policy",
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()");

    private static final String NO_STORE = "no-store, must-revalidate";

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private static final String NO_CACHE = "no-cache";

    private static final Pattern BUILT_ASSET =
            Pattern.compile("(?:src|href)=\"\\./(assets/[^\"]+-[A-Za-z0-9_-]{8,}\\.[^\"]+)\"");

    /**
     * Panels whose primary data lives at a plain {@code GET /bootui/api/<id>} and must answer 200 with
     * JSON whenever the manifest reports them available and enabled. Scan-first and action-capable panels
     * remain safe to include because their root GETs only return the current report/state; this inventory
     * never invokes a scan, network call, file capture, or other mutation.
     */
    private static final Set<String> DATA_PANEL_ROOT_GETS = Set.of(
            "overview",
            "health",
            "metrics",
            "live-memory",
            "jvm-tuning",
            "heap-dump",
            "threads",
            "memory",
            "startup",
            "graalvm",
            "config",
            "profile-diff",
            "loggers",
            "beans",
            "conditions",
            "mappings",
            "spring-security",
            "scheduled",
            "hibernate",
            "security",
            "pentesting",
            "spring",
            "cache",
            "exceptions",
            "http-exchanges",
            "security-logs",
            "http-sessions",
            "traces",
            "architecture",
            "rest-api",
            "vulnerabilities",
            "dev-services",
            "devtools",
            "github",
            "crac",
            "sql-trace",
            "rest-client-trace",
            "mcp-server",
            "email",
            "kafka",
            "activity");

    /** Panels whose primary data lives at a nested path instead of the root {@code GET /bootui/api/<id>}. */
    private static final Map<String, String> NESTED_GET_PATHS = Map.ofEntries(
            Map.entry("data", "/bootui/api/data/repositories"),
            Map.entry("ai", "/bootui/api/ai/overview"),
            Map.entry("log-tail", "/bootui/api/log-tail/recent"),
            Map.entry("copilot", "/bootui/api/copilot/dashboard"),
            Map.entry("claude-code", "/bootui/api/claude-code/dashboard"),
            Map.entry("flyway", "/bootui/api/flyway/migrations"),
            Map.entry("liquibase", "/bootui/api/liquibase/changesets"),
            Map.entry("database-connection-pools", "/bootui/api/database-connection-pools/pools"));

    /** Panels that intentionally have no safe GET because their API is an action form. */
    private static final Set<String> ACTION_ONLY_PANELS = Set.of("http-probe");

    /**
     * Action-capable panels whose mutating endpoint requires an explicit {@code {"confirm":true}} body; the
     * path is the well-known action endpoint for the confirmation gate test.
     */
    private static final Map<String, String> CONFIRMATION_ACTION_PATHS = Map.of(
            "flyway", "/bootui/api/flyway/migrate",
            "liquibase", "/bootui/api/liquibase/update");

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
            if (!DATA_PANEL_ROOT_GETS.contains(id)
                    || !panel.path("available").asBoolean(false)
                    || !panel.path("enabled").asBoolean(true)) {
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
    void endpointInventoryCoversEveryManifestPanel() {
        JsonNode panels = probe().get("/bootui/api/panels").json().get("panels");
        List<String> missing = new ArrayList<>();
        panels.forEach(panel -> {
            String id = panel.path("id").asText(null);
            if (!DATA_PANEL_ROOT_GETS.contains(id)
                    && !NESTED_GET_PATHS.containsKey(id)
                    && !ACTION_ONLY_PANELS.contains(id)) {
                missing.add(id);
            }
        });

        assertThat(missing)
                .as("every manifest panel must declare its safe primary GET or be explicitly action-only")
                .isEmpty();
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
        assertSecurityHeaders(rejected, NO_STORE, true);
    }

    @Test
    void securityHeadersCoverShellAndHashedAssets() {
        Response shell = probe().get("/bootui/");
        assertThat(shell.status()).as("GET /bootui/ status").isEqualTo(200);
        assertThat(shell.contentType()).as("GET /bootui/ content-type").containsIgnoringCase("text/html");
        assertSecurityHeaders(shell, NO_CACHE, true);

        Matcher asset = BUILT_ASSET.matcher(shell.body());
        assertThat(asset.find())
                .as("packaged index.html must reference a content-hashed asset: %s", shell.body())
                .isTrue();
        Response builtAsset = probe().get("/bootui/" + asset.group(1));
        assertThat(builtAsset.status()).as("GET packaged hashed asset status").isEqualTo(200);
        assertSecurityHeaders(builtAsset, IMMUTABLE, false);

        Response missingHashedAsset = probe().get("/bootui/assets/missing-C2x2BcDS.js");
        assertThat(missingHashedAsset.status())
                .as("GET missing hashed-looking asset status")
                .isEqualTo(404);
        assertSecurityHeaders(missingHashedAsset, NO_CACHE, true);
    }

    @Test
    void securityHeadersCoverApiErrorsStreamsAndDownloads() {
        Response api = probe().get("/bootui/api/overview");
        assertThat(api.status()).as("GET overview status").isEqualTo(200);
        assertSecurityHeaders(api, NO_STORE, true);

        Response error = probe().get("/bootui/api/this-route-does-not-exist");
        assertThat(error.status()).as("unmatched BootUI API route status").isEqualTo(404);
        assertSecurityHeaders(error, NO_STORE, true);

        Response stream = probe().getStreaming("/bootui/api/log-tail/stream");
        assertThat(stream.status()).as("GET log-tail SSE stream status").isEqualTo(200);
        assertThat(stream.contentType()).as("GET log-tail SSE content-type").containsIgnoringCase("text/event-stream");
        assertSecurityHeaders(stream, NO_STORE, true);

        BootUiHttpProbe downloadProbe = probe();
        Response download = downloadProbe.post("/bootui/api/threads/download", stateChangingHeaders(downloadProbe));
        assertThat(download.status()).as("POST thread-dump download status").isEqualTo(200);
        assertThat(download.headerValues("Content-Disposition"))
                .as("download must have one attachment disposition")
                .containsExactly("attachment; filename=\"thread-dump.txt\"");
        assertSecurityHeaders(download, NO_STORE, true);
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
        assertThat(isPanelUsableInLiveManifest("loggers"))
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

    @Test
    void panelDisabledRequestIsRejectedWithCanonicalBody() {
        // A panel disabled via bootui.panels.<id>.enabled=false must respond with 403 for all requests
        // to its /bootui/api/<id> paths — on both adapters, via their respective access filters
        // (PanelAccessFilter on Spring, QuarkusPanelAccessFilter on Quarkus). Both filters emit the same
        // canonical JSON 403 body {"error":"BootUI panel access denied","panel":"<id>","reason":"..."}.
        // Test environments should configure at least one panel as disabled so this test always exercises
        // the gate; the recommended setting is bootui.panels.copilot.enabled=false (copilot is present on
        // every adapter, is not in DATA_PANEL_ROOT_GETS, and disabling it does not affect other tests).
        String disabledId = "copilot";
        JsonNode panel = panelFromLiveManifest(disabledId);
        assertThat(panel)
                .as("the manifest must contain the configured disabled panel")
                .isNotNull();
        assertThat(panel.path("enabled").asBoolean(true))
                .as("conformance fixtures must set bootui.panels.copilot.enabled=false")
                .isFalse();

        Response response = probe().get("/bootui/api/" + disabledId);
        assertThat(response.status())
                .as("GET /bootui/api/%s must be rejected with 403 when the panel is disabled", disabledId)
                .isEqualTo(403);
        assertThat(response.isJson())
                .as("disabled-panel 403 content-type must be JSON (%s)", response.contentType())
                .isTrue();
        JsonNode body = response.json();
        assertThat(body.path("error").asText())
                .as("disabled-panel 403 body.error")
                .isEqualTo("BootUI panel access denied");
        assertThat(body.path("panel").asText())
                .as("disabled-panel 403 body.panel must match the disabled panel id")
                .isEqualTo(disabledId);
        assertThat(body.path("reason").isTextual())
                .as("disabled-panel 403 body.reason must be a non-null string")
                .isTrue();
        assertThat(body.path("reason").asText()).isEqualTo("Panel is disabled via bootui.panels.copilot.enabled=false");
    }

    @Test
    void panelReadOnlyActionIsRejectedWithCanonicalBody() {
        // An action-capable panel configured read-only via bootui.panels.<id>.read-only=true must reject
        // state-changing (POST/PUT/DELETE/PATCH) requests with 403 while still allowing safe reads (GET).
        // Both adapters emit the canonical body {"error":"BootUI panel access denied","panel":"...","reason":"..."}.
        // The heap-dump panel's POST /capture action is the well-known action path used here; test
        // environments should add bootui.panels.heap-dump.read-only=true to the conformance test properties.
        JsonNode heapDumpPanel = panelFromLiveManifest("heap-dump");
        assertThat(heapDumpPanel)
                .as("the manifest must contain the configured read-only panel")
                .isNotNull();
        assertThat(heapDumpPanel.path("readOnly").asBoolean(false))
                .as("conformance fixtures must set bootui.panels.heap-dump.read-only=true")
                .isTrue();

        BootUiHttpProbe probe = probe();
        Map<String, String> headers = stateChangingHeaders(probe);
        Response response = probe.request("POST", "/bootui/api/heap-dump/capture", headers, "");
        assertThat(response.status())
                .as("POST /bootui/api/heap-dump/capture must be rejected with 403 when the panel is read-only")
                .isEqualTo(403);
        assertThat(response.isJson())
                .as("read-only-panel 403 content-type must be JSON (%s)", response.contentType())
                .isTrue();
        JsonNode body = response.json();
        assertThat(body.path("error").asText())
                .as("read-only-panel 403 body.error")
                .isEqualTo("BootUI panel access denied");
        assertThat(body.path("panel").asText())
                .as("read-only-panel 403 body.panel")
                .isEqualTo("heap-dump");
        assertThat(body.path("reason").isTextual())
                .as("read-only-panel 403 body.reason must be a non-null string")
                .isTrue();
        assertThat(body.path("reason").asText())
                .isEqualTo("Panel is read-only via bootui.panels.heap-dump.read-only=true");
    }

    @Test
    void architectureScanLifecycleFromUnscannedToScanned() {
        // The architecture panel delivers its data through an on-demand scan (GET returns the last
        // cached report; POST /scan runs the ArchUnit ruleset). This test validates the cross-adapter
        // contract for the scan lifecycle: the initial GET has a scan.status string field, and POST
        // /scan returns 200 JSON with a non-null scan.status and a numeric scannedAt timestamp,
        // proving the analysis actually ran rather than returning a cached no-op.
        assumeTrue(
                isPanelUsableInLiveManifest("architecture"), "architecture panel is not available in this environment");

        // 1. Initial GET – scan.status must be a string (typically NOT_SCANNED, but any status is valid).
        Response initial = probe().get("/bootui/api/architecture");
        assertThat(initial.status())
                .as("GET /bootui/api/architecture initial status")
                .isEqualTo(200);
        assertThat(initial.isJson())
                .as("GET /bootui/api/architecture content-type")
                .isTrue();
        assertThat(initial.json().path("scan").path("status").isTextual())
                .as("GET /bootui/api/architecture scan.status must be a string before any scan")
                .isTrue();

        // 2. POST /scan – must return the fresh scan report; scannedAt proves the engine ran.
        BootUiHttpProbe probe = probe();
        Map<String, String> headers = stateChangingHeaders(probe);
        Response scanResponse = probe.request("POST", "/bootui/api/architecture/scan", headers, "");
        assertThat(scanResponse.status())
                .as("POST /bootui/api/architecture/scan status")
                .isEqualTo(200);
        assertThat(scanResponse.isJson())
                .as("POST /bootui/api/architecture/scan content-type")
                .isTrue();
        JsonNode scanned = scanResponse.json();
        assertThat(scanned.path("scan").path("status").isTextual())
                .as("POST /bootui/api/architecture/scan scan.status must be a string")
                .isTrue();
        assertThat(scanned.path("scan").path("scannedAt").isNumber())
                .as("POST /bootui/api/architecture/scan scan.scannedAt must be a number after a real scan")
                .isTrue();
    }

    @Test
    void vulnerabilitiesGetHasCanonicalShape() {
        // GET /bootui/api/vulnerabilities must never trigger a network call to OSV.dev (scans are always
        // user-initiated via POST /scan). This test validates the initial-shape contract shared by both
        // adapters: a scan status descriptor object, a scanningEnabled boolean, a total count, and a
        // dependencies array (the local classpath inventory). The scan.status value is unasserted because
        // both adapters may pre-populate it differently (e.g. NOT_SCANNED vs DISABLED when OSV is off).
        assumeTrue(
                isPanelUsableInLiveManifest("vulnerabilities"),
                "vulnerabilities panel is not available in this environment");

        Response response = probe().get("/bootui/api/vulnerabilities");
        assertThat(response.status())
                .as("GET /bootui/api/vulnerabilities status")
                .isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/vulnerabilities content-type")
                .isTrue();
        JsonNode report = response.json();
        assertThat(report.path("scan").isObject())
                .as("$.scan must be an object")
                .isTrue();
        assertThat(report.path("scan").path("status").isTextual())
                .as("$.scan.status must be a string")
                .isTrue();
        assertThat(report.path("scanningEnabled").isBoolean())
                .as("$.scanningEnabled must be a boolean")
                .isTrue();
        assertThat(report.path("dependencies").isArray())
                .as("$.dependencies must be an array")
                .isTrue();
        assertThat(report.path("total").isInt())
                .as("$.total must be an integer")
                .isTrue();
    }

    @Test
    void beansEndpointSupportsPaginationAndFilter() {
        // Beans pagination and filter are shared cross-adapter concerns owned by the engine BeansService;
        // divergence in offset/limit handling or query filtering could break the SPA's infinite-scroll
        // behaviour. This test validates that: the root response has the expected shape (total, beans
        // array, page metadata), limit=1 returns at most 1 bean, and a query that matches nothing returns
        // total=0 with an empty array.
        assumeTrue(isPanelUsableInLiveManifest("beans"), "beans panel is not available in this environment");

        // Root GET — shape contract.
        Response root = probe().get("/bootui/api/beans");
        assertThat(root.status()).as("GET /bootui/api/beans status").isEqualTo(200);
        assertThat(root.isJson()).as("GET /bootui/api/beans content-type").isTrue();
        JsonNode report = root.json();
        assertThat(report.path("total").isInt())
                .as("$.total must be an integer")
                .isTrue();
        assertThat(report.path("beans").isArray())
                .as("$.beans must be an array")
                .isTrue();
        assertThat(report.path("page").isObject())
                .as("$.page must be an object (pagination metadata)")
                .isTrue();

        // Pagination: limit=1 must return at most 1 bean regardless of the total count.
        Response limited = probe().get("/bootui/api/beans?limit=1");
        assertThat(limited.status()).as("GET /bootui/api/beans?limit=1 status").isEqualTo(200);
        assertThat(limited.json().path("beans").size())
                .as("GET /bootui/api/beans?limit=1 must return at most 1 bean")
                .isLessThanOrEqualTo(1);

        // Query filter: a value that cannot match any bean name should return an empty page.
        Response noMatch = probe().get("/bootui/api/beans?q=conformanceprobexyz123notabean");
        assertThat(noMatch.status())
                .as("GET /bootui/api/beans?q=<nonexistent> status")
                .isEqualTo(200);
        assertThat(noMatch.json().path("page").path("matched").asInt())
                .as("GET /bootui/api/beans?q=<nonexistent> page.matched must be 0")
                .isZero();
        assertThat(noMatch.json().path("beans").isEmpty())
                .as("GET /bootui/api/beans?q=<nonexistent> beans must be empty")
                .isTrue();
    }

    @Test
    void loggersEndpointSupportsPaginationParams() {
        // The loggers pagination contract is shared between the Spring adapter (Actuator LoggersEndpoint)
        // and the Quarkus adapter (JBoss LogManager). The engine LoggersService owns the sort/filter/page
        // logic; this test validates that both adapters honour the limit param and return the expected
        // response shape (loggers array, page metadata).
        assumeTrue(isPanelUsableInLiveManifest("loggers"), "loggers panel is not available in this environment");

        // Root GET — shape contract.
        Response root = probe().get("/bootui/api/loggers");
        assertThat(root.status()).as("GET /bootui/api/loggers status").isEqualTo(200);
        assertThat(root.isJson()).as("GET /bootui/api/loggers content-type").isTrue();
        JsonNode report = root.json();
        assertThat(report.path("loggers").isArray())
                .as("$.loggers must be an array")
                .isTrue();
        assertThat(report.path("page").isObject())
                .as("$.page must be an object (pagination metadata)")
                .isTrue();

        // Pagination: limit=1 must return at most 1 logger.
        Response limited = probe().get("/bootui/api/loggers?limit=1");
        assertThat(limited.status())
                .as("GET /bootui/api/loggers?limit=1 status")
                .isEqualTo(200);
        assertThat(limited.json().path("loggers").size())
                .as("GET /bootui/api/loggers?limit=1 must return at most 1 logger")
                .isLessThanOrEqualTo(1);

        // Query filter: a query that cannot match any logger name should return an empty page.
        Response noMatch = probe().get("/bootui/api/loggers?q=conformanceprobexyz123notalogger");
        assertThat(noMatch.status())
                .as("GET /bootui/api/loggers?q=<nonexistent> status")
                .isEqualTo(200);
        assertThat(noMatch.json().path("loggers").size())
                .as("GET /bootui/api/loggers?q=<nonexistent> must return an empty list")
                .isZero();
    }

    @Test
    void tracesListClearAndDetailContract() {
        // The Traces panel is statically available on both adapters (OTel telemetry store). This test
        // covers three endpoints that existing root-GET coverage misses: DELETE /traces (returns 204 No
        // Content), GET /traces/{id} for an unknown id (returns 404), and the list response shape
        // (enabled boolean + traces array). All three status codes are part of the shared contract.
        assumeTrue(isPanelUsableInLiveManifest("traces"), "traces panel is not available in this environment");

        // 1. GET /traces — shape contract.
        Response listResponse = probe().get("/bootui/api/traces");
        assertThat(listResponse.status()).as("GET /bootui/api/traces status").isEqualTo(200);
        assertThat(listResponse.isJson())
                .as("GET /bootui/api/traces content-type")
                .isTrue();
        JsonNode report = listResponse.json();
        assertThat(report.path("traces").isArray())
                .as("$.traces must be an array")
                .isTrue();
        assertThat(report.path("enabled").isBoolean())
                .as("$.enabled must be a boolean")
                .isTrue();

        // 2. DELETE /traces — clears the buffer; must return 204 No Content with no body.
        BootUiHttpProbe probe = probe();
        Map<String, String> headers = stateChangingHeaders(probe);
        Response clearResponse = probe.request("DELETE", "/bootui/api/traces", headers, null);
        assertThat(clearResponse.status())
                .as("DELETE /bootui/api/traces must return 204 No Content")
                .isEqualTo(204);
        assertThat(clearResponse.body())
                .as("DELETE /bootui/api/traces response body")
                .isEmpty();

        // 3. GET /traces/{unknown} — must return 404 for an unrecognised trace id.
        Response detailResponse = probe().get("/bootui/api/traces/conformance-probe-unknown-trace-id-xyz");
        assertThat(detailResponse.status())
                .as("GET /bootui/api/traces/{unknown} must return 404 for an unrecognised trace id")
                .isEqualTo(404);
    }

    @Test
    void nestedGetEndpointsReturnJsonForAvailablePanels() {
        // Panels whose primary data lives at a sub-path (not /bootui/api/<id>) are excluded from
        // availablePanelsAnswerTheirPrimaryGet(); this test covers their nested paths. Each panel is
        // skipped when the live manifest reports it unavailable (capability not on the classpath, not
        // configured, etc.) rather than failing, so the test remains green on any adapter combination.
        JsonNode panelsArray = probe().get("/bootui/api/panels").json().get("panels");
        Map<String, PanelState> panelStates = new java.util.LinkedHashMap<>();
        if (panelsArray != null) {
            panelsArray.forEach(panel -> panelStates.put(
                    panel.path("id").asText(null),
                    new PanelState(
                            panel.path("available").asBoolean(false),
                            panel.path("enabled").asBoolean(true))));
        }

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> entry : NESTED_GET_PATHS.entrySet()) {
            String panelId = entry.getKey();
            String path = entry.getValue();
            if (!panelStates.getOrDefault(panelId, PanelState.UNUSABLE).usable()) {
                // Panel not available or explicitly disabled on this adapter / environment.
                continue;
            }
            Response response = probe().get(path);
            if (response.status() != 200) {
                failures.add(panelId + " GET " + path + " -> HTTP " + response.status());
            } else if (!response.isJson()) {
                failures.add(panelId + " GET " + path + " -> non-JSON content-type '" + response.contentType() + "'");
            }
        }
        if (!failures.isEmpty()) {
            fail("Available panels' nested GET endpoints regressed: " + failures);
        }
    }

    @Test
    void confirmationGatedActionsReturn400WhenConfirmMissing() {
        // Flyway and Liquibase expose mutating actions that require an explicit {"confirm":true} in the
        // request body. Omitting confirm (empty body or {"confirm":false}) must return HTTP 400 with a
        // JSON body containing a "message" field — the canonical confirmation gate enforced by the shared
        // engine FlywayService / LiquibaseService. Both adapters must fire this gate identically.
        // Panels are skipped when unavailable (optional dependency not on the classpath).
        JsonNode panelsArray = probe().get("/bootui/api/panels").json().get("panels");
        Map<String, PanelState> panelStates = new java.util.LinkedHashMap<>();
        if (panelsArray != null) {
            panelsArray.forEach(panel -> panelStates.put(
                    panel.path("id").asText(null),
                    new PanelState(
                            panel.path("available").asBoolean(false),
                            panel.path("enabled").asBoolean(true))));
        }

        BootUiHttpProbe probe = probe();
        Map<String, String> headers = stateChangingHeaders(probe);
        List<String> failures = new ArrayList<>();

        for (Map.Entry<String, String> entry : CONFIRMATION_ACTION_PATHS.entrySet()) {
            String panelId = entry.getKey();
            String path = entry.getValue();
            if (!panelStates.getOrDefault(panelId, PanelState.UNUSABLE).usable()) {
                continue; // not available on this adapter / environment
            }
            // Send without confirm=true — the engine must return 400 before touching the database.
            Response response = probe.request("POST", path, headers, "{}");
            if (response.status() != 400) {
                failures.add(panelId + " POST " + path + " without confirm -> HTTP " + response.status()
                        + " (expected 400)");
            } else if (!response.isJson()) {
                failures.add(panelId + " POST " + path + " 400 body is not JSON");
            } else if (!"blocked".equals(response.json().path("status").asText())) {
                failures.add(panelId + " POST " + path + " 400 body.status is not 'blocked'");
            } else if (!"Action requires confirm=true because it mutates the application database."
                    .equals(response.json().path("message").asText())) {
                failures.add(panelId + " POST " + path + " 400 body.message is not canonical");
            }
        }
        if (!failures.isEmpty()) {
            fail("Confirmation-gated actions did not return 400 as expected: " + failures);
        }
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
        headers.put("Origin", baseUrl());
        probe.get("/bootui/api/overview");
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        return headers;
    }

    private void assertSecurityHeaders(Response response, String cacheControl, boolean expectPragma) {
        COMMON_SECURITY_HEADERS.forEach((name, value) -> assertThat(response.headerValues(name))
                .as("%s must be present exactly once", name)
                .containsExactly(value));
        assertThat(response.headerValues("Cache-Control"))
                .as("Cache-Control must be present exactly once")
                .containsExactly(cacheControl);
        if (expectPragma) {
            assertThat(response.headerValues("Pragma"))
                    .as("Pragma must be present exactly once")
                    .containsExactly("no-cache");
        } else {
            assertThat(response.headerValues("Pragma"))
                    .as("immutable assets must not carry a conflicting Pragma")
                    .isEmpty();
        }
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

    /** Returns the live manifest entry for the named panel, or {@code null} if not found. */
    private JsonNode panelFromLiveManifest(String id) {
        JsonNode panels = probe().get("/bootui/api/panels").json().get("panels");
        if (panels == null) {
            return null;
        }
        for (JsonNode panel : panels) {
            if (id.equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        return null;
    }

    /**
     * Returns {@code true} when the live manifest reports the named panel as available
     * ({@code available: true}) on the currently-booted adapter.
     */
    private boolean isPanelUsableInLiveManifest(String id) {
        JsonNode panel = panelFromLiveManifest(id);
        return panel != null
                && panel.path("available").asBoolean(false)
                && panel.path("enabled").asBoolean(true);
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

    private record PanelState(boolean available, boolean enabled) {

        private static final PanelState UNUSABLE = new PanelState(false, false);

        private boolean usable() {
            return available && enabled;
        }
    }
}
