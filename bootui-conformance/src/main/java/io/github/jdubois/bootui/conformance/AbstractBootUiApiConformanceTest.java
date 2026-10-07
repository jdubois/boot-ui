package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.ActionContract;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.JsonType;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.ReadContract;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.Runtime;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** System property every consumer module passes on the test JVM command line to exercise argument masking. */
    protected static final String JVM_SECRET_ARGUMENT_KEY = "bootui.conformance.jvm-password";

    protected static final String JVM_SECRET_ARGUMENT_VALUE = "conformance-raw-jvm-secret";

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

    /**
     * The booted app's BootUI span store, so a contract can record a span exactly as an exporter would, with no
     * OpenTelemetry dependency in the app under test.
     */
    protected abstract TelemetryStore telemetryStore();

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
    /**
     * Components the running application declares exception handlers on, by simple name. A stack that
     * returns a well-shaped but empty catalogue would otherwise satisfy every assertion in
     * {@code errorContractEndpointReturnsAStableDeclarationOnlyCatalogue}, so each sample application
     * names its own fixtures here and the test proves discovery actually works on that stack.
     */
    protected Set<String> expectedErrorContractComponents() {
        return Set.of();
    }

    protected Set<String> unsupportedReadContracts() {
        return Set.of();
    }

    /**
     * The host application's own root path, such as a servlet context path, WebFlux base path, or Quarkus
     * root path, with no trailing slash. Tests that send application traffic prefix it, so the request
     * reaches the application rather than falling outside its mount.
     */
    protected String applicationPath() {
        return "";
    }

    /**
     * An application request the route-ranking contract sends and then looks up. It must contain
     * {@code conformance-route-probe}, reach the application without credentials so its exchange is recorded,
     * and end in the path value {@code 4711}, which no route may carry. An unmapped path suits stacks that
     * record a 404; a stack whose security chain rejects unmapped paths before exchange capture names a
     * permitted, templated endpoint instead.
     */
    protected String routeProbePath() {
        return applicationPath() + "/conformance-route-probe/4711";
    }

    /**
     * An application request that fails with an exception BootUI captures into an exception group, or {@code null} when
     * the host application has none; the Live Activity exception-link contract is skipped then.
     */
    protected String exceptionProbePath() {
        return null;
    }

    /** Browser-visible UI mount, including any host application root path. */
    protected String uiPath() {
        return "/bootui";
    }

    /** Browser-visible API mount, including any host application root path. */
    protected String apiPath() {
        return "/bootui/api";
    }

    /** Expected on-disk artifact, independent of the running adapter's path resolution. */
    protected Path dismissalFile() {
        return Path.of("target/api-conformance/boot-ui.yml");
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

        assertThat(report.path("catalogueVersion").asText())
                .as("$.catalogueVersion identifies the curated meter catalogue")
                .isNotBlank();
        assertThat(report.path("groups").isArray()).as("$.groups").isTrue();
        int groupedMeters = 0;
        for (JsonNode group : report.path("groups")) {
            assertThat(group.path("id").asText()).as("$.groups[].id").isNotBlank();
            assertThat(group.path("label").asText()).as("$.groups[].label").isNotBlank();
            assertThat(group.path("contributor").asText())
                    .as("$.groups[].contributor")
                    .isNotBlank();
            assertThat(group.path("meterCount").asInt())
                    .as("$.groups[].meterCount")
                    .isPositive();
            assertThat(group.path("describedMeterCount").asInt())
                    .as("$.groups[].describedMeterCount never exceeds the group size")
                    .isLessThanOrEqualTo(group.path("meterCount").asInt());
            assertThat(group.path("families").isArray())
                    .as("$.groups[].families")
                    .isTrue();
            assertThat(group.path("commonTagKeys").isArray())
                    .as("$.groups[].commonTagKeys")
                    .isTrue();
            groupedMeters += group.path("meterCount").asInt();
        }
        assertThat(groupedMeters)
                .as("provenance groups account for every matched meter, not just the returned page")
                .isEqualTo(report.path("page").path("matched").asInt());

        for (JsonNode meter : report.path("meters")) {
            JsonNode provenance = meter.path("provenance");
            assertThat(provenance.isObject()).as("$.meters[].provenance").isTrue();
            assertThat(provenance.path("groupId").asText())
                    .as("$.meters[].provenance.groupId")
                    .isNotBlank();
            assertThat(provenance.path("groupLabel").asText())
                    .as("$.meters[].provenance.groupLabel")
                    .isNotBlank();
            assertThat(provenance.path("classified").isBoolean())
                    .as("$.meters[].provenance.classified")
                    .isTrue();
            assertThat(provenance.path("explanationSource").asText())
                    .as("$.meters[].provenance.explanationSource")
                    .isIn("NATIVE", "CURATED", "UNKNOWN");
        }

        if (!report.path("groups").isEmpty()) {
            String groupId = report.path("groups").get(0).path("id").asText();
            Response grouped =
                    probe().get(api("/metrics?limit=1&group=" + URLEncoder.encode(groupId, StandardCharsets.UTF_8)));
            assertThat(grouped.status()).as("group-filtered metrics status").isEqualTo(200);
            // Compared inside one response, so a meter registered between the two calls cannot fail the invariant.
            int groupSize = -1;
            for (JsonNode group : grouped.json().path("groups")) {
                if (groupId.equals(group.path("id").asText())) {
                    groupSize = group.path("meterCount").asInt();
                }
            }
            assertThat(groupSize)
                    .as("groups stay facets of the unfiltered set, so the requested group is still described")
                    .isGreaterThanOrEqualTo(0);
            assertThat(grouped.json().path("page").path("matched").asInt())
                    .as("group filter narrows the matched set to that group")
                    .isEqualTo(groupSize);
            for (JsonNode meter : grouped.json().path("meters")) {
                assertThat(meter.path("provenance").path("groupId").asText())
                        .as("group-filtered meters belong to the requested group")
                        .isEqualTo(groupId);
            }
        }

        Response unclassified = probe().get(api("/metrics?limit=1&provenance=unclassified"));
        assertThat(unclassified.status())
                .as("provenance-filtered metrics status")
                .isEqualTo(200);
        for (JsonNode meter : unclassified.json().path("meters")) {
            assertThat(meter.path("provenance").path("classified").asBoolean())
                    .as("unclassified filter never returns classified meters")
                    .isFalse();
        }

        Response invalidGroup = probe().get(api("/metrics?group=not-a-group"));
        assertThat(invalidGroup.status()).as("invalid metric group status").isEqualTo(400);
        assertThat(invalidGroup.json().path("error").asText())
                .as("canonical metric group error")
                .startsWith("Metric group must be one of: application");

        Response invalidProvenance = probe().get(api("/metrics?provenance=maybe"));
        assertThat(invalidProvenance.status())
                .as("invalid metric provenance status")
                .isEqualTo(400);
        assertThat(invalidProvenance.json().path("error").asText())
                .as("canonical metric provenance error")
                .isEqualTo("Metric provenance must be one of: classified, unclassified");

        Response invalidExplanation = probe().get(api("/metrics?explanation=guessed"));
        assertThat(invalidExplanation.status())
                .as("invalid metric explanation status")
                .isEqualTo(400);
        assertThat(invalidExplanation.json().path("error").asText())
                .as("canonical metric explanation error")
                .isEqualTo("Metric explanation source must be one of: CURATED, NATIVE, UNKNOWN");

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
            assertThat(detail.json().path("provenance").path("groupId").asText())
                    .as("$.provenance.groupId")
                    .isNotBlank();
            assertThat(detail.json()
                            .path("provenance")
                            .path("explanationSource")
                            .asText())
                    .as("$.provenance.explanationSource")
                    .isIn("NATIVE", "CURATED", "UNKNOWN");
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
    void logTailMasksSecretAssignmentsInTheSnapshotAndTheStream() {
        LogTailExposureContract snapshot = new LogTailExposureContract().log();
        Response recent = probe().get(api("/log-tail/recent"));
        assertThat(recent.status()).as("GET log-tail recent status").isEqualTo(200);
        snapshot.assertMaskedIn(recent.json(), "GET /log-tail/recent");

        LogTailExposureContract backlog = new LogTailExposureContract().log();
        String replayed = readLogStream(() -> {}, backlog);
        backlog.assertMaskedInStream(replayed, "the log-tail SSE backlog");

        LogTailExposureContract live = new LogTailExposureContract();
        String streamed = readLogStream(live::log, live);
        live.assertMaskedInStream(streamed, "the log-tail SSE stream");
    }

    @Test
    void logTailFollowsALiveExposureChangeWithoutARestart() {
        LogTailExposureContract retained = new LogTailExposureContract().log();

        LogTailExposureContract.withExposure("METADATA_ONLY", null, () -> {
            retained.assertOmittedIn(
                    probe().get(api("/log-tail/recent")).json(), "GET /log-tail/recent (METADATA_ONLY)");
            retained.assertOmittedInStream(
                    readLogStream(() -> {}, retained), "the log-tail SSE backlog (METADATA_ONLY)");
        });
        LogTailExposureContract.withExposure(
                "FULL",
                null,
                () -> retained.assertVerbatimIn(
                        probe().get(api("/log-tail/recent")).json(), "GET /log-tail/recent (FULL)"));
        LogTailExposureContract.withExposure(
                "MASKED",
                "false",
                () -> retained.assertVerbatimIn(
                        probe().get(api("/log-tail/recent")).json(), "GET /log-tail/recent (mask-secrets=false)"));
        retained.assertMaskedIn(probe().get(api("/log-tail/recent")).json(), "GET /log-tail/recent (restored)");

        // An open stream picks up the change for the next line it sends.
        LogTailExposureContract live = new LogTailExposureContract();
        LogTailExposureContract.withExposure(null, null, () -> {
            String streamed = readLogStream(
                    () -> {
                        LogTailExposureContract.setExposure("METADATA_ONLY");
                        live.log();
                    },
                    live);
            live.assertOmittedInStream(streamed, "the open log-tail SSE stream after a change to METADATA_ONLY");
        });
    }

    private String readLogStream(Runnable afterOpen, LogTailExposureContract contract) {
        return probe().readStreamUntil(api("/log-tail/stream"), afterOpen, contract.logger, Duration.ofSeconds(10));
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
    void cacheTiersAndStatisticsShareOneShapeOnEveryPlatform() {
        // The Cache panel's tier and counter structure is a *nested* contract the flat catalog cannot pin,
        // and it is the surface the shared Vue panel binds to, so every adapter has to emit the same shape:
        // Spring MVC, Spring WebFlux and Quarkus all build it in the engine CacheService from their own
        // CacheProvider. Values are platform-specific (Quarkus has no provider statistics at all), so this
        // asserts shape and the honesty rules, never a reading.
        Response response = probe().get(api("/cache"));
        if (response.status() == 403 || response.status() == 404) {
            return; // the panel is disabled or unavailable on this platform; the manifest test covers that
        }
        assertThat(response.status()).as("GET /bootui/api/cache status").isEqualTo(200);

        JsonNode report = response.json();
        int tiersSeen = 0;
        for (JsonNode manager : report.path("managers")) {
            for (JsonNode cache : manager.path("caches")) {
                assertCacheStatisticsShape(
                        cache.path("statistics"), "cache '" + cache.path("name").asText() + "'");
                assertThat(cache.path("opaque").isBoolean())
                        .as("$.managers[].caches[].opaque must be a boolean")
                        .isTrue();
                if (cache.path("opaque").asBoolean(false)) {
                    assertThat(cache.path("opaqueReason").isTextual())
                            .as("an opaque cache must say why its tiers are unknown")
                            .isTrue();
                    assertThat(cache.path("tiers"))
                            .as("an opaque cache reports no tier")
                            .isEmpty();
                }
                for (JsonNode tier : cache.path("tiers")) {
                    tiersSeen++;
                    assertThat(tier.path("id").isTextual())
                            .as("$..tiers[].id must be a string")
                            .isTrue();
                    assertThat(tier.path("name").isTextual())
                            .as("$..tiers[].name must be a string")
                            .isTrue();
                    assertThat(tier.path("level").isInt())
                            .as("$..tiers[].level must be an int")
                            .isTrue();
                    assertThat(tier.path("locality").asText(""))
                            .as("$..tiers[].locality must be a canonical locality")
                            .isIn("LOCAL", "DISTRIBUTED", "UNKNOWN");
                    assertThat(tier.path("maximumSize").isNull()
                                    || tier.path("maximumSize").isNumber())
                            .as("$..tiers[].maximumSize is a number or null, never a guess")
                            .isTrue();
                    assertCacheStatisticsShape(
                            tier.path("statistics"),
                            "a tier of '" + cache.path("name").asText() + "'");
                }
            }
        }
        assertThat(report.path("tierCount").asInt(-1))
                .as("$.tierCount must count the reported tiers")
                .isEqualTo(tiersSeen);
    }

    /** Pins the honesty rules of one statistics object: unavailable means a reason, and a ratio needs requests. */
    private void assertCacheStatisticsShape(JsonNode statistics, String where) {
        assertThat(statistics.path("available").isBoolean())
                .as("statistics.available of %s must be a boolean", where)
                .isTrue();
        if (!statistics.path("available").asBoolean(false)) {
            assertThat(statistics.path("unavailableReason").asText(""))
                    .as("unavailable statistics of %s must carry a reason", where)
                    .isNotBlank();
            assertThat(statistics.path("hitRatio").isNull())
                    .as("unavailable statistics of %s must not carry a ratio", where)
                    .isTrue();
        }
        if (statistics.path("hitRatio").isNull()) {
            assertThat(statistics.path("ratioUnavailableReason").asText(""))
                    .as("a missing ratio of %s must say why", where)
                    .isNotBlank();
        } else if (statistics.path("hitRatio").isNumber()) {
            assertThat(statistics.path("hitRatio").asDouble())
                    .as("a reported ratio of %s must be a fraction", where)
                    .isBetween(0.0d, 1.0d);
        }
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
        assertAdvisorEvidence(initial.json());
        assertAdvisorEvidence(scanned);
        Response cached = probe.get(api("/architecture"));
        assertThat(cached.json().path("evidence")).isEqualTo(scanned.path("evidence"));
        assertThat(scanned.path("scan").path("status").isTextual())
                .as("POST /bootui/api/architecture/scan scan.status must be a string")
                .isTrue();
        assertThat(scanned.path("scan").path("scannedAt").isNumber())
                .as("POST /bootui/api/architecture/scan scan.scannedAt must be a number after a real scan")
                .isTrue();
    }

    @Test
    void advisorViolationPagesStayBoundToTheCountedSnapshot() {
        for (String panel :
                List.of("architecture", "hibernate", "spring", "rest-api", "memory", "security", "database-advisor")) {
            if (!isPanelUsableInLiveManifest(panel)) continue;
            BootUiHttpProbe probe = probe();
            Response scanned = probe.request("POST", api("/" + panel + "/scan"), stateChangingHeaders(probe), "");
            assertThat(scanned.status()).as(panel + " scan").isEqualTo(200);
            JsonNode report = scanned.json();
            JsonNode metadata = report.path("violationDetails");
            String scanId = metadata.path("scanId").asText();
            assertThat(scanId).as(panel + " snapshot identifier").isNotBlank();
            assertThat(metadata.path("retentionLimit").asInt()).isPositive();
            assertThat(metadata.path("truncated").isBoolean()).isTrue();
            int counted = 0;
            for (JsonNode result : report.path("results"))
                counted += result.path("violationCount").asInt();
            assertThat(metadata.path("total").asInt())
                    .as(panel + " original count")
                    .isEqualTo(counted);
            assertThat(metadata.path("retained").asInt()).isLessThanOrEqualTo(counted);
            String query = "?scanId=" + URLEncoder.encode(scanId, StandardCharsets.UTF_8);
            String missing = api("/" + panel + "/rules/NO-SUCH-RULE/violations");
            assertThat(probe.get(missing + query).status()).isEqualTo(404);
            assertThat(probe.get(missing + "?scanId=stale-snapshot").status()).isEqualTo(409);
            assertThat(probe.get(missing + query + "&offset=-1").status()).isEqualTo(400);
            assertThat(probe.get(missing + query + "&limit=0").status()).isEqualTo(400);
            assertThat(probe.get(missing + query + "&offset=1.5").status()).isEqualTo(400);
            if (!report.path("results").isEmpty()) {
                JsonNode rule = report.path("results").get(0);
                String id = rule.path("id").asText();
                String route =
                        api("/" + panel + "/rules/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/violations");
                Response response = probe.get(route + query + "&offset=0&limit=1");
                assertThat(response.status()).as(panel + " detail page").isEqualTo(200);
                assertThat(response.isJson()).isTrue();
                JsonNode page = response.json();
                assertThat(page.path("scanId").asText()).isEqualTo(scanId);
                assertThat(page.path("ruleId").asText()).isEqualTo(id);
                assertThat(page.path("violationCount").asInt())
                        .isEqualTo(rule.path("violationCount").asInt());
                assertThat(page.path("violations").isArray()).isTrue();
                assertThat(page.path("violations").size()).isLessThanOrEqualTo(1);
                assertThat(page.path("page").path("returned").asInt())
                        .isEqualTo(page.path("violations").size());
                assertThat(page.path("page").path("total").asInt())
                        .isEqualTo(page.path("retainedCount").asInt());
                if (!page.path("violations").isEmpty() && !"QS-AUTHZ-004".equals(id)) {
                    assertThat(page.path("violations").get(0))
                            .isEqualTo(rule.path("sampleViolations").get(0));
                }
                int end = page.path("retainedCount").asInt();
                JsonNode last =
                        probe.get(route + query + "&offset=" + end + "&limit=1").json();
                assertThat(last.path("violations")).isEmpty();
                assertThat(last.path("page").path("hasMore").asBoolean()).isFalse();
            }
            assertThat(probe.get(api("/" + panel)).json().path("violationDetails"))
                    .isEqualTo(metadata);
        }
    }

    /**
     * Structured violation locations: every location list is either empty or aligned index-for-index with its
     * text list, on report samples and on detail pages alike, and only the Architecture, REST API, and Hibernate
     * advisors carry any. Locations are bounded and precise about what they know.
     */
    @Test
    void advisorViolationLocationsAreAlignedBoundedAndOnlyOnLocatedAdvisors() {
        Set<String> located = Set.of("architecture", "rest-api", "hibernate");
        for (String panel :
                List.of("architecture", "hibernate", "spring", "rest-api", "memory", "security", "database-advisor")) {
            if (!isPanelUsableInLiveManifest(panel)) continue;
            BootUiHttpProbe probe = probe();
            Response scanned = probe.request("POST", api("/" + panel + "/scan"), stateChangingHeaders(probe), "");
            assertThat(scanned.status()).as(panel + " scan").isEqualTo(200);
            JsonNode report = scanned.json();
            JsonNode notes = report.path("violationDetails").path("locationNotes");
            assertThat(notes.isArray())
                    .as(panel + " violationDetails.locationNotes")
                    .isTrue();
            String query = "?scanId="
                    + URLEncoder.encode(
                            report.path("violationDetails").path("scanId").asText(), StandardCharsets.UTF_8);
            if (located.contains(panel) && !panel.equals("hibernate") && expectsResolvedSourcePaths()) {
                assertThat(report.path("results").findValues("sampleLocations").stream()
                                .flatMap(list -> java.util.stream.StreamSupport.stream(list.spliterator(), false))
                                .filter(location -> !location.isNull()
                                        && !location.path("sourcePath").isNull())
                                .map(location -> java.nio.file.Path.of(
                                        location.path("sourcePath").asText()))
                                .anyMatch(path -> java.nio.file.Files.isRegularFile(path)
                                        && (path.toString().contains("src" + java.io.File.separator + "main")
                                                || path.toString().contains("src" + java.io.File.separator + "test"))))
                        .as(panel + ": a location resolves to the application's own source file")
                        .isTrue();
            }
            for (JsonNode rule : report.path("results")) {
                JsonNode samples = rule.path("sampleViolations");
                JsonNode sampleLocations = rule.path("sampleLocations");
                if (located.contains(panel)) {
                    assertThat(sampleLocations.isArray())
                            .as(panel + " sampleLocations")
                            .isTrue();
                    assertLocations(panel + " " + rule.path("id").asText() + " samples", samples, sampleLocations);
                } else {
                    assertThat(sampleLocations.isMissingNode())
                            .as(panel + " results carry no sampleLocations")
                            .isTrue();
                }
                String route = api("/" + panel + "/rules/"
                        + URLEncoder.encode(rule.path("id").asText(), StandardCharsets.UTF_8) + "/violations");
                Response detail = probe.get(route + query + "&offset=0&limit=5");
                if (detail.status() != 200) continue;
                JsonNode page = detail.json();
                assertThat(page.path("locations").isArray())
                        .as(panel + " detail locations")
                        .isTrue();
                assertLocations(panel + " detail page", page.path("violations"), page.path("locations"));
                if (!located.contains(panel)) {
                    assertThat(page.path("locations"))
                            .as(panel + " detail page carries no locations")
                            .isEmpty();
                } else if (!page.path("locations").isEmpty() && sampleLocations.size() > 0) {
                    assertThat(page.path("locations").get(0)).isEqualTo(sampleLocations.get(0));
                }
            }
        }
    }

    /**
     * Whether this runner's application classes are compiled into a local Maven or Gradle output directory, so
     * the explicit Architecture and REST API scans must each resolve at least one location to a source file under
     * the module's {@code src/main} or {@code src/test} tree.
     */
    protected boolean expectsResolvedSourcePaths() {
        return false;
    }

    private static void assertLocations(String subject, JsonNode texts, JsonNode locations) {
        if (locations.isEmpty()) return;
        assertThat(locations.size()).as(subject + " locations align with texts").isEqualTo(texts.size());
        boolean any = false;
        for (JsonNode location : locations) {
            if (location.isNull()) continue;
            any = true;
            assertThat(location.path("className").asText())
                    .as(subject + " className")
                    .isNotBlank();
            assertThat(location.path("className").asText().length()).isLessThanOrEqualTo(512);
            assertThat(location.path("kind").asText()).isIn("CLASS", "METHOD", "CONSTRUCTOR", "FIELD");
            assertThat(location.path("precision").asText()).isIn("LINE", "MEMBER", "CLASS");
            JsonNode line = location.path("line");
            assertThat(line.isNull() || line.asInt() > 0).as(subject + " line").isTrue();
            assertThat(location.path("precision").asText().equals("LINE")).isEqualTo(!line.isNull());
            JsonNode path = location.path("sourcePath");
            assertThat(path.isNull() || path.asText().length() <= 1024)
                    .as(subject + " sourcePath")
                    .isTrue();
        }
        assertThat(any)
                .as(subject + " a non-empty location list has a location")
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
            assertAdvisorEvidence(completed.json());
            assertThat(completed.json().path("scan").path("scannedAt").isNumber())
                    .isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void pentestingDismissalsUpdateCachedReportsWithoutChangingScanEvidence() {
        assumeTrue(isPanelUsableInLiveManifest("pentesting"));
        BootUiHttpProbe probe = probe();
        PentestingDismissalContract.verify(
                probe,
                api(""),
                dismissalFile(),
                () -> PentestingDismissalContract.response(
                        probe.post(api("/pentesting/scan"), stateChangingHeaders(probe))),
                () -> PentestingDismissalContract.response(probe.get(api("/pentesting"))));
    }

    @Test
    void scoredAdvisorReportsExposeConservativeEvidenceWithoutTriggeringScans() {
        for (String panel : List.of(
                "architecture",
                "memory",
                "rest-api",
                "spring",
                "database-advisor",
                "hibernate",
                "security",
                "pentesting",
                "vulnerabilities")) {
            if (!isPanelUsableInLiveManifest(panel)) {
                continue;
            }
            Response response = probe().get(api("/" + panel));
            assertThat(response.status()).as(panel).isEqualTo(200);
            assertAdvisorEvidence(response.json());
            if ("hibernate".equals(panel)) {
                assertThat(response.json().path("diagnostics").isArray())
                        .as("hibernate diagnostics")
                        .isTrue();
                for (JsonNode diagnostic : response.json().path("diagnostics")) {
                    for (String member : List.of("source", "unit", "level", "message")) {
                        assertThat(diagnostic.path(member).isTextual())
                                .as("hibernate diagnostic " + member)
                                .isTrue();
                    }
                }
                for (JsonNode result : response.json().path("results")) {
                    JsonNode coverageNote = result.path("coverageNote");
                    assertThat(coverageNote.isMissingNode() || coverageNote.isNull() || coverageNote.isTextual())
                            .as("hibernate coverageNote")
                            .isTrue();
                }
            }
            if ("vulnerabilities".equals(panel)) {
                for (JsonNode dependency : response.json().path("dependencies")) {
                    JsonNode assessment = dependency.path("assessment");
                    assertThat(assessment.path("queryComplete").isBoolean()).isTrue();
                    assertThat(assessment.path("detailAssessmentComplete").isBoolean())
                            .isTrue();
                    if (assessment.path("detailAssessmentComplete").asBoolean()) {
                        assertThat(assessment.path("queryComplete").asBoolean()).isTrue();
                    }
                }
            }
        }
    }

    private static void assertAdvisorEvidence(JsonNode report) {
        JsonNode evidence = report.path("evidence");
        assertThat(evidence.isObject()).isTrue();
        assertThat(evidence.size()).isEqualTo(3);
        assertThat(evidence.path("usable").isBoolean()).isTrue();
        assertThat(evidence.path("coverageComplete").isBoolean()).isTrue();
        assertThat(evidence.path("limitations").isArray()).isTrue();
        assertThat(evidence.path("limitations").size()).isLessThanOrEqualTo(20);
        for (JsonNode limitation : evidence.path("limitations")) {
            assertThat(limitation.isTextual()).isTrue();
            assertThat(limitation.textValue().length()).isLessThanOrEqualTo(240);
        }
    }

    @Test
    void databaseAdvisorPreservesPartialEvidenceAcrossScanAndCachedRead() {
        assumeTrue(isPanelUsableInLiveManifest("database-advisor"));
        BootUiHttpProbe probe = probe();
        Response response = probe.request("POST", api("/database-advisor/scan"), stateChangingHeaders(probe), "");
        assertThat(response.status()).isEqualTo(200);
        JsonNode report = response.json();
        assertAdvisorEvidence(report);
        if (report.path("tablesAnalyzed").asInt() > 0 || !report.path("results").isEmpty()) {
            assertThat(report.path("evidence").path("usable").asBoolean()).isTrue();
        }
        if ("PARTIAL".equals(report.path("scan").path("status").asText())) {
            assertThat(report.path("evidence").path("coverageComplete").asBoolean())
                    .isFalse();
            assertThat(report.path("evidence").path("limitations")).isNotEmpty();
        }
        assertThat(probe.get(api("/database-advisor")).json().path("evidence")).isEqualTo(report.path("evidence"));
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
        assertAdvisorEvidence(report);
        if (List.of("NOT_SCANNED", "DISABLED")
                .contains(report.path("scan").path("status").asText())) {
            assertThat(report.path("evidence").path("usable").asBoolean()).isFalse();
            assertThat(report.path("evidence").path("coverageComplete").asBoolean())
                    .isFalse();
        }
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
        assertThat(report.path("scan").path("packagesSkipped").isInt())
                .as("$.scan.packagesSkipped must be an integer")
                .isTrue();
        // Coverage tells the caller how much of the application the inventory actually accounts for, so a
        // clean report can never be mistaken for a full-coverage scan. Every adapter must report it.
        JsonNode coverage = report.path("coverage");
        assertThat(coverage.isObject()).as("$.coverage must be an object").isTrue();
        assertThat(coverage.path("status").asText())
                .as("$.coverage.status must be one of the canonical states")
                .isIn("COMPLETE", "INCOMPLETE", "UNAVAILABLE");
        assertThat(coverage.path("archivesFound").isInt())
                .as("$.coverage.archivesFound must be an integer")
                .isTrue();
        assertThat(coverage.path("archivesIdentified").isInt())
                .as("$.coverage.archivesIdentified must be an integer")
                .isTrue();
        assertThat(coverage.path("archivesUnidentified").isInt())
                .as("$.coverage.archivesUnidentified must be an integer")
                .isTrue();
        assertThat(coverage.path("unidentifiedArchives").isArray())
                .as("$.coverage.unidentifiedArchives must be an array")
                .isTrue();
        assertThat(coverage.path("unidentifiedArchivesTruncated").isBoolean())
                .as("$.coverage.unidentifiedArchivesTruncated must be a boolean")
                .isTrue();
        assertThat(coverage.path("archivesFirstParty").isInt())
                .as("$.coverage.archivesFirstParty must be an integer")
                .isTrue();
        assertThat(coverage.path("firstPartyArchives").isArray())
                .as("$.coverage.firstPartyArchives must be an array")
                .isTrue();
        assertThat(coverage.path("firstPartyArchivesTruncated").isBoolean())
                .as("$.coverage.firstPartyArchivesTruncated must be a boolean")
                .isTrue();
        assertThat(coverage.path("archivesIdentified").asInt()
                        + coverage.path("archivesUnidentified").asInt()
                        + coverage.path("archivesFirstParty").asInt())
                .as("$.coverage archive counts must add up to archivesFound")
                .isEqualTo(coverage.path("archivesFound").asInt());
        // Runtime reach (PLAN-v2 §5.15): every adapter says whether rows carry it and, without it, why; a row never
        // reads NOT_LOADED without the agent's evidence.
        JsonNode reach = report.path("runtimeReach");
        assertThat(reach.isObject()).as("$.runtimeReach must be an object").isTrue();
        assertThat(reach.path("available").isBoolean())
                .as("$.runtimeReach.available must be a boolean")
                .isTrue();
        for (JsonNode dependency : report.path("dependencies")) {
            if (reach.path("available").asBoolean()) {
                assertThat(dependency.path("runtimeReach").path("status").asText())
                        .as("$.dependencies[].runtimeReach.status")
                        .isIn("NOT_LOADED", "LOADED", "AFFECTED_CLASS_LOADED", "UNKNOWN");
            } else {
                assertThat(dependency.path("runtimeReach").isNull())
                        .as("$.dependencies[].runtimeReach without reach")
                        .isTrue();
            }
        }
        if (!reach.path("available").asBoolean()) {
            assertThat(reach.path("unavailableReason").asText())
                    .as("$.runtimeReach.unavailableReason")
                    .isNotBlank();
        }
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
    void errorContractEndpointReturnsAStableDeclarationOnlyCatalogue() {
        // The declared error contract is assembled by the engine's ErrorContractService from raw facts the
        // Spring and Quarkus adapters read from bean metadata and the build-time Jandex index respectively.
        // The engine owns classification, precedence and paging so all three stacks return one shape; this
        // test pins that shape, the availability contract, and the fact that filtering and paging are
        // honoured identically. It deliberately does not assert a specific handler: the sample applications
        // differ, and the panel must never claim more than the declarations support.
        assumeTrue(isPanelUsableInLiveManifest("rest-api"), "rest-api panel is not available in this environment");

        Response root = probe().get(api("/rest-api/error-contract"));
        assertThat(root.status())
                .as("GET /bootui/api/rest-api/error-contract status")
                .isEqualTo(200);
        assertThat(root.isJson())
                .as("GET /bootui/api/rest-api/error-contract content-type")
                .isTrue();

        JsonNode report = root.json();
        assertThat(report.path("available").isBoolean())
                .as("$.available must be a boolean (honest availability)")
                .isTrue();
        assertThat(report.path("entries").isArray())
                .as("$.entries must be an array")
                .isTrue();
        assertThat(report.path("page").isObject())
                .as("$.page must be an object (pagination metadata)")
                .isTrue();
        assertThat(report.path("truncated").isBoolean())
                .as("$.truncated must be a boolean (bounded output)")
                .isTrue();

        if (!report.path("available").asBoolean()) {
            assertThat(report.path("unavailableReason").asText(""))
                    .as("an unavailable error contract must explain itself rather than look empty")
                    .isNotBlank();
            assertThat(report.path("entries").isEmpty())
                    .as("an unavailable error contract must not report entries")
                    .isTrue();
            return;
        }

        for (JsonNode entry : report.path("entries")) {
            assertThat(entry.path("id").asText(""))
                    .as("every entry needs a stable id the UI can key on")
                    .isNotBlank();
            assertThat(entry.path("exceptionType").asText(""))
                    .as("every entry names the exception type it declares it handles")
                    .isNotBlank();
            assertThat(entry.path("component").asText(""))
                    .as("every entry names its declaring component")
                    .isNotBlank();
            assertThat(entry.path("source").asText(""))
                    .as("every entry states where the declaration came from")
                    .isNotBlank();
            assertThat(entry.path("scope").asText(""))
                    .as("every entry states its scope, using UNKNOWN rather than guessing")
                    .isIn("GLOBAL", "SCOPED", "CONTROLLER", "UNKNOWN");
            assertThat(entry.path("statusSource").asText(""))
                    .as("a status is either declared, built at runtime, or honestly unresolved")
                    .isIn("ANNOTATION", "DYNAMIC", "UNRESOLVED");
            assertThat(entry.path("bodyCategory").asText(""))
                    .as("a body category is classified by the engine, identically on every stack")
                    .isIn("PROBLEM_DETAIL", "CUSTOM_OBJECT", "STRING", "EMPTY", "DYNAMIC", "UNRESOLVED");
            assertThat(entry.path("precedenceSource").asText(""))
                    .as("precedence is either declared, defaulted, or honestly unresolved")
                    .isIn("DECLARED", "DEFAULT", "UNRESOLVED");
            assertThat(entry.path("produces").isArray())
                    .as("$.entries[].produces must be an array")
                    .isTrue();
        }

        Set<String> expectedComponents = expectedErrorContractComponents();
        if (!expectedComponents.isEmpty()) {
            List<String> discovered = new ArrayList<>();
            for (JsonNode entry : report.path("entries")) {
                discovered.add(entry.path("componentSimpleName").asText(""));
            }
            assertThat(discovered)
                    .as("this stack must actually discover the application's declared exception handlers,"
                            + " not merely return a well-shaped empty catalogue")
                    .containsAll(expectedComponents);
        }

        Response limited = probe().get(api("/rest-api/error-contract?limit=1"));
        assertThat(limited.status())
                .as("GET /bootui/api/rest-api/error-contract?limit=1 status")
                .isEqualTo(200);
        assertThat(limited.json().path("entries").size())
                .as("limit=1 must return at most one entry")
                .isLessThanOrEqualTo(1);

        Response noMatch = probe().get(api("/rest-api/error-contract?q=conformanceprobexyz123nohandler"));
        assertThat(noMatch.status())
                .as("GET /bootui/api/rest-api/error-contract?q=<nonexistent> status")
                .isEqualTo(200);
        assertThat(noMatch.json().path("page").path("matched").asInt())
                .as("a query that matches nothing must report zero matches")
                .isZero();
        assertThat(noMatch.json().path("entries").isEmpty())
                .as("a query that matches nothing must return an empty page")
                .isTrue();
        assertThat(noMatch.json().path("total").asInt())
                .as("the unfiltered total must survive filtering so the UI can say 'x of y'")
                .isEqualTo(report.path("total").asInt());
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
    void runtimeJournalReportsOneShapeAndClearingItNeedsConfirmation() {
        assumeTrue(isPanelUsableInLiveManifest("activity"), "activity panel is not available in this environment");
        ReadContract contract = BootUiApiContractCatalog.runtimeJournal();
        BootUiHttpProbe probe = probe();

        Response status = probe.get(api(contract.relativePath()));

        assertThat(status.status()).as("GET %s status", contract.relativePath()).isEqualTo(200);
        List<String> failures = new ArrayList<>();
        assertJsonContract("runtime journal status", contract, status.json(), failures);
        assertThat(failures).as("runtime journal contract").isEmpty();
        assertThat(status.json().path("enabled").asBoolean(false)).isTrue();
        assertThat(status.json().path("runId").asText()).matches("[0-9a-f]{8}");

        Response unconfirmed = probe.request("POST", api("/activity/journal/clear"), stateChangingHeaders(probe), "{}");
        assertThat(unconfirmed.status())
                .as("Clear recording without confirm=true")
                .isEqualTo(400);
        assertThat(unconfirmed.json().path("status").asText()).isEqualTo("blocked");
    }

    @Test
    void liveActivityServesTheFeedRenderedFromTheJournalOnRequest() {
        assumeTrue(isPanelUsableInLiveManifest("activity"), "activity panel is not available in this environment");
        ReadContract contract = BootUiApiContractCatalog.reads().stream()
                .filter(read -> read.relativePath().equals("/activity"))
                .findFirst()
                .orElseThrow();
        BootUiHttpProbe probe = probe();

        Response journal = probe.get(api("/activity?source=journal"));

        assertThat(journal.status()).as("GET /activity?source=journal status").isEqualTo(200);
        List<String> failures = new ArrayList<>();
        assertJsonContract("live activity from the journal", contract, journal.json(), failures);
        assertThat(failures).as("live activity contract, from the journal").isEmpty();
        assertThat(journal.json().path("available").asBoolean(false)).isTrue();
        assertThat(journal.json().path("sources").toString()).contains("Runtime journal");

        Response unknown = probe.get(api("/activity?source=elsewhere"));
        assertThat(unknown.status()).as("an unknown feed source is rejected").isEqualTo(400);

        Response buffers = probe.get(api("/activity?source=buffers&noRequest=true"));
        assertThat(buffers.status()).isEqualTo(200);
        assertThat(buffers.json().path("warnings").toString())
                .as("a journal-only filter on the buffers' feed is reported, never silently dropped")
                .contains("source=journal");
    }

    @Test
    void liveActivityExceptionEntriesNameTheirExceptionGroup() throws InterruptedException {
        assumeTrue(isPanelUsableInLiveManifest("activity"), "activity panel is not available in this environment");
        assumeTrue(isPanelUsableInLiveManifest("exceptions"), "exceptions panel is not available in this environment");
        String failing = exceptionProbePath();
        assumeTrue(failing != null, "this host application has no failing request to send");
        BootUiHttpProbe probe = probe();
        probe.get(failing);
        for (String feed : List.of("/activity?source=journal&type=EXCEPTION&limit=50", "/activity?source=buffers")) {
            String groupId = null;
            for (int attempt = 0; attempt < 30 && groupId == null; attempt++) {
                for (JsonNode entry : probe.get(api(feed)).json().path("entries")) {
                    if ("EXCEPTION".equals(entry.path("type").asText())) {
                        assertThat(entry.has("exceptionGroupId"))
                                .as("%s: an EXCEPTION entry carries exceptionGroupId", feed)
                                .isTrue();
                        if (!entry.path("exceptionGroupId").asText("").isBlank()) {
                            groupId = entry.path("exceptionGroupId").asText();
                            break;
                        }
                    }
                }
                if (groupId == null) {
                    Thread.sleep(100);
                }
            }
            assertThat(groupId)
                    .as("%s lists the failing request's exception with its group id", feed)
                    .isNotNull();
            Response detail = probe.get(api("/exceptions/" + groupId));
            assertThat(detail.status())
                    .as("%s: GET /exceptions/{exceptionGroupId} resolves the entry's group", feed)
                    .isEqualTo(200);
        }
    }

    @Test
    void requestJournalProfileKeepsOneShapeForAnUnknownAndARecordedRequest() throws InterruptedException {
        assumeTrue(isPanelUsableInLiveManifest("activity"), "activity panel is not available in this environment");
        ReadContract contract = BootUiApiContractCatalog.requestJournalProfile();
        BootUiHttpProbe probe = probe();

        Response unknown = probe.get(api(contract.relativePath()));
        assertThat(unknown.status())
                .as("GET %s status", contract.relativePath())
                .isEqualTo(200);
        List<String> failures = new ArrayList<>();
        assertJsonContract("request journal profile, unknown", contract, unknown.json(), failures);
        assertThat(unknown.json().path("available").asBoolean(true)).isFalse();

        probe.get(routeProbePath());
        String requestId = null;
        for (int attempt = 0; attempt < 20 && requestId == null; attempt++) {
            for (JsonNode entry : probe.get(api("/activity?source=journal&type=REQUEST&limit=50"))
                    .json()
                    .path("entries")) {
                if (entry.path("path").asText("").contains("conformance-route-probe")) {
                    requestId = entry.path("id").asText();
                    break;
                }
            }
            if (requestId == null) {
                Thread.sleep(100);
            }
        }
        assertThat(requestId)
                .as("the journal's feed lists the route probe request")
                .isNotNull();
        Response recorded = probe.get(api("/activity/request/" + requestId + "/journal"));
        assertThat(recorded.status()).isEqualTo(200);
        assertJsonContract("request journal profile, recorded", contract, recorded.json(), failures);
        assertThat(failures).as("request journal profile contract").isEmpty();
        assertThat(recorded.json().path("available").asBoolean(false)).isTrue();
        assertThat(recorded.json().path("requestId").asText()).isEqualTo(requestId);
        assertThat(recorded.json().path("route").asText()).isNotBlank();
    }

    @Test
    void changeImpactNeverGuessesAnUnknownSymbol() {
        assumeTrue(
                isPanelUsableInLiveManifest("runtime-insights"),
                "runtime-insights panel is not available in this environment");
        ReadContract contract = BootUiApiContractCatalog.changeImpact();
        List<String> failures = new ArrayList<>();

        Response unknown = probe().get(api(contract.relativePath()));

        assertThat(unknown.status())
                .as("GET %s status", contract.relativePath())
                .isEqualTo(200);
        assertJsonContract("change impact, unknown symbol", contract, unknown.json(), failures);
        assertThat(failures).as("change impact contract").isEmpty();
        assertThat(unknown.json().path("status").asText()).isIn("NOT_FOUND", "UNAVAILABLE");
        assertThat(unknown.json().path("reason").asText()).isNotBlank();
        assertThat(unknown.json().path("observed").size()).isZero();

        // A method is a symbol too (M5-7a): an unknown one answers the same shape on every stack, never guessed.
        Response method = probe().get(api("/runtime-insights/impact?symbol="
                + URLEncoder.encode("ConformanceUnknown#method(String)", StandardCharsets.UTF_8)));
        assertThat(method.status()).isEqualTo(200);
        assertJsonContract("change impact, unknown method", contract, method.json(), failures);
        assertThat(failures).as("change impact contract, method").isEmpty();
        assertThat(method.json().path("status").asText()).isIn("NOT_FOUND", "UNAVAILABLE");
        assertThat(method.json().path("reason").asText()).isNotBlank();
        assertThat(method.json().path("notObserved").size()).isZero();
    }

    @Test
    void changeImpactSymbolsSuggestNothingForAnUnknownQuery() {
        assumeTrue(
                isPanelUsableInLiveManifest("runtime-insights"),
                "runtime-insights panel is not available in this environment");
        ReadContract contract = BootUiApiContractCatalog.changeImpactSymbols();
        List<String> failures = new ArrayList<>();

        Response unknown = probe().get(api(contract.relativePath()));

        assertThat(unknown.status())
                .as("GET %s status", contract.relativePath())
                .isEqualTo(200);
        assertJsonContract("change impact symbols, unknown query", contract, unknown.json(), failures);
        assertThat(failures).as("change impact symbols contract").isEmpty();
        assertThat(unknown.json().path("query").asText()).isEqualTo("conformanceUnknownSymbol");
        assertThat(unknown.json().path("symbols").size()).isZero();
        assertThat(unknown.json().path("total").asInt(-1)).isZero();
    }

    @Test
    void readingTheResourceProfileStartsNoSession() {
        assumeTrue(
                isPanelUsableInLiveManifest("runtime-insights"),
                "runtime-insights panel is not available in this environment");
        ReadContract contract = BootUiApiContractCatalog.resourceProfile();
        BootUiHttpProbe probe = probe();
        List<String> failures = new ArrayList<>();

        Response first = probe.get(api(contract.relativePath()));
        Response second = probe.get(api(contract.relativePath()));

        assertThat(first.status()).as("GET %s status", contract.relativePath()).isEqualTo(200);
        assertJsonContract("resource profile", contract, first.json(), failures);
        assertThat(failures).as("resource profile contract").isEmpty();
        assertThat(first.json().path("state").asText()).isIn("IDLE", "COMPLETED", "FAILED", "UNAVAILABLE");
        assertThat(second.json().path("state").asText())
                .as("a read never starts a JFR session")
                .isEqualTo(first.json().path("state").asText());
        assertThat(first.json().path("maxDurationSeconds").asLong()).isPositive();
        if ("UNAVAILABLE".equals(first.json().path("state").asText())) {
            assertThat(first.json().path("reason").asText()).isNotBlank();
        }
    }

    @Test
    void theRunComparisonComparesTheCurrentRunWithAKeptOneOrSaysWhyNot() {
        assumeTrue(
                isPanelUsableInLiveManifest("runtime-insights"),
                "runtime-insights panel is not available in this environment");
        ReadContract contract = BootUiApiContractCatalog.runComparison();
        BootUiHttpProbe probe = probe();
        List<String> failures = new ArrayList<>();

        Response newest = probe.get(api(contract.relativePath()));
        assertThat(newest.status()).as("GET %s status", contract.relativePath()).isEqualTo(200);
        assertJsonContract("run comparison, newest", contract, newest.json(), failures);
        JsonNode json = newest.json();
        assertThat(json.path("status").asText()).isIn("COMPARED", "INSUFFICIENT", "NOT_COMPARABLE", "NO_PREVIOUS_RUN");
        assertThat(json.path("current").path("source").asText()).isEqualTo("CURRENT");
        if (json.path("previous").isNull()) {
            assertThat(json.path("status").asText()).isEqualTo("NO_PREVIOUS_RUN");
            assertThat(json.path("reason").asText()).isNotBlank();
        }
        assertThat(json.path("restartCost").path("status").asText()).isIn("COMPARED", "UNAVAILABLE");
        // Code changes lead the comparison with the BootUI agent (M5-7a): null without it, the same shape on every
        // stack with it, unavailable with its reason when it cannot list them.
        JsonNode codeChanges = json.path("codeChanges");
        if (!codeChanges.isNull()) {
            assertThat(codeChanges.path("available").isBoolean())
                    .as(codeChanges.toString())
                    .isTrue();
            assertThat(codeChanges.path("methods").isArray())
                    .as(codeChanges.toString())
                    .isTrue();
            if (!codeChanges.path("available").asBoolean()) {
                assertThat(codeChanges.path("unavailableReason").asText()).isNotBlank();
            }
        }
        // Side effects follow with the BootUI agent (M5-7b): null without it; with it, the same shape on every stack,
        // each of the four compared sensors with a status, or unavailable with its reason.
        JsonNode sideEffects = json.path("sideEffects");
        if (!sideEffects.isNull()) {
            assertThat(sideEffects.path("available").isBoolean())
                    .as(sideEffects.toString())
                    .isTrue();
            assertThat(sideEffects.path("changes").isArray())
                    .as(sideEffects.toString())
                    .isTrue();
            if (sideEffects.path("available").asBoolean()) {
                List<String> sensors = new ArrayList<>();
                sideEffects.path("sensors").forEach(sensor -> {
                    sensors.add(sensor.path("sensor").asText());
                    assertThat(sensor.path("status").asText()).isIn("COMPARED", "PARTIAL", "NOT_COMPARED");
                });
                assertThat(sensors).containsExactly("network", "files", "processes", "environment");
            } else {
                assertThat(sideEffects.path("unavailableReason").asText()).isNotBlank();
            }
        }

        Response unknown = probe.get(api(contract.relativePath() + "?run=conformance-unknown-run"));
        assertThat(unknown.status()).isEqualTo(200);
        assertJsonContract("run comparison, unknown run", contract, unknown.json(), failures);
        assertThat(failures).as("run comparison contract").isEmpty();
        assertThat(unknown.json().path("status").asText()).isEqualTo("NO_PREVIOUS_RUN");
        assertThat(unknown.json().path("reason").asText()).contains("conformance-unknown-run");
    }

    @Test
    void runtimeInsightsProjectTheJournalIntoObservationsWithStableIdsAndEvidence() throws InterruptedException {
        assumeTrue(
                isPanelUsableInLiveManifest("runtime-insights"),
                "runtime-insights panel is not available in this environment");
        ReadContract detailContract = BootUiApiContractCatalog.runtimeInsight();
        BootUiHttpProbe probe = probe();
        List<String> failures = new ArrayList<>();

        Response unknown = probe.get(api(detailContract.relativePath()));
        assertThat(unknown.status())
                .as("GET %s status", detailContract.relativePath())
                .isEqualTo(200);
        assertJsonContract("runtime insight, unknown", detailContract, unknown.json(), failures);
        assertThat(unknown.json().path("available").asBoolean(true)).isFalse();

        for (int i = 0; i < 7; i++) {
            probe.get(routeProbePath());
        }
        JsonNode breakdown = null;
        for (int attempt = 0; attempt < 30 && breakdown == null; attempt++) {
            JsonNode report = probe.get(api("/runtime-insights")).json();
            for (JsonNode observation : report.path("observations")) {
                // The probe's route is labelled by its template where a stack resolves one, so any breakdown over
                // five warm requests will do: the probe guarantees at least one route has them. Its time is split
                // into phases, or, when its requests reached no handler BootUI marks (an unmapped path on Quarkus)
                // or made no recorded call on a stack that marks no phases (WebFlux), it says so as insufficient.
                if ("route-time-breakdown".equals(observation.path("kind").asText())
                        && observation.path("eligible").asLong() >= 5
                        && ("OBSERVED".equals(observation.path("status").asText())
                                || explainsUnsplitTime(observation))) {
                    breakdown = observation;
                }
            }
            if (breakdown == null) {
                Thread.sleep(100);
            }
        }
        assertThat(breakdown)
                .as("a route with five warm requests shows where its time went, or why it cannot")
                .isNotNull();
        String id = breakdown.path("id").asText();
        assertThat(id).matches("route-time-breakdown:[0-9a-f]{10}");
        assertThat(breakdown.path("minimumTier").asText()).isEqualTo("REQUEST_ID");
        assertThat(breakdown.path("sentence").asText()).contains("warm median");

        Response detail = probe.get(api("/runtime-insights/insights/" + id));
        assertThat(detail.status()).isEqualTo(200);
        assertJsonContract("runtime insight, observed", detailContract, detail.json(), failures);
        assertThat(failures).as("runtime insight contract").isEmpty();
        assertThat(detail.json().path("available").asBoolean(false)).isTrue();
        assertThat(detail.json().path("observation").path("id").asText()).isEqualTo(id);
        assertThat(detail.json().path("columns").size()).isPositive();
        assertThat(detail.json().path("rows").size()).isPositive();

        JsonNode report = probe.get(api("/runtime-insights")).json();
        assertThat(report.path("checks").size())
                .as("every observation reports whether it ran")
                .isEqualTo(23);
        // Each kind's external validation (docs/PLAN-v2.md M4-20), from the engine's one registry, on every stack.
        Map<String, String> validation = new HashMap<>();
        for (JsonNode check : report.path("checks")) {
            assertThat(check.path("validation").asText())
                    .as("check %s says how its external validation went", check.path("kind"))
                    .isIn("PASSED", "NOT_VALIDATED", "FAILED", "UNDER_SAMPLED", "NOT_LISTED", "NOT_JUDGED");
            assertThat(check.path("validationReason").asText()).isNotBlank();
            validation.put(check.path("kind").asText(), check.path("validation").asText());
        }
        assertThat(validation)
                .containsEntry("route-time-breakdown", "FAILED")
                .containsEntry("exception-hotspots", "FAILED")
                .containsEntry("errors-behind-2xx", "PASSED")
                .containsEntry("repeated-selects", "UNDER_SAMPLED")
                .containsEntry("safe-method-dml", "NOT_VALIDATED");
        for (JsonNode observation : report.path("observations")) {
            // The default list (docs/PLAN-v2.md M4-19): every row says whether it is listed, and why when it is not.
            assertThat(observation.path("listed").isBoolean())
                    .as("observation %s says whether it is listed by default", observation.path("id"))
                    .isTrue();
            assertThat(observation.path("unlistedReason").isTextual())
                    .as("observation %s says why it is left out exactly when it is", observation.path("id"))
                    .isEqualTo(!observation.path("listed").asBoolean());
            String outcome = validation.get(observation.path("kind").asText());
            if (!"PASSED".equals(outcome) && !"NOT_VALIDATED".equals(outcome)) {
                assertThat(observation.path("listed").asBoolean())
                        .as("observation %s of a kind that is not listed by default (M4-20)", observation.path("id"))
                        .isFalse();
            }
        }
        boolean httpCovered = false;
        for (JsonNode coverage : report.path("coverage")) {
            httpCovered |= "http".equals(coverage.path("source").asText())
                    && coverage.path("byRequestId").asLong() > 0;
        }
        assertThat(httpCovered).as("HTTP events are linked by request id").isTrue();
        for (JsonNode route : report.path("notExercised")) {
            assertThat(route.asText())
                    .as("a route not exercised is a METHOD route label, never a BootUI or framework endpoint")
                    .matches("[A-Z]+ /.*")
                    .doesNotContain("/bootui")
                    .doesNotContain("*");
        }
    }

    /** Whether an insufficient breakdown says why its route's time is not split into phases. */
    private static boolean explainsUnsplitTime(JsonNode observation) {
        String sentence = observation.path("sentence").asText();
        return "INSUFFICIENT".equals(observation.path("status").asText())
                && (sentence.contains("not split into phases") || sentence.contains("marks no phases"));
    }

    private static boolean bootstrapAgentBridgeAbsent() {
        try {
            Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", false, null);
            return false;
        } catch (ClassNotFoundException | LinkageError ex) {
            return true;
        }
    }

    @Test
    void theJavaAgentPanelReportsNotAttachedWithSetupSnippetsWhenTheJvmRunsWithoutTheAgent() {
        assumeTrue(isPanelUsableInLiveManifest("java-agent"), "java-agent panel is not available in this environment");
        assumeTrue(bootstrapAgentBridgeAbsent(), "this JVM runs with the BootUI agent attached");
        Response response = probe().get(api("/java-agent"));

        assertThat(response.status()).isEqualTo(200);
        JsonNode report = response.json();
        assertThat(report.path("state").asText())
                .as("the sample applications run without the BootUI agent")
                .isEqualTo("NOT_ATTACHED");
        assertThat(report.path("reason").asText()).isNotBlank();
        assertThat(report.path("expectedProtocol").asInt()).isEqualTo(1);
        assertThat(report.path("protocol").isNull()).isTrue();
        assertThat(report.path("claim").isNull()).isTrue();
        assertThat(report.path("sensors").size()).isZero();
        JsonNode snippets = report.path("setup").path("snippets");
        assertThat(snippets.size()).isPositive();
        for (JsonNode snippet : snippets) {
            assertThat(snippet.path("id").asText()).isNotBlank();
            assertThat(snippet.path("label").asText()).isNotBlank();
            assertThat(snippet.path("language").asText()).isNotBlank();
            assertThat(snippet.path("text").asText()).contains("bootui-agent");
        }
        assertThat(report.path("setup").path("jarPath").asText()).contains("bootui-agent");
    }

    /**
     * The runtime switch of an opt-in agent sensor ({@code docs/PLAN-v2.md} M5-14) without the BootUI agent: the report
     * offers no switch, an opt-in sensor answers 409 with the reason, and any other sensor or a body without
     * {@code enabled} answers 400, each with the canonical {@code error} body; a read-only panel refuses it first with 403.
     */
    @Test
    void anOptInSensorCannotBeSwitchedWithoutTheAgentAndOtherSensorsNever() {
        assumeTrue(isPanelUsableInLiveManifest("java-agent"), "java-agent panel is not available in this environment");
        assumeTrue(bootstrapAgentBridgeAbsent(), "this JVM runs with the BootUI agent attached");
        assertThat(probe().get(api("/java-agent")).json().path("toggles").size())
                .isZero();
        JsonNode panel = livePanelsById().get("java-agent");

        BootUiHttpProbe probe = probe();
        Response refused = probe.request(
                "POST", api("/java-agent/sensors/environment"), stateChangingHeaders(probe), "{\"enabled\":true}");
        if (panel.path("readOnly").asBoolean(false)) {
            assertThat(refused.status())
                    .as("a read-only java-agent panel refuses the switch")
                    .isEqualTo(403);
            return;
        }
        assertThat(refused.status())
                .as("POST java-agent/sensors/environment without the agent")
                .isEqualTo(409);
        assertThat(refused.isJson()).isTrue();
        assertThat(refused.json().path("error").asText()).contains("not armed");

        BootUiHttpProbe other = probe();
        Response unknown = other.request(
                "POST", api("/java-agent/sensors/executors"), stateChangingHeaders(other), "{\"enabled\":true}");
        assertThat(unknown.status()).as("POST java-agent/sensors/executors").isEqualTo(400);
        assertThat(unknown.json().path("error").asText()).contains("threads, files, environment");

        BootUiHttpProbe empty = probe();
        Response missing =
                empty.request("POST", api("/java-agent/sensors/environment"), stateChangingHeaders(empty), "{}");
        assertThat(missing.status())
                .as("POST java-agent/sensors/environment without enabled")
                .isEqualTo(400);
        assertThat(missing.json().path("error").asText()).contains("enabled");
    }

    /**
     * Code Inventory without the BootUI agent ({@code docs/PLAN-v2.md} §5.15): the panel is unavailable with the Java
     * Agent panel's reason, and every read still answers its shape, {@code available: false} with that reason. The
     * available shape is asserted with the agent attached, by the Spring sample's agent scenario.
     */
    @Test
    void codeInventoryIsUnavailableWithTheJavaAgentReasonWithoutTheAgent() {
        assumeTrue(bootstrapAgentBridgeAbsent(), "this JVM runs with the BootUI agent attached");
        JsonNode panel = panelFromLiveManifest("code-inventory");
        assertThat(panel).as("the code-inventory panel is in the manifest").isNotNull();
        assumeTrue(panel.path("enabled").asBoolean(true), "the code-inventory panel is disabled here");
        assertThat(panel.path("available").asBoolean())
                .as("Code Inventory needs the agent")
                .isFalse();
        assertThat(panel.path("unavailableReason").asText()).startsWith("Requires the BootUI agent's inventory sensor");

        List<String> failures = new ArrayList<>();
        List<ReadContract> contracts = new ArrayList<>();
        contracts.add(BootUiApiContractCatalog.reads().stream()
                .filter(contract -> contract.relativePath().equals("/code-inventory"))
                .findFirst()
                .orElseThrow());
        contracts.addAll(BootUiApiContractCatalog.codeInventoryLists());
        for (ReadContract contract : contracts) {
            Response response = probe().get(api(contract.relativePath()));
            assertThat(response.status())
                    .as("GET %s status", contract.relativePath())
                    .isEqualTo(200);
            JsonNode body = response.json();
            assertJsonContract(contract.relativePath(), contract, body, failures);
            assertThat(body.path("available").asBoolean())
                    .as("GET %s available", contract.relativePath())
                    .isFalse();
            assertThat(body.path("unavailableReason").asText())
                    .as("GET %s unavailableReason", contract.relativePath())
                    .startsWith("Requires the BootUI agent's inventory sensor");
        }
        assertThat(failures).as("code inventory contracts").isEmpty();
    }

    /**
     * Code Paths without the BootUI agent ({@code docs/PLAN-v2.md} §5.14): the panel is unavailable with the Java Agent
     * panel's reason, and every read still answers its shape, {@code available: false} with that reason. The available
     * shape is asserted with the agent attached, by the Spring sample's agent scenario.
     */
    @Test
    void codePathsIsUnavailableWithTheJavaAgentReasonWithoutTheAgent() {
        assumeTrue(bootstrapAgentBridgeAbsent(), "this JVM runs with the BootUI agent attached");
        JsonNode panel = panelFromLiveManifest("code-paths");
        assertThat(panel).as("the code-paths panel is in the manifest").isNotNull();
        assumeTrue(panel.path("enabled").asBoolean(true), "the code-paths panel is disabled here");
        assertThat(panel.path("available").asBoolean())
                .as("Code Paths needs the agent")
                .isFalse();
        assertThat(panel.path("unavailableReason").asText())
                .startsWith("Requires the BootUI agent's code-paths sensor");

        List<String> failures = new ArrayList<>();
        List<ReadContract> contracts = new ArrayList<>();
        contracts.add(BootUiApiContractCatalog.reads().stream()
                .filter(contract -> contract.relativePath().equals("/code-paths"))
                .findFirst()
                .orElseThrow());
        contracts.addAll(BootUiApiContractCatalog.codePathsTrees());
        for (ReadContract contract : contracts) {
            Response response = probe().get(api(contract.relativePath()));
            assertThat(response.status())
                    .as("GET %s status", contract.relativePath())
                    .isEqualTo(200);
            JsonNode body = response.json();
            assertJsonContract(contract.relativePath(), contract, body, failures);
            assertThat(body.path("available").asBoolean())
                    .as("GET %s available", contract.relativePath())
                    .isFalse();
            assertThat(body.path("unavailableReason").asText())
                    .as("GET %s unavailableReason", contract.relativePath())
                    .startsWith("Requires the BootUI agent's code-paths sensor");
        }
        assertThat(failures).as("code paths contracts").isEmpty();
    }

    /**
     * Side Effects without the BootUI agent ({@code docs/PLAN-v2.md} §5.16): the panel is unavailable with the Java Agent
     * panel's reason, and its reads still answer their shape, {@code available: false} with that reason, every sensor
     * listed; an unknown sensor is a {@code 400}. The available shape is asserted with the agent attached, by the Spring
     * sample's agent scenario.
     */
    /**
     * Without the agent's caught-exceptions sensor, the Exceptions panel's report carries no caught-in-code summary
     * and its section answers its contract, unavailable with why ({@code docs/PLAN-v2.md} M5-6), on every stack.
     */
    @Test
    void caughtInApplicationCodeIsUnavailableWithoutTheAgent() {
        assumeTrue(bootstrapAgentBridgeAbsent(), "this JVM runs with the BootUI agent attached");
        JsonNode panel = panelFromLiveManifest("exceptions");
        assumeTrue(panel != null && panel.path("enabled").asBoolean(true), "the exceptions panel is disabled here");

        JsonNode report = probe().get(api("/exceptions")).json();
        assertThat(report.has("caughtInCode"))
                .as("the summary field is present")
                .isTrue();
        assertThat(report.path("caughtInCode").isNull())
                .as("no summary without the sensor")
                .isTrue();

        ReadContract contract = BootUiApiContractCatalog.caughtExceptions();
        Response response = probe().get(api(contract.relativePath()));
        assertThat(response.status())
                .as("GET %s status", contract.relativePath())
                .isEqualTo(200);
        List<String> failures = new ArrayList<>();
        JsonNode body = response.json();
        assertJsonContract(contract.relativePath(), contract, body, failures);
        assertThat(failures).isEmpty();
        assertThat(body.path("available").asBoolean()).isFalse();
        assertThat(body.path("unavailableReason").asText()).contains("caught-exceptions sensor");
        assertThat(body.path("rows")).isEmpty();
    }

    @Test
    void sideEffectsIsUnavailableWithTheJavaAgentReasonWithoutTheAgent() {
        assumeTrue(bootstrapAgentBridgeAbsent(), "this JVM runs with the BootUI agent attached");
        JsonNode panel = panelFromLiveManifest("side-effects");
        assertThat(panel).as("the side-effects panel is in the manifest").isNotNull();
        assumeTrue(panel.path("enabled").asBoolean(true), "the side-effects panel is disabled here");
        assertThat(panel.path("available").asBoolean())
                .as("Side Effects needs the agent")
                .isFalse();
        assertThat(panel.path("unavailableReason").asText()).startsWith("Requires the BootUI agent");

        List<String> failures = new ArrayList<>();
        List<ReadContract> contracts = new ArrayList<>();
        contracts.add(BootUiApiContractCatalog.reads().stream()
                .filter(contract -> contract.relativePath().equals("/side-effects"))
                .findFirst()
                .orElseThrow());
        contracts.add(BootUiApiContractCatalog.sideEffectsSensor());
        for (ReadContract contract : contracts) {
            Response response = probe().get(api(contract.relativePath()));
            assertThat(response.status())
                    .as("GET %s status", contract.relativePath())
                    .isEqualTo(200);
            JsonNode body = response.json();
            assertJsonContract(contract.relativePath(), contract, body, failures);
            assertThat(body.path("available").asBoolean())
                    .as("GET %s available", contract.relativePath())
                    .isFalse();
            assertThat(body.path("unavailableReason").asText())
                    .as("GET %s unavailableReason", contract.relativePath())
                    .startsWith("Requires the BootUI agent");
        }
        JsonNode report = probe().get(api("/side-effects")).json();
        List<String> sensors = new ArrayList<>();
        report.path("sensors").forEach(sensor -> sensors.add(sensor.path("id").asText()));
        assertThat(sensors)
                .containsExactly(
                        "network",
                        "files",
                        "processes",
                        "environment",
                        "thread-activity",
                        "thread-locals",
                        "resources",
                        "blocking",
                        "security-sinks");
        report.path("sensors").forEach(sensor -> {
            if (java.util.Set.of(
                            "processes",
                            "network",
                            "files",
                            "environment",
                            "blocking",
                            "thread-activity",
                            "thread-locals")
                    .contains(sensor.path("id").asText())) {
                // Shipped sensors (M5-5a to M5-5f): unavailable with the Java Agent panel's reason without the agent.
                assertThat(sensor.path("state").asText()).isEqualTo("unavailable");
                assertThat(sensor.path("reason").asText()).startsWith("Requires the BootUI agent");
            } else {
                assertThat(sensor.path("state").asText()).isEqualTo("not-available");
                assertThat(sensor.path("reason").asText()).isEqualTo("Not available in this version.");
            }
        });
        assertThat(failures).as("side effects contracts").isEmpty();

        Response unknown = probe().get(api("/side-effects/sensor?sensor=not-a-sensor"));
        assertThat(unknown.status()).as("an unknown sensor").isEqualTo(400);
        assertThat(unknown.json().path("error").asText()).contains("not-a-sensor");
    }

    @Test
    void runtimeResourcesReportOneShapeWithABalancedLedger() {
        assumeTrue(isPanelUsableInLiveManifest("activity"), "activity panel is not available in this environment");
        ReadContract contract = BootUiApiContractCatalog.runtimeResources();

        Response response = probe().get(api(contract.relativePath()));

        assertThat(response.status())
                .as("GET %s status", contract.relativePath())
                .isEqualTo(200);
        List<String> failures = new ArrayList<>();
        assertJsonContract("runtime resources", contract, response.json(), failures);
        assertThat(failures).as("runtime resources contract").isEmpty();
        JsonNode resources = response.json();
        assertThat(resources.path("available").asBoolean(false))
                .as("the resources source is on by default: %s", resources.path("unavailableReason"))
                .isTrue();
        int families = resources.path("families").size();
        for (JsonNode point : resources.path("points")) {
            assertThat(point.path("familyCpuNanos").size()).isLessThanOrEqualTo(families);
            long process = point.path("processCpuNanos").asLong();
            if (process >= 0) {
                long parts = point.path("requestCpuNanos").asLong()
                        + point.path("internalCpuNanos").asLong();
                for (JsonNode family : point.path("familyCpuNanos")) {
                    parts += family.asLong();
                }
                assertThat(parts)
                        .as("a point's CPU parts sum to the process's CPU")
                        .isEqualTo(process);
            }
        }
    }

    @Test
    void traceDataFollowsTheLiveValueExposurePolicy() {
        // Spans are stored raw and every trace read applies the live exposure policy, so the same retained span
        // must come back verbatim, masked, and without its message text as the policy changes, on the Traces
        // detail and in the per-request profile that embeds the trace.
        assumeTrue(isPanelUsableInLiveManifest("traces"), "traces panel is not available in this environment");
        TraceExposureContract trace = new TraceExposureContract();
        assertThat(telemetryStore().add(trace.span(), false))
                .as("the seeded span is retained")
                .isTrue();
        String requestId = requestOnTrace(trace);

        LogTailExposureContract.withExposure("FULL", null, () -> {
            trace.assertVerbatim(traceDetail(trace), "GET /traces/{id} (FULL)");
            if (requestId != null) {
                trace.assertVerbatim(profiledTrace(requestId), "GET /activity/request/{id} trace (FULL)");
            }
        });
        LogTailExposureContract.withExposure("MASKED", null, () -> {
            trace.assertMasked(traceDetail(trace), "GET /traces/{id} (MASKED)");
            trace.assertNoSecret(probe().get(api("/traces")).json(), "GET /traces (MASKED)");
            if (requestId != null) {
                trace.assertMasked(profiledTrace(requestId), "GET /activity/request/{id} trace (MASKED)");
            }
        });
        LogTailExposureContract.withExposure("METADATA_ONLY", null, () -> {
            trace.assertOmitted(traceDetail(trace), "GET /traces/{id} (METADATA_ONLY)");
            if (requestId != null) {
                // The request's traceparent header value is itself withheld, so the profile may carry no trace.
                Response response = probe().get(api("/activity/request/" + requestId));
                assertThat(response.status())
                        .as("GET /activity/request/{id} status")
                        .isEqualTo(200);
                trace.assertNoSecret(response.json(), "GET /activity/request/{id} (METADATA_ONLY)");
                JsonNode profiled = response.json().path("trace");
                if (profiled.isObject()) {
                    trace.assertOmitted(profiled, "GET /activity/request/{id} trace (METADATA_ONLY)");
                }
            }
        });
        trace.assertMasked(traceDetail(trace), "GET /traces/{id} (restored default)");
    }

    @Test
    void aiChatDetailFollowsTheLiveValueExposurePolicy() {
        // The AI Framework chat detail returns the chat span's attributes and events. They are stored raw and must
        // follow the same live exposure policy as the Traces detail. The endpoint is served whether or not an AI
        // framework is on the classpath, so only a disabled panel skips the contract.
        JsonNode panel = panelFromLiveManifest("ai");
        assumeTrue(panel != null && panel.path("enabled").asBoolean(true), "ai panel is disabled");
        AiChatExposureContract chat = new AiChatExposureContract();
        assertThat(telemetryStore().add(chat.span(), false))
                .as("the seeded chat span is retained")
                .isTrue();

        LogTailExposureContract.withExposure(
                "FULL", null, () -> chat.assertVerbatim(aiChatDetail(chat), "GET /ai/chats/{id} (FULL)"));
        LogTailExposureContract.withExposure("MASKED", null, () -> {
            chat.assertMasked(aiChatDetail(chat), "GET /ai/chats/{id} (MASKED)");
            chat.assertNoSecret(probe().get(api("/ai/overview")).json(), "GET /ai/overview (MASKED)");
            chat.assertNoSecret(probe().get(api("/ai/chats")).json(), "GET /ai/chats (MASKED)");
        });
        LogTailExposureContract.withExposure("METADATA_ONLY", null, () -> {
            chat.assertOmitted(aiChatDetail(chat), "GET /ai/chats/{id} (METADATA_ONLY)");
            chat.assertNoSecret(probe().get(api("/ai/overview")).json(), "GET /ai/overview (METADATA_ONLY)");
        });
        chat.assertMasked(aiChatDetail(chat), "GET /ai/chats/{id} (restored default)");
    }

    private JsonNode aiChatDetail(AiChatExposureContract chat) {
        Response response = probe().get(api("/ai/chats/" + chat.spanId));
        assertThat(response.status()).as("GET /ai/chats/{id} status").isEqualTo(200);
        return response.json();
    }

    private JsonNode traceDetail(TraceExposureContract trace) {
        Response response = probe().get(api("/traces/" + trace.traceId));
        assertThat(response.status()).as("GET /traces/{id} status").isEqualTo(200);
        return response.json();
    }

    private JsonNode profiledTrace(String requestId) {
        Response response = probe().get(api("/activity/request/" + requestId));
        assertThat(response.status()).as("GET /activity/request/{id} status").isEqualTo(200);
        JsonNode trace = response.json().path("trace");
        assertThat(trace.isObject())
                .as("the profile embeds the request's trace; received %s", response.body())
                .isTrue();
        return trace;
    }

    /**
     * Sends one application request on the contract's trace and returns its Live Activity id, or {@code null} when
     * this environment captures no HTTP exchanges or has no Live Activity panel to profile it.
     */
    private String requestOnTrace(TraceExposureContract trace) {
        if (!isPanelUsableInLiveManifest("activity") || !isPanelUsableInLiveManifest("http-exchanges")) {
            return null;
        }
        probe().request("GET", routeProbePath(), Map.of("traceparent", trace.traceparent()), null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Response list = probe().get(api("/http-exchanges?q=" + trace.traceId));
            assertThat(list.status()).as("GET /http-exchanges?q= status").isEqualTo(200);
            assumeTrue(
                    isNull(list.json().path("unavailableReason")),
                    "HTTP exchanges are not recorded in this environment");
            JsonNode exchange = list.json().path("exchanges").path(0);
            if (exchange.isObject()) {
                assertThat(exchange.path("traceId").asText())
                        .as("exchange.traceId")
                        .isEqualTo(trace.traceId);
                return exchange.path("id").asText();
            }
            assertThat(System.nanoTime())
                    .as("the request on the seeded trace is recorded")
                    .isLessThan(deadline);
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the exchange", ex);
            }
        }
    }

    @Test
    void requestProfileKeepsOneBackwardCompatibleShapeForAnUnknownRequest() {
        // The profile drill-down is a detail read of Live Activity, so the root-read sweep never reaches it.
        // An id that was never captured must answer 200 with the canonical unavailable profile, and every
        // later, additive section must be present and empty rather than missing, on every adapter.
        assumeTrue(isPanelUsableInLiveManifest("activity"), "activity panel is not available in this environment");
        ReadContract contract = BootUiApiContractCatalog.requestProfile();

        Response response = probe().get(api(contract.relativePath()));

        assertThat(response.status())
                .as("GET %s status", contract.relativePath())
                .isEqualTo(200);
        assertThat(response.isJson())
                .as("GET %s content-type", contract.relativePath())
                .isTrue();
        List<String> failures = new ArrayList<>();
        assertJsonContract("activity request profile", contract, response.json(), failures);
        assertThat(failures).as("request profile contract").isEmpty();
        JsonNode profile = response.json();
        assertThat(profile.path("available").asBoolean(true)).isFalse();
        assertThat(profile.path("unavailableReason").asText())
                .isEqualTo("Request conformance-unknown-request is no longer in the buffer");
        for (String section : List.of("sql", "restCalls", "cacheAccesses", "sections", "correlationTiers")) {
            assertThat(profile.path(section).size())
                    .as("$.%s of an unavailable profile", section)
                    .isZero();
        }
        assertThat(profile.path("approximate").asBoolean(true)).isFalse();
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
    void httpProbeInputBudgetsAreEnforcedIdenticallyOnEveryAdapter() {
        // The HTTP Probe request body, path and headers are bounded by the shared engine HttpProbeLimits
        // *before* any outbound work happens. Every adapter must therefore reject over-limit input with
        // the same canonical HTTP 400 + {"error": ...} body, and still run a probe that sits exactly on
        // the ceiling. A probe that runs stays an HTTP 200 envelope, whatever the probed path answers.
        assumeTrue(isPanelUsableInLiveManifest("http-probe"), "http-probe panel is not available here");

        BootUiHttpProbe probe = probe();
        Map<String, String> headers = stateChangingHeaders(probe);
        String probePath = "/__bootui_conformance_probe__";

        Response accepted = probe.request(
                "POST", api("/http-probe"), headers, probeRequest("POST", probePath, "a".repeat(65536), 0));
        assertThat(accepted.status())
                .as("a probe request body exactly at the 65536-byte ceiling must still run")
                .isEqualTo(200);

        assertProbeRejection(
                probe,
                headers,
                probeRequest("POST", probePath, "a".repeat(65537), 0),
                "HTTP Probe request body exceeds the maximum of 65536 bytes");
        assertProbeRejection(
                probe,
                headers,
                probeRequest("GET", "/" + "p".repeat(2048), null, 0),
                "HTTP Probe request path exceeds the maximum of 2048 bytes");
        assertProbeRejection(
                probe,
                headers,
                probeRequest("GET", probePath, null, 51),
                "HTTP Probe request exceeds the maximum of 50 request headers");
    }

    private void assertProbeRejection(
            BootUiHttpProbe probe, Map<String, String> headers, String body, String expectedError) {
        Response response = probe.request("POST", api("/http-probe"), headers, body);
        assertThat(response.status())
                .as("over-limit probe input must be rejected with the canonical 400")
                .isEqualTo(400);
        assertThat(response.isJson())
                .as("the probe rejection body must be JSON (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("error").asText())
                .as("the probe rejection carries the canonical engine message")
                .isEqualTo(expectedError);
    }

    /** Builds a probe request payload with an optional body and {@code headerCount} synthetic headers. */
    private static String probeRequest(String method, String path, String body, int headerCount) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < headerCount; i++) {
            headers.put("X-Conformance-" + i, "v");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", method);
        payload.put("path", path);
        payload.put("body", body);
        payload.put("headers", headers);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to build HTTP probe test payload", ex);
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

    /**
     * JVM input arguments carry system properties exactly as typed on the command line. The Live Memory and JVM
     * Tuning reports must mask a secret-named {@code -D} value while keeping its key, on every stack. Each consumer
     * module passes {@value #JVM_SECRET_ARGUMENT_KEY} through its Surefire {@code argLine}; the test JVM is the
     * server JVM on Spring MVC, Spring WebFlux and Quarkus alike.
     */
    @Test
    void jvmInputArgumentSecretsAreMaskedBeforeSerialization() {
        String masked = "-D" + JVM_SECRET_ARGUMENT_KEY + "=******";
        for (String panel : List.of("jvm-tuning", "live-memory")) {
            // Both panels read only JMX beans present on every JVM, so unavailability is itself a wiring
            // regression and must fail rather than skip the masking check.
            assertThat(isPanelUsableInLiveManifest(panel))
                    .as(panel + " must be available on every stack")
                    .isTrue();
            Response response = probe().get(api("/" + panel));
            assertThat(response.status()).as(panel + " status").isEqualTo(200);
            assertThat(response.body())
                    .as(panel + " must never serialize a raw JVM argument secret")
                    .doesNotContain(JVM_SECRET_ARGUMENT_VALUE);
            List<String> arguments = new ArrayList<>();
            response.json().path("jvmInputArguments").forEach(argument -> arguments.add(argument.asText()));
            assertThat(arguments)
                    .as(panel + " must keep the secret argument key visible with a masked value; configure "
                            + "-D" + JVM_SECRET_ARGUMENT_KEY + "=" + JVM_SECRET_ARGUMENT_VALUE
                            + " in the Surefire argLine")
                    .contains(masked);
        }
    }

    /**
     * A property is enumerated under the literal name its source uses: an environment variable stays
     * {@code UPPER_SNAKE_CASE} on Spring and on Quarkus alike, because relaxed binding applies on lookup and
     * never on enumeration. Free-text search must therefore compare canonicalized names, so the dotted,
     * kebab-case and {@code UPPER_SNAKE_CASE} spellings of one property all find it on every stack.
     */
    @Test
    void configSearchFindsAPropertyThroughItsRelaxedBindingNameForms() {
        assumeTrue(isPanelUsableInLiveManifest("config"), "config panel is not available in this environment");

        String key = "bootui.conformance.api-token";
        String rawSecret = "conformance-raw-secret-value";
        Response response = probe().get(api("/config?q=BOOTUI_CONFORMANCE_API_TOKEN&limit=10"));
        assertThat(response.status()).as("relaxed config query status").isEqualTo(200);
        assertThat(response.body()).as("raw secret must never be serialized").doesNotContain(rawSecret);

        JsonNode matching = null;
        for (JsonNode property : response.json().path("properties")) {
            if (key.equals(property.path("name").asText())) {
                matching = property;
                break;
            }
        }
        assertThat(matching)
                .as("an UPPER_SNAKE_CASE query must find the dotted property, as relaxed binding would")
                .isNotNull();
        assertThat(matching.path("masked").asBoolean()).isTrue();
    }

    /**
     * Failure-preserving retention must describe each BootUI-owned capture buffer the same way on Spring MVC, Spring
     * WebFlux, and Quarkus: counts that reconcile with the records in the same response, a reservation carved out
     * of the capacity rather than added to it, and one shared request slow threshold for HTTP exchanges. An
     * application-provided recorder reports only what BootUI read.
     */
    @Test
    void captureBuffersReportReconcilingRetention() {
        if (isPanelUsableInLiveManifest("http-exchanges")) {
            JsonNode root = okJson("/http-exchanges?limit=1");
            JsonNode retention = root.path("retention");
            assertThat(retention.isObject()).as("http-exchanges.retention").isTrue();
            assertThat(retention.path("retained").asInt(-1))
                    .as("http-exchanges.retention.retained must equal the recorded exchanges")
                    .isEqualTo(root.path("recorded").asInt());
            if (!retention.path("applicationManaged").asBoolean(true)) {
                assertOwnedRetention("http-exchanges", retention);
                assertThat(retention.path("slowThresholdMillis").asLong(-1))
                        .as("every adapter classifies slow exchanges with bootui.activity.request-slow-threshold-ms")
                        .isEqualTo(1_000L);
            }
        }
        for (String panel : List.of("sql-trace", "rest-client-trace")) {
            if (!isPanelUsableInLiveManifest(panel)) {
                continue;
            }
            JsonNode root = okJson("/" + panel);
            if (!root.path("available").asBoolean(false)) {
                continue;
            }
            JsonNode retention = root.path("retention");
            assertThat(retention.isObject()).as(panel + ".retention").isTrue();
            assertThat(retention.path("applicationManaged").asBoolean(true))
                    .as(panel + " buffers are always BootUI-owned")
                    .isFalse();
            assertOwnedRetention(panel, retention);
            assertThat(retention.path("retained").asInt(-1))
                    .as(panel + ".retention.retained must equal the returned entries")
                    .isEqualTo(root.path("entries").size());
            assertThat(retention.path("capacity").asInt(-1))
                    .as(panel + ".retention.capacity must equal bufferSize")
                    .isEqualTo(root.path("bufferSize").asInt());
            assertThat(retention.path("evicted").asLong(-1))
                    .as(panel + ".retention.evicted must equal stats.evicted")
                    .isEqualTo(root.path("stats").path("evicted").asLong());
        }
    }

    private JsonNode okJson(String relativePath) {
        Response response = probe().get(api(relativePath));
        assertThat(response.status()).as("GET %s status", relativePath).isEqualTo(200);
        assertThat(response.isJson()).as("GET %s content-type", relativePath).isTrue();
        return response.json();
    }

    private static void assertOwnedRetention(String panel, JsonNode retention) {
        int capacity = retention.path("capacity").asInt(-1);
        int reservedCapacity = retention.path("reservedCapacity").asInt(-1);
        int retained = retention.path("retained").asInt(-1);
        int reserved = retention.path("reserved").asInt(-1);
        assertThat(capacity).as(panel + ".retention.capacity").isPositive();
        assertThat(reservedCapacity)
                .as(panel + ".retention.reservedCapacity is carved out of the capacity")
                .isBetween(0, capacity - 1);
        assertThat(retained).as(panel + ".retention.retained").isBetween(0, capacity);
        assertThat(reserved).as(panel + ".retention.reserved").isBetween(0, Math.min(reservedCapacity, retained));
        assertThat(retention.path("evicted").isIntegralNumber())
                .as(panel + ".retention.evicted")
                .isTrue();
        assertThat(retention.path("evicted").asLong(-1))
                .as(panel + ".retention.evicted")
                .isNotNegative();
        assertThat(retention.path("slowThresholdMillis").asLong(-1))
                .as(panel + ".retention.slowThresholdMillis")
                .isNotNegative();
    }

    /**
     * SQL Trace rankings and route attribution must present the same bounded, self-describing shape on
     * Spring MVC, Spring WebFlux and Quarkus. Values differ per runtime and per workload; the contract does
     * not. In particular the response must always say which correlation tiers it could use, so a stack with
     * no thread affinity discloses that instead of looking like it lost data.
     */
    @Test
    void sqlTraceInsightsAreBoundedAndDiscloseTheirCorrelationTiers() {
        assumeTrue(isPanelUsableInLiveManifest("sql-trace"), "sql-trace panel is not available in this environment");

        Response response = probe().get(api("/sql-trace/insights"));
        assertThat(response.status()).as("GET /sql-trace/insights status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /sql-trace/insights content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();
        assertThat(root.path("available").isBoolean()).as("insights.available").isTrue();
        assertThat(root.path("capturing").isBoolean()).as("insights.capturing").isTrue();
        assertThat(root.path("notes").isArray()).as("insights.notes").isTrue();
        assumeTrue(root.path("available").asBoolean(false), "SQL tracing is not active in this environment");

        JsonNode window = root.path("window");
        assertThat(window.isObject()).as("insights.window").isTrue();
        for (String field :
                List.of("retainedStatements", "bufferSize", "evicted", "totalCaptured", "totalDurationMillis")) {
            assertThat(window.path(field).isNumber())
                    .as("insights.window.%s must be numeric", field)
                    .isTrue();
        }

        int topPerCriterion = root.path("topPerCriterion").asInt(-1);
        assertThat(topPerCriterion).as("insights.topPerCriterion").isPositive();
        JsonNode statements = root.path("statements");
        assertThat(statements.isArray()).as("insights.statements").isTrue();
        assertThat(statements.size())
                .as("ranked statements must stay bounded by the seven ranking criteria")
                .isLessThanOrEqualTo(7 * topPerCriterion);
        for (JsonNode statement : statements) {
            for (String field : List.of(
                    "executions",
                    "totalDurationMillis",
                    "maxDurationMillis",
                    "avgDurationMillis",
                    "errorCount",
                    "p50DurationMillis",
                    "p95DurationMillis",
                    "p99DurationMillis",
                    "shareOfRetainedTimePercent")) {
                assertThat(statement.path(field).isNumber())
                        .as("ranked statement field '%s'", field)
                        .isTrue();
            }
            assertThat(statement.path("sql").isTextual())
                    .as("ranked statement sql")
                    .isTrue();
            assertThat(statement.path("entryIds").isArray())
                    .as("ranked statement must deep-link to retained executions")
                    .isTrue();
            assertThat(statement.path("entryIdsTruncated").isBoolean())
                    .as("a ranked statement must say when its deep link covers only part of the group")
                    .isTrue();
            JsonNode topFor = statement.path("topFor");
            assertThat(topFor.isArray())
                    .as("a ranked statement must say which criteria earned it its place")
                    .isTrue();
            assertThat(topFor.size())
                    .as("a ranked statement cannot lead more criteria than exist")
                    .isBetween(1, 7);
            topFor.forEach(criterion -> assertThat(criterion.asText())
                    .as("ranking criterion")
                    .isIn(
                            "TOTAL_DURATION",
                            "MAX_DURATION",
                            "EXECUTIONS",
                            "AVG_DURATION",
                            "ERROR_COUNT",
                            "P95_DURATION",
                            "P99_DURATION"));
        }

        JsonNode attribution = root.path("attribution");
        assertThat(attribution.path("available").isBoolean())
                .as("attribution.available")
                .isTrue();
        List<String> tiers = new ArrayList<>();
        attribution.path("supportedCorrelations").forEach(tier -> tiers.add(tier.asText()));
        assertThat(tiers)
                .as("every runtime must offer trace-id correlation and disclose the tiers it uses")
                .contains("TRACE_ID");
        assertThat(tiers).isSubsetOf("TRACE_ID", "SERVING_THREAD", "TIME_WINDOW");
        for (String bucket : List.of("unattributed", "ambiguous")) {
            JsonNode node = attribution.path(bucket);
            assertThat(node.path("executions").isNumber())
                    .as("attribution.%s.executions", bucket)
                    .isTrue();
            assertThat(node.path("reason").isTextual())
                    .as("attribution.%s must explain itself rather than showing a bare number", bucket)
                    .isTrue();
        }

        JsonNode routes = attribution.path("routes");
        assertThat(routes.isArray()).as("attribution.routes").isTrue();
        assertThat(routes.size()).as("route ranking must stay bounded").isLessThanOrEqualTo(20);
        for (JsonNode route : routes) {
            assertThat(route.path("routeSource").asText())
                    .as("route grouping key must declare its provenance")
                    .isIn("ROUTE_TEMPLATE", "MASKED_PATH");
            assertThat(route.path("route").asText())
                    .as("a route grouping key must never carry a query string")
                    .doesNotContain("?");
            assertThat(route.path("topStatements").isArray())
                    .as("route.topStatements")
                    .isTrue();
            assertThat(route.path("topStatements").size())
                    .as("route-by-statement cross product must stay bounded")
                    .isLessThanOrEqualTo(5);
        }
    }

    /**
     * HTTP route rankings must present the same bounded, self-describing shape on Spring MVC, Spring WebFlux
     * and Quarkus: every route declares how it was resolved, never carries a query string, reconciles its
     * status classes with its request count, and the response states the retained window it summarizes with
     * BootUI's own traffic kept out of it.
     */
    @Test
    void httpRouteRankingsAreBoundedAndStateTheirWindow() {
        assumeTrue(
                isPanelUsableInLiveManifest("http-exchanges"),
                "http-exchanges panel is not available in this environment");

        Response response = probe().get(api("/http-exchanges/routes?limit=3"));
        assertThat(response.status()).as("GET /http-exchanges/routes status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /http-exchanges/routes content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();
        assertThat(root.path("available").isBoolean()).as("routes.available").isTrue();
        assertThat(root.path("notes").isArray()).as("routes.notes").isTrue();
        assumeTrue(root.path("available").asBoolean(false), "HTTP exchanges are not recorded in this environment");
        assertThat(root.path("topPerCriterion").asInt(-1))
                .as("routes.topPerCriterion")
                .isEqualTo(3);
        assertThat(root.path("routesTruncated").isBoolean())
                .as("routes.routesTruncated")
                .isTrue();
        assertThat(root.path("distinctRoutes").isInt())
                .as("routes.distinctRoutes")
                .isTrue();

        JsonNode window = root.path("window");
        for (String field : List.of(
                "retainedExchanges",
                "hiddenSelfExchanges",
                "summarizedExchanges",
                "timedExchanges",
                "totalDurationMs")) {
            assertThat(window.path(field).isNumber())
                    .as("routes.window.%s must be numeric", field)
                    .isTrue();
        }
        for (String field : List.of("bufferSize", "evicted", "oldestTimestamp", "newestTimestamp")) {
            assertThat(window.path(field).isNumber() || isNull(window.path(field)))
                    .as("routes.window.%s must be a number or null", field)
                    .isTrue();
        }
        assertThat(window.path("summarizedExchanges").asInt()
                        + window.path("hiddenSelfExchanges").asInt())
                .as("summarized and hidden BootUI exchanges reconcile with the retained window")
                .isEqualTo(window.path("retainedExchanges").asInt());

        JsonNode routes = root.path("routes");
        assertThat(routes.isArray()).as("routes.routes").isTrue();
        assertThat(routes.size())
                .as("ranked routes stay bounded by the five ranking criteria")
                .isLessThanOrEqualTo(5 * 3);
        for (JsonNode route : routes) {
            assertThat(route.path("routeSource").asText())
                    .as("a route must declare how it was resolved")
                    .isIn("FRAMEWORK_TEMPLATE", "DECLARED_MAPPING", "MASKED_PATH");
            assertThat(route.path("route").asText())
                    .as("a route never carries a query string")
                    .doesNotContain("?");
            assertThat(route.path("id").asText())
                    .isEqualTo(route.path("method").asText() + " "
                            + route.path("route").asText());
            long statuses = 0;
            for (String field : List.of("status2xx", "status3xx", "status4xx", "status5xx", "statusOther")) {
                statuses += route.path(field).asLong();
            }
            assertThat(statuses)
                    .as("status classes reconcile with requests")
                    .isEqualTo(route.path("requests").asLong());
            for (String field : List.of("p50DurationMs", "p95DurationMs", "p99DurationMs", "maxDurationMs")) {
                assertThat(route.path(field).isNumber() || isNull(route.path(field)))
                        .as("route.%s", field)
                        .isTrue();
            }
            assertThat(route.path("shareOfRetainedTimePercent").isNumber()).isTrue();
            route.path("topFor")
                    .forEach(criterion -> assertThat(criterion.asText())
                            .as("ranking criterion")
                            .isIn("REQUESTS", "TOTAL_DURATION", "P95_DURATION", "MAX_DURATION", "ERROR_COUNT"));
        }
    }

    /**
     * An application request is listed with its route, and that route's row — pinned, so it is returned
     * whatever its rank — counts it. The exchange list and the route rankings must agree on every adapter,
     * and the numeric path segment must never become part of a route.
     */
    @Test
    void httpExchangeRoutesLinkToTheirRankingRow() {
        assumeTrue(
                isPanelUsableInLiveManifest("http-exchanges"),
                "http-exchanges panel is not available in this environment");
        String marker = "conformance-route-probe";
        probe().get(routeProbePath());

        // Spring WebFlux records an exchange once the response has completed, so it can land a moment after
        // the client has read the response, and the route template can be attached a moment after that: until
        // then the exchange shows its masked path while the rankings already group it under the template. Poll
        // both reads together until they agree, rather than race the recorder.
        JsonNode exchange = MissingNode.getInstance();
        String routeId = null;
        JsonNode row = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Response list = probe().get(api("/http-exchanges?q=" + marker));
            assertThat(list.status()).as("GET /http-exchanges?q= status").isEqualTo(200);
            assumeTrue(
                    isNull(list.json().path("unavailableReason")),
                    "HTTP exchanges are not recorded in this environment");
            exchange = list.json().path("exchanges").path(0);
            if (exchange.isObject()) {
                routeId = exchange.path("method").asText() + " "
                        + exchange.path("route").asText("");
                Response rankings = probe().get(api(
                        "/http-exchanges/routes?limit=1&route=" + URLEncoder.encode(routeId, StandardCharsets.UTF_8)));
                assertThat(rankings.status())
                        .as("GET /http-exchanges/routes?route= status")
                        .isEqualTo(200);
                row = null;
                for (JsonNode candidate : rankings.json().path("routes")) {
                    if (routeId.equals(candidate.path("id").asText())) {
                        row = candidate;
                    }
                }
            }
            if (row != null || System.nanoTime() > deadline) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(exchange.isObject()).as("the probe request is recorded").isTrue();
        String route = exchange.path("route").asText("");
        assertThat(route)
                .as("exchange.route")
                .startsWith("/")
                .doesNotContain("4711")
                .doesNotContain("?");
        assertThat(exchange.path("routeSource").asText()).isIn("FRAMEWORK_TEMPLATE", "DECLARED_MAPPING", "MASKED_PATH");
        String encodedRouteId = URLEncoder.encode(routeId, StandardCharsets.UTF_8);
        assertThat(row).as("the pinned row for %s", routeId).isNotNull();
        assertThat(row.path("requests").asLong()).isPositive();

        Response filtered = probe().get(api("/http-exchanges?route=" + encodedRouteId));
        assertThat(filtered.json().path("page").path("matched").asLong())
                .as("the route filter lists exactly the exchanges the row counts")
                .isEqualTo(row.path("requests").asLong());
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

    /**
     * Adds to {@code failures} every way {@code root} breaks {@code contract}: its root type, each required field's type,
     * the availability pair, and a non-negative total. Public, so an agent scenario outside this suite, such as the
     * Spring sample's, asserts the available shapes it alone can reach against the same catalog.
     */
    public static void assertJsonContract(String panelId, ReadContract contract, JsonNode root, List<String> failures) {
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
            case NULLABLE_INTEGER -> isNull(node) || node.isIntegralNumber();
            case NULLABLE_NUMBER -> isNull(node) || node.isNumber();
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
