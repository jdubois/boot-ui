package io.github.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.bootui.core.BootUiDtos.DevToolsActionResult;
import io.github.bootui.core.BootUiDtos.DevToolsStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class DevToolsControllerTests {

    @Test
    void reportsDevToolsStatus() throws Exception {
        MockMvc mvc = standaloneSetup(new DevToolsController(new StubDevToolsBridge(
                new DevToolsStatus(true, null, false, true, 35729, null),
                new DevToolsActionResult("livereload", "triggered", "ok"),
                new DevToolsActionResult("restart", "scheduled", "ok")))).build();

        mvc.perform(get("/bootui/api/devtools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restartAvailable").value(true))
                .andExpect(jsonPath("$.restartPending").value(false))
                .andExpect(jsonPath("$.liveReloadAvailable").value(true))
                .andExpect(jsonPath("$.liveReloadPort").value(35729));
    }

    @Test
    void triggersLiveReloadWhenAvailable() throws Exception {
        MockMvc mvc = standaloneSetup(new DevToolsController(new StubDevToolsBridge(
                new DevToolsStatus(true, null, false, true, 35729, null),
                new DevToolsActionResult("livereload", "triggered", "LiveReload notification sent."),
                new DevToolsActionResult("restart", "scheduled", "ok")))).build();

        mvc.perform(post("/bootui/api/devtools/livereload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("livereload"))
                .andExpect(jsonPath("$.status").value("triggered"));
    }

    @Test
    void returnsConflictWhenLiveReloadUnavailable() throws Exception {
        MockMvc mvc = standaloneSetup(new DevToolsController(new StubDevToolsBridge(
                new DevToolsStatus(false, "no restart", false, false, null, "no livereload"),
                new DevToolsActionResult("livereload", "unavailable", "no livereload"),
                new DevToolsActionResult("restart", "unavailable", "no restart")))).build();

        mvc.perform(post("/bootui/api/devtools/livereload"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("unavailable"))
                .andExpect(jsonPath("$.message").value("no livereload"));
    }

    @Test
    void restartRequiresConfirmation() throws Exception {
        MockMvc mvc = standaloneSetup(new DevToolsController(new StubDevToolsBridge(
                new DevToolsStatus(true, null, false, true, 35729, null),
                new DevToolsActionResult("livereload", "triggered", "ok"),
                new DevToolsActionResult("restart", "scheduled", "ok")))).build();

        mvc.perform(post("/bootui/api/devtools/restart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("confirmation_required"));
    }

    @Test
    void schedulesRestartWhenConfirmed() throws Exception {
        MockMvc mvc = standaloneSetup(new DevToolsController(new StubDevToolsBridge(
                new DevToolsStatus(true, null, false, true, 35729, null),
                new DevToolsActionResult("livereload", "triggered", "ok"),
                new DevToolsActionResult("restart", "scheduled", "Restart scheduled.")))).build();

        mvc.perform(post("/bootui/api/devtools/restart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.action").value("restart"))
                .andExpect(jsonPath("$.status").value("scheduled"));
    }

    private record StubDevToolsBridge(
            DevToolsStatus status,
            DevToolsActionResult liveReloadResult,
            DevToolsActionResult restartResult) implements DevToolsBridge {

        @Override
        public DevToolsActionResult triggerLiveReload() {
            return liveReloadResult;
        }

        @Override
        public DevToolsActionResult scheduleRestart() {
            return restartResult;
        }
    }
}
