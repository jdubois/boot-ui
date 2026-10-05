package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiReactiveAutoConfiguration;
import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Pins the WebFlux command-line endpoint to the same contract the servlet stack answers, since a CLI built
 * against one stack must work unchanged against the other.
 */
class ReactiveBootUiCliControllerTests {

    private static final String CLI = "/bootui/api/cli";

    private static ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CodecsAutoConfiguration.class,
                        HttpHandlerAutoConfiguration.class,
                        WebFluxAutoConfiguration.class,
                        BootUiReactiveAutoConfiguration.class));
    }

    private static WebTestClient client(ApplicationContext context) {
        return WebTestClient.bindToApplicationContext(context)
                .configureClient()
                .defaultHeader(
                        "Authorization",
                        "Bearer " + context.getBean(ApiTokenAuthenticator.class).token())
                .build();
    }

    @Test
    void cliBeansAreRegisteredAndEnabledByDefault() {
        runner().withPropertyValues("bootui.enabled=ON").run(context -> {
            assertThat(context).hasSingleBean(CliService.class);
            assertThat(context).hasSingleBean(ReactiveBootUiCliController.class);
            assertThat(context.getBean(CliService.class).enabled()).isTrue();
        });
    }

    @Test
    void cliCatalogIsServedWithoutEnablingTheMcpServer() {
        runner().withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> client(context.getSourceApplicationContext())
                        .get()
                        .uri(CLI)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.enabled")
                        .isEqualTo(true)
                        .jsonPath("$.serverName")
                        .isEqualTo("bootui")
                        .jsonPath("$.endpoint")
                        .isEqualTo(CLI)
                        .jsonPath("$.tools")
                        .isArray());
    }

    @Test
    void readToolIsInvocable() {
        runner().withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> client(context.getSourceApplicationContext())
                        .post()
                        .uri(CLI + "/tools/get_overview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}")
                        .exchange()
                        .expectStatus()
                        .isOk());
    }

    @Test
    void unknownToolIsNotFound() {
        runner().withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> client(context.getSourceApplicationContext())
                        .post()
                        .uri(CLI + "/tools/no_such_tool")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}")
                        .exchange()
                        .expectStatus()
                        .isNotFound()
                        .expectBody()
                        .jsonPath("$.error")
                        .exists());
    }

    @Test
    void argumentTheToolDoesNotDeclareIsRejected() {
        runner().withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> client(context.getSourceApplicationContext())
                        .post()
                        .uri(CLI + "/tools/get_overview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"id\":\"anything\"}")
                        .exchange()
                        .expectStatus()
                        .isBadRequest());
    }

    @Test
    void disabledPanelRefusesItsToolsWithForbidden() {
        runner().withPropertyValues(
                        "bootui.enabled=ON", "bootui.allow-non-localhost=true", "bootui.panels.overview.enabled=false")
                .run(context -> client(context.getSourceApplicationContext())
                        .post()
                        .uri(CLI + "/tools/get_overview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}")
                        .exchange()
                        .expectStatus()
                        .isForbidden());
    }

    @Test
    void globalReadOnlyStillServesReadToolsOverPost() {
        runner().withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true", "bootui.read-only=true")
                .run(context -> client(context.getSourceApplicationContext())
                        .post()
                        .uri(CLI + "/tools/get_overview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}")
                        .exchange()
                        .expectStatus()
                        .isOk());
    }

    @Test
    void disabledEndpointAnswersTheCatalogButRefusesInvocation() {
        runner().withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true", "bootui.cli.enabled=false")
                .run(context -> {
                    WebTestClient client = client(context.getSourceApplicationContext());
                    client.get()
                            .uri(CLI)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody()
                            .jsonPath("$.enabled")
                            .isEqualTo(false)
                            .jsonPath("$.toolCount")
                            .isEqualTo(0);
                    client.post()
                            .uri(CLI + "/tools/get_overview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue("{}")
                            .exchange()
                            .expectStatus()
                            .isEqualTo(503);
                });
    }
}
