package io.github.jdubois.bootui.autoconfigure.websocket;

import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.spi.WebSocketSessionProvider;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import io.github.jdubois.bootui.spi.WebSocketSubscriptionSnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bounded, framework-neutral inventory of the WebSocket sessions and STOMP subscriptions BootUI has
 * observed on the servlet stack.
 *
 * <p>Fed by {@link BootUiWebSocketHandlerDecorator} (session lifecycle) and
 * {@link BootUiStompChannelInterceptor} (subscribe/unsubscribe), both of which install through Spring's
 * own {@code WebSocketMessageBrokerConfigurer} seams so application decorators and interceptors keep
 * running in their declared order.</p>
 *
 * <p>Only the framework session identifier, path, negotiated subprotocol, and transport addresses are
 * retained. Principals, handshake headers, cookies, and query strings never enter this registry — the
 * decorator strips the query string before the path is stored, and the engine hashes the identifier
 * before serialization.</p>
 *
 * <p>Both maps are hard-capped. Closed sessions are retained only until the open-session budget needs the
 * room, so a reconnect storm cannot grow the registry without bound, and subscription churn can never
 * evict session state (the two live in separate maps with separate caps).</p>
 */
public class BootUiWebSocketSessionRegistry implements WebSocketSessionProvider {

    private final int maxSessions;
    private final int maxSubscriptions;

    private final ConcurrentMap<String, WebSocketSessionSnapshot> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSubscriptionSnapshot> subscriptions = new LinkedHashMap<>();
    private final Object subscriptionLock = new Object();
    private final Object sessionLock = new Object();

    public BootUiWebSocketSessionRegistry(WebSocketSettings settings) {
        // Retain a little more than the panel serializes so recently-closed sessions remain visible.
        this.maxSessions = Math.max(1, settings.maxSessions() * 2);
        this.maxSubscriptions = Math.max(1, settings.maxSubscriptions() * 2);
    }

    void opened(WebSocketSessionSnapshot session) {
        if (session == null || session.rawId() == null) {
            return;
        }
        // Prune and insert under one lock: concurrent handshakes that each observed room below the cap
        // would otherwise all insert and push the map past it.
        synchronized (sessionLock) {
            pruneSessions();
            sessions.put(session.rawId(), session);
        }
    }

    void closed(String rawId, Integer closeStatus) {
        if (rawId == null) {
            return;
        }
        sessions.computeIfPresent(
                rawId,
                (key, existing) -> new WebSocketSessionSnapshot(
                        existing.rawId(),
                        existing.endpointId(),
                        existing.path(),
                        false,
                        existing.openedAt(),
                        existing.subprotocol(),
                        existing.remoteAddress(),
                        existing.localAddress(),
                        closeStatus));
        synchronized (subscriptionLock) {
            subscriptions.values().removeIf(subscription -> rawId.equals(subscription.rawSessionId()));
        }
    }

    void subscribed(String rawSessionId, String rawSubscriptionId, String endpointId, String destination) {
        if (rawSessionId == null || destination == null) {
            return;
        }
        String key = subscriptionKey(rawSessionId, rawSubscriptionId);
        synchronized (subscriptionLock) {
            if (subscriptions.size() >= maxSubscriptions && !subscriptions.containsKey(key)) {
                java.util.Iterator<String> oldest = subscriptions.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            subscriptions.put(
                    key,
                    new WebSocketSubscriptionSnapshot(
                            key, rawSessionId, endpointId, destination, System.currentTimeMillis()));
        }
    }

    void unsubscribed(String rawSessionId, String rawSubscriptionId) {
        if (rawSessionId == null) {
            return;
        }
        synchronized (subscriptionLock) {
            subscriptions.remove(subscriptionKey(rawSessionId, rawSubscriptionId));
        }
    }

    /** Returns the endpoint id a live session belongs to, or {@code null} when the session is unknown. */
    String endpointIdFor(String rawSessionId) {
        WebSocketSessionSnapshot session = rawSessionId == null ? null : sessions.get(rawSessionId);
        return session == null ? null : session.endpointId();
    }

    @Override
    public List<WebSocketSessionSnapshot> sessions() {
        return List.copyOf(sessions.values());
    }

    @Override
    public List<WebSocketSubscriptionSnapshot> subscriptions() {
        synchronized (subscriptionLock) {
            return List.copyOf(subscriptions.values());
        }
    }

    /**
     * Drops retained history: every closed session and the subscriptions belonging to it. Sessions that are
     * still open stay in the inventory with their subscriptions, because they are live application state
     * that BootUI is merely observing — clearing must never make a live connection disappear from the panel
     * until it actually closes. No session is closed, cancelled, or otherwise disturbed.
     */
    @Override
    public void clearRetainedSessions() {
        sessions.values().removeIf(session -> !session.open());
        synchronized (subscriptionLock) {
            subscriptions.values().removeIf(subscription -> !sessions.containsKey(subscription.rawSessionId()));
        }
    }

    private void pruneSessions() {
        if (sessions.size() < maxSessions) {
            return;
        }
        Collection<WebSocketSessionSnapshot> values = sessions.values();
        List<WebSocketSessionSnapshot> closed = new ArrayList<>();
        for (WebSocketSessionSnapshot session : values) {
            if (!session.open()) {
                closed.add(session);
            }
        }
        closed.sort(java.util.Comparator.comparingLong(WebSocketSessionSnapshot::openedAt));
        for (WebSocketSessionSnapshot session : closed) {
            if (sessions.size() < maxSessions) {
                return;
            }
            sessions.remove(session.rawId());
        }
        // Still over budget with only open sessions: drop the oldest open entries from the inventory.
        // The application's sessions are untouched; only BootUI's view of them is bounded.
        while (sessions.size() >= maxSessions) {
            WebSocketSessionSnapshot oldest = sessions.values().stream()
                    .min(java.util.Comparator.comparingLong(WebSocketSessionSnapshot::openedAt))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            sessions.remove(oldest.rawId());
        }
    }

    private static String subscriptionKey(String rawSessionId, String rawSubscriptionId) {
        return rawSessionId + "#" + (rawSubscriptionId == null ? "" : rawSubscriptionId);
    }
}
