package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.mysql.MySqlInsightService;
import io.github.jdubois.bootui.engine.mysql.MySqlRowLimits;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;

class MySqlControllerTests {

    private static final ExposurePolicy MASKED = new ExposurePolicy() {
        @Override
        public ValueExposure valueExposure() {
            return ValueExposure.MASKED;
        }

        @Override
        public boolean maskSecrets() {
            return true;
        }
    };

    private static MySqlController controller(AtomicInteger discoveries) {
        return new MySqlController(MySqlInsightService.using(
                () -> {
                    discoveries.incrementAndGet();
                    return new DatabaseAdvisorDataSourceDiscovery(List.of(), List.of());
                },
                MASKED,
                Clock.systemUTC(),
                MySqlRowLimits.defaults()));
    }

    @Test
    void mvcReadsOnlyOnPostAndSharesTheEngineCache() throws Exception {
        AtomicInteger discoveries = new AtomicInteger();
        MockMvc mvc = standaloneSetup(controller(discoveries)).build();

        mvc.perform(get("/bootui/api/mysql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_READ"))
                .andExpect(jsonPath("$.dataSources").isArray());
        assertThat(discoveries).hasValue(0);
        mvc.perform(post("/bootui/api/mysql/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(get("/bootui/api/mysql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(discoveries).hasValue(1);
    }

    @Test
    void theSameBindingRunsUnderWebFlux() {
        AtomicInteger discoveries = new AtomicInteger();
        WebTestClient client =
                WebTestClient.bindToController(controller(discoveries)).build();

        client.get()
                .uri("/bootui/api/mysql")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("NOT_READ");
        assertThat(discoveries).hasValue(0);
        client.post()
                .uri("/bootui/api/mysql/read")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("DISABLED");
        client.get()
                .uri("/bootui/api/mysql")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("DISABLED");
        assertThat(discoveries).hasValue(1);
    }
}
