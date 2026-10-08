package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.conformance.McpStreamDisconnectContract;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Quarkus: closing a request-scoped MCP stream cancels the call (MCP 2026-07-28). */
@QuarkusTest
@TestProfile(BootUiQuarkusMcpStreamDisconnectTest.DisconnectProfile.class)
class BootUiQuarkusMcpStreamDisconnectTest {

    static final McpStreamDisconnectContract CONTRACT = new McpStreamDisconnectContract();

    public static class DisconnectProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.mcp.enabled", "ON");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(CancellableScanDispatcher.class);
        }
    }

    /** Replaces the MCP dispatcher with one whose only tool is the contract's cancellable scan. */
    @Alternative
    @ApplicationScoped
    public static class CancellableScanDispatcher {

        @Produces
        @Singleton
        McpDispatcher dispatcher() {
            return new McpDispatcher(
                    List.of(CONTRACT.tool()),
                    List.of(),
                    new AllowAll(),
                    "test",
                    "test",
                    50,
                    20,
                    30_000,
                    (operation, failure) -> {
                        throw new AssertionError("A cancelled call is not a server fault: " + operation, failure);
                    });
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Inject
    McpDispatcher dispatcher;

    @Test
    void closingTheStreamCancelsTheCall() throws Exception {
        // Quarkus notices the close at once (the routing context's end handler), well before a keep-alive.
        CONTRACT.closeAfterFirstEventCancels(
                baseUrl.getPort(),
                "/bootui/api/mcp",
                dispatcher,
                java.time.Duration.ofMillis(
                        io.github.jdubois.bootui.engine.mcp.McpStreamingCall.HEARTBEAT_MILLIS * 3 / 4));
    }

    @Test
    void closingALegacyStreamDoesNotCancelButNotificationsCancelledDoes() throws Exception {
        CONTRACT.legacyCloseRunsOnUntilNotificationsCancelled(
                baseUrl.getPort(),
                "/bootui/api/mcp",
                () -> dispatcher.runtimeStats().snapshot());
    }

    @Test
    void notificationsCancelledStopsALegacyBlockingCall() throws Exception {
        CONTRACT.legacyBlockingCallIsCancelledByNotification(
                baseUrl.getPort(),
                "/bootui/api/mcp",
                () -> dispatcher.runtimeStats().snapshot());
    }

    private static final class AllowAll implements McpPanelPolicy {

        @Override
        public boolean isEnabled(String panelId) {
            return true;
        }

        @Override
        public String disabledReason(String panelId) {
            return "";
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return false;
        }

        @Override
        public String readOnlyReason(String panelId) {
            return "";
        }
    }
}
