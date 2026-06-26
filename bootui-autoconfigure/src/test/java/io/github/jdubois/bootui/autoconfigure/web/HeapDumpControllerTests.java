package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.HeapDumpCaptureStatusDto;
import io.github.jdubois.bootui.core.dto.HeapDumpFileDto;
import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link HeapDumpController}.
 *
 * <p>The Heap Dump panel's logic lives in the framework-neutral {@code bootui-engine}
 * {@link HeapDumpService} (covered by its own tests there); these tests mock that service and verify
 * only the Spring MVC binding — request mapping, parameter binding, JSON projection, and the
 * controller-owned raw-download gating ({@code rawDownloadAllowed()} plus {@code resolveExisting()}).</p>
 */
class HeapDumpControllerTests {

    private MockMvc mvc(HeapDumpService service) {
        return standaloneSetup(new HeapDumpController(service)).build();
    }

    private static HeapDumpReport report(
            String captureStatus, List<HeapDumpFileDto> dumps, List<HeapClassHistogramEntryDto> topClasses) {
        return new HeapDumpReport(
                true,
                true,
                false,
                ".bootui/heap-dumps",
                5,
                dumps.size(),
                0L,
                0L,
                new HeapDumpCaptureStatusDto(captureStatus, null, null),
                dumps,
                0L,
                0L,
                topClasses);
    }

    @Test
    void reportWithFilterReturnsFilteredClasses() throws Exception {
        HeapDumpService service = mock(HeapDumpService.class);
        when(service.report("java.lang", ""))
                .thenReturn(report(
                        "ANALYZED",
                        List.of(),
                        List.of(new HeapClassHistogramEntryDto(2, "java.lang.String", 5, 24000))));

        mvc(service)
                .perform(get("/bootui/api/heap-dump").param("filter", "java.lang"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topClasses.length()").value(1))
                .andExpect(jsonPath("$.topClasses[0].className").value("java.lang.String"));
    }

    @Test
    void reportReturnsState() throws Exception {
        HeapDumpService service = mock(HeapDumpService.class);
        when(service.report("", "")).thenReturn(report("NOT_CAPTURED", List.of(), List.of()));

        mvc(service)
                .perform(get("/bootui/api/heap-dump"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotspotAvailable").value(true))
                .andExpect(jsonPath("$.dumpCount").value(0));
    }

    @Test
    void captureWritesDump() throws Exception {
        HeapDumpService service = mock(HeapDumpService.class);
        when(service.capture(true))
                .thenReturn(report(
                        "CAPTURED",
                        List.of(new HeapDumpFileDto("app-heap-20260531-050000-live.hprof", 10, 1L, true)),
                        List.of()));

        mvc(service)
                .perform(post("/bootui/api/heap-dump/capture").param("live", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capture.status").value("CAPTURED"))
                .andExpect(jsonPath("$.dumps.length()").value(1));
    }

    @Test
    void analyzeReturnsHistogram() throws Exception {
        HeapDumpService service = mock(HeapDumpService.class);
        when(service.analyze())
                .thenReturn(report(
                        "ANALYZED", List.of(), List.of(new HeapClassHistogramEntryDto(1, "byte[]", 1000, 80000))));

        mvc(service)
                .perform(post("/bootui/api/heap-dump/analyze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capture.status").value("ANALYZED"))
                .andExpect(jsonPath("$.topClasses.length()").value(1));
    }

    @Test
    void downloadReturns404WhenRawDownloadDisabled() throws Exception {
        HeapDumpService service = mock(HeapDumpService.class);
        when(service.rawDownloadAllowed()).thenReturn(false);

        mvc(service)
                .perform(get("/bootui/api/heap-dump/download").param("name", "app-heap.hprof"))
                .andExpect(status().isNotFound());
        verify(service, never()).resolveExisting(any());
    }

    @Test
    void downloadStreamsDumpWhenEnabled(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app-heap-20260531-050000-live.hprof");
        Files.writeString(file, "fake-hprof");
        HeapDumpService service = mock(HeapDumpService.class);
        when(service.rawDownloadAllowed()).thenReturn(true);
        when(service.resolveExisting(file.getFileName().toString())).thenReturn(file);

        mvc(service)
                .perform(get("/bootui/api/heap-dump/download")
                        .param("name", file.getFileName().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(result -> {
                    String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
                    if (disposition == null
                            || !disposition.contains(file.getFileName().toString())) {
                        throw new AssertionError("Expected attachment disposition for " + file.getFileName());
                    }
                });
    }

    @Test
    void downloadRejectsUnsafeName() throws Exception {
        HeapDumpService service = mock(HeapDumpService.class);
        when(service.rawDownloadAllowed()).thenReturn(true);
        when(service.resolveExisting("../escape.hprof")).thenReturn(null);

        mvc(service)
                .perform(get("/bootui/api/heap-dump/download").param("name", "../escape.hprof"))
                .andExpect(status().isNotFound());
    }
}
