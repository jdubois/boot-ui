package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

class HeapDumpControllerTests {

    private MockMvc mvc(HeapDumpService service, BootUiProperties.HeapDump config) {
        return standaloneSetup(new HeapDumpController(service, config)).build();
    }

    private HeapDumpService service(BootUiProperties.HeapDump config, Path dir) {
        return new HeapDumpService(
                config,
                dir,
                (file, live) -> Files.writeString(file, "fake-hprof"),
                () -> " num     #instances         #bytes  class name\n   1:   10   100  java.lang.String",
                Clock.systemUTC(),
                true);
    }

    @Test
    void reportReturnsState(@TempDir Path dir) throws Exception {
        BootUiProperties.HeapDump config = new BootUiProperties.HeapDump();
        mvc(service(config, dir), config)
                .perform(get("/bootui/api/heap-dump"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotspotAvailable").value(true))
                .andExpect(jsonPath("$.dumpCount").value(0));
    }

    @Test
    void captureWritesDump(@TempDir Path dir) throws Exception {
        BootUiProperties.HeapDump config = new BootUiProperties.HeapDump();
        mvc(service(config, dir), config)
                .perform(post("/bootui/api/heap-dump/capture").param("live", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capture.status").value("CAPTURED"))
                .andExpect(jsonPath("$.dumps.length()").value(1));
    }

    @Test
    void analyzeReturnsHistogram(@TempDir Path dir) throws Exception {
        BootUiProperties.HeapDump config = new BootUiProperties.HeapDump();
        mvc(service(config, dir), config)
                .perform(post("/bootui/api/heap-dump/analyze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capture.status").value("ANALYZED"))
                .andExpect(jsonPath("$.topClasses.length()").value(1));
    }

    @Test
    void downloadReturns404WhenRawDownloadDisabled(@TempDir Path dir) throws Exception {
        BootUiProperties.HeapDump config = new BootUiProperties.HeapDump();
        HeapDumpService service = service(config, dir);
        String name = service.capture(true).dumps().get(0).name();

        mvc(service, config)
                .perform(get("/bootui/api/heap-dump/download").param("name", name))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadStreamsDumpWhenEnabled(@TempDir Path dir) throws Exception {
        BootUiProperties.HeapDump config = new BootUiProperties.HeapDump();
        config.setAllowRawDownload(true);
        HeapDumpService service = service(config, dir);
        String name = service.capture(true).dumps().get(0).name();

        mvc(service, config)
                .perform(get("/bootui/api/heap-dump/download").param("name", name))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(result -> {
                    String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
                    if (disposition == null || !disposition.contains(name)) {
                        throw new AssertionError("Expected attachment disposition for " + name);
                    }
                });
    }

    @Test
    void downloadRejectsUnsafeName(@TempDir Path dir) throws Exception {
        BootUiProperties.HeapDump config = new BootUiProperties.HeapDump();
        config.setAllowRawDownload(true);
        mvc(service(config, dir), config)
                .perform(get("/bootui/api/heap-dump/download").param("name", "../escape.hprof"))
                .andExpect(status().isNotFound());
    }
}
