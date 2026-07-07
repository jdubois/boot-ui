package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * White-box binding tests for {@link QuarkusPanelAccessFilter}, mirroring the Spring adapter's
 * {@code PanelAccessFilterTests} scenario-for-scenario at behavioral parity (same config keys, same
 * canonical JSON 403 shape and reason strings). Lives in the integration-tests module (same package as
 * the runtime class) because Mockito is only a test dependency there, matching
 * {@link BootUiQuarkusSafetyFilterTest}'s convention.
 */
class QuarkusPanelAccessFilterTest {

    // --- basic enabled/disabled gating --------------------------------------------------------------

    @Test
    void allowsEnabledPanelReadRequest() {
        RoutingContext rc = mockRequest("GET", "/bootui/api/memory");
        QuarkusPanelAccessFilter filter = newFilter(Map.of());

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void blocksDisabledPanelReadRequest() {
        RoutingContext rc = mockRequest("GET", "/bootui/api/memory");
        HttpServerResponse resp = rc.response();
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.panels.memory.enabled", "false"));

        filter.handle(rc);

        assertBlocked(resp, "memory", "Panel is disabled via bootui.panels.memory.enabled=false");
        verify(rc, never()).next();
    }

    @Test
    void blocksDisabledPanelActionRequest() {
        RoutingContext rc = mockRequest("POST", "/bootui/api/loggers/io.github.jdubois.bootui");
        HttpServerResponse resp = rc.response();
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.panels.loggers.enabled", "false"));

        filter.handle(rc);

        assertBlocked(resp, "loggers", "Panel is disabled via bootui.panels.loggers.enabled=false");
        verify(rc, never()).next();
    }

    // --- per-panel read-only -------------------------------------------------------------------------

    @Test
    void allowsReadOnlyPanelReadRequest() {
        RoutingContext rc = mockRequest("GET", "/bootui/api/memory");
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.panels.memory.read-only", "true"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void blocksReadOnlyPanelActionRequest() {
        RoutingContext rc = mockRequest("POST", "/bootui/api/memory/scan");
        HttpServerResponse resp = rc.response();
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.panels.memory.read-only", "true"));

        filter.handle(rc);

        assertBlocked(resp, "memory", "Panel is read-only via bootui.panels.memory.read-only=true");
        verify(rc, never()).next();
    }

    @Test
    void perPanelReadOnlyBlocksPentestingScanAction() {
        RoutingContext rc = mockRequest("POST", "/bootui/api/pentesting/scan");
        HttpServerResponse resp = rc.response();
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.panels.pentesting.read-only", "true"));

        filter.handle(rc);

        assertBlocked(resp, "pentesting", "Panel is read-only via bootui.panels.pentesting.read-only=true");
        verify(rc, never()).next();
    }

    @Test
    void perPanelReadOnlyAllowsPentestingReportRead() {
        RoutingContext rc = mockRequest("GET", "/bootui/api/pentesting");
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.panels.pentesting.read-only", "true"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    // --- global read-only ----------------------------------------------------------------------------

    @Test
    void globalReadOnlyBlocksEveryQuarkusActionCapablePanelAction() {
        Map<String, ActionRequest> actionRequestsByPanel = actionRequestsByPanel();

        // Completeness self-check: every actionCapable panel in the shared registry must be triaged into
        // exactly one of the fixture map, the not-applicable-on-Quarkus set, or the no-write-path-yet set
        // (Config). This fails loudly if a new action-capable panel is added to the registry without being
        // triaged, instead of silently under-covering it.
        assertThat(actionRequestsByPanel.keySet())
                .containsExactlyInAnyOrderElementsOf(BootUiPanels.all().stream()
                        .filter(BootUiPanels.Panel::actionCapable)
                        .map(BootUiPanels.Panel::id)
                        .filter(id -> !NOT_APPLICABLE_ON_QUARKUS.contains(id))
                        .filter(id -> !NO_WRITE_PATH_ON_QUARKUS_YET.contains(id))
                        .toList());

        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.read-only", "true"));
        for (Map.Entry<String, ActionRequest> entry : actionRequestsByPanel.entrySet()) {
            RoutingContext rc =
                    mockRequest(entry.getValue().method(), entry.getValue().uri());
            HttpServerResponse resp = rc.response();

            filter.handle(rc);

            assertBlocked(resp, entry.getKey(), "BootUI is read-only via bootui.read-only=true");
        }
    }

    @Test
    void globalReadOnlyDoesNotBlockAPanelWithoutActions() {
        RoutingContext rc = mockRequest("GET", "/bootui/api/metrics");
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.read-only", "true"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    // --- bypasses --------------------------------------------------------------------------------------

    @Test
    void overviewShellEndpointIsNeverGatedByPanelToggle() {
        // GET /bootui/api/overview is the shell's framework-neutral chrome data source (and CSRF-cookie
        // primer), so disabling the Overview dashboard panel must not 403 it. BootUiPanels.OVERVIEW
        // registers no API prefix, so BootUiPanels.byApiPath("/overview") never resolves a Panel and this
        // filter's panel == null branch lets it through untouched, exactly like the Spring filter.
        RoutingContext rc = mockRequest("GET", "/bootui/api/overview");
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.panels.overview.enabled", "false"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void skipsCorePanelMetadataEndpoint() {
        RoutingContext rc = mockRequest("GET", "/bootui/api/panels");
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.read-only", "true"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void ignoresRequestsOutsideTheBootuiApiSurface() {
        // The static UI shell under /bootui/** (not /bootui/api/**) is never gated by a panel toggle.
        RoutingContext rc = mockRequest("GET", "/bootui/index.html");
        QuarkusPanelAccessFilter filter = newFilter(Map.of("bootui.read-only", "true"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    // --- root-path handling --------------------------------------------------------------------------

    @Test
    void guardsPanelAccessUnderANonDefaultRootPath() {
        // Under quarkus.http.root-path=/app the console is served at /app/bootui/**; the filter must strip
        // the prefix (shared QuarkusRootPath helper) and still apply per-panel gating.
        RoutingContext rc = mockRequest("POST", "/app/bootui/api/cache/clear");
        HttpServerResponse resp = rc.response();
        QuarkusPanelAccessFilter filter =
                newFilter(Map.of("quarkus.http.root-path", "/app", "bootui.panels.cache.enabled", "false"));

        filter.handle(rc);

        assertBlocked(resp, "cache", "Panel is disabled via bootui.panels.cache.enabled=false");
        verify(rc, never()).next();
    }

    @Test
    void ignoresNonBootuiPathsUnderANonDefaultRootPath() {
        RoutingContext rc = mockRequest("POST", "/app/other");
        QuarkusPanelAccessFilter filter =
                newFilter(Map.of("quarkus.http.root-path", "/app", "bootui.read-only", "true"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    // --- helpers ---------------------------------------------------------------------------------------

    /**
     * Action-capable panels in the shared registry that have zero Quarkus {@code @Path} resources at all
     * (deliberately not ported: GraalVM/CRaC compile native images and target the Spring startup model
     * respectively; DevTools and HTTP Sessions have no Quarkus analogue).
     */
    private static final Set<String> NOT_APPLICABLE_ON_QUARKUS =
            Set.of(BootUiPanels.HTTP_SESSIONS, BootUiPanels.GRAALVM, BootUiPanels.DEVTOOLS, BootUiPanels.CRAC);

    /** Action-capable in the shared registry (Spring has a write path), but Quarkus has none yet. */
    private static final Set<String> NO_WRITE_PATH_ON_QUARKUS_YET = Set.of(BootUiPanels.CONFIG, BootUiPanels.EMAIL);

    private static QuarkusPanelAccessFilter newFilter(Map<String, String> properties) {
        return new QuarkusPanelAccessFilter(configOf(properties));
    }

    private static Config configOf(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .build();
    }

    private static RoutingContext mockRequest(String method, String path) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.valueOf(method));

        HttpServerResponse response = mock(HttpServerResponse.class, RETURNS_SELF);

        RoutingContext rc = mock(RoutingContext.class);
        when(rc.normalizedPath()).thenReturn(path);
        when(rc.request()).thenReturn(request);
        when(rc.response()).thenReturn(response);
        return rc;
    }

    private static void assertBlocked(HttpServerResponse response, String expectedPanelId, String expectedReason) {
        verify(response).setStatusCode(403);
        verify(response).putHeader("Content-Type", "application/json");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(response).end(body.capture());
        assertThat(body.getValue())
                .isEqualTo("{\"error\":\"BootUI panel access denied\",\"panel\":\"" + expectedPanelId
                        + "\",\"reason\":\"" + expectedReason + "\"}");
    }

    /**
     * The Quarkus-verified action-request fixture, one representative endpoint per real, available,
     * action-capable Quarkus panel — the Quarkus analogue of Spring's {@code actionRequestsByPanel()},
     * re-derived from the actual {@code @POST}/{@code @DELETE} JAX-RS resources rather than copied from
     * Spring (paths can differ — e.g. Spring's {@code dev-services} path has an extra {@code services/}
     * segment that the real Quarkus resource does not).
     */
    private static Map<String, ActionRequest> actionRequestsByPanel() {
        Map<String, ActionRequest> requests = new LinkedHashMap<>();
        requests.put("heap-dump", new ActionRequest("POST", "/bootui/api/heap-dump/capture"));
        requests.put("threads", new ActionRequest("POST", "/bootui/api/threads/download"));
        requests.put("memory", new ActionRequest("POST", "/bootui/api/memory/scan"));
        requests.put("loggers", new ActionRequest("POST", "/bootui/api/loggers/io.github.jdubois.bootui"));
        requests.put("security", new ActionRequest("POST", "/bootui/api/security/scan"));
        requests.put("pentesting", new ActionRequest("POST", "/bootui/api/pentesting/scan"));
        requests.put("hibernate", new ActionRequest("POST", "/bootui/api/hibernate/scan"));
        requests.put("cache", new ActionRequest("POST", "/bootui/api/cache/clear"));
        requests.put("traces", new ActionRequest("DELETE", "/bootui/api/traces"));
        requests.put("exceptions", new ActionRequest("DELETE", "/bootui/api/exceptions"));
        requests.put("http-probe", new ActionRequest("POST", "/bootui/api/http-probe"));
        requests.put("architecture", new ActionRequest("POST", "/bootui/api/architecture/scan"));
        requests.put("vulnerabilities", new ActionRequest("POST", "/bootui/api/vulnerabilities/scan"));
        requests.put("dev-services", new ActionRequest("POST", "/bootui/api/dev-services/demo/restart"));
        requests.put("flyway", new ActionRequest("POST", "/bootui/api/flyway/migrate"));
        requests.put("liquibase", new ActionRequest("POST", "/bootui/api/liquibase/update"));
        requests.put("github", new ActionRequest("POST", "/bootui/api/github/refresh"));
        requests.put("rest-api", new ActionRequest("POST", "/bootui/api/rest-api/scan"));
        requests.put("spring", new ActionRequest("POST", "/bootui/api/spring/scan"));
        requests.put("sql-trace", new ActionRequest("POST", "/bootui/api/sql-trace/clear"));
        requests.put("mcp-server", new ActionRequest("POST", "/bootui/api/mcp-server/toggle"));
        requests.put("activity", new ActionRequest("POST", "/bootui/api/activity/use-existing-datasource"));
        return requests;
    }

    private record ActionRequest(String method, String uri) {}
}
