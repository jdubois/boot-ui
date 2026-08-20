package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.HttpClientDto;
import io.github.jdubois.bootui.core.dto.HttpClientRegistryReport;
import io.github.jdubois.bootui.core.dto.HttpClientSettingDto;
import io.github.jdubois.bootui.engine.httpclient.HttpClientRegistryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin wiring test for {@link HttpClientsController}: it must delegate {@code GET /bootui/api/http-clients}
 * to the engine and serialize the report verbatim, including the explicit unavailable shape, so the browser
 * never has to distinguish a missing panel from a 404.
 */
class HttpClientsControllerTests {

    @Test
    void delegatesToServiceAndSerializesReport() throws Exception {
        HttpClientRegistryService service = mock(HttpClientRegistryService.class);
        when(service.report())
                .thenReturn(new HttpClientRegistryReport(
                        true,
                        null,
                        1,
                        "MASKED",
                        false,
                        "REST Client trace is not available on this runtime.",
                        List.of(new HttpClientDto(
                                "http_interface:orders",
                                "orders",
                                "HTTP_INTERFACE",
                                "HTTP Interface",
                                "Spring HTTP Interface",
                                "com.example.OrdersClient",
                                "orders",
                                "https://orders.example.com",
                                "https://orders.example.com",
                                "RESOLVED",
                                "CLIENT",
                                "spring.http.serviceclient.orders.base-url",
                                List.of(new HttpClientSettingDto(
                                        "TIMEOUT",
                                        "Connect timeout",
                                        "2s",
                                        "CLIENT",
                                        "spring.http.serviceclient.orders.connect-timeout")),
                                List.of(),
                                "UNAVAILABLE")),
                        List.of()));

        MockMvc mvc = standaloneSetup(new HttpClientsController(service)).build();

        mvc.perform(get("/bootui/api/http-clients").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.valueExposure").value("MASKED"))
                .andExpect(jsonPath("$.observedCallsAvailable").value(false))
                .andExpect(jsonPath("$.clients[0].id").value("http_interface:orders"))
                .andExpect(jsonPath("$.clients[0].kindLabel").value("HTTP Interface"))
                .andExpect(jsonPath("$.clients[0].baseUrlStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.clients[0].baseUrlSource").value("spring.http.serviceclient.orders.base-url"))
                .andExpect(jsonPath("$.clients[0].settings[0].provenance").value("CLIENT"))
                .andExpect(jsonPath("$.clients[0].observedCallsStatus").value("UNAVAILABLE"));
    }

    @Test
    void servesAnExplicitUnavailableReportInsteadOfFailing() throws Exception {
        HttpClientRegistryService service = mock(HttpClientRegistryService.class);
        when(service.report())
                .thenReturn(HttpClientRegistryReport.unavailable("No declarative HTTP client found.", "MASKED"));

        MockMvc mvc = standaloneSetup(new HttpClientsController(service)).build();

        mvc.perform(get("/bootui/api/http-clients").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("No declarative HTTP client found."))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.clients").isEmpty());
    }
}
