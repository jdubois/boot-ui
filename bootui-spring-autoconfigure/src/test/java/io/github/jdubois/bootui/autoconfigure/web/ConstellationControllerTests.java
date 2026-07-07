package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.constellation.ConstellationService;
import io.github.jdubois.bootui.engine.constellation.PeerSnapshot;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class ConstellationControllerTests {

    @Test
    void constellationIsDisabledByDefault() throws Exception {
        MockMvc mvc = standaloneSetup(new ConstellationController(new BootUiProperties())).build();

        mvc.perform(get("/bootui/api/constellation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.peers.length()").value(0));
    }

    @Test
    void constellationStaysDisabledWithPeersConfiguredButPanelOff() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getConstellation().setPeers(List.of("http://localhost:8081"));
        // enabled stays false (the default)

        MockMvc mvc = standaloneSetup(new ConstellationController(properties)).build();

        mvc.perform(get("/bootui/api/constellation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.peers.length()").value(0));
    }

    @Test
    void constellationReturnsEveryConfiguredPeerWhenEnabled() throws Exception {
        ConstellationService service = ConstellationService.using(
                List.of("http://localhost:8081"),
                Duration.ofSeconds(1),
                (url, timeout) ->
                        new PeerSnapshot(url, true, "orders-service", "spring-boot", "4.1.0", "17", List.of("dev"), null));

        MockMvc mvc = standaloneSetup(new ConstellationController(service)).build();

        mvc.perform(get("/bootui/api/constellation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.peers.length()").value(1))
                .andExpect(jsonPath("$.peers[0].url").value("http://localhost:8081"))
                .andExpect(jsonPath("$.peers[0].reachable").value(true))
                .andExpect(jsonPath("$.peers[0].applicationName").value("orders-service"))
                .andExpect(jsonPath("$.peers[0].platform").value("spring-boot"));
    }
}
