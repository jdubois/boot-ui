package io.github.jdubois.bootui.autoconfigure.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import io.github.jdubois.bootui.spi.WebSocketSubscriptionSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the servlet session inventory: what it retains, what it caps, and — most importantly — what it never
 * retains. Only framework identifiers, the query-free path, the negotiated subprotocol, and transport
 * addresses enter this registry; there is no code path here that could accept a message payload.
 */
class BootUiWebSocketSessionRegistryTests {

    private static WebSocketSessionSnapshot session(String id, boolean open) {
        return new WebSocketSessionSnapshot(
                id, "stomp:/ws", "/ws", open, 1_000L, "v12.stomp", "127.0.0.1:1", null, null);
    }

    private static BootUiWebSocketSessionRegistry registry(int maxSessions, int maxSubscriptions) {
        return new BootUiWebSocketSessionRegistry(
                new WebSocketSettings(true, true, 10, maxSessions, maxSubscriptions, 10, 100));
    }

    @Test
    void tracksOpenAndClosedSessionsWithTheirCloseStatus() {
        BootUiWebSocketSessionRegistry registry = registry(50, 50);

        registry.opened(session("s1", true));
        assertThat(registry.sessions()).hasSize(1);
        assertThat(registry.sessions().get(0).open()).isTrue();
        assertThat(registry.endpointIdFor("s1")).isEqualTo("stomp:/ws");

        registry.closed("s1", 1000);
        assertThat(registry.sessions()).hasSize(1);
        assertThat(registry.sessions().get(0).open()).isFalse();
        assertThat(registry.sessions().get(0).closeStatus()).isEqualTo(1000);
    }

    @Test
    void closingASessionDropsItsSubscriptions() {
        BootUiWebSocketSessionRegistry registry = registry(50, 50);
        registry.opened(session("s1", true));
        registry.subscribed("s1", "sub-0", "stomp:/ws", "/topic/chat");
        registry.subscribed("s1", "sub-1", "stomp:/ws", "/topic/news");
        assertThat(registry.subscriptions()).hasSize(2);

        registry.unsubscribed("s1", "sub-1");
        assertThat(registry.subscriptions()).hasSize(1);
        assertThat(registry.subscriptions().get(0).destination()).isEqualTo("/topic/chat");

        registry.closed("s1", 1000);
        assertThat(registry.subscriptions())
                .as("a closed session cannot still be subscribed")
                .isEmpty();
    }

    @Test
    void clearingKeepsLiveSessionsAndDropsOnlyClosedHistory() {
        BootUiWebSocketSessionRegistry registry = registry(50, 50);
        registry.opened(session("live", true));
        registry.subscribed("live", "sub-0", "stomp:/ws", "/topic/chat");
        registry.opened(session("gone", true));
        registry.closed("gone", 1001);

        registry.clearRetainedSessions();

        assertThat(registry.sessions())
                .as("an open connection must not vanish from the panel just because history was cleared")
                .extracting(WebSocketSessionSnapshot::rawId)
                .containsExactly("live");
        assertThat(registry.subscriptions())
                .extracting(WebSocketSubscriptionSnapshot::rawSessionId)
                .containsExactly("live");
    }

    @Test
    void sessionInventoryIsHardCappedAndEvictsClosedSessionsFirst() {
        BootUiWebSocketSessionRegistry registry = registry(2, 50); // retains 2 * 2 = 4 sessions
        for (int i = 0; i < 3; i++) {
            registry.opened(
                    new WebSocketSessionSnapshot("closed-" + i, "stomp:/ws", "/ws", true, i, null, null, null, null));
            registry.closed("closed-" + i, 1000);
        }
        registry.opened(new WebSocketSessionSnapshot("open-1", "stomp:/ws", "/ws", true, 100, null, null, null, null));

        for (int i = 0; i < 20; i++) {
            registry.opened(new WebSocketSessionSnapshot(
                    "burst-" + i, "stomp:/ws", "/ws", true, 200 + i, null, null, null, null));
        }

        assertThat(registry.sessions())
                .as("a reconnect storm cannot grow the inventory without bound")
                .hasSizeLessThanOrEqualTo(4);
        assertThat(registry.sessions())
                .as("closed sessions are evicted before live ones")
                .allMatch(WebSocketSessionSnapshot::open);
    }

    @Test
    void subscriptionInventoryIsHardCappedIndependentlyOfSessions() {
        BootUiWebSocketSessionRegistry registry = registry(50, 2); // retains 2 * 2 = 4 subscriptions
        registry.opened(session("s1", true));
        for (int i = 0; i < 30; i++) {
            registry.subscribed("s1", "sub-" + i, "stomp:/ws", "/topic/" + i);
        }

        assertThat(registry.subscriptions()).hasSizeLessThanOrEqualTo(4);
        assertThat(registry.sessions())
                .as("subscription churn must never evict session state")
                .hasSize(1);
    }

    @Test
    void ignoresIncompleteEventsInsteadOfThrowing() {
        BootUiWebSocketSessionRegistry registry = registry(50, 50);

        registry.opened(null);
        registry.opened(new WebSocketSessionSnapshot(null, null, null, true, 0, null, null, null, null));
        registry.closed(null, 1000);
        registry.subscribed(null, "sub", "e", "/topic/x");
        registry.subscribed("s1", "sub", "e", null);
        registry.unsubscribed(null, "sub");

        assertThat(registry.sessions()).isEmpty();
        assertThat(registry.subscriptions()).isEmpty();
        assertThat(registry.endpointIdFor(null)).isNull();
        assertThat(registry.endpointIdFor("unknown")).isNull();
    }

    @Test
    void snapshotsAreDefensiveCopies() {
        BootUiWebSocketSessionRegistry registry = registry(50, 50);
        registry.opened(session("s1", true));
        registry.subscribed("s1", "sub-0", "stomp:/ws", "/topic/chat");

        List<WebSocketSessionSnapshot> sessions = registry.sessions();
        List<WebSocketSubscriptionSnapshot> subscriptions = registry.subscriptions();
        registry.opened(session("s2", true));

        assertThat(sessions).hasSize(1);
        assertThat(subscriptions).hasSize(1);
    }
}
