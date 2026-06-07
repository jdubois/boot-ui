package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.SecretMasker;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Masking edge-case tests for {@link ProfileDiffController}.
 *
 * <p>The existing {@code ProfileDiffControllerTests} covers the three main exposure modes
 * (masked by default, METADATA_ONLY, FULL). This class focuses exclusively on
 * masking corner cases that those tests do not exercise:</p>
 * <ul>
 *   <li>A secret key appearing only in one profile source.</li>
 *   <li>The same secret key in two profile sources with different values (both masked).</li>
 *   <li>Non-string property values (integers) converted to strings before masking.</li>
 *   <li>Opt-out via {@code maskSecrets=false} exposes secret-named keys in plain text.</li>
 *   <li>Non-secret keys are never masked regardless of their value.</li>
 * </ul>
 */
class ProfileDiffMaskingTests {

    // ── secret key in a single profile source ─────────────────────────────────

    @Test
    void secretKeyInSingleProfileSourceIsMasked() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");
        // Only "staging" profile has this secret key
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "application-staging.properties",
                        Map.of(
                                "db.password", "supersecret",
                                "db.url", "jdbc:h2:mem:test")));

        BootUiProperties properties = new BootUiProperties(); // defaults: MASKED, maskSecrets=true
        MockMvc mvc = standaloneSetup(new ProfileDiffController(environment, properties))
                .build();

        mvc.perform(get("/bootui/api/profile-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileSources[0].profile").value("staging"))
                // secret-named key must be masked
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='db.password')].masked")
                        .value(true))
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='db.password')].value")
                        .value(SecretMasker.MASKED_VALUE))
                // non-secret key is exposed normally
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='db.url')].masked")
                        .value(false))
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='db.url')].value")
                        .value("jdbc:h2:mem:test"));
    }

    // ── same secret key in two profile sources ────────────────────────────────

    @Test
    void sameSecretKeyInTwoProfileSourcesBothMasked() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "application-dev.properties", Map.of("spring.datasource.password", "dev-pass")));
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "application-prod.properties", Map.of("spring.datasource.password", "prod-pass-different")));

        BootUiProperties properties = new BootUiProperties();
        MockMvc mvc = standaloneSetup(new ProfileDiffController(environment, properties))
                .build();

        mvc.perform(get("/bootui/api/profile-diff"))
                .andExpect(status().isOk())
                // Two profile source entries; both must mask the password key
                .andExpect(jsonPath(
                                "$.profileSources[?(@.profile=='dev')].properties[?(@.name=='spring.datasource.password')].masked")
                        .value(true))
                .andExpect(jsonPath(
                                "$.profileSources[?(@.profile=='dev')].properties[?(@.name=='spring.datasource.password')].value")
                        .value(SecretMasker.MASKED_VALUE))
                .andExpect(jsonPath(
                                "$.profileSources[?(@.profile=='prod')].properties[?(@.name=='spring.datasource.password')].masked")
                        .value(true))
                .andExpect(jsonPath(
                                "$.profileSources[?(@.profile=='prod')].properties[?(@.name=='spring.datasource.password')].value")
                        .value(SecretMasker.MASKED_VALUE));
    }

    // ── non-string (integer) property values ──────────────────────────────────

    @Test
    void integerPropertyValueStringifiedInDto() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("server.port", 9090); // Integer, non-secret
        props.put("app.secret.count", 42); // Integer, but key contains "secret"
        environment.getPropertySources().addFirst(new MapPropertySource("application-test.properties", props));

        BootUiProperties properties = new BootUiProperties();
        MockMvc mvc = standaloneSetup(new ProfileDiffController(environment, properties))
                .build();

        mvc.perform(get("/bootui/api/profile-diff"))
                .andExpect(status().isOk())
                // Non-secret integer: stringified value exposed
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='server.port')].value")
                        .value("9090"))
                // Secret-named integer: stringified then masked
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='app.secret.count')].masked")
                        .value(true))
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='app.secret.count')].value")
                        .value(SecretMasker.MASKED_VALUE));
    }

    // ── maskSecrets opt-out ───────────────────────────────────────────────────

    @Test
    void maskSecretsDisabledExposesSecretNamedKeyInPlainText() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("ci");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("application-ci.properties", Map.of("api.token", "plaintext-token")));

        BootUiProperties properties = new BootUiProperties();
        properties.setMaskSecrets(false); // opt-out of masking
        // exposeValues stays at default MASKED
        MockMvc mvc = standaloneSetup(new ProfileDiffController(environment, properties))
                .build();

        mvc.perform(get("/bootui/api/profile-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='api.token')].masked")
                        .value(false))
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='api.token')].value")
                        .value("plaintext-token"));
    }

    // ── non-secret key never masked ───────────────────────────────────────────

    @Test
    void nonSecretKeyIsNeverMaskedRegardlessOfValue() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("perf");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "application-perf.properties", Map.of("spring.application.name", "boot-ui-perf")));

        BootUiProperties properties = new BootUiProperties(); // default: MASKED, maskSecrets=true
        MockMvc mvc = standaloneSetup(new ProfileDiffController(environment, properties))
                .build();

        mvc.perform(get("/bootui/api/profile-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='spring.application.name')].masked")
                        .value(false))
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='spring.application.name')].value")
                        .value("boot-ui-perf"));
    }

    // ── null property value ───────────────────────────────────────────────────

    @Test
    void nullPropertyValueKeptAsNullInDto() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("nulltest");
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("nullable.key", null);
        environment.getPropertySources().addFirst(new MapPropertySource("application-nulltest.properties", props));

        BootUiProperties properties = new BootUiProperties();
        MockMvc mvc = standaloneSetup(new ProfileDiffController(environment, properties))
                .build();

        mvc.perform(get("/bootui/api/profile-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileSources[0].properties[?(@.name=='nullable.key')].value[0]")
                        .doesNotExist());
    }
}
