package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link ThreadDumpController}.
 */
class ThreadDumpControllerTests {

    private final MockMvc mvc = standaloneSetup(
                    new ThreadDumpController(new ThreadDumpService(new BootUiExposure(new BootUiProperties()))))
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
                        new ThreadDumpService(null, new BootUiExposure(new BootUiProperties()))))
                .build();

        unavailable.perform(post("/bootui/api/threads/download")).andExpect(status().isNotFound());
    }
}
