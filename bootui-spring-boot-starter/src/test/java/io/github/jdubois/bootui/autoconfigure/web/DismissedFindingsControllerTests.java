package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactivePanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.safety.PanelAccessFilter;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;

/** The same behaviour on Spring MVC and Spring WebFlux, which share one controller class. */
class DismissedFindingsControllerTests {

    private static final String PATH = "/bootui/api/dismissed-findings";
    private static final String BODY =
            "{\"ruleId\":\"HIB-ID-006\",\"identity\":\"v1:unit=app|entity=com.acme.Item|member=id\",\"note\":\"Intentional\"}";
    private static final String CANARY = "PrivateDismissalReason";

    @Test
    void mvcDismissesListsAndRestoresAFindingWithItsNote(@TempDir Path dir) throws Exception {
        MockMvc mvc = mvc(dir, new BootUiProperties());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed.length()").value(1))
                .andExpect(jsonPath("$.dismissed[0].ruleId").value("HIB-ID-006"))
                .andExpect(jsonPath("$.dismissed[0].note").value("Intentional"))
                .andExpect(jsonPath("$.ignored").value(0));
        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed.length()").value(1));
        mvc.perform(delete(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed.length()").value(0));
    }

    @Test
    void mvcRefusesBadBodiesWithAFixedMessageThatNeverQuotesTheNote(@TempDir Path dir) throws Exception {
        MockMvc mvc = mvc(dir, new BootUiProperties());

        for (String body : new String[] {
            "{\"note\":" + CANARY + "}",
            "{\"ruleId\":\"HIB-ID-006\",\"identity\":\"bad identity\",\"note\":\"" + CANARY + "\"}",
            "{\"ruleId\":\"HIB-ID-006\",\"identity\":\"v1:a=b\",\"note\":7}",
            "[\"" + CANARY + "\"]",
            ""
        }) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isString())
                    .andExpect(
                            content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(CANARY))));
            if (!body.contains("\"note\":7")) {
                // A restore reads no note, so only a bad rule id or identity can refuse it.
                mvc.perform(delete(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest());
            }
        }
        mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(BODY))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get(PATH)).andExpect(jsonPath("$.dismissed.length()").value(0));
    }

    @Test
    void mvcGlobalReadOnlyBlocksTheMutationsButNotTheList(@TempDir Path dir) throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setReadOnly(true);
        MockMvc mvc = mvc(dir, properties);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.panel").value("dismissed-findings"));
        mvc.perform(delete(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH)).andExpect(status().isOk());
    }

    @Test
    void webFluxDismissesListsAndRestoresAFindingWithItsNote(@TempDir Path dir) {
        WebTestClient client = client(dir, new BootUiProperties());

        client.post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.dismissed.length()")
                .isEqualTo(1)
                .jsonPath("$.dismissed[0].note")
                .isEqualTo("Intentional");
        client.get().uri(PATH).exchange().expectStatus().isOk();
        client.method(org.springframework.http.HttpMethod.DELETE)
                .uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.dismissed.length()")
                .isEqualTo(0);
    }

    @Test
    void webFluxRefusesBadBodiesWithAFixedMessageThatNeverQuotesTheNote(@TempDir Path dir) {
        WebTestClient client = client(dir, new BootUiProperties());

        for (String body : new String[] {
            "{\"note\":" + CANARY + "}",
            "{\"ruleId\":\"HIB-ID-006\",\"identity\":\"bad identity\",\"note\":\"" + CANARY + "\"}",
            "[\"" + CANARY + "\"]"
        }) {
            String response = new String(client.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.error")
                    .isNotEmpty()
                    .returnResult()
                    .getResponseBody());
            assertThat(response).doesNotContain(CANARY);
        }
        client.post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.get()
                .uri(PATH)
                .exchange()
                .expectBody()
                .jsonPath("$.dismissed.length()")
                .isEqualTo(0);
    }

    @Test
    void webFluxGlobalReadOnlyBlocksTheMutationsButNotTheList(@TempDir Path dir) {
        BootUiProperties properties = new BootUiProperties();
        properties.setReadOnly(true);
        WebTestClient client = client(dir, properties);

        client.post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.panel")
                .isEqualTo("dismissed-findings");
        client.method(org.springframework.http.HttpMethod.DELETE)
                .uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY)
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get().uri(PATH).exchange().expectStatus().isOk();
    }

    @Test
    void mvcReadsTheBodyAsBytesSoTheFrameworkNeverLogsTheNote(@TempDir Path dir) throws Exception {
        var method = DismissedFindingsController.class.getDeclaredMethods();
        assertThat(java.util.Arrays.stream(method)
                        .filter(candidate -> candidate.getName().equals("dismiss"))
                        .flatMap(candidate -> java.util.Arrays.stream(candidate.getParameterTypes())))
                .containsExactly(byte[].class);
        mvc(dir, new BootUiProperties())
                .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    private static MockMvc mvc(Path dir, BootUiProperties properties) {
        return standaloneSetup(new DismissedFindingsController(new DismissedRulesStore(dir.resolve("boot-ui.yml"))))
                .setControllerAdvice(new AdvisorViolationExceptionHandler())
                .addFilters(new PanelAccessFilter(properties))
                .build();
    }

    private static WebTestClient client(Path dir, BootUiProperties properties) {
        return WebTestClient.bindToController(
                        new DismissedFindingsController(new DismissedRulesStore(dir.resolve("boot-ui.yml"))))
                .controllerAdvice(new AdvisorViolationExceptionHandler())
                .webFilter(new ReactivePanelAccessFilter(properties))
                .build();
    }
}
