package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.HttpProbeRequest;
import io.github.jdubois.bootui.core.dto.HttpProbeResponse;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Controller-level tests for {@link HttpProbeController}.
 *
 * <p>The probe logic lives in the framework-neutral {@code bootui-engine}
 * {@link HttpProbeService} (covered by its own behavior tests there); these tests mock that service
 * and verify only the Spring MVC binding — request mapping, {@code @RequestBody} deserialization, and
 * JSON projection of the returned {@link HttpProbeResponse}.</p>
 */
class HttpProbeControllerTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void postBindsRequestBodyAndProjectsResponse() throws Exception {
        HttpProbeService service = mock(HttpProbeService.class);
        when(service.probe(any(HttpProbeRequest.class)))
                .thenReturn(new HttpProbeResponse(
                        200, "OK", Map.of("content-type", "application/json"), "{\"ok\":true}", 12L, null));
        MockMvc mvc = standaloneSetup(new HttpProbeController(service)).build();

        mvc.perform(post("/bootui/api/http-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                mapper.writeValueAsString(new HttpProbeRequest("GET", "/actuator/health", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.statusText").value("OK"))
                .andExpect(jsonPath("$.body").value("{\"ok\":true}"))
                .andExpect(jsonPath("$.durationMs").value(12))
                .andExpect(jsonPath("$.headers['content-type']").value("application/json"));
    }

    @Test
    void errorDtoFromServiceIsReturnedAsHttp200() throws Exception {
        HttpProbeService service = mock(HttpProbeService.class);
        when(service.probe(any(HttpProbeRequest.class)))
                .thenReturn(new HttpProbeResponse(0, "Error", Map.of(), null, 3L, "Connection refused"));
        MockMvc mvc = standaloneSetup(new HttpProbeController(service)).build();

        mvc.perform(post("/bootui/api/http-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new HttpProbeRequest("GET", "/", null, null))))
                // the controller always returns HTTP 200; failures are carried in the DTO
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.statusText").value("Error"))
                .andExpect(jsonPath("$.error").value("Connection refused"));
    }
}
