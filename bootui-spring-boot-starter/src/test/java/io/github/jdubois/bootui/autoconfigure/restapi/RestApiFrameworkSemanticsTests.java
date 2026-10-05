package io.github.jdubois.bootui.autoconfigure.restapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class RestApiFrameworkSemanticsTests {

    @Test
    void mvcMatchesTheAdvisorStatusBindingAndLiteralMediaAssumptions() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SemanticController()).build();
        mvc.perform(get("/optional"))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
        mvc.perform(get("/empty-default")).andExpect(status().isBadRequest());
        mvc.perform(get("/direct-headers"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Test", "value"))
                .andExpect(content().string(""));
        mvc.perform(get("/headers-payload"))
                .andExpect(status().isOk())
                .andExpect(result ->
                        assertThat(result.getResponse().getContentAsString()).isNotBlank());
        mvc.perform(get("/http-entity"))
                .andExpect(status().isAccepted())
                .andExpect(content().string("body"));
        mvc.perform(patch("/configuration.txt").contentType("text/plain").content("patch"))
                .andExpect(status().isOk())
                .andExpect(content().string("patch"));
        mvc.perform(get("/error"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("mapped"));
        MvcResult result =
                mvc.perform(get("/async")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted())
                .andExpect(content().string("body"));
    }

    @Test
    void webFluxMatchesTheAdvisorStatusBindingAndLiteralMediaAssumptions() {
        WebTestClient client =
                WebTestClient.bindToController(new SemanticController()).build();
        client.get()
                .uri("/optional")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("false");
        client.get().uri("/empty-default").exchange().expectStatus().isBadRequest();
        client.get()
                .uri("/direct-headers")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Test", "value")
                .expectBody()
                .isEmpty();
        client.get()
                .uri("/headers-payload")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> assertThat(result.getResponseBody()).isNotEmpty());
        client.get()
                .uri("/http-entity")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(String.class)
                .isEqualTo("body");
        client.get()
                .uri("/async")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(String.class)
                .isEqualTo("body");
        client.get()
                .uri("/empty")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .isEmpty();
        client.patch()
                .uri("/configuration.txt")
                .header("Content-Type", "text/plain")
                .bodyValue("patch")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("patch");
        client.get()
                .uri("/error")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(String.class)
                .isEqualTo("mapped");
    }

    @RestController
    static class SemanticController {
        @GetMapping("/optional")
        String optionalBoolean(@RequestParam(required = false) boolean value) {
            return Boolean.toString(value);
        }

        @GetMapping("/empty-default")
        String emptyDefault(@RequestParam(name = "count", required = false, defaultValue = "") int count) {
            return Integer.toString(count);
        }

        @GetMapping("/direct-headers")
        HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Test", "value");
            return headers;
        }

        @GetMapping("/headers-payload")
        ResponseEntity<HttpHeaders> headersPayload() {
            return ResponseEntity.ok(headers());
        }

        @GetMapping("/http-entity")
        @ResponseStatus(HttpStatus.ACCEPTED)
        HttpEntity<String> httpEntity() {
            return new HttpEntity<>("body");
        }

        @GetMapping("/async")
        Mono<ResponseEntity<String>> async() {
            return Mono.just(ResponseEntity.accepted().body("body"));
        }

        @GetMapping("/empty")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        Mono<Void> empty() {
            return Mono.empty();
        }

        @PatchMapping(value = "/configuration.txt", consumes = "text/plain", produces = "text/plain")
        String patchText(@RequestBody String patch) {
            return patch;
        }

        @GetMapping("/error")
        Map<String, String> error() {
            throw new IllegalArgumentException("fixture");
        }

        @ExceptionHandler(exception = IllegalArgumentException.class)
        ResponseEntity<String> aliasedException() {
            return ResponseEntity.badRequest().body("mapped");
        }
    }
}
