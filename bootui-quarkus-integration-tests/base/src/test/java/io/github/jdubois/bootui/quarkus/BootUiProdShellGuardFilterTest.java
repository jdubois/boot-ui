package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * White-box binding tests for {@link BootUiProdShellGuardFilter} (hence the same package as the runtime
 * class, even though it lives in the integration-tests module). The filter's launch-mode decision is
 * constructor-injected rather than read from the static {@link LaunchMode#current()}, so these tests can
 * exercise both the {@link LaunchMode#NORMAL} (production) branch and every other launch mode
 * deterministically, without touching any process-global state — see
 * {@code BootUiQuarkusProdShellGuardBootTest} (in the separate
 * {@code bootui-quarkus-prod-shell-guard-integration-tests} module — not linkable from here, since a
 * {@code QuarkusProdModeTest}-based test cannot share a module/Surefire fork with this module's
 * {@code @QuarkusTest} classes) for the complementary real-boot proof that this actually behaves correctly
 * in a genuine {@link LaunchMode#NORMAL} build.
 */
class BootUiProdShellGuardFilterTest {

    // --- production: the guarded surface ------------------------------------------------------------

    @Test
    void production404sTheStaticShellRoot() {
        RoutingContext rc = mockRequest("/bootui");
        HttpServerResponse resp = rc.response();
        BootUiProdShellGuardFilter filter = newFilter(Map.of(), LaunchMode.NORMAL);

        filter.handle(rc);

        verify(resp).setStatusCode(404);
        verify(resp).end();
        verify(rc, never()).next();
    }

    @Test
    void production404sTheStaticShellDirectoryIndex() {
        RoutingContext rc = mockRequest("/bootui/");
        HttpServerResponse resp = rc.response();
        BootUiProdShellGuardFilter filter = newFilter(Map.of(), LaunchMode.NORMAL);

        filter.handle(rc);

        verify(resp).setStatusCode(404);
        verify(resp).end();
        verify(rc, never()).next();
    }

    @Test
    void production404sAStaticAsset() {
        RoutingContext rc = mockRequest("/bootui/index.html");
        HttpServerResponse resp = rc.response();
        BootUiProdShellGuardFilter filter = newFilter(Map.of(), LaunchMode.NORMAL);

        filter.handle(rc);

        verify(resp).setStatusCode(404);
        verify(resp).end();
        verify(rc, never()).next();
    }

    @Test
    void production404sTheApiSurfaceTooAsDefenseInDepth() {
        // /bootui/api/** is already unreachable in production because nothing registers it, but the guard
        // covers it anyway (it is not scoped to only the static shell) as a defense-in-depth backstop.
        RoutingContext rc = mockRequest("/bootui/api/overview");
        HttpServerResponse resp = rc.response();
        BootUiProdShellGuardFilter filter = newFilter(Map.of(), LaunchMode.NORMAL);

        filter.handle(rc);

        verify(resp).setStatusCode(404);
        verify(resp).end();
        verify(rc, never()).next();
    }

    @Test
    void productionDoesNotTouchLookalikePaths() {
        RoutingContext rc = mockRequest("/bootui-other");
        BootUiProdShellGuardFilter filter = newFilter(Map.of(), LaunchMode.NORMAL);

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void productionDoesNotTouchUnrelatedPaths() {
        RoutingContext rc = mockRequest("/other");
        BootUiProdShellGuardFilter filter = newFilter(Map.of(), LaunchMode.NORMAL);

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    // --- dev/test: complete pass-through -------------------------------------------------------------

    @Test
    void developmentNeverTouchesTheStaticShell() {
        RoutingContext rc = mockRequest("/bootui/");
        BootUiProdShellGuardFilter filter = newFilter(Map.of(), LaunchMode.DEVELOPMENT);

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
        // The pass-through must be immediate: it must not even inspect the request path.
        verify(rc, never()).normalizedPath();
    }

    @Test
    void testLaunchModeNeverTouchesTheStaticShell() {
        RoutingContext rc = mockRequest("/bootui/index.html");
        BootUiProdShellGuardFilter filter = newFilter(Map.of(), LaunchMode.TEST);

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
        verify(rc, never()).normalizedPath();
    }

    // --- root-path handling --------------------------------------------------------------------------

    @Test
    void productionGuardsTheShellUnderANonDefaultRootPath() {
        // Under quarkus.http.root-path=/app the shell is served at /app/bootui/**; the guard must strip
        // the prefix and still 404 it (fail-open regression guard, mirroring BootUiQuarkusSafetyFilter).
        RoutingContext rc = mockRequest("/app/bootui/index.html");
        HttpServerResponse resp = rc.response();
        BootUiProdShellGuardFilter filter = newFilter(Map.of("quarkus.http.root-path", "/app"), LaunchMode.NORMAL);

        filter.handle(rc);

        verify(resp).setStatusCode(404);
        verify(rc, never()).next();
    }

    @Test
    void productionIgnoresNonShellPathsUnderANonDefaultRootPath() {
        RoutingContext rc = mockRequest("/app/other");
        BootUiProdShellGuardFilter filter = newFilter(Map.of("quarkus.http.root-path", "/app"), LaunchMode.NORMAL);

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    // --- isBootUiPath scope ----------------------------------------------------------------------------

    @Test
    void scopeCoversTheWholeBootuiSurfaceButNotLookalikePaths() {
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootui")).isTrue();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootui/")).isTrue();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootui/index.html"))
                .isTrue();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootui/api/overview"))
                .isTrue();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootui-other")).isFalse();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/bootuixyz")).isFalse();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/other")).isFalse();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath("/")).isFalse();
        assertThat(BootUiProdShellGuardFilter.isBootUiPath(null)).isFalse();
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private static BootUiProdShellGuardFilter newFilter(Map<String, String> properties, LaunchMode launchMode) {
        return new BootUiProdShellGuardFilter(configOf(properties), launchMode);
    }

    private static Config configOf(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .build();
    }

    private static RoutingContext mockRequest(String path) {
        HttpServerResponse response = mock(HttpServerResponse.class, RETURNS_SELF);

        RoutingContext rc = mock(RoutingContext.class);
        when(rc.normalizedPath()).thenReturn(path);
        when(rc.response()).thenReturn(response);
        return rc;
    }
}
