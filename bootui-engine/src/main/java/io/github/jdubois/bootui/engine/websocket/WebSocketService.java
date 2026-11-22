package io.github.jdubois.bootui.engine.websocket;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.WebSocketActivityEntryDto;
import io.github.jdubois.bootui.core.dto.WebSocketCallbackDto;
import io.github.jdubois.bootui.core.dto.WebSocketEndpointDto;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.core.dto.WebSocketSessionDto;
import io.github.jdubois.bootui.core.dto.WebSocketStatsDto;
import io.github.jdubois.bootui.core.dto.WebSocketSubscriptionDto;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder.CapturedFrame;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder.SessionCounters;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.WebSocketCallbackSnapshot;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketMetadataProvider;
import io.github.jdubois.bootui.spi.WebSocketSessionProvider;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import io.github.jdubois.bootui.spi.WebSocketSubscriptionSnapshot;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral assembly of the WebSockets panel report.
 *
 * <p>Reads the adapter-supplied topology and session state, joins them with the panel's own bounded
 * activity buffer, hashes every framework identifier, applies the live exposure policy, enforces the
 * independent endpoint/session/subscription/activity caps, and produces stable, ordered DTOs.</p>
 *
 * <p>Assembling a report is strictly read-only: it opens no connection, sends no frame, changes no
 * subscription, and closes no application session. Reading it is therefore safe on page load.</p>
 */
public final class WebSocketService {

    private static final System.Logger LOG = System.getLogger(WebSocketService.class.getName());
    private static final String USER_DESTINATION_SUFFIX = "-user";

    static final String SESSION_TRACKING_UNAVAILABLE =
            "This stack exposes no seam for observing live WebSocket sessions, so connections and "
                    + "subscriptions are not listed. Endpoints are still reported.";

    static final String NOT_CONFIGURED =
            "No supported WebSocket integration is active. Add spring-boot-starter-websocket (Spring) "
                    + "or quarkus-websockets-next (Quarkus) and declare an endpoint.";

    private final WebSocketMetadataProvider metadataProvider;
    private final WebSocketSessionProvider sessionProvider;
    private final WebSocketActivityRecorder recorder;
    private final WebSocketSettings settings;
    private final ExposurePolicy exposurePolicy;

    public WebSocketService(
            WebSocketMetadataProvider metadataProvider,
            WebSocketSessionProvider sessionProvider,
            WebSocketActivityRecorder recorder,
            WebSocketSettings settings,
            ExposurePolicy exposurePolicy) {
        this.metadataProvider = metadataProvider;
        this.sessionProvider = sessionProvider;
        this.recorder = recorder;
        this.settings = settings;
        this.exposurePolicy = exposurePolicy;
    }

    /**
     * Clears everything the panel retains: captured frame metadata, per-session counters, and the adapters'
     * closed-session history.
     *
     * <p>Every stack clears the same things, so the panel behaves identically on Spring MVC, Spring WebFlux,
     * and Quarkus. Open sessions and their subscriptions survive: they describe live connections the user did
     * not ask to forget, and dropping them would make the inventory lie until the next handshake.</p>
     */
    public void clear() {
        recorder.clear();
        if (sessionProvider != null) {
            sessionProvider.clearRetainedSessions();
        }
    }

    public WebSocketReport report() {
        WebSocketTopologySnapshot topology = topology();
        if (topology == null) {
            return WebSocketReport.unavailable(NOT_CONFIGURED);
        }
        ValueExposure exposure = exposure();

        List<WebSocketSessionSnapshot> allSessions = sessions();
        List<WebSocketSubscriptionSnapshot> allSubscriptions = subscriptions();

        Map<String, Integer> openByEndpoint = new HashMap<>();
        int openSessions = 0;
        for (WebSocketSessionSnapshot session : allSessions) {
            if (session.open()) {
                openSessions++;
                openByEndpoint.merge(session.endpointId(), 1, Integer::sum);
            }
        }

        List<WebSocketEndpointSnapshot> endpoints = new ArrayList<>(topology.endpoints());
        endpoints.sort(Comparator.comparing((WebSocketEndpointSnapshot endpoint) -> nullSafe(endpoint.path()))
                .thenComparing(endpoint -> nullSafe(endpoint.id())));
        boolean endpointsTruncated = endpoints.size() > settings.maxEndpoints();
        List<WebSocketEndpointDto> endpointDtos = endpoints.stream()
                .limit(settings.maxEndpoints())
                .map(endpoint -> toDto(endpoint, openByEndpoint.getOrDefault(endpoint.id(), 0), exposure))
                .toList();

        List<WebSocketSessionSnapshot> orderedSessions = new ArrayList<>(allSessions);
        orderedSessions.sort(Comparator.comparing(WebSocketSessionSnapshot::open)
                .reversed()
                .thenComparing(Comparator.comparingLong(WebSocketSessionSnapshot::openedAt)
                        .reversed()));
        boolean sessionsTruncated = orderedSessions.size() > settings.maxSessions();
        List<WebSocketSessionDto> sessionDtos = orderedSessions.stream()
                .limit(settings.maxSessions())
                .map(session -> toDto(session, exposure))
                .toList();

        List<WebSocketSubscriptionSnapshot> orderedSubscriptions = new ArrayList<>(allSubscriptions);
        orderedSubscriptions.sort(Comparator.comparingLong(WebSocketSubscriptionSnapshot::subscribedAt)
                .reversed());
        boolean subscriptionsTruncated = orderedSubscriptions.size() > settings.maxSubscriptions();
        List<WebSocketSubscriptionDto> subscriptionDtos = orderedSubscriptions.stream()
                .limit(settings.maxSubscriptions())
                .map(subscription -> toDto(subscription, exposure))
                .toList();

        List<WebSocketActivityEntryDto> activity =
                recorder.recent().stream().map(frame -> toDto(frame, exposure)).toList();

        WebSocketStatsDto stats = new WebSocketStatsDto(
                endpoints.size(),
                openSessions,
                allSessions.size() - openSessions,
                allSubscriptions.size(),
                recorder.inboundFrames(),
                recorder.outboundFrames(),
                recorder.inboundBytes(),
                recorder.outboundBytes(),
                recorder.failedFrames(),
                recorder.totalCaptured(),
                recorder.evicted());

        return new WebSocketReport(
                true,
                null,
                topology.framework(),
                // A stack with no frame-capture seam never reports itself as capturing, even if the toggle was
                // flipped: the UI must not claim frames are being recorded when none can be.
                topology.frameCaptureSupported() && recorder.isCapturing(),
                topology.frameCaptureSupported(),
                topology.frameCaptureSupported() ? null : topology.frameCaptureUnavailableReason(),
                sessionProvider != null,
                sessionProvider != null ? null : SESSION_TRACKING_UNAVAILABLE,
                topology.brokerPrefixes(),
                topology.applicationDestinationPrefixes(),
                topology.userDestinationPrefix(),
                settings.maxEndpoints(),
                settings.maxSessions(),
                settings.maxSubscriptions(),
                settings.maxActivityEntries(),
                endpointsTruncated,
                sessionsTruncated,
                subscriptionsTruncated,
                endpointDtos,
                sessionDtos,
                subscriptionDtos,
                activity,
                stats,
                warnings(topology, endpointsTruncated, sessionsTruncated, subscriptionsTruncated));
    }

    private List<String> warnings(
            WebSocketTopologySnapshot topology,
            boolean endpointsTruncated,
            boolean sessionsTruncated,
            boolean subscriptionsTruncated) {
        List<String> warnings = new ArrayList<>();
        if (!recorder.isEnabled()) {
            warnings.add("WebSocket activity capture is disabled (bootui.websockets.enabled=false).");
        } else if (!recorder.isCapturing()) {
            warnings.add("WebSocket activity capture is paused; endpoints and sessions are still live.");
        }
        if (endpointsTruncated) {
            warnings.add("Endpoint list truncated to " + settings.maxEndpoints() + " entries.");
        }
        if (sessionsTruncated) {
            warnings.add("Session list truncated to " + settings.maxSessions() + " entries.");
        }
        if (subscriptionsTruncated) {
            warnings.add("Subscription list truncated to " + settings.maxSubscriptions() + " entries.");
        }
        if (recorder.evicted() > 0) {
            warnings.add(recorder.evicted() + " activity entries were dropped because the buffer is full.");
        }
        return warnings;
    }

    /**
     * Whether a supported WebSocket integration is present, without assembling a report.
     *
     * <p>The panel manifest is fetched on every page load and route change. The adapter can answer from
     * its registrations alone, without resolving endpoint detail or reading sessions or activity.</p>
     */
    public boolean isAvailable() {
        if (metadataProvider == null) {
            return false;
        }
        try {
            return metadataProvider.hasEndpoints();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.DEBUG, "Could not determine WebSocket endpoint availability");
            return false;
        }
    }

    private WebSocketTopologySnapshot topology() {
        if (metadataProvider == null) {
            return null;
        }
        try {
            return metadataProvider.topology();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private List<WebSocketSessionSnapshot> sessions() {
        if (sessionProvider == null) {
            return List.of();
        }
        try {
            List<WebSocketSessionSnapshot> sessions = sessionProvider.sessions();
            return sessions == null ? List.of() : sessions;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<WebSocketSubscriptionSnapshot> subscriptions() {
        if (sessionProvider == null) {
            return List.of();
        }
        try {
            List<WebSocketSubscriptionSnapshot> subscriptions = sessionProvider.subscriptions();
            return subscriptions == null ? List.of() : subscriptions;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private ValueExposure exposure() {
        if (exposurePolicy == null) {
            return ValueExposure.MASKED;
        }
        ValueExposure exposure = exposurePolicy.valueExposure();
        return exposure == null ? ValueExposure.MASKED : exposure;
    }

    private WebSocketEndpointDto toDto(WebSocketEndpointSnapshot endpoint, int openSessions, ValueExposure exposure) {
        return new WebSocketEndpointDto(
                endpoint.id(),
                displayPath(endpoint.path(), exposure),
                endpoint.kind(),
                endpoint.handlerClass(),
                endpoint.subprotocols(),
                endpoint.sockJs(),
                endpoint.allowedOrigins(),
                endpoint.interceptors(),
                endpoint.callbacks().stream()
                        .map(callback -> toDto(callback, exposure))
                        .toList(),
                openSessions,
                endpoint.inboundProcessingMode(),
                endpoint.captureInstalled());
    }

    private WebSocketCallbackDto toDto(WebSocketCallbackSnapshot callback, ValueExposure exposure) {
        return new WebSocketCallbackDto(
                callback.type(),
                displayPath(callback.destination(), exposure),
                callback.declaringClass(),
                callback.method(),
                callback.messageType());
    }

    private WebSocketSessionDto toDto(WebSocketSessionSnapshot session, ValueExposure exposure) {
        SessionCounters counters = recorder.counters(session.rawId());
        return new WebSocketSessionDto(
                WebSocketSessionIds.opaque(session.rawId()),
                session.endpointId(),
                displayPath(session.path(), exposure),
                session.open(),
                session.openedAt(),
                counters.lastActivityAt() == 0 ? null : counters.lastActivityAt(),
                session.subprotocol(),
                displayAddress(session.remoteAddress(), exposure),
                displayAddress(session.localAddress(), exposure),
                counters.messagesIn(),
                counters.messagesOut(),
                counters.bytesIn(),
                counters.bytesOut(),
                session.closeStatus());
    }

    private WebSocketSubscriptionDto toDto(WebSocketSubscriptionSnapshot subscription, ValueExposure exposure) {
        return new WebSocketSubscriptionDto(
                WebSocketSessionIds.opaque(subscription.rawId()),
                subscription.endpointId(),
                WebSocketSessionIds.opaque(subscription.rawSessionId()),
                displayDestination(subscription.destination(), exposure),
                subscription.subscribedAt());
    }

    private WebSocketActivityEntryDto toDto(CapturedFrame frame, ValueExposure exposure) {
        return new WebSocketActivityEntryDto(
                frame.id(),
                frame.timestamp(),
                frame.endpointId(),
                frame.sessionId(),
                frame.direction().name(),
                frame.frameType().name(),
                displayDestination(frame.destination(), exposure),
                frame.payloadBytes(),
                frame.durationMillis(),
                frame.success(),
                frame.errorCategory());
    }

    /**
     * Strips any query string before display: a handshake query can carry a token or other secret value,
     * and the panel never needs it. Under {@link ValueExposure#METADATA_ONLY} the path is withheld
     * entirely, matching the Config and HTTP Exchanges panels.
     */
    static String displayPath(String path, ValueExposure exposure) {
        if (path == null) {
            return null;
        }
        if (exposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    /**
     * Displays a destination like a path, then removes the raw session identifier that Spring's
     * user-destination resolver appends to a broker destination.
     *
     * <p>A message sent to {@code /user/{name}/queue/greetings} is resolved to
     * {@code /queue/greetings-user<simpSessionId>}, which embeds the very identifier BootUI hashes
     * everywhere else. Serializing it would hand out a live, addressable session id through the back door,
     * so the suffix is replaced with a marker that keeps the destination readable.</p>
     *
     * <p>Everything else in a destination is application routing metadata — the same class of information
     * as an HTTP path, which BootUI already shows — and is preserved so the panel stays useful.</p>
     */
    static String displayDestination(String destination, ValueExposure exposure) {
        String path = displayPath(destination, exposure);
        if (path == null) {
            return null;
        }
        int suffix = path.lastIndexOf(USER_DESTINATION_SUFFIX);
        if (suffix <= 0 || suffix + USER_DESTINATION_SUFFIX.length() >= path.length()) {
            return path;
        }
        return path.substring(0, suffix) + USER_DESTINATION_SUFFIX + "{session}";
    }

    /**
     * Transport addresses are shown as-is except under {@link ValueExposure#METADATA_ONLY}, where they are
     * withheld like every other value.
     */
    static String displayAddress(String address, ValueExposure exposure) {
        return exposure == ValueExposure.METADATA_ONLY ? null : address;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
