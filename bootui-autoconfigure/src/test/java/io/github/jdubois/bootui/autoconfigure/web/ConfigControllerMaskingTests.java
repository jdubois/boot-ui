package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Verifies that {@link ConfigController} applies the three {@link ValueExposure} modes
 * correctly for both secret-named and non-secret properties.
 *
 * <p>Synthetic properties are injected via {@link MockEnvironment}:
 * <ul>
 *   <li>{@code my.datasource.password} — secret-named (matches {@link io.github.jdubois.bootui.core.SecretMasker})</li>
 *   <li>{@code my.datasource.url} — non-secret</li>
 * </ul>
 */
class ConfigControllerMaskingTests {

    private static final String SECRET_KEY = "my.datasource.password";
    private static final String SECRET_VALUE = "s3cr3t-pass";
    private static final String PLAIN_KEY = "my.datasource.url";
    private static final String PLAIN_VALUE = "jdbc:h2:mem:test";

    @TempDir
    Path tmp;

    private BootUiProperties properties;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(SECRET_KEY, SECRET_VALUE);
        environment.setProperty(PLAIN_KEY, PLAIN_VALUE);

        properties = new BootUiProperties();
        properties.setOverridesFile(tmp.resolve("overrides.properties").toString());

        ConfigOverrideService overrideService = new ConfigOverrideService(environment, properties);
        ConfigController controller = new ConfigController(
            environment, overrideService, properties,
            new ConfigMetadataCatalog(getClass().getClassLoader()));
        mvc = standaloneSetup(controller).build();
    }

    // ── MASKED mode (default) ─────────────────────────────────────────────────

    @Test
    void masked_secretPropertyIsReplacedWithStars() throws Exception {
        properties.setExposeValues(ValueExposure.MASKED);

        mvc.perform(get("/bootui/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.properties[?(@.name=='" + SECRET_KEY + "')].masked").value(true))
            .andExpect(jsonPath("$.properties[?(@.name=='" + SECRET_KEY + "')].value")
                .value("******"));
    }

    @Test
    void masked_nonSecretPropertyIsReturnedAsIs() throws Exception {
        properties.setExposeValues(ValueExposure.MASKED);

        mvc.perform(get("/bootui/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.properties[?(@.name=='" + PLAIN_KEY + "')].masked").value(false))
            .andExpect(jsonPath("$.properties[?(@.name=='" + PLAIN_KEY + "')].value")
                .value(PLAIN_VALUE));
    }

    // ── METADATA_ONLY mode ────────────────────────────────────────────────────

    @Test
    void metadataOnly_secretPropertyHasNoValue() throws Exception {
        properties.setExposeValues(ValueExposure.METADATA_ONLY);

        mvc.perform(get("/bootui/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.properties[?(@.name=='" + SECRET_KEY + "')].value[0]")
                .doesNotExist());
    }

    @Test
    void metadataOnly_nonSecretPropertyAlsoHasNoValue() throws Exception {
        properties.setExposeValues(ValueExposure.METADATA_ONLY);

        mvc.perform(get("/bootui/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.properties[?(@.name=='" + PLAIN_KEY + "')].value[0]")
                .doesNotExist());
    }

    // ── FULL mode ─────────────────────────────────────────────────────────────

    @Test
    void full_secretPropertyIsReturnedUnmasked() throws Exception {
        properties.setExposeValues(ValueExposure.FULL);

        mvc.perform(get("/bootui/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.properties[?(@.name=='" + SECRET_KEY + "')].masked").value(false))
            .andExpect(jsonPath("$.properties[?(@.name=='" + SECRET_KEY + "')].value")
                .value(SECRET_VALUE));
    }

    @Test
    void full_nonSecretPropertyIsReturnedAsIs() throws Exception {
        properties.setExposeValues(ValueExposure.FULL);

        mvc.perform(get("/bootui/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.properties[?(@.name=='" + PLAIN_KEY + "')].masked").value(false))
            .andExpect(jsonPath("$.properties[?(@.name=='" + PLAIN_KEY + "')].value")
                .value(PLAIN_VALUE));
    }
}
