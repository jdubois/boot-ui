package io.github.jdubois.bootui.quarkus.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkusWebSocketOriginTests {
    @Test
    @SuppressWarnings("unchecked")
    void capturedEndpointsDoNotTreatEmptyOriginListsOrHttpCorsAsPolicyEvidence() {
        Instance<QuarkusWebSockets> captured = mock(Instance.class);
        when(captured.isResolvable()).thenReturn(true);
        when(captured.get())
                .thenReturn(new QuarkusWebSockets(
                        List.of(new RawWebSocketEndpoint("endpoint:/ws", "/ws", "example.Socket", null, List.of()))));
        var provider = new QuarkusWebSocketMetadataProvider(captured);
        var observation = provider.originObservation();
        assertThat(observation.complete()).isFalse();
        assertThat(observation.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/ws");
            assertThat(endpoint.policyKnown()).isFalse();
            assertThat(endpoint.wildcard()).isFalse();
        });
    }

    @Test
    void absentExtensionAndEmptyInventoryAreConfirmedAbsence() {
        assertThat(new QuarkusWebSocketMetadataProvider(null)
                        .originObservation()
                        .complete())
                .isTrue();
        assertThat(new QuarkusWebSocketMetadataProvider(null)
                        .originObservation()
                        .endpoints())
                .isEmpty();
    }
}
