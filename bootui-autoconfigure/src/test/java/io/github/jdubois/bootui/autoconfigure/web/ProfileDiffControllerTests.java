package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.ValueExposure;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

class ProfileDiffControllerTests {

    private BootUiProperties properties;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "application-dev.properties",
                        Map.of(
                                "sample.name", "demo",
                                "sample.password", "secret")));
        properties = new BootUiProperties();
        mvc = standaloneSetup(new ProfileDiffController(environment, properties))
                .build();
    }

    @Test
    void profilesMaskSecretValuesByDefault() throws Exception {
        mvc.perform(get("/bootui/api/profile-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfiles[0]").value("dev"))
                .andExpect(jsonPath("$.profileSources[0].profile").value("dev"))
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='sample.password')].masked")
                        .value(true))
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='sample.password')].value")
                        .value("******"));
    }

    @Test
    void profilesHideValuesUnderMetadataOnlyExposure() throws Exception {
        properties.setExposeValues(ValueExposure.METADATA_ONLY);

        mvc.perform(get("/bootui/api/profile-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='sample.name')].value[0]")
                        .doesNotExist())
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='sample.password')].masked")
                        .value(false));
    }

    @Test
    void profilesExposeSecretValuesUnderFullExposure() throws Exception {
        properties.setExposeValues(ValueExposure.FULL);

        mvc.perform(get("/bootui/api/profile-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='sample.password')].masked")
                        .value(false))
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='sample.password')].value")
                        .value("secret"));
    }
}
