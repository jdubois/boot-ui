package io.github.jdubois.bootui.engine.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.WebSocketEndpointDto;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.core.dto.WebSocketSessionDto;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder.Direction;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder.FrameType;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.WebSocketCallbackSnapshot;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketMetadataProvider;
import io.github.jdubois.bootui.spi.WebSocketSessionProvider;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import io.github.jdubois.bootui.spi.WebSocketSubscriptionSnapshot;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebSocketServiceTests {

    private static ExposurePolicy exposurePolicy(ValueExposure exposure) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
    }

    private static WebSocketEndpointSnapshot endpoint(String id, String path, boolean captureInstalled) {
        return new WebSocketEndpointSnapshot(
                id,
                path,
                "STOMP",
                "com.example.Handler",
                List.of("v12.stomp"),
                true,
                List.of("http://localhost:8080"),
                List.of(),
                List.of(new WebSocketCallbackSnapshot(
                        "MESSAGE_MAPPING", "/app/chat", "com.example.ChatController", "chat", null)),
                null,
                captureInstalled);
    }

    private static WebSocketTopologySnapshot topology(List<WebSocketEndpointSnapshot> endpoints) {
        return new WebSocketTopologySnapshot(
                "spring-websocket", endpoints, List.of("/topic"), List.of("/app"), "/user/", true, null);
    }

    private static WebSocketService service(
            WebSocketTopologySnapshot topology,
            WebSocketSessionProvider sessionProvider,
            WebSocketActivityRecorder recorder,
            WebSocketSettings settings,
            ValueExposure exposure) {
        return new WebSocketService(
                topology == null ? null : () -> topology,
                sessionProvider,
                recorder,
                settings,
                exposurePolicy(exposure));
    }

    @Test
    void reportsUnavailableWhenNoMetadataProviderIsWired() {
        WebSocketReport report = service(
                        null,
                        null,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo(WebSocketService.NOT_CONFIGURED);
        assertThat(report.endpoints()).isEmpty();
        assertThat(report.stats().endpoints()).isZero();
    }

    @Test
    void reportsUnavailableRatherThanPropagatingAProviderFailure() {
        WebSocketMetadataProvider failing = () -> {
            throw new IllegalStateException("registry unavailable");
        };
        WebSocketService service = new WebSocketService(
                failing,
                null,
                new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                WebSocketSettings.defaults(),
                exposurePolicy(ValueExposure.MASKED));

        assertThat(service.report().available()).isFalse();
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void toleratesAFailingSessionProviderWithoutLosingTheTopology() {
        WebSocketSessionProvider failing = new WebSocketSessionProvider() {
            @Override
            public List<WebSocketSessionSnapshot> sessions() {
                throw new IllegalStateException("registry unavailable");
            }

            @Override
            public List<WebSocketSubscriptionSnapshot> subscriptions() {
                throw new IllegalStateException("registry unavailable");
            }
        };

        WebSocketReport report = service(
                        topology(List.of(endpoint("stomp:/ws", "/ws", true))),
                        failing,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.available()).isTrue();
        assertThat(report.endpoints()).hasSize(1);
        assertThat(report.sessions()).isEmpty();
    }

    @Test
    void countsOpenSessionsPerEndpointAndOrdersSessionsOpenFirstThenNewest() {
        WebSocketSessionProvider sessions = () -> List.of(
                new WebSocketSessionSnapshot(
                        "s-closed", "stomp:/ws", "/ws", false, 3_000, null, "127.0.0.1", null, 1000),
                new WebSocketSessionSnapshot(
                        "s-old", "stomp:/ws", "/ws", true, 1_000, "v12.stomp", "127.0.0.1", null, null),
                new WebSocketSessionSnapshot(
                        "s-new", "stomp:/ws", "/ws", true, 2_000, "v12.stomp", "127.0.0.1", null, null));

        WebSocketReport report = service(
                        topology(List.of(endpoint("stomp:/ws", "/ws", true))),
                        sessions,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.endpoints().get(0).openSessions()).isEqualTo(2);
        assertThat(report.sessions()).extracting(WebSocketSessionDto::open).containsExactly(true, true, false);
        assertThat(report.sessions().get(0).openedAt()).isEqualTo(2_000);
        assertThat(report.stats().openSessions()).isEqualTo(2);
        assertThat(report.stats().closedSessions()).isEqualTo(1);
    }

    @Test
    void neverSerializesARawSessionOrSubscriptionIdentifier() {
        WebSocketSessionProvider sessions = new WebSocketSessionProvider() {
            @Override
            public List<WebSocketSessionSnapshot> sessions() {
                return List.of(new WebSocketSessionSnapshot(
                        "raw-session", "stomp:/ws", "/ws", true, 1_000, null, "127.0.0.1", null, null));
            }

            @Override
            public List<WebSocketSubscriptionSnapshot> subscriptions() {
                return List.of(new WebSocketSubscriptionSnapshot(
                        "raw-sub", "raw-session", "stomp:/ws", "/topic/orders", 1_500));
            }
        };
        WebSocketActivityRecorder recorder = new WebSocketActivityRecorder(WebSocketSettings.defaults());
        recorder.recordFrame(
                "stomp:/ws", "raw-session", Direction.INBOUND, FrameType.TEXT, "/topic/orders", 10L, null, true, null);

        WebSocketReport report = service(
                        topology(List.of(endpoint("stomp:/ws", "/ws", true))),
                        sessions,
                        recorder,
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        String opaque = WebSocketSessionIds.opaque("raw-session");
        assertThat(report.sessions().get(0).id()).isEqualTo(opaque);
        assertThat(report.subscriptions().get(0).sessionId()).isEqualTo(opaque);
        assertThat(report.subscriptions().get(0).id()).isEqualTo(WebSocketSessionIds.opaque("raw-sub"));
        assertThat(report.activity().get(0).sessionId()).isEqualTo(opaque);
        assertThat(report.toString()).doesNotContain("raw-session").doesNotContain("raw-sub");
    }

    @Test
    void joinsPerSessionCountersOntoTheSessionRow() {
        WebSocketSessionProvider sessions = () -> List.of(
                new WebSocketSessionSnapshot("s1", "stomp:/ws", "/ws", true, 1_000, null, "127.0.0.1", null, null));
        WebSocketActivityRecorder recorder = new WebSocketActivityRecorder(WebSocketSettings.defaults());
        recorder.recordFrame("stomp:/ws", "s1", Direction.INBOUND, FrameType.TEXT, null, 100L, null, true, null);
        recorder.recordFrame("stomp:/ws", "s1", Direction.OUTBOUND, FrameType.TEXT, null, 25L, null, true, null);

        WebSocketSessionDto session = service(
                        topology(List.of(endpoint("stomp:/ws", "/ws", true))),
                        sessions,
                        recorder,
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report()
                .sessions()
                .get(0);

        assertThat(session.messagesIn()).isEqualTo(1);
        assertThat(session.messagesOut()).isEqualTo(1);
        assertThat(session.bytesIn()).isEqualTo(100);
        assertThat(session.bytesOut()).isEqualTo(25);
        assertThat(session.lastActivityAt()).isNotNull();
    }

    @Test
    void stripsAHandshakeQueryStringFromEveryDisplayedPath() {
        WebSocketSessionProvider sessions = () -> List.of(new WebSocketSessionSnapshot(
                "s1", "stomp:/ws", "/ws?access_token=super-secret", true, 1_000, null, "127.0.0.1", null, null));

        WebSocketReport report = service(
                        topology(List.of(endpoint("stomp:/ws", "/ws?token=secret", true))),
                        sessions,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.endpoints().get(0).path()).isEqualTo("/ws");
        assertThat(report.sessions().get(0).path()).isEqualTo("/ws");
        assertThat(report.toString()).doesNotContain("super-secret").doesNotContain("token=secret");
    }

    @Test
    void withholdsPathsAndAddressesUnderMetadataOnlyExposure() {
        WebSocketSessionProvider sessions = () -> List.of(new WebSocketSessionSnapshot(
                "s1", "stomp:/ws", "/ws", true, 1_000, null, "10.0.0.5", "10.0.0.1", null));

        WebSocketReport report = service(
                        topology(List.of(endpoint("stomp:/ws", "/ws", true))),
                        sessions,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.METADATA_ONLY)
                .report();

        WebSocketEndpointDto endpoint = report.endpoints().get(0);
        assertThat(endpoint.path()).isNull();
        assertThat(endpoint.callbacks().get(0).destination()).isNull();
        assertThat(endpoint.id()).isEqualTo("stomp:/ws");
        assertThat(report.sessions().get(0).path()).isNull();
        assertThat(report.sessions().get(0).remoteAddress()).isNull();
        assertThat(report.sessions().get(0).localAddress()).isNull();
    }

    @Test
    void masksValuesWhenNoExposurePolicyIsWired() {
        WebSocketService service = new WebSocketService(
                () -> topology(List.of(endpoint("stomp:/ws", "/ws", true))),
                null,
                new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                WebSocketSettings.defaults(),
                null);

        assertThat(service.report().endpoints().get(0).path()).isEqualTo("/ws");
    }

    @Test
    void truncatesEachCollectionIndependentlyAndSaysSoInWarnings() {
        List<WebSocketEndpointSnapshot> endpoints = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            endpoints.add(endpoint("e" + i, "/ws" + i, true));
        }
        List<WebSocketSessionSnapshot> sessions = new ArrayList<>();
        List<WebSocketSubscriptionSnapshot> subscriptions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sessions.add(new WebSocketSessionSnapshot("s" + i, "e0", "/ws", true, i, null, null, null, null));
            subscriptions.add(new WebSocketSubscriptionSnapshot("sub" + i, "s" + i, "e0", "/topic/" + i, i));
        }
        WebSocketSessionProvider provider = new WebSocketSessionProvider() {
            @Override
            public List<WebSocketSessionSnapshot> sessions() {
                return sessions;
            }

            @Override
            public List<WebSocketSubscriptionSnapshot> subscriptions() {
                return subscriptions;
            }
        };

        WebSocketReport report = service(
                        topology(endpoints),
                        provider,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        new WebSocketSettings(true, true, 2, 3, 4, 500, 2_000),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.endpoints()).hasSize(2);
        assertThat(report.sessions()).hasSize(3);
        assertThat(report.subscriptions()).hasSize(4);
        assertThat(report.endpointsTruncated()).isTrue();
        assertThat(report.sessionsTruncated()).isTrue();
        assertThat(report.subscriptionsTruncated()).isTrue();
        assertThat(report.stats().endpoints()).isEqualTo(5);
        assertThat(report.warnings())
                .anyMatch(warning -> warning.contains("Endpoint list truncated to 2"))
                .anyMatch(warning -> warning.contains("Session list truncated to 3"))
                .anyMatch(warning -> warning.contains("Subscription list truncated to 4"));
    }

    @Test
    void warnsWhenCaptureIsDisabledOrPaused() {
        WebSocketReport disabled = service(
                        topology(List.of()),
                        null,
                        new WebSocketActivityRecorder(new WebSocketSettings(false, false, 200, 200, 500, 500, 2_000)),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();
        assertThat(disabled.warnings()).anyMatch(warning -> warning.contains("bootui.websockets.enabled=false"));
        assertThat(disabled.warnings())
                .as("every adapter reports an endpointless application as an unavailable panel, so the panel "
                        + "never renders a redundant 'no endpoint declared' warning next to an empty list")
                .noneMatch(warning -> warning.contains("endpoint is declared"));

        WebSocketActivityRecorder paused = new WebSocketActivityRecorder(WebSocketSettings.defaults());
        paused.setCapturing(false);
        WebSocketReport pausedReport = service(
                        topology(List.of(endpoint("e", "/ws", true))),
                        null,
                        paused,
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();
        assertThat(pausedReport.capturing()).isFalse();
        assertThat(pausedReport.warnings()).anyMatch(warning -> warning.contains("capture is paused"));
    }

    @Test
    void reportsTheFrameCaptureReasonOnlyWhenCaptureIsUnsupported() {
        WebSocketTopologySnapshot unsupported = new WebSocketTopologySnapshot(
                "quarkus-websockets-next",
                List.of(endpoint("e", "/ws", false)),
                List.of(),
                List.of(),
                null,
                false,
                "Quarkus WebSockets Next exposes no message interception SPI.");

        WebSocketReport report = service(
                        unsupported,
                        null,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.frameCaptureSupported()).isFalse();
        assertThat(report.frameCaptureUnavailableReason())
                .isEqualTo("Quarkus WebSockets Next exposes no message interception SPI.");
        assertThat(report.endpoints().get(0).captureInstalled()).isFalse();
        assertThat(report.framework()).isEqualTo("quarkus-websockets-next");

        WebSocketReport supported = service(
                        topology(List.of(endpoint("e", "/ws", true))),
                        null,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();
        assertThat(supported.frameCaptureSupported()).isTrue();
        assertThat(supported.frameCaptureUnavailableReason()).isNull();
    }

    @Test
    void neverReportsCapturingOnAStackWithoutAFrameCaptureSeam() {
        WebSocketTopologySnapshot unsupported = new WebSocketTopologySnapshot(
                "quarkus-websockets-next",
                List.of(endpoint("e", "/ws", false)),
                List.of(),
                List.of(),
                null,
                false,
                "Quarkus WebSockets Next exposes no message interception SPI.");
        WebSocketActivityRecorder recorder = new WebSocketActivityRecorder(WebSocketSettings.defaults());
        recorder.setCapturing(true);

        WebSocketReport report = service(
                        unsupported, null, recorder, WebSocketSettings.defaults(), ValueExposure.MASKED)
                .report();

        assertThat(report.capturing())
                .as("the toggle cannot claim frames are recorded on a stack that has no seam to record them")
                .isFalse();
    }

    @Test
    void ordersEndpointsByPathThenIdAndCarriesBrokerPrefixes() {
        WebSocketReport report = service(
                        topology(List.of(endpoint("b", "/zeta", true), endpoint("a", "/alpha", true))),
                        null,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.endpoints()).extracting(WebSocketEndpointDto::path).containsExactly("/alpha", "/zeta");
        assertThat(report.brokerPrefixes()).containsExactly("/topic");
        assertThat(report.applicationDestinationPrefixes()).containsExactly("/app");
        assertThat(report.userDestinationPrefix()).isEqualTo("/user/");
    }

    @Test
    void reportsSessionTrackingAsUnsupportedWhenNoStackProvidesLiveSessions() {
        WebSocketReport report = service(
                        topology(List.of(endpoint("ws:/ws", "/ws", false))),
                        null,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.sessionTrackingSupported())
                .as("a stack without a session seam must say so rather than render an empty list as 'none open'")
                .isFalse();
        assertThat(report.sessionTrackingUnavailableReason()).isEqualTo(WebSocketService.SESSION_TRACKING_UNAVAILABLE);
    }

    @Test
    void reportsSessionTrackingAsSupportedWhenASessionProviderIsWired() {
        WebSocketSessionProvider sessions = List::of;
        WebSocketReport report = service(
                        topology(List.of(endpoint("stomp:/ws", "/ws", true))),
                        sessions,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.sessionTrackingSupported()).isTrue();
        assertThat(report.sessionTrackingUnavailableReason()).isNull();
    }

    @Test
    void answersAvailabilityWithoutAssemblingAWholeReport() {
        List<String> reportBuilds = new ArrayList<>();
        WebSocketMetadataProvider counting = () -> {
            reportBuilds.add("topology");
            return topology(List.of(endpoint("stomp:/ws", "/ws", true)));
        };
        WebSocketService service = new WebSocketService(
                counting,
                null,
                new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                WebSocketSettings.defaults(),
                exposurePolicy(ValueExposure.MASKED));

        assertThat(service.isAvailable())
                .as("the panel manifest is fetched on every page load and must stay cheap")
                .isTrue();
        assertThat(reportBuilds).hasSize(1);
    }

    @Test
    void isNotAvailableWhenTheTopologyCannotBeResolved() {
        assertThat(service(
                                null,
                                null,
                                new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                                WebSocketSettings.defaults(),
                                ValueExposure.MASKED)
                        .isAvailable())
                .isFalse();
    }

    @Test
    void clearingDiscardsRetainedSessionsOnEveryStackNotJustTheActivityBuffer() {
        List<String> cleared = new ArrayList<>();
        WebSocketSessionProvider sessions = new WebSocketSessionProvider() {
            @Override
            public List<WebSocketSessionSnapshot> sessions() {
                return List.of();
            }

            @Override
            public void clearRetainedSessions() {
                cleared.add("sessions");
            }
        };
        WebSocketActivityRecorder recorder = new WebSocketActivityRecorder(WebSocketSettings.defaults());
        recorder.recordFrame("s1", "stomp:/ws", Direction.INBOUND, FrameType.TEXT, "/app/chat", 4L, null, true, null);
        WebSocketService service = service(
                topology(List.of(endpoint("stomp:/ws", "/ws", true))),
                sessions,
                recorder,
                WebSocketSettings.defaults(),
                ValueExposure.MASKED);

        service.clear();

        assertThat(service.report().activity()).isEmpty();
        assertThat(cleared)
                .as("closed-session history is part of what the user asked to clear, on every adapter")
                .containsExactly("sessions");
    }

    @Test
    void masksTheRawSessionIdSpringAppendsToAResolvedUserDestination() {
        WebSocketSessionProvider sessions = new WebSocketSessionProvider() {
            @Override
            public List<WebSocketSessionSnapshot> sessions() {
                return List.of(new WebSocketSessionSnapshot(
                        "abc123", "stomp:/ws", "/ws", true, 1_000, "v12.stomp", "127.0.0.1", null, null));
            }

            @Override
            public List<WebSocketSubscriptionSnapshot> subscriptions() {
                return List.of(new WebSocketSubscriptionSnapshot(
                        "sub-0", "abc123", "stomp:/ws", "/queue/notifications-userabc123", 1_000));
            }
        };
        WebSocketReport report = service(
                        topology(List.of(endpoint("stomp:/ws", "/ws", true))),
                        sessions,
                        new WebSocketActivityRecorder(WebSocketSettings.defaults()),
                        WebSocketSettings.defaults(),
                        ValueExposure.MASKED)
                .report();

        assertThat(report.subscriptions().get(0).destination())
                .as("the broker's user-destination suffix is a live session id, never a routing detail to publish")
                .isEqualTo("/queue/notifications-user{session}");
    }
}
