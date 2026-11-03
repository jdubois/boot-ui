package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.mcp.SpringMcpPanelPolicy;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.threads.ThreadAnalysisService;
import io.github.jdubois.bootui.engine.threads.ThreadCaptureSettings;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link ThreadDumpController}.
 */
class ThreadDumpControllerTests {

    private final MockMvc mvc = standaloneSetup(new ThreadDumpController(
                    new ThreadDumpService(new BootUiExposure(new BootUiProperties())),
                    mock(ThreadAnalysisService.class)))
            .build();

    @Test
    void threadsReturnsLiveSnapshot() throws Exception {
        mvc.perform(get("/bootui/api/threads").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.totalThreads").isNumber())
                .andExpect(jsonPath("$.threads").isArray())
                .andExpect(jsonPath("$.stateCounts").isArray())
                .andExpect(jsonPath("$.page").exists());
    }

    @Test
    void threadsHonorsPagingParameters() throws Exception {
        mvc.perform(get("/bootui/api/threads")
                        .param("offset", "0")
                        .param("limit", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threads.length()").value(1))
                .andExpect(jsonPath("$.page.limit").value(1));
    }

    @Test
    void downloadReturnsPlainTextAttachment() throws Exception {
        mvc.perform(post("/bootui/api/threads/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"thread-dump.txt\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BootUI thread dump")));
    }

    @Test
    void downloadReturnsNotFoundWhenUnavailable() throws Exception {
        MockMvc unavailable = standaloneSetup(new ThreadDumpController(
                        new ThreadDumpService(null, new BootUiExposure(new BootUiProperties())),
                        mock(ThreadAnalysisService.class)))
                .build();

        unavailable.perform(post("/bootui/api/threads/download")).andExpect(status().isNotFound());
    }

    @Test
    void capturesRemainStableAcrossReadsAndExportsAndClearedIdsNeverRecapture() throws Exception {
        var properties = new BootUiProperties();
        var exposure = new BootUiExposure(properties);
        var reader = new ThreadDumpService(exposure);
        try (var analysis = new ThreadAnalysisService(
                reader,
                ThreadCaptureSettings.defaults(),
                RunIdentity.start(),
                exposure,
                new SpringMcpPanelPolicy(properties),
                () -> false)) {
            var retained =
                    standaloneSetup(new ThreadDumpController(reader, analysis)).build();
            retained.perform(get("/bootui/api/threads/captures"))
                    .andExpect(jsonPath("$.captures.length()").value(0));
            retained.perform(post("/bootui/api/threads/captures")).andExpect(status().isOk());
            String id = analysis.list(1).captures().get(0).id();
            retained.perform(get("/bootui/api/threads/captures/" + id + "/threads")
                            .param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.captureId").value(id))
                    .andExpect(jsonPath("$.threads.length()").value(1));
            retained.perform(get("/bootui/api/threads/captures/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.capture.id").value(id));
            retained.perform(post("/bootui/api/threads/captures/" + id + "/export"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.format").value("bootui-thread-capture"))
                    .andExpect(jsonPath("$.schemaVersion").value(1));
            properties.setReadOnly(true);
            retained.perform(post("/bootui/api/threads/captures")).andExpect(status().isForbidden());
            retained.perform(post("/bootui/api/threads/observations")).andExpect(status().isForbidden());
            retained.perform(get("/bootui/api/threads/captures/" + id)).andExpect(status().isOk());
            properties.setReadOnly(false);
            retained.perform(get("/bootui/api/threads/captures/" + id + "/threads")
                            .param("sort", "invalid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isString());
            retained.perform(post("/bootui/api/threads/captures/clear"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.clearedCaptures").value(1));
            retained.perform(get("/bootui/api/threads/captures/" + id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").isString());
            org.assertj.core.api.Assertions.assertThat(analysis.list(1).captures())
                    .isEmpty();
        }
    }
}
