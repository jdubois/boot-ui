package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.KubernetesMemoryRecommendationDto;
import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryCalculationDto;
import io.github.jdubois.bootui.core.dto.MemoryPoolDto;
import io.github.jdubois.bootui.engine.memory.MemoryOffloadService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link LiveMemoryController} and {@link JvmTuningController}.
 *
 * <p>The report logic lives in the framework-neutral {@code bootui-engine}
 * {@link MemoryReportProvider} (covered by its own behavior tests there); these tests mock that
 * provider and verify only the Spring MVC binding — both routes map to it, pass their query
 * parameters through, and project the returned {@link LiveMemoryReport} as JSON.</p>
 */
class LiveMemoryControllerTests {

    private static LiveMemoryReport sampleReport() {
        MemoryPoolDto heap = new MemoryPoolDto("Heap", 1L, 2L, 4L, 25);
        MemoryPoolDto nonHeap = new MemoryPoolDto("Non-Heap", 1L, 2L, 4L, 25);
        MemoryCalculationDto calculation = new MemoryCalculationDto(
                512L,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                7,
                0,
                0,
                0,
                0,
                false,
                "spring.threads.virtual.enabled",
                "-Xmx",
                true,
                null);
        KubernetesMemoryRecommendationDto kubernetes = new KubernetesMemoryRecommendationDto(
                0, 0, 0, 0, null, "", "", "", "", "", "Depends on CPU", "", List.of(), "", 0, 0, "", false, true);
        return new LiveMemoryReport(heap, nonHeap, List.of(heap), List.of("-Xss"), "-Xmx", calculation, kubernetes);
    }

    private static MemoryReportProvider stubbedProvider() {
        MemoryReportProvider provider = mock(MemoryReportProvider.class);
        when(provider.buildReport(any(), any(), any(), any(), any())).thenReturn(sampleReport());
        return provider;
    }

    private static MemoryOffloadService offloadService(List<Object> singletons) {
        return new MemoryOffloadService(() -> singletons);
    }

    @Test
    void offloadRouteEmptiesTheSuppliedStoresAndProjectsTheReport() throws Exception {
        List<String> retained = new ArrayList<>(List.of("a", "b", "c"));
        MemoryOffloadable store = new MemoryOffloadable() {
            @Override
            public String offloadId() {
                return "test-store";
            }

            @Override
            public String offloadLabel() {
                return "Test store";
            }

            @Override
            public long offloadRetainedData() {
                int size = retained.size();
                retained.clear();
                return size;
            }
        };
        MockMvc mvc = standaloneSetup(new LiveMemoryController(
                        stubbedProvider(), offloadService(new ArrayList<>(List.of(store, "not a store")))))
                .build();

        mvc.perform(post("/bootui/api/live-memory/offload").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gcRequested").value(true))
                .andExpect(jsonPath("$.entriesCleared").value(3))
                .andExpect(jsonPath("$.stores.length()").value(1))
                .andExpect(jsonPath("$.stores[0].id").value("test-store"))
                .andExpect(jsonPath("$.stores[0].label").value("Test store"))
                .andExpect(jsonPath("$.stores[0].cleared").value(true))
                .andExpect(jsonPath("$.stores[0].entriesCleared").value(3))
                .andExpect(jsonPath("$.heapUsedBeforeBytes").isNumber())
                .andExpect(jsonPath("$.heapUsedAfterBytes").isNumber())
                .andExpect(jsonPath("$.reclaimedBytes").isNumber());
        assertThat(retained).isEmpty();
    }

    @Test
    void liveMemoryRouteProjectsReport() throws Exception {
        MockMvc mvc = standaloneSetup(new LiveMemoryController(stubbedProvider(), offloadService(new ArrayList<>())))
                .build();

        mvc.perform(get("/bootui/api/live-memory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heap.name").value("Heap"))
                .andExpect(jsonPath("$.nonHeap.name").value("Non-Heap"))
                .andExpect(jsonPath("$.calculation.threadCount").value(7))
                .andExpect(jsonPath("$.kubernetes.qosClass").value("Depends on CPU"));
    }

    @Test
    void liveMemoryRoutePassesQueryParamsToProvider() throws Exception {
        MemoryReportProvider provider = stubbedProvider();
        MockMvc mvc = standaloneSetup(new LiveMemoryController(provider, offloadService(new ArrayList<>())))
                .build();

        mvc.perform(get("/bootui/api/live-memory")
                        .param("totalMemoryMb", "512")
                        .param("threadCount", "50")
                        .param("headRoomPercent", "10")
                        .param("kubernetesBurstableEnabled", "true")
                        .param("kubernetesActuatorEnabled", "false")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(provider).buildReport(512L, 50, 10, true, false);
    }

    @Test
    void jvmTuningRouteProjectsReportAndPassesParams() throws Exception {
        MemoryReportProvider provider = stubbedProvider();
        MockMvc mvc = standaloneSetup(new JvmTuningController(provider)).build();

        mvc.perform(get("/bootui/api/jvm-tuning").param("totalMemoryMb", "512").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heap.name").value("Heap"))
                .andExpect(jsonPath("$.kubernetes.qosClass").value("Depends on CPU"));

        verify(provider).buildReport(512L, null, null, null, null);
    }
}
