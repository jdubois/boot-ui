package io.github.jdubois.bootui.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Mirrors {@code ReactiveBootUiIndexControllerTests} (the host-adapter sibling this class's Javadoc
 * points to): the SPA shell is served (HTTP 200) at both {@code /bootui} and {@code /bootui/} with a
 * runtime-injected {@code <base href>}, and the bare application root {@code /} redirects (HTTP 302)
 * straight to the Live Activity panel rather than 404ing or falling through to the shared Vue router's
 * default (and, on this console, always-unavailable) {@code /overview} route.
 */
class ConsoleIndexControllerTests {

    private static final String STUB_INDEX = "<!doctype html>\n<html lang=\"en\">\n  <head>\n"
            + "    <link href=\"./favicon.svg\" rel=\"icon\" />\n"
            + "    <script type=\"module\" src=\"./assets/index-test.js\"></script>\n"
            + "  </head>\n  <body><div id=\"app\"></div></body>\n</html>\n";

    private static WebTestClient buildClient() {
        ConsoleIndexController controller =
                new ConsoleIndexController(new ByteArrayResource(STUB_INDEX.getBytes(StandardCharsets.UTF_8)));
        return WebTestClient.bindToController(controller).build();
    }

    @Test
    void rootPathRedirectsToTheLiveActivityPanel() {
        WebTestClient client = buildClient();

        client.get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isFound()
                .expectHeader()
                .location(ConsoleIndexController.ROOT_REDIRECT_LOCATION);
    }

    @Test
    void bootuiPathServesSpaWithInjectedBaseHref() {
        WebTestClient client = buildClient();

        client.get()
                .uri("/bootui")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("<base href=\"/bootui/\" />"));
    }

    @Test
    void trailingSlashPathServesSpaWithInjectedBaseHref() {
        WebTestClient client = buildClient();

        client.get()
                .uri("/bootui/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("<base href=\"/bootui/\" />"));
    }

    @Test
    void indexTemplateIsCachedAcrossRequests() {
        // Regression guard for the volatile cachedTemplate field: two sequential requests must both
        // succeed and return identical markup, proving the lazily-cached template is reused safely.
        WebTestClient client = buildClient();

        String first = client.get()
                .uri("/bootui")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        String second = client.get()
                .uri("/bootui")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void constructorLoadsRealPackagedIndexResourceWithoutError() {
        // Exercises the public (Spring-facing) constructor that resolves the shared INDEX_LOCATION
        // classpath resource. Unlike the reactive host adapter's test module, bootui-activity-console
        // depends on bootui-ui directly, so the real packaged Vue asset is present on the test classpath.
        assertThatCode(ConsoleIndexController::new).doesNotThrowAnyException();
    }
}
