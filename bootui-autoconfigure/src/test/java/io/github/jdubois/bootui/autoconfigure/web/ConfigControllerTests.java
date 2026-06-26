package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.core.ValueExposure;
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

        ConfigController controller = new ConfigController(
                environment,
                overrideService,
                properties,
                new ConfigMetadataCatalog(getClass().getClassLoader()));
        mvc = standaloneSetup(controller).build();
    }

    @Test
    void listReturnsActiveProfilesPropertiesAndSources() throws Exception {
        mvc.perform(get("/bootui/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfiles[0]").value("dev"))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(
                        jsonPath("$.properties[?(@.name=='server.port')].value").value("8080"));
    }

    @Test
    void listSupportsServerSideFilteringAndPaging() throws Exception {
        environment.setProperty("server.address", "127.0.0.1");
        environment.setProperty("management.server.port", "8081");

        mvc.perform(get("/bootui/api/config")
                        .param("q", "server")
                        .param("offset", "1")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties.length()").value(1))
                .andExpect(jsonPath("$.properties[0].name").value("server.address"))
                .andExpect(jsonPath("$.page.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.page.matched").value(3))
                .andExpect(jsonPath("$.page.offset").value(1))
                .andExpect(jsonPath("$.page.returned").value(1))
                .andExpect(jsonPath("$.page.hasMore").value(true));
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
                .andExpect(jsonPath("$.properties[?(@.name=='server.port')].value[0]")
                        .doesNotExist());
    }

    @Test
    void listExposesSecretValuesUnderFullExposure() throws Exception {
        properties.setExposeValues(ValueExposure.FULL);

        mvc.perform(get("/bootui/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties[?(@.name=='spring.datasource.password')].masked")
                        .value(false))
                .andExpect(jsonPath("$.properties[?(@.name=='spring.datasource.password')].value")
                        .value("s3cret"));
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
                .andExpect(jsonPath("$.persisted").value(true))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("until restart")));

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
