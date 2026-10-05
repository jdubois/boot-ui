package io.github.jdubois.bootui.autoconfigure.restapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pins the Spring Framework behavior behind RAPI-MAP-002, RAPI-VALID-006, RAPI-VER-007, RAPI-RESP-010 and
 * RAPI-RESP-011 to the shipped Spring version on both Spring MVC and Spring WebFlux.
 */
class RestApiContractSemanticsTests {

    @Test
    void mvcContractDefectsBehaveAsTheRulesDescribe() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ContractController()).build();

        // RAPI-VER-007: no Content-Type on a bodyless request is matched as application/octet-stream.
        mvc.perform(get("/consumes")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/consumes").contentType("application/json")).andExpect(status().isOk());
        // RAPI-VALID-006: the second @RequestBody finds the request stream already consumed.
        mvc.perform(post("/two-bodies").contentType("application/json").content("{\"a\":\"1\"}"))
                .andExpect(status().isBadRequest());
        // RAPI-RESP-010: a reason makes Spring MVC call sendError and discard the returned body.
        mvc.perform(get("/reason"))
                .andExpect(result ->
                        assertThat(result.getResponse().getErrorMessage()).isEqualTo("Fine"))
                .andExpect(content().string(""));
        // RAPI-RESP-011: an empty Optional is answered with 200, not 404.
        mvc.perform(get("/optional")).andExpect(status().isOk());
    }

    @Test
    void mvcRejectsIdenticalMappingsAtStartupButPartialOverlapsOnlyAtRequestTime() throws Exception {
        assertThatThrownBy(() ->
                        MockMvcBuilders.standaloneSetup(new IdenticalMappings()).build())
                .hasStackTraceContaining("Ambiguous mapping");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OverlappingMappings()).build();
        mvc.perform(get("/b")).andExpect(content().string("ab"));
        assertThatThrownBy(() -> mvc.perform(get("/a"))).hasStackTraceContaining("Ambiguous handler methods");
        assertThatThrownBy(() -> mvc.perform(get("/x"))).hasStackTraceContaining("Ambiguous handler methods");
    }

    @Test
    void webFluxContractDefectsBehaveAsTheRulesDescribe() {
        WebTestClient client =
                WebTestClient.bindToController(new ContractController()).build();

        client.get().uri("/consumes").exchange().expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        // WebFlux applies the status but ignores the reason, so the body is still written (RAPI-RESP-010 skips).
        client.get()
                .uri("/reason")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.k")
                .isEqualTo("v");
        client.get().uri("/optional").exchange().expectStatus().isOk();
    }

    @RestController
    static class ContractController {
        @GetMapping(value = "/consumes", consumes = "application/json")
        String consumes() {
            return "ok";
        }

        @PostMapping("/two-bodies")
        String twoBodies(@RequestBody Map<String, String> first, @RequestBody Map<String, String> second) {
            return first + "/" + second;
        }

        @GetMapping("/reason")
        @ResponseStatus(code = HttpStatus.OK, reason = "Fine")
        Map<String, String> reason() {
            return Map.of("k", "v");
        }

        @GetMapping("/optional")
        Optional<Map<String, String>> optional() {
            return Optional.empty();
        }
    }

    @RestController
    static class IdenticalMappings {
        @GetMapping("/same")
        String one() {
            return "1";
        }

        @GetMapping("/same")
        String two() {
            return "2";
        }
    }

    @RestController
    static class OverlappingMappings {
        @GetMapping({"/a", "/b"})
        String ab() {
            return "ab";
        }

        @GetMapping({"/a", "/c"})
        String ac() {
            return "ac";
        }

        @RequestMapping(
                path = "/x",
                method = {RequestMethod.GET, RequestMethod.POST})
        String any() {
            return "any";
        }

        @GetMapping("/x")
        String read() {
            return "read";
        }
    }
}
