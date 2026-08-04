package io.github.jdubois.bootui.quarkus;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class BootUiPathRewriteFilterTest {

    @Test
    void defaultApiPathPassesThrough() {
        RoutingContext context = request("/bootui/api/overview", null);

        filter(Map.of()).handle(context);

        verify(context).next();
        verify(context, never()).reroute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void trailingSlashShellUsesTheInjectingIndexResource() {
        RoutingContext context = request("/bootui/", null);

        filter(Map.of()).handle(context);

        verify(context).put(BootUiPathRewriteFilter.REROUTE_MARKER, Boolean.TRUE);
        verify(context).reroute("/bootui");
    }

    @Test
    void customPathPreservesRootPathAndQueryString() {
        RoutingContext context = request("/host/dev-console/api/mappings/flat", "offset=2&limit=1");
        BootUiPathRewriteFilter filter = filter(Map.of(
                "bootui.path", "/dev-console",
                "quarkus.http.root-path", "/host"));

        filter.handle(context);

        verify(context).reroute("/host/bootui/api/mappings/flat?offset=2&limit=1");
        verify(context, never()).next();
    }

    @Test
    void separateApiPathRewritesDirectlyToInternalApi() {
        RoutingContext context = request("/internal/bootui-api/overview", null);
        BootUiPathRewriteFilter filter = filter(Map.of(
                "bootui.path", "/console",
                "bootui.api-path", "/internal/bootui-api"));

        filter.handle(context);

        verify(context).reroute("/bootui/api/overview");
    }

    @Test
    void directInternalPathIsBlockedForCustomConfiguration() {
        RoutingContext context = request("/bootui/assets/app.js", null);

        filter(Map.of("bootui.path", "/console")).handle(context);

        verify(context.response()).setStatusCode(404);
        verify(context.response()).end();
        verify(context, never()).next();
    }

    @Test
    void reroutedCyclePassesThroughWithoutLooping() {
        RoutingContext context = request("/bootui/api/overview", null);
        when(context.get(BootUiPathRewriteFilter.REROUTE_MARKER)).thenReturn(Boolean.TRUE);

        filter(Map.of("bootui.path", "/console")).handle(context);

        verify(context).next();
        verify(context, never()).reroute(org.mockito.ArgumentMatchers.anyString());
        verify(context.response(), never()).setStatusCode(anyInt());
    }

    private static BootUiPathRewriteFilter filter(Map<String, String> properties) {
        Config config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .build();
        return new BootUiPathRewriteFilter(config, LaunchMode.TEST);
    }

    private static RoutingContext request(String path, String query) {
        HttpServerResponse response = mock(HttpServerResponse.class, RETURNS_SELF);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.query()).thenReturn(query);
        RoutingContext context = mock(RoutingContext.class);
        when(context.normalizedPath()).thenReturn(path);
        when(context.response()).thenReturn(response);
        when(context.request()).thenReturn(request);
        return context;
    }
}
