package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiIndexController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiStaticResourceConfigurer;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactivePanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.web.OverviewController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Tests for {@link BootUiReactiveAutoConfiguration}: proves the reactive (WebFlux) BootUI entry point
 * activates under the exact same {@link BootUiActivationCondition} rules as the servlet
 * {@link BootUiAutoConfiguration} (the activation condition itself is framework-neutral and already
 * exhaustively tested by {@code BootUiAutoConfigurationTests}, so this suite focuses on the
 * reactive-specific wiring: the {@code @ConditionalOnWebApplication(REACTIVE)} gate, the safety filter
 * beans, and the static shell/assets serving over WebFlux).
 */
class BootUiReactiveAutoConfigurationTests {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiReactiveAutoConfiguration.class));

    @Test
    void doesNotActivateInAutoModeWithoutEnablingProfile() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(BootUiReactiveAutoConfiguration.class)
                .doesNotHaveBean(ReactiveLocalhostOnlyFilter.class));
    }

    @Test
    void activatesWhenEnabledOn() {
        runner.withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(BootUiReactiveAutoConfiguration.class)
                        .hasSingleBean(ReactiveLocalhostOnlyFilter.class)
                        .hasSingleBean(ReactivePanelAccessFilter.class)
                        .hasSingleBean(ReactiveBootUiIndexController.class)
                        .hasSingleBean(OverviewController.class)
                        .hasSingleBean(BootUiActivation.class));
    }

    @Test
    void activatesOnEnabledProfile() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasSingleBean(BootUiReactiveAutoConfiguration.class));
    }

    @Test
    void disabledByProductionProfileEvenWithEnabledProfile() {
        runner.withPropertyValues("spring.profiles.active=prod,dev")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiReactiveAutoConfiguration.class));
    }

    @Test
    void enabledOnOverridesProductionProfile() {
        runner.withPropertyValues("spring.profiles.active=prod", "bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasSingleBean(BootUiReactiveAutoConfiguration.class);
                    BootUiActivation activation = context.getBean(BootUiActivation.class);
                    assertThat(activation.enabled()).isTrue();
                    assertThat(activation.warnings()).isNotEmpty();
                });
    }

    @Test
    void enabledOffForcesDisabledEvenWithDevProfile() {
        runner.withPropertyValues("spring.profiles.active=dev", "bootui.enabled=OFF")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiReactiveAutoConfiguration.class));
    }

    @Test
    void invalidEnabledValueFailsClosed() {
        runner.withPropertyValues("spring.profiles.active=dev", "bootui.enabled=maybe")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiReactiveAutoConfiguration.class));
    }

    @Test
    void servletAutoConfigurationNeverActivatesAlongsideReactiveOne() {
        // Documents the mutual-exclusivity invariant from BootUiReactiveAutoConfiguration's Javadoc:
        // even with BOTH auto-configurations on the candidate list, a REACTIVE web application context
        // only ever satisfies the reactive one's @ConditionalOnWebApplication(REACTIVE) gate.
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(BootUiAutoConfiguration.class, BootUiReactiveAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(BootUiReactiveAutoConfiguration.class)
                        .doesNotHaveBean(BootUiAutoConfiguration.class));
    }

    @Test
    void registersBootUiStaticResourceConfigurerByDefault() {
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context).hasSingleBean(ReactiveBootUiStaticResourceConfigurer.class));
    }

    @Test
    void doesNotRegisterStaticResourceConfigurerWhenInactive() {
        webFluxRunner()
                .run(context -> assertThat(context).doesNotHaveBean(ReactiveBootUiStaticResourceConfigurer.class));
    }

    @Test
    void servesBootUiAssetsFromClasspath() {
        // bootui.allow-non-localhost=true: WebTestClient.bindToApplicationContext(...) has no simulated
        // TCP peer (unlike servlet MockHttpServletRequest, which defaults remoteAddr to 127.0.0.1), so
        // ReactiveLocalhostOnlyFilter would otherwise fail-closed on a null remote address. That
        // adapter-level safety behavior has its own precise coverage in ReactiveLocalhostOnlyFilterTests;
        // this test is about resource-serving wiring, not the safety filter.
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    // Fixture-backed test resource at META-INF/resources/bootui/index.html (see
                    // BootUiStaticResourceConfigurerTests for the servlet-side twin of this fixture).
                    client.get()
                            .uri("/bootui/index.html")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(String.class)
                            .value(body -> assertThat(body).contains("bootui-test-index"));
                });
    }

    @Test
    void servesBootUiAssetsEvenWhenDefaultResourceMappingsDisabled() {
        webFluxRunner()
                .withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.allow-non-localhost=true",
                        "spring.web.resources.add-mappings=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ReactiveBootUiStaticResourceConfigurer.class);
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    client.get()
                            .uri("/bootui/index.html")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(String.class)
                            .value(body -> assertThat(body).contains("bootui-test-index"));
                });
    }

    @Test
    void serveSpaShellAtBootUiRoot() {
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    // BootUiIndexController.INDEX_LOCATION resolves to the same
                    // META-INF/resources/bootui/index.html test fixture the static-asset tests use, so this is a
                    // genuine end-to-end proof that DispatcherHandler routed the request to the reactive
                    // @Controller bean (via WebFluxAutoConfiguration's annotation mapping) and that it served the
                    // real production ClassPathResource constructor path, not just the stubbed test constructor
                    // exercised by ReactiveBootUiIndexControllerTests.
                    client.get()
                            .uri("/bootui")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectHeader()
                            .contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML)
                            .expectBody(String.class)
                            .value(body -> assertThat(body)
                                    .contains("bootui-test-index")
                                    .contains("<base href=\"/bootui/\" />"));
                });
    }

    private static ReactiveWebApplicationContextRunner webFluxRunner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpHandlerAutoConfiguration.class,
                        WebFluxAutoConfiguration.class,
                        BootUiReactiveAutoConfiguration.class));
    }
}
