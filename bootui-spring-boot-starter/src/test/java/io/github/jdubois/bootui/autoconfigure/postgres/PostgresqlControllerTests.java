package io.github.jdubois.bootui.autoconfigure.postgres;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.postgres.PostgresInsightService;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for {@link PostgresqlController}. The read logic lives in the engine
 * {@link PostgresInsightService} (covered in {@code bootui-engine}), so here we only assert that {@code GET}
 * returns the cached "not read" report, that {@code POST /read} performs the read and refreshes the cache,
 * and that {@code GET} then serves the last read report. The service runs against an empty datasource
 * discovery, so the read stays entirely in-process (no database, no Testcontainers) and reports
 * {@code DISABLED}.
 */
class PostgresqlControllerTests {

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

    private static PostgresInsightService service() {
        return PostgresInsightService.using(
                () -> new DatabaseAdvisorDataSourceDiscovery(List.of(), List.of()), MASKED, Clock.systemUTC());
    }

    @Test
    void getReturnsTheNotReadReportBeforeAnyRead() throws Exception {
        MockMvc mvc = standaloneSetup(new PostgresqlController(service())).build();

        mvc.perform(get("/bootui/api/postgresql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_READ"));
    }

    @Test
    void readPerformsTheReadAndReturnsAReport() throws Exception {
        MockMvc mvc = standaloneSetup(new PostgresqlController(service())).build();

        mvc.perform(post("/bootui/api/postgresql/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void getReturnsTheLastReadReportAfterARead() throws Exception {
        MockMvc mvc = standaloneSetup(new PostgresqlController(service())).build();

        mvc.perform(post("/bootui/api/postgresql/read")).andExpect(status().isOk());
        mvc.perform(get("/bootui/api/postgresql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }
}
