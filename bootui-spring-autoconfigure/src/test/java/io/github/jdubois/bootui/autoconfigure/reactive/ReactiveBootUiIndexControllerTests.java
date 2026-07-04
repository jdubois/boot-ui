package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Reactive (WebFlux) sibling of {@code BootUiIndexControllerTests}: the SPA shell is served (HTTP 200)
 * at <em>both</em> {@code /bootui} and {@code /bootui/} with a runtime-injected {@code <base href>},
 * exactly like the servlet controller. Unlike the servlet version there is no
 * {@code server.servlet.context-path} analog under WebFlux, so the base href is always
 * application-relative.
 */
class ReactiveBootUiIndexControllerTests {

    private static final String STUB_INDEX = "<!doctype html>\n<html lang=\"en\">\n  <head>\n"
            + "    <link href=\"./favicon.svg\" rel=\"icon\" />\n"
            + "    <script type=\"module\" src=\"./assets/index-test.js\"></script>\n"
            + "  </head>\n  <body><div id=\"app\"></div></body>\n</html>\n";

    private static WebTestClient buildClient(BootUiProperties properties) {
        ReactiveBootUiIndexController controller = new ReactiveBootUiIndexController(
                properties, new ByteArrayResource(STUB_INDEX.getBytes(StandardCharsets.UTF_8)));
        return WebTestClient.bindToController(controller).build();
    }

    @Test
    void rootPathServesSpaWithInjectedBaseHref() {
        WebTestClient client = buildClient(new BootUiProperties());

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
        WebTestClient client = buildClient(new BootUiProperties());

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
    void baseHrefHonorsCustomPathProperty() {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/devtools");
        WebTestClient client = buildClient(properties);

        client.get()
                .uri("/bootui")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("<base href=\"/devtools/\" />"));
    }

    @Test
    void indexTemplateIsCachedAcrossRequests() {
        // Regression guard for the volatile cachedTemplate field: two sequential requests must both
        // succeed and return identical markup, proving the lazily-cached template is reused safely.
        WebTestClient client = buildClient(new BootUiProperties());

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
        // Exercises the public (Spring-facing) constructor that resolves BootUiIndexController's
        // shared INDEX_LOCATION classpath resource, mirroring the servlet controller's default wiring.
        // The reactive test module does not package the built Vue asset, so the resource is intentionally
        // absent here; the constructor itself (resource lookup, no eager read) must not throw either way.
        org.assertj.core.api.Assertions.assertThatCode(() -> new ReactiveBootUiIndexController(new BootUiProperties()))
                .doesNotThrowAnyException();
    }
}
