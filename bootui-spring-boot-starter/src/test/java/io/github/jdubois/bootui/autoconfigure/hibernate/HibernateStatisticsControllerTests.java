package io.github.jdubois.bootui.autoconfigure.hibernate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.HibernateStatisticsDto;
import io.github.jdubois.bootui.core.dto.HibernateStatisticsReport;
import io.github.jdubois.bootui.engine.hibernate.HibernateStatisticsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for {@link HibernateStatisticsController}, the standalone Hibernate Statistics
 * runtime-monitoring panel (Database group). The statistics collection itself lives in the engine
 * {@link HibernateStatisticsService}; here we only assert the transport wiring.
 */
class HibernateStatisticsControllerTests {

    @Test
    void statisticsDelegatesToEngineServiceWhenAvailable() throws Exception {
        HibernateStatisticsService statisticsService = mock(HibernateStatisticsService.class);
        when(statisticsService.report())
                .thenReturn(new HibernateStatisticsReport(
                        true,
                        false,
                        null,
                        new HibernateStatisticsDto(
                                10,
                                8,
                                5,
                                3,
                                2,
                                2,
                                1,
                                1,
                                1,
                                1,
                                1,
                                0,
                                0,
                                0,
                                0,
                                0,
                                4,
                                12,
                                "select 1",
                                false,
                                0,
                                0,
                                0,
                                false,
                                0,
                                0,
                                0,
                                List.of())));

        MockMvc mvc = standaloneSetup(new HibernateStatisticsController(statisticsService))
                .build();

        mvc.perform(get("/bootui/api/hibernate-statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.statistics.sessionOpenCount").value(10));
    }

    @Test
    void statisticsReportsUnavailableWhenStatisticsDisabled() throws Exception {
        HibernateStatisticsService statisticsService = mock(HibernateStatisticsService.class);
        when(statisticsService.report())
                .thenReturn(new HibernateStatisticsReport(
                        false,
                        true,
                        "Hibernate statistics are disabled. Set hibernate.generate_statistics=true.",
                        null));

        MockMvc mvc = standaloneSetup(new HibernateStatisticsController(statisticsService))
                .build();

        mvc.perform(get("/bootui/api/hibernate-statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.statistics").doesNotExist());
    }

    @Test
    void enableDelegatesToEngineService() throws Exception {
        HibernateStatisticsService statisticsService = mock(HibernateStatisticsService.class);
        when(statisticsService.enable())
                .thenReturn(new HibernateStatisticsReport(
                        false,
                        true,
                        "Hibernate statistics are disabled. Set hibernate.generate_statistics=true.",
                        null));

        MockMvc mvc = standaloneSetup(new HibernateStatisticsController(statisticsService))
                .build();

        mvc.perform(post("/bootui/api/hibernate-statistics/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enableAvailable").value(true));
    }
}
