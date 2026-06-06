package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.context.WebApplicationContext;

class BootUiWebApplicationTypeEnvironmentPostProcessorTests {

    private static final String TYPE_PROPERTY =
            BootUiWebApplicationTypeEnvironmentPostProcessor.WEB_APPLICATION_TYPE_PROPERTY;

    private static final String SOURCE_NAME = BootUiWebApplicationTypeEnvironmentPostProcessor.PROPERTY_SOURCE_NAME;

    private final BootUiWebApplicationTypeEnvironmentPostProcessor processor =
            new BootUiWebApplicationTypeEnvironmentPostProcessor(noOpLogFactory());

    @Test
    void forcesServletWhenActiveAndWebTypeIsNone() {
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.enabled", "ON");

        processor.postProcessEnvironment(environment, applicationWithWebType(WebApplicationType.NONE));

        assertThat(environment.getPropertySources().contains(SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty(TYPE_PROPERTY)).isEqualTo(WebApplicationType.SERVLET.name());
    }

    @Test
    void forcesServletWhenActiveAndWebTypePropertyIsNone() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("bootui.enabled", "ON").withProperty(TYPE_PROPERTY, "none");

        processor.postProcessEnvironment(environment, applicationWithWebType(WebApplicationType.SERVLET));

        assertThat(environment.getPropertySources().contains(SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty(TYPE_PROPERTY)).isEqualTo(WebApplicationType.SERVLET.name());
    }

    @Test
    void doesNotForceWhenAlreadyServlet() {
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.enabled", "ON");

        processor.postProcessEnvironment(environment, applicationWithWebType(WebApplicationType.SERVLET));

        assertThat(environment.getPropertySources().contains(SOURCE_NAME)).isFalse();
    }

    @Test
    void doesNotForceWhenReactiveWebType() {
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.enabled", "ON");

        processor.postProcessEnvironment(environment, applicationWithWebType(WebApplicationType.REACTIVE));

        assertThat(environment.getPropertySources().contains(SOURCE_NAME)).isFalse();
    }

    @Test
    void doesNotForceWhenReactiveRequestedByProperty() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("bootui.enabled", "ON").withProperty(TYPE_PROPERTY, "reactive");

        processor.postProcessEnvironment(environment, applicationWithWebType(WebApplicationType.NONE));

        assertThat(environment.getPropertySources().contains(SOURCE_NAME)).isFalse();
    }

    @Test
    void doesNotForceWhenBootUiInactive() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, applicationWithWebType(WebApplicationType.NONE));

        assertThat(environment.getPropertySources().contains(SOURCE_NAME)).isFalse();
    }

    @Test
    void doesNotForceWhenDisabledByForceWebProperty() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("bootui.enabled", "ON").withProperty("bootui.force-web", "false");

        processor.postProcessEnvironment(environment, applicationWithWebType(WebApplicationType.NONE));

        assertThat(environment.getPropertySources().contains(SOURCE_NAME)).isFalse();
    }

    @Test
    void doesNotForceWhenNoServletContainerOnClasspath() {
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.enabled", "ON");
        SpringApplication application = applicationWithClassLoader(new FilteredClassLoader(
                "org.apache.catalina.startup.Tomcat", "org.eclipse.jetty.server.Server", "io.undertow.Undertow"));
        application.setWebApplicationType(WebApplicationType.NONE);

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getPropertySources().contains(SOURCE_NAME)).isFalse();
    }

    @Test
    void doesNotForceWhenDispatcherServletMissing() {
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.enabled", "ON");
        SpringApplication application = applicationWithClassLoader(
                new FilteredClassLoader("org.springframework.web.servlet.DispatcherServlet"));
        application.setWebApplicationType(WebApplicationType.NONE);

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getPropertySources().contains(SOURCE_NAME)).isFalse();
    }

    @Test
    void commandLineApplicationStartsServletAndActivatesBootUi() {
        try (ConfigurableApplicationContext context = cliApplication()
                .web(WebApplicationType.NONE)
                .properties("bootui.enabled=ON")
                .run()) {
            assertThat(context).isInstanceOf(WebApplicationContext.class);
            assertThat(context.containsBean("bootUiActivation")).isTrue();
            assertThat(context.getEnvironment().getProperty("local.server.port"))
                    .isNotNull();
        }
    }

    @Test
    void commandLineApplicationStaysNonWebWhenForceWebDisabled() {
        try (ConfigurableApplicationContext context = cliApplication()
                .web(WebApplicationType.NONE)
                .properties("bootui.enabled=ON", "bootui.force-web=false")
                .run()) {
            assertThat(context).isNotInstanceOf(WebApplicationContext.class);
            assertThat(context.containsBean("bootUiActivation")).isFalse();
        }
    }

    private SpringApplicationBuilder cliApplication() {
        return new SpringApplicationBuilder(CommandLineApplication.class)
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "spring.jmx.enabled=false",
                        "spring.flyway.enabled=false",
                        "spring.liquibase.enabled=false");
    }

    private static SpringApplication applicationWithWebType(WebApplicationType type) {
        SpringApplication application = new SpringApplication();
        application.setWebApplicationType(type);
        return application;
    }

    private static SpringApplication applicationWithClassLoader(ClassLoader classLoader) {
        return new SpringApplication(new DefaultResourceLoader(classLoader));
    }

    private static DeferredLogFactory noOpLogFactory() {
        return destination -> new DeferredLog();
    }

    @EnableAutoConfiguration
    static class CommandLineApplication {}
}
