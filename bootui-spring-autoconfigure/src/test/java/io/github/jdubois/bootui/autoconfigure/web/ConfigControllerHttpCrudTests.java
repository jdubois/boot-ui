package io.github.jdubois.bootui.autoconfigure.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level CRUD tests for {@link ConfigController} override endpoints.
 *
 * <p>Covers POST to create/update, DELETE for existing and non-existing keys,
 * cross-instance state persistence via a shared {@link ConfigOverrideService},
 * and the restart-warning message that must accompany every override result.</p>
 */
class ConfigControllerHttpCrudTests {

    @TempDir
    Path tmp;

    private MockEnvironment environment;
    private BootUiProperties properties;
    private ConfigOverrideService overrideService;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        environment.setProperty("server.port", "8080");
        environment.setProperty("app.name", "test-app");

        properties = new BootUiProperties();
        properties.setOverridesFile(tmp.resolve("overrides.properties").toString());

        overrideService = new ConfigOverrideService(environment, properties);
    }

    /**
     * Builds a fresh {@link ConfigController} backed by the shared service.
     */
    private MockMvc buildMvc() {
        ConfigController controller = new ConfigController(
                new io.github.jdubois.bootui.engine.config.ConfigService(
                        new io.github.jdubois.bootui.autoconfigure.config.SpringConfigProvider(
                                environment,
                                new ConfigMetadataCatalog(getClass().getClassLoader())),
                        new io.github.jdubois.bootui.autoconfigure.config.BootUiExposure(environment, properties)),
                overrideService);
        return standaloneSetup(controller).build();
    }

    @Test
    void postCreatesNewOverrideAndReturnsCorrectDto() throws Exception {
        buildMvc()
                .perform(post("/bootui/api/config/overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"app.name\",\"value\":\"overridden-app\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("app.name"))
                .andExpect(jsonPath("$.value").value("overridden-app"))
                .andExpect(jsonPath("$.persisted").value(true));
    }

    @Test
    void postIsIdempotentAndReturnsPreviousValue() throws Exception {
        MockMvc mvc = buildMvc();

        // First write
        mvc.perform(post("/bootui/api/config/overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"server.port\",\"value\":\"9090\"}"))
                .andExpect(status().isOk());

        // Second write with a different value must report the previous one
        mvc.perform(post("/bootui/api/config/overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"server.port\",\"value\":\"9191\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("server.port"))
                .andExpect(jsonPath("$.value").value("9191"))
                .andExpect(jsonPath("$.previousValue").value("9090"))
                .andExpect(jsonPath("$.persisted").value(true));
    }

    @Test
    void deleteExistingOverrideReturnsNullValueAndPreviousDisplay() throws Exception {
        MockMvc mvc = buildMvc();

        mvc.perform(post("/bootui/api/config/overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"app.name\",\"value\":\"to-delete\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/bootui/api/config/overrides/app.name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("app.name"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.previousValue").value("to-delete"))
                .andExpect(jsonPath("$.persisted").value(true));
    }

    @Test
    void deleteNonExistingOverrideIsIdempotentAndReturns200() throws Exception {
        // No prior POST — controller must return a stable 200 result, not an error
        buildMvc()
                .perform(delete("/bootui/api/config/overrides/does.not.exist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("does.not.exist"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.persisted").value(true));
    }

    @Test
    void persistedStateSurvivesAcrossControllerInstances() throws Exception {
        // Write through one controller instance
        buildMvc()
                .perform(post("/bootui/api/config/overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"server.port\",\"value\":\"7777\"}"))
                .andExpect(status().isOk());

        // A second, independently constructed controller sharing the same service
        // must see the override in the GET response
        buildMvc()
                .perform(get("/bootui/api/config"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.properties[?(@.name=='server.port')].value").value("7777"))
                .andExpect(jsonPath("$.properties[?(@.name=='server.port')].override")
                        .value(true));
    }

    @Test
    void restartWarningMessageIsPresentForOverriddenProperty() throws Exception {
        // The service always warns that already-bound beans may need a restart
        buildMvc()
                .perform(post("/bootui/api/config/overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"server.port\",\"value\":\"9090\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("restart")));
    }
}
