package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.ScheduledReport;
import io.github.jdubois.bootui.core.dto.ScheduledTaskDto;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTasksService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin wiring test for {@link ScheduledController}: it must delegate {@code GET /bootui/api/scheduled} to the
 * engine {@link ScheduledTasksService} and serialize its {@link ScheduledReport} verbatim. The trigger
 * mapping and self-data filtering are covered by {@code SpringScheduledTaskProviderTests}, and the sort /
 * {@code schedulingPresent} / {@code total} wrapping by {@code ScheduledTasksServiceTests}.
 */
class ScheduledControllerTests {

    @Test
    void delegatesToServiceAndSerializesReport() throws Exception {
        ScheduledTasksService service = mock(ScheduledTasksService.class);
        when(service.report())
                .thenReturn(new ScheduledReport(
                        true,
                        1,
                        List.of(new ScheduledTaskDto("com.example.MyJob#run", "CRON", "0 0/5 * * * ?", null, null))));

        MockMvc mvc = standaloneSetup(new ScheduledController(service)).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulingPresent").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.tasks[0].triggerType").value("CRON"))
                .andExpect(jsonPath("$.tasks[0].expression").value("0 0/5 * * * ?"))
                .andExpect(jsonPath("$.tasks[0].runnable").value("com.example.MyJob#run"));
    }

    @Test
    void serializesAbsentReport() throws Exception {
        ScheduledTasksService service = mock(ScheduledTasksService.class);
        when(service.report()).thenReturn(new ScheduledReport(false, 0, List.of()));

        MockMvc mvc = standaloneSetup(new ScheduledController(service)).build();

        mvc.perform(get("/bootui/api/scheduled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulingPresent").value(false))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.tasks").isEmpty());
    }
}
