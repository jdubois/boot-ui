package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.web.HttpProbeController;
import io.github.jdubois.bootui.core.dto.HttpProbeRequest;
import io.github.jdubois.bootui.core.dto.HttpProbeResponse;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Reactive (WebFlux) binding checks for the shared {@code HttpProbeController}. The reactive adapter
 * reuses the same annotated controller as Spring MVC, so this pins that a probe result and an inbound
 * limit violation are projected identically on both Spring stacks: a probe outcome (including a probe
 * that failed to connect) is an HTTP 200 DTO, while input rejected by the engine's
 * {@code HttpProbeLimits} is the canonical HTTP 400 with a {@code {"error": ...}} body.
 */
class ReactiveHttpProbeControllerTests {

    private static WebTestClient client(HttpProbeService service) {
        return WebTestClient.bindToController(new HttpProbeController(service)).build();
    }

    @Test
    void probeResultIsProjectedAsJson() {
        HttpProbeService service = mock(HttpProbeService.class);
        when(service.probe(any(HttpProbeRequest.class)))
                .thenReturn(new HttpProbeResponse(
                        200, "OK", Map.of("content-type", "application/json"), "{\"ok\":true}", 7L, null, false));

        client(service)
                .post()
                .uri("/bootui/api/http-probe")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new HttpProbeRequest("GET", "/actuator/health", null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(200)
                .jsonPath("$.body")
                .isEqualTo("{\"ok\":true}")
                .jsonPath("$.headers['content-type']")
                .isEqualTo("application/json");
    }

    @Test
    void inputThatExceedsAProbeLimitIsRejectedWithTheCanonicalBadRequestBody() {
        HttpProbeService service = mock(HttpProbeService.class);
        when(service.probe(any(HttpProbeRequest.class)))
                .thenThrow(
                        new IllegalArgumentException("HTTP Probe request exceeds the maximum of 50 request headers"));

        client(service)
                .post()
                .uri("/bootui/api/http-probe")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new HttpProbeRequest("GET", "/", null, Map.of("X-One", "1")))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("HTTP Probe request exceeds the maximum of 50 request headers");
    }

    @Test
    void probeFailureRemainsATwoHundredOutcome() {
        HttpProbeService service = mock(HttpProbeService.class);
        when(service.probe(any(HttpProbeRequest.class)))
                .thenReturn(new HttpProbeResponse(0, "Error", Map.of(), null, 3L, "Connection refused", false));

        client(service)
                .post()
                .uri("/bootui/api/http-probe")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new HttpProbeRequest("GET", "/", null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("Connection refused");
    }
}
