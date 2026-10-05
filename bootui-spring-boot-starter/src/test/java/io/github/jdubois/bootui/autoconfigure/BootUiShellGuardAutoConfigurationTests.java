package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiShellGuardFilter;
import io.github.jdubois.bootui.autoconfigure.safety.BootUiShellGuardFilter;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests for {@link BootUiShellGuardAutoConfiguration} (issue #856).
 *
 * <p>The console's API is already unreachable when {@link BootUiActivationCondition} does not match,
 * because nothing is registered. The compiled Vue bundle was not: it ships at
 * {@code META-INF/resources/bootui/}, one of Spring Boot's default static-resource locations, so both
 * {@code WebMvcAutoConfiguration} and {@code WebFluxAutoConfiguration} served it regardless of BootUI's
 * activation state. {@code shellIsReachableWithoutTheGuard*} pins that underlying exposure so these
 * tests keep proving something real, and the rest assert the guard closes it on both stacks.</p>
 */
class BootUiShellGuardAutoConfigurationTests {

    private static final ClassPathResource PACKAGED_SHELL =
            new ClassPathResource("META-INF/resources/bootui/index.html");

    @Test
    void registersServletGuardWhenBootUiIsInactive() {
        servletRunner()
                .run(context -> assertThat(context)
                        .hasSingleBean(BootUiShellGuardAutoConfiguration.class)
                        .hasSingleBean(BootUiShellGuardFilter.class));
    }

    @Test
    void registersServletGuardForEveryDisablingReason() {
        servletRunner()
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasSingleBean(BootUiShellGuardFilter.class));
        servletRunner()
                .withPropertyValues("spring.profiles.active=dev", "bootui.enabled=OFF")
                .run(context -> assertThat(context).hasSingleBean(BootUiShellGuardFilter.class));
        // Fail-closed: an unparseable bootui.enabled disables BootUI, so the shell must go dark too.
        servletRunner()
                .withPropertyValues("spring.profiles.active=dev", "bootui.enabled=maybe")
                .run(context -> assertThat(context).hasSingleBean(BootUiShellGuardFilter.class));
    }

    @Test
    void doesNotRegisterServletGuardWhenBootUiIsActive() {
        servletRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(BootUiShellGuardAutoConfiguration.class)
                        .doesNotHaveBean(BootUiShellGuardFilter.class));
        servletRunner()
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiShellGuardFilter.class));
        // bootui.enabled=ON wins over a disabled profile, and so must the guard's polarity.
        servletRunner()
                .withPropertyValues("spring.profiles.active=prod", "bootui.enabled=ON")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiShellGuardFilter.class));
    }

    @Test
    void registersTheServletFilterForTheReservedMountOnly() {
        servletRunner().run(context -> {
            FilterRegistrationBean<?> registration =
                    context.getBean("bootUiShellGuardFilterRegistration", FilterRegistrationBean.class);
            assertThat(registration.getUrlPatterns()).containsExactly("/bootui/*");
            assertThat(registration.getOrder()).isEqualTo(Integer.MIN_VALUE);
        });
    }

    @Test
    void doesNotRegisterAGuardWithoutThePackagedShellOnTheClasspath() {
        // The auto-configuration without bootui-ui: there is no shell to leak, so /bootui stays
        // the host application's to use.
        servletRunner()
                .withClassLoader(new FilteredClassLoader(PACKAGED_SHELL))
                .run(context -> assertThat(context).doesNotHaveBean(BootUiShellGuardFilter.class));
    }

    @Test
    void doesNotRegisterAnyGuardInANonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiShellGuardAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(BootUiShellGuardFilter.class)
                        .doesNotHaveBean(ReactiveBootUiShellGuardFilter.class));
    }

    @Test
    void shellIsReachableWithoutTheGuardOnSpringMvc() {
        // Regression anchor: this is exactly what #856 reported.
        servletRunner().run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(
                            (WebApplicationContext) context.getSourceApplicationContext())
                    .build();
            mvc.perform(get("/bootui/index.html")).andExpect(status().isOk());
            // The encoded spelling resolves to the same bundle entry, which is why the guard has to
            // match the decoded path rather than the raw request URI.
            mvc.perform(get(URI.create("/%62ootui/index.html"))).andExpect(status().isOk());
        });
    }

    @Test
    void guardBlocksTheShellOnSpringMvc() {
        servletRunner().run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(
                            (WebApplicationContext) context.getSourceApplicationContext())
                    .addFilters(context.getBean(BootUiShellGuardFilter.class))
                    .build();
            mvc.perform(get("/bootui/index.html")).andExpect(status().isNotFound());
            mvc.perform(get("/bootui/assets/index-abc123.js")).andExpect(status().isNotFound());
            mvc.perform(get("/bootui")).andExpect(status().isNotFound());
        });
    }

    @Test
    void guardBlocksAnEncodedSpellingOfTheMountOnSpringMvc() {
        // Spring resolves the resource against the decoded path, so a guard that compared the raw
        // request URI would pass this straight through to the packaged bundle.
        servletRunner().run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(
                            (WebApplicationContext) context.getSourceApplicationContext())
                    .addFilters(context.getBean(BootUiShellGuardFilter.class))
                    .build();
            mvc.perform(get(URI.create("/%62ootui/index.html"))).andExpect(status().isNotFound());
        });
    }

    @Test
    void registersReactiveGuardWhenBootUiIsInactive() {
        reactiveRunner()
                .run(context -> assertThat(context)
                        .hasSingleBean(BootUiShellGuardAutoConfiguration.class)
                        .hasSingleBean(ReactiveBootUiShellGuardFilter.class)
                        .doesNotHaveBean(BootUiShellGuardFilter.class));
    }

    @Test
    void doesNotRegisterReactiveGuardWhenBootUiIsActive() {
        reactiveRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context).doesNotHaveBean(ReactiveBootUiShellGuardFilter.class));
    }

    @Test
    void shellIsReachableWithoutTheGuardOnWebFlux() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class))
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    client.get()
                            .uri("/bootui/index.html")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    // Same point as on Spring MVC: the encoded spelling reaches the bundle too.
                    client.get()
                            .uri(URI.create("/%62ootui/index.html"))
                            .exchange()
                            .expectStatus()
                            .isOk();
                });
    }

    @Test
    void guardBlocksTheShellOnWebFlux() {
        reactiveRunner().run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                    .build();
            client.get().uri("/bootui/index.html").exchange().expectStatus().isNotFound();
            client.get()
                    .uri("/bootui/assets/index-abc123.js")
                    .exchange()
                    .expectStatus()
                    .isNotFound();
            client.get().uri("/bootui").exchange().expectStatus().isNotFound();
            client.get()
                    .uri(URI.create("/%62ootui/index.html"))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        });
    }

    @Test
    void reactiveGuardWorksWithoutTheServletApiOnTheClasspath() {
        // A WebFlux-only application has no jakarta.servlet at all. An earlier revision of the guard had
        // the reactive filter call a static on its servlet sibling, which raised
        // NoClassDefFoundError: jakarta/servlet/Filter on *every* request (not only BootUI's), leaving
        // Netty to close the connection with no response.
        reactiveRunner()
                .withClassLoader(new FilteredClassLoader(jakarta.servlet.Filter.class))
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    client.get()
                            .uri("/bootui/index.html")
                            .exchange()
                            .expectStatus()
                            .isNotFound();
                    client.get().uri("/api/orders").exchange().expectStatus().isNotFound();
                });
    }

    private static WebApplicationContextRunner servletRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        BootUiAutoConfiguration.class,
                        BootUiShellGuardAutoConfiguration.class));
    }

    private static ReactiveWebApplicationContextRunner reactiveRunner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpHandlerAutoConfiguration.class,
                        WebFluxAutoConfiguration.class,
                        BootUiReactiveAutoConfiguration.class,
                        BootUiShellGuardAutoConfiguration.class));
    }
}
