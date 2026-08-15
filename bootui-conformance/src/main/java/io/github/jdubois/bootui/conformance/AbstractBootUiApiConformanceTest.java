package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.ActionContract;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.JsonType;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.ReadContract;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.Runtime;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * <p>The assertions here are deliberately framework-neutral: they verify the panel manifest, apply
 * maintainable DTO-family shape and semantic contracts to every available panel, exercise canonical
 * action outcomes, and pin the transport safety floor. Runtime values may vary; field types, null/empty
 * semantics, masking, stable statuses, and error bodies may not.
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

    /** Panels that intentionally have no safe GET because their API is an action form. */
    private static final Set<String> ACTION_ONLY_PANELS = Set.of("http-probe");

    /**
     * Action-capable panels whose mutating endpoint requires an explicit {@code {"confirm":true}} body; the
     * path is the well-known action endpoint for the confirmation gate test.
     */
    private static final Map<String, String> CONFIRMATION_ACTION_PATHS = Map.of(
            "flyway", "/flyway/migrate",
            "liquibase", "/liquibase/update");

    /** Base URL of the booted app under test, e.g. {@code http://localhost:54321} (no trailing slash). */
    protected abstract String baseUrl();

    /** Classpath resource of the expected panel manifest for this platform. */
    protected String expectedPanelsResource() {
        return DEFAULT_EXPECTED_PANELS;
    }

    /** Runtime adapter exercised by this concrete consumer. */
    protected Runtime runtime() {
        return Runtime.SPRING_MVC;
    }

    /** Registry action-capable panels that intentionally expose no write route on this runtime. */
    protected Set<String> actionlessPanels() {
        return Set.of();
    }

    /**
     * Available panels whose configured-path transport is supplied by a pending sibling adapter change.
     * Concrete custom-mount consumers may exclude only those known transport gaps.
     */
    protected Set<String> unsupportedReadContracts() {
        return Set.of();
    }

    /** Browser-visible UI mount, including any host application root path. */
    protected String uiPath() {
        return "/bootui";
    }

    /** Browser-visible API mount, including any host application root path. */
    protected String apiPath() {
        return "/bootui/api";
    }

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl());
    }

    private String api(String relativePath) {
        return apiPath() + relativePath;
    }

    private String ui(String relativePath) {
        return uiPath() + relativePath;
    }

    @Test
    void panelsManifestMatchesExpectedContract() {
        List<ExpectedPanel> expected = loadExpectedPanels();

        Response response = probe().get(api("/panels"));
        assertThat(response.status()).as("GET %s/panels status", apiPath()).isEqualTo(200);
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
    void availablePanelsMatchTheirDtoFamilyContracts() {
        Response manifest = probe().get(api("/panels"));
        assertThat(manifest.status()).as("GET %s/panels status", apiPath()).isEqualTo(200);

        Map<String, ReadContract> contracts = BootUiApiContractCatalog.readsByPanel();
        List<String> failures = new ArrayList<>();
        for (JsonNode panel : manifest.json().get("panels")) {
            String id = panel.path("id").asText(null);
            ReadContract contract = contracts.get(id);
            if (contract == null
                    || unsupportedReadContracts().contains(id)
                    || !panel.path("available").asBoolean(false)
                    || !panel.path("enabled").asBoolean(true)) {
                continue;
            }
            String path = api(contract.relativePath());
            Response response = probe().get(path);
            if (response.status() != 200) {
                failures.add(id + " -> HTTP " + response.status());
            } else if (!response.isJson()) {
                failures.add(id + " -> non-JSON content-type '" + response.contentType() + "'");
            } else {
                assertJsonContract(id, contract, response.json(), failures);
            }
        }

        if (!failures.isEmpty()) {
            fail("Available panel DTO contracts regressed: " + failures);
        }
    }

    @Test
    void metricsContractIsBoundedAndValidatesQueriesCanonically() {
        Response bounded = probe().get(api("/metrics?offset=0&limit=1"));
        assertThat(bounded.status()).as("bounded metrics list status").isEqualTo(200);
        assertThat(bounded.isJson()).as("bounded metrics list content type").isTrue();

        JsonNode report = bounded.json();
        assertThat(report.path("meters").isArray()).as("$.meters").isTrue();
        assertThat(report.path("meters").size()).as("bounded meter count").isLessThanOrEqualTo(1);
        assertThat(report.path("availableTypes").isArray())
                .as("$.availableTypes")
                .isTrue();
        assertThat(report.path("page").path("limit").asInt()).as("$.page.limit").isEqualTo(1);
        assertThat(report.path("page").path("returned").asInt())
                .as("$.page.returned")
                .isEqualTo(report.path("meters").size());
        assertThat(report.path("page").path("total").asInt())
                .as("$.page.total preserves the visible meter total")
                .isEqualTo(report.path("total").asInt());

        Response invalid = probe().get(api("/metrics?limit=1001"));
        assertThat(invalid.status()).as("invalid metrics limit status").isEqualTo(400);
        assertThat(invalid.isJson()).as("invalid metrics limit content type").isTrue();
        assertThat(invalid.json().path("error").asText())
                .as("canonical metrics validation error")
                .isEqualTo("Metric limit must be between 1 and 1000");

        Response missingName = probe().get(api("/metrics/detail"));
        assertThat(missingName.status()).as("missing metric name status").isEqualTo(400);
        assertThat(missingName.json().path("error").asText())
                .as("canonical missing metric name error")
                .isEqualTo("Metric name must not be blank");

        if (!report.path("meters").isEmpty()) {
            String name = report.path("meters").get(0).path("name").asText();
            Response detail = probe().get(api(
                    "/metrics/detail?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&offset=0&limit=1"));
            assertThat(detail.status()).as("bounded metric detail status").isEqualTo(200);
            assertThat(detail.isJson()).as("bounded metric detail content type").isTrue();
            assertThat(detail.json().path("samples").size())
                    .as("bounded metric sample count")
                    .isLessThanOrEqualTo(1);
            assertThat(detail.json().path("samplePage").path("limit").asInt())
                    .as("$.samplePage.limit")
                    .isEqualTo(1);
            assertThat(detail.json().path("samplePage").path("returned").asInt())
                    .as("$.samplePage.returned")
                    .isEqualTo(detail.json().path("samples").size());
            assertThat(detail.json().path("totalSamples").isInt())
                    .as("$.totalSamples")
                    .isTrue();
            assertThat(detail.json().path("samplesTruncated").isBoolean())
                    .as("$.samplesTruncated")
                    .isTrue();
        }
    }

    @Test
    void endpointInventoryCoversEveryManifestPanel() {
        JsonNode panels = probe().get(api("/panels")).json().get("panels");
        Map<String, ReadContract> contracts = BootUiApiContractCatalog.readsByPanel();
        List<String> missing = new ArrayList<>();
        panels.forEach(panel -> {
            String id = panel.path("id").asText(null);
            if (!contracts.containsKey(id) && !ACTION_ONLY_PANELS.contains(id)) {
                missing.add(id);
            }
        });

        assertThat(missing)
                .as("every manifest panel must declare a typed read contract or be explicitly action-only")
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
                        api("/overview"), Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));
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
        Response shell = probe().get(ui("/"));
        assertThat(shell.status()).as("GET %s/ status", uiPath()).isEqualTo(200);
        assertThat(shell.contentType()).as("GET %s/ content-type", uiPath()).containsIgnoringCase("text/html");
        assertSecurityHeaders(shell, NO_CACHE, true);

        Matcher asset = BUILT_ASSET.matcher(shell.body());
        assertThat(asset.find())
                .as("packaged index.html must reference a content-hashed asset: %s", shell.body())
                .isTrue();
        Response builtAsset = probe().get(ui("/" + asset.group(1)));
        assertThat(builtAsset.status()).as("GET packaged hashed asset status").isEqualTo(200);
        assertSecurityHeaders(builtAsset, IMMUTABLE, false);

        Response missingHashedAsset = probe().get(ui("/assets/missing-C2x2BcDS.js"));
        assertThat(missingHashedAsset.status())
                .as("GET missing hashed-looking asset status")
                .isEqualTo(404);
        assertSecurityHeaders(missingHashedAsset, NO_CACHE, true);
    }

    @Test
    void securityHeadersCoverApiErrorsStreamsAndDownloads() {
        Response api = probe().get(api("/overview"));
        assertThat(api.status()).as("GET overview status").isEqualTo(200);
        assertSecurityHeaders(api, NO_STORE, true);

        Response error = probe().get(api("/this-route-does-not-exist"));
        assertThat(error.status()).as("unmatched BootUI API route status").isEqualTo(404);
        assertSecurityHeaders(error, NO_STORE, true);

        Response stream = probe().getStreaming(api("/log-tail/stream"));
        assertThat(stream.status()).as("GET log-tail SSE stream status").isEqualTo(200);
        assertThat(stream.contentType()).as("GET log-tail SSE content-type").containsIgnoringCase("text/event-stream");
        assertSecurityHeaders(stream, NO_STORE, true);

        BootUiHttpProbe downloadProbe = probe();
        Response download = downloadProbe.post(api("/threads/download"), stateChangingHeaders(downloadProbe));
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
        Response response = probe().get(api("/overview"));
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

        Response set = probe.request("POST", api("/loggers/" + logger), headers, "{\"level\":\"DEBUG\"}");
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

        Response reset = probe.request("POST", api("/loggers/" + logger), headers, "{\"level\":null}");
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

        Response response = probe().get(api("/" + disabledId));
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
        Response response = probe.request("POST", api("/heap-dump/capture"), headers, "");
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
        Response initial = probe().get(api("/architecture"));
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
        Response scanResponse = probe.request("POST", api("/architecture/scan"), headers, "");
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
    void concurrentArchitectureScansReturnCanonicalBusyConflict() throws Exception {
        assumeTrue(
                isPanelUsableInLiveManifest("architecture"), "architecture panel is not available in this environment");

        int requests = 16;
        BootUiHttpProbe probe = probe();
        Map<String, String> headers = stateChangingHeaders(probe);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        List<Future<Response>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requests; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent architecture scans");
                    }
                    return probe.request("POST", api("/architecture/scan"), headers, "");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get(40, TimeUnit.SECONDS));
            }
            assertThat(responses)
                    .as("concurrent architecture responses must be either the winner or a single-flight conflict")
                    .allSatisfy(response -> assertThat(response.status()).isIn(200, 409));
            // HTTP stacks may admit queued requests after an earlier scan has completed, so multiple
            // sequential winners are valid; the engine unit test pins that overlapping suppliers never run.
            assertThat(responses.stream()
                            .filter(response -> response.status() == 200)
                            .count())
                    .as("at least one architecture scan must complete successfully")
                    .isPositive();

            List<Response> conflicts = responses.stream()
                    .filter(response -> response.status() == 409)
                    .toList();
            assertThat(conflicts).isNotEmpty();
            assertThat(conflicts).allSatisfy(response -> {
                assertThat(response.isJson()).isTrue();
                JsonNode body = response.json();
                assertThat(body.path("error").asText()).isEqualTo("BootUI action already in progress");
                assertThat(body.path("operation").asText()).isEqualTo("architecture.scan");
                assertThat(body.path("activeOperation").asText()).isEqualTo("architecture.scan");
                assertThat(body.path("message").asText())
                        .isEqualTo(
                                "Operation 'architecture.scan' cannot start while 'architecture.scan' is in progress.");
            });

            Response completed = probe.get(api("/architecture"));
            assertThat(completed.status()).isEqualTo(200);
            assertThat(completed.json().path("scan").path("scannedAt").isNumber())
                    .isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
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

        Response response = probe().get(api("/vulnerabilities"));
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
        Response root = probe().get(api("/beans"));
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
        Response limited = probe().get(api("/beans?limit=1"));
        assertThat(limited.status()).as("GET /bootui/api/beans?limit=1 status").isEqualTo(200);
        assertThat(limited.json().path("beans").size())
                .as("GET /bootui/api/beans?limit=1 must return at most 1 bean")
                .isLessThanOrEqualTo(1);

        // Query filter: a value that cannot match any bean name should return an empty page.
        Response noMatch = probe().get(api("/beans?q=conformanceprobexyz123notabean"));
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
        Response root = probe().get(api("/loggers"));
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
        Response limited = probe().get(api("/loggers?limit=1"));
        assertThat(limited.status())
                .as("GET /bootui/api/loggers?limit=1 status")
                .isEqualTo(200);
        assertThat(limited.json().path("loggers").size())
                .as("GET /bootui/api/loggers?limit=1 must return at most 1 logger")
                .isLessThanOrEqualTo(1);

        // Query filter: a query that cannot match any logger name should return an empty page.
        Response noMatch = probe().get(api("/loggers?q=conformanceprobexyz123notalogger"));
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
        Response listResponse = probe().get(api("/traces"));
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
        Response clearResponse = probe.request("DELETE", api("/traces"), headers, null);
        assertThat(clearResponse.status())
                .as("DELETE /bootui/api/traces must return 204 No Content")
                .isEqualTo(204);
        assertThat(clearResponse.body())
                .as("DELETE /bootui/api/traces response body")
                .isEmpty();

        // 3. GET /traces/{unknown} — must return 404 for an unrecognised trace id.
        Response detailResponse = probe().get(api("/traces/conformance-probe-unknown-trace-id-xyz"));
        assertThat(detailResponse.status())
                .as("GET /bootui/api/traces/{unknown} must return 404 for an unrecognised trace id")
                .isEqualTo(404);
    }

    @Test
    void confirmationGatedActionsReturn400WhenConfirmMissing() {
        // Flyway and Liquibase expose mutating actions that require an explicit {"confirm":true} in the
        // request body. Omitting confirm (empty body or {"confirm":false}) must return HTTP 400 with a
        // JSON body containing a "message" field — the canonical confirmation gate enforced by the shared
        // engine FlywayService / LiquibaseService. Both adapters must fire this gate identically.
        // Panels are skipped when unavailable (optional dependency not on the classpath).
        JsonNode panelsArray = probe().get(api("/panels")).json().get("panels");
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
            Response response = probe.request("POST", api(path), headers, "{}");
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

    @Test
    void unavailableActionTargetReturnsCanonicalNotFound() {
        assumeTrue(isPanelUsableInLiveManifest("flyway"), "flyway panel is not available in this environment");

        BootUiHttpProbe probe = probe();
        Response response = probe.request(
                "POST",
                api("/flyway/migrate"),
                stateChangingHeaders(probe),
                "{\"beanName\":\"conformance-missing-flyway\",\"confirm\":true}");

        assertThat(response.status()).as("unknown Flyway target status").isEqualTo(404);
        assertThat(response.isJson()).as("unknown Flyway target content type").isTrue();
        assertThat(response.json().path("status").asText()).isEqualTo("unavailable");
        assertThat(response.json().path("message").asText())
                .isEqualTo("No Flyway bean matched the requested datasource.");
    }

    @Test
    void databaseBackedActivitySwitchRequiresConfirmationWhenCapable() {
        assumeTrue(isPanelUsableInLiveManifest("activity"), "activity panel is not available in this environment");

        JsonNode report = probe().get(api("/activity")).json();
        JsonNode option = report.path("persistenceOption");
        assumeTrue(option.isObject(), "activity persistence option is not exposed in this environment");
        assumeTrue(
                option.path("dataSourceAvailable").asBoolean(false),
                "activity persistence cannot reuse a datasource in this environment");
        assumeTrue(!option.path("active").asBoolean(false), "activity persistence is already active");

        BootUiHttpProbe probe = probe();
        Response response = probe.request(
                "POST", api("/activity/use-existing-datasource"), stateChangingHeaders(probe), "{\"confirm\":false}");

        assertThat(response.status())
                .as("unconfirmed activity persistence switch status")
                .isEqualTo(400);
        assertThat(response.json().path("status").asText()).isEqualTo("blocked");
        assertThat(response.json().path("message").asText())
                .isEqualTo(
                        "Action requires confirm=true because it creates a database table and starts writing to it.");
    }

    @Test
    void serviceMapIsBoundedAndCarriesOnlySafeIdentities() {
        assumeTrue(isPanelUsableInLiveManifest("activity"), "activity panel is not available in this environment");

        Response response = probe().get(api("/activity/service-map"));

        assertThat(response.status()).as("service map status").isEqualTo(200);
        assertThat(response.isJson()).as("service map content type").isTrue();

        JsonNode map = response.json();
        assertThat(map.path("available").isBoolean()).as("$.available").isTrue();
        assertThat(map.path("nodes").isArray()).as("$.nodes").isTrue();
        assertThat(map.path("edges").isArray()).as("$.edges").isTrue();
        assertThat(map.path("sources").isArray()).as("$.sources").isTrue();
        assertThat(map.path("warnings").isArray()).as("$.warnings").isTrue();
        assertThat(map.path("generatedAt").isNumber()).as("$.generatedAt").isTrue();

        JsonNode truncation = map.path("truncation");
        assertThat(truncation.isObject()).as("$.truncation").isTrue();
        int dependencyLimit = truncation.path("dependencyLimit").asInt();
        int interactionLimit = truncation.path("interactionLimit").asInt();
        assertThat(dependencyLimit).as("$.truncation.dependencyLimit").isGreaterThan(0);
        assertThat(interactionLimit).as("$.truncation.interactionLimit").isGreaterThan(0);
        assertThat(truncation.path("truncated").isBoolean())
                .as("$.truncation.truncated")
                .isTrue();

        if (!map.path("available").asBoolean(false)) {
            assertThat(map.path("unavailableReason").isTextual())
                    .as("an unavailable service map explains why")
                    .isTrue();
            return;
        }

        assertThat(map.path("application").path("kind").asText())
                .as("the running application is the centre of the map")
                .isEqualTo("APPLICATION");

        List<String> failures = new ArrayList<>();
        int dependencies = 0;
        for (JsonNode node : map.path("nodes")) {
            String id = node.path("id").asText();
            if ("DEPENDENCY".equals(node.path("kind").asText())) {
                dependencies++;
            }
            if (!node.path("configured").isBoolean() || !node.path("observed").isBoolean()) {
                failures.add(id + " does not report configured/observed separately");
            }
            if (!Set.of("NO_EVIDENCE", "OBSERVED_OK", "RETAINED_FAILURES")
                    .contains(node.path("outcome").asText())) {
                failures.add(id + " has a non-contractual outcome "
                        + node.path("outcome").asText());
            }
            String label = node.path("label").asText("");
            if (label.contains("?") || label.contains("@")) {
                // A safe identity is an origin or a masked target: never a query string, never user info.
                if (label.contains("?") || label.matches(".*://[^/]*[^*]@.*")) {
                    failures.add(id + " label carries an unsafe identity fragment");
                }
            }
        }
        assertThat(dependencies)
                .as("mapped dependencies must stay within the published cap")
                .isLessThanOrEqualTo(dependencyLimit);

        for (JsonNode edge : map.path("edges")) {
            String id = edge.path("id").asText();
            if (!Set.of("INBOUND", "OUTBOUND").contains(edge.path("direction").asText())) {
                failures.add(id + " has a non-contractual direction");
            }
            JsonNode recent = edge.path("recentInteractions");
            if (!recent.isArray()) {
                failures.add(id + " does not carry a recentInteractions array");
            } else if (recent.size() > interactionLimit) {
                failures.add(id + " carries more retained interactions than the published cap");
            }
            for (JsonNode interaction : recent) {
                if (!interaction.path("id").isTextual()
                        || !interaction.path("timestamp").isNumber()) {
                    failures.add(id + " carries an interaction without a stable id and timestamp");
                }
                if (!Set.of("OK", "FAILED").contains(interaction.path("outcome").asText())) {
                    failures.add(id + " carries an interaction with a non-contractual outcome");
                }
                // flowId is nullable-opaque: present only as text or JSON null, and it must never simply
                // echo the interaction id (a canary against a future regression that forgets to hash it).
                JsonNode flowId = interaction.path("flowId");
                if (!flowId.isNull() && !flowId.isTextual()) {
                    failures.add(id + " carries a flowId that is neither null nor text");
                }
                if (flowId.isTextual()
                        && flowId.asText().equals(interaction.path("id").asText())) {
                    failures.add(id + " carries a flowId equal to the interaction id");
                }
            }
        }

        if (!failures.isEmpty()) {
            fail("Service map contract regressed: " + failures);
        }
    }

    @Test
    void devToolsRestartRequiresConfirmationWithoutSchedulingARestart() {
        assumeTrue(runtime() != Runtime.QUARKUS, "DevTools restart is Spring-only");
        assumeTrue(isPanelUsableInLiveManifest("devtools"), "devtools panel is not available in this environment");

        BootUiHttpProbe probe = probe();
        Response response =
                probe.request("POST", api("/devtools/restart"), stateChangingHeaders(probe), "{\"confirm\":false}");

        assertThat(response.status()).as("unconfirmed DevTools restart status").isEqualTo(400);
        assertThat(response.json().path("action").asText()).isEqualTo("restart");
        assertThat(response.json().path("status").asText()).isEqualTo("confirmation_required");
        assertThat(response.json().path("message").asText()).isEqualTo("Restart requires explicit confirmation.");
    }

    @Test
    void configSecretsAreMaskedBeforeSerialization() {
        assumeTrue(isPanelUsableInLiveManifest("config"), "config panel is not available in this environment");

        String key = "bootui.conformance.api-token";
        String rawSecret = "conformance-raw-secret-value";
        Response response = probe().get(api("/config?q=" + key + "&limit=10"));
        assertThat(response.status()).as("masked config query status").isEqualTo(200);
        assertThat(response.body()).as("raw secret must never be serialized").doesNotContain(rawSecret);

        JsonNode matching = null;
        for (JsonNode property : response.json().path("properties")) {
            if (key.equals(property.path("name").asText())) {
                matching = property;
                break;
            }
        }
        assertThat(matching)
                .as("conformance secret property must be observable in the config inventory")
                .isNotNull();
        assertThat(matching.path("masked").asBoolean()).isTrue();
        assertThat(matching.path("value").asText()).isEqualTo("******");
    }

    @Test
    void actionCatalogCoversEveryAvailableActionPanelForThisRuntime() {
        Map<String, JsonNode> livePanels = livePanelsById();
        Set<String> expectedActionPanels = loadExpectedPanels().stream()
                .filter(ExpectedPanel::actionCapable)
                .map(ExpectedPanel::id)
                .filter(id -> {
                    JsonNode panel = livePanels.get(id);
                    return panel != null
                            && panel.path("available").asBoolean(false)
                            && panel.path("enabled").asBoolean(true)
                            && !actionlessPanels().contains(id);
                })
                .collect(java.util.stream.Collectors.toSet());
        Set<String> cataloged = BootUiApiContractCatalog.actions(runtime()).stream()
                .map(ActionContract::panelId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(cataloged)
                .as("available action-capable panels must have a state-changing route in the runtime catalog")
                .containsAll(expectedActionPanels);
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
        probe.get(api("/overview"));
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

    private void assertJsonContract(String panelId, ReadContract contract, JsonNode root, List<String> failures) {
        if (!matchesType(root, contract.rootType())) {
            failures.add(panelId + " -> root expected " + contract.rootType() + " but was " + root.getNodeType());
            return;
        }
        for (Map.Entry<String, JsonType> field : contract.requiredFields().entrySet()) {
            JsonNode value = at(root, field.getKey());
            if (!matchesType(value, field.getValue())) {
                failures.add(panelId + " -> $." + field.getKey() + " expected " + field.getValue() + " but was "
                        + describe(value));
            }
        }
        if (root.isObject() && root.has("available") && root.has("unavailableReason")) {
            boolean available = root.path("available").asBoolean(false);
            JsonNode reason = root.path("unavailableReason");
            if (available && !isNull(reason)) {
                failures.add(panelId + " -> unavailableReason must be null when available=true");
            } else if (!available && !reason.isTextual()) {
                failures.add(panelId + " -> unavailableReason must be a string when available=false");
            }
        }
        if (root.isObject() && root.has("total")) {
            int total = root.path("total").asInt();
            if (total < 0) {
                failures.add(panelId + " -> total must be non-negative");
            }
        }
    }

    private static JsonNode at(JsonNode root, String dottedPath) {
        JsonNode current = root;
        for (String segment : dottedPath.split("\\.")) {
            current = current.path(segment);
        }
        return current;
    }

    private static boolean matchesType(JsonNode node, JsonType type) {
        return switch (type) {
            case STRING -> node.isTextual();
            case BOOLEAN -> node.isBoolean();
            case INTEGER -> node.isIntegralNumber();
            case NUMBER -> node.isNumber();
            case ARRAY -> node.isArray();
            case OBJECT -> node.isObject();
            case NULLABLE_STRING -> isNull(node) || node.isTextual();
            case NULLABLE_OBJECT -> isNull(node) || node.isObject();
        };
    }

    private static String describe(JsonNode node) {
        return node == null || node.isMissingNode()
                ? "missing"
                : node.getNodeType().name();
    }

    private Map<String, JsonNode> livePanelsById() {
        Map<String, JsonNode> panels = new java.util.LinkedHashMap<>();
        JsonNode array = probe().get(api("/panels")).json().path("panels");
        array.forEach(panel -> panels.put(panel.path("id").asText(), panel));
        return panels;
    }

    private static boolean isNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    /** Returns the live manifest entry for the named panel, or {@code null} if not found. */
    private JsonNode panelFromLiveManifest(String id) {
        JsonNode panels = probe().get(api("/panels")).json().get("panels");
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
