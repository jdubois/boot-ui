package io.github.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.bootui.autoconfigure.BootUiProperties;
import io.github.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.bootui.autoconfigure.config.ConfigOverrideService;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link ConfigController}.
 *
 * <p>Covers GET listing, POST/DELETE overrides, secret masking, and the
 * {@link ValueExposure#METADATA_ONLY} masking mode through the HTTP surface.</p>
 */
class ConfigControllerTests {

    @TempDir
    Path tmp;

    private MockEnvironment environment;
    private BootUiProperties properties;
    private ConfigOverrideService overrideService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("server.port", "8080");
        environment.setProperty("spring.datasource.password", "s3cret");

        properties = new BootUiProperties();
        properties.setOverridesFile(tmp.resolve("overrides.properties").toString());

        overrideService = new ConfigOverrideService(environment, properties);

        ConfigController controller = new ConfigController(environment, overrideService, properties,
                new ConfigMetadataCatalog(getClass().getClassLoader()));
        mvc = standaloneSetup(controller).build();
    }

    @Test
    void listReturnsActiveProfilesPropertiesAndSources() throws Exception {
        mvc.perform(get("/bootui/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfiles[0]").value("dev"))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.properties[?(@.name=='server.port')].value").value("8080"));
    }

    @Test
    void listMasksSecretValuesByDefault() throws Exception {
        mvc.perform(get("/bootui/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties[?(@.name=='spring.datasource.password')].masked")
                        .value(true))
                .andExpect(jsonPath("$.properties[?(@.name=='spring.datasource.password')].value")
                        .value("******"));
    }

    @Test
    void listHidesAllValuesUnderMetadataOnlyExposure() throws Exception {
        properties.setExposeValues(ValueExposure.METADATA_ONLY);

        mvc.perform(get("/bootui/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties[?(@.name=='server.port')].value[0]").doesNotExist());
    }

    @Test
    void postCreatesOverrideAndDeleteRemovesIt() throws Exception {
        // First write to establish a previous override value.
        mvc.perform(post("/bootui/api/config/overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"server.port\",\"value\":\"9090\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("server.port"))
                .andExpect(jsonPath("$.value").value("9090"))
                .andExpect(jsonPath("$.persisted").value(true));

        // Second write should report the previous override value.
        mvc.perform(post("/bootui/api/config/overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"server.port\",\"value\":\"9191\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousValue").value("9090"))
                .andExpect(jsonPath("$.value").value("9191"));

        mvc.perform(delete("/bootui/api/config/overrides/server.port"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("server.port"))
                .andExpect(jsonPath("$.value").doesNotExist());
    }
}
