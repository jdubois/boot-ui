package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.BeanList;
import io.github.jdubois.bootui.core.dto.BeanGraphReport;
import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.github.jdubois.bootui.engine.beans.BeansService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for the neutral {@link BeansController}: it delegates {@code GET
 * /bootui/api/beans} to the engine {@link BeansService} (mocked here, house style) and serializes the
 * list. The mapping/self-filter/classification logic is covered by {@code SpringBeanProviderTests} and the
 * sort/filter/page logic by the engine {@code BeansServiceTests}.
 */
class BeansControllerTests {

    @Test
    void delegatesQueryClassificationOffsetAndLimitToTheEngineService() throws Exception {
        BeansService service = mock(BeansService.class);
        BeanList list = new BeanList(
                1,
                List.of(new BeanSummary(
                        "sampleBean", "com.example.Sample", "singleton", null, List.of(), List.of(), "APPLICATION")));
        when(service.beans(eq("sample"), eq("APPLICATION"), eq(5), eq(10))).thenReturn(list);

        MockMvc mvc = standaloneSetup(new BeansController(service)).build();

        mvc.perform(get("/bootui/api/beans")
                        .param("q", "sample")
                        .param("classification", "APPLICATION")
                        .param("offset", "5")
                        .param("limit", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.beans.length()").value(1))
                .andExpect(jsonPath("$.beans[0].name").value("sampleBean"));

        verify(service).beans("sample", "APPLICATION", 5, 10);
    }

    @Test
    void passesNullParamsThroughWhenAbsent() throws Exception {
        BeansService service = mock(BeansService.class);
        when(service.beans(null, null, null, null)).thenReturn(new BeanList(0, List.of()));

        MockMvc mvc = standaloneSetup(new BeansController(service)).build();

        mvc.perform(get("/bootui/api/beans").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.beans.length()").value(0));

        verify(service).beans(null, null, null, null);
    }

    @Test
    void delegatesGraphFocusAndLimitToTheEngineService() throws Exception {
        BeansService service = mock(BeansService.class);
        when(service.graph("sampleBean", 8)).thenReturn(BeanGraphReport.empty());

        MockMvc mvc = standaloneSetup(new BeansController(service)).build();

        mvc.perform(get("/bootui/api/beans/graph")
                        .param("focus", "sampleBean")
                        .param("limit", "8")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.dependencies.length()").value(0));

        verify(service).graph("sampleBean", 8);
    }
}
