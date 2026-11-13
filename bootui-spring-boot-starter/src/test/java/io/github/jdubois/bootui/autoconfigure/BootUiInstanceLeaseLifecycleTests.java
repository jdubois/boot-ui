package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.context.reactive.GenericReactiveWebApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.context.support.GenericWebApplicationContext;

class BootUiInstanceLeaseLifecycleTests {

    @TempDir
    Path tempDir;

    private WebApplicationContextRunner servletRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        BootUiAutoConfiguration.class));
    }

    private ReactiveWebApplicationContextRunner reactiveRunner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiReactiveAutoConfiguration.class));
    }

    @Test
    void disabledByDefaultUnderTestFrameworksUnlessExplicitlyEnabled() {
        servletRunner()
                .withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.cli.instances-dir=" + tempDir.toString(),
                        "local.server.port=18080")
                .run(context -> {
                    BootUiInstanceLeaseLifecycle lifecycle = context.getBean(BootUiInstanceLeaseLifecycle.class);
                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(), new String[0], context.getSourceApplicationContext(), null));
                    assertThat(lifecycle.leaseFile()).isNull();
                    assertThat(tempDir).isEmptyDirectory();
                });
    }

    @Test
    void writesLeaseOnReadyWithServletContextAndMvcPathsAndDeletesOnClose() throws Exception {
        final Path[] leasePath = new Path[1];
        servletRunner()
                .withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.cli.instance-discovery.enabled=true",
                        "bootui.cli.instances-dir=" + tempDir.toString(),
                        "spring.application.name=order-service",
                        "local.server.port=18081",
                        "server.servlet.context-path=/shop",
                        "spring.mvc.servlet.path=/mvc",
                        "bootui.path=/console")
                .run(context -> {
                    BootUiInstanceLeaseLifecycle lifecycle = context.getBean(BootUiInstanceLeaseLifecycle.class);
                    assertThat(lifecycle.leaseFile())
                            .as("lease must not be written before port binding / ready event")
                            .isNull();
                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(), new String[0], context.getSourceApplicationContext(), null));
                    leasePath[0] = lifecycle.leaseFile();
                    assertThat(leasePath[0]).isNotNull().exists();
                    String json = Files.readString(leasePath[0]);
                    assertThat(json)
                            .contains("\"instanceId\": \"" + RunIdentity.instanceId() + "\"")
                            .contains("\"appName\": \"order-service\"")
                            .contains("\"framework\": \"Spring Boot\"")
                            .contains("\"baseUrl\": \"http://localhost:18081\"")
                            .contains("\"apiPath\": \"/shop/mvc/console/api\"");
                });
        assertThat(leasePath[0]).doesNotExist();
    }

    @Test
    void writesLeaseOnReactiveStackWithWebFluxBasePathAndDeletesOnClose() throws Exception {
        final Path[] leasePath = new Path[1];
        reactiveRunner()
                .withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.cli.instance-discovery.enabled=true",
                        "bootui.cli.instances-dir=" + tempDir.toString(),
                        "spring.application.name=reactive-gateway",
                        "local.server.port=18082",
                        "spring.webflux.base-path=/flux")
                .run(context -> {
                    BootUiInstanceLeaseLifecycle lifecycle = context.getBean(BootUiInstanceLeaseLifecycle.class);
                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(), new String[0], context.getSourceApplicationContext(), null));
                    leasePath[0] = lifecycle.leaseFile();
                    assertThat(leasePath[0]).isNotNull().exists();
                    String json = Files.readString(leasePath[0]);
                    assertThat(json)
                            .contains("\"appName\": \"reactive-gateway\"")
                            .contains("\"baseUrl\": \"http://localhost:18082\"")
                            .contains("\"apiPath\": \"/flux/bootui/api\"");
                });
        assertThat(leasePath[0]).doesNotExist();
    }

    @Test
    void doesNotWriteLeaseWhenPortIsZeroOrCliEndpointIsDisabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("bootui.cli.instance-discovery.enabled", "true")
                .withProperty("bootui.cli.instances-dir", tempDir.toString())
                .withProperty("server.port", "0");
        GenericWebApplicationContext context = new GenericWebApplicationContext();
        context.setEnvironment(env);
        BootUiProperties properties = new BootUiProperties();
        BootUiInstanceLeaseLifecycle lifecycle =
                new BootUiInstanceLeaseLifecycle(RunIdentity.start(), properties, context, false);

        assertThat(lifecycle.registerIfEligible(null)).isNull();

        properties.getCli().setEnabled(false);
        assertThat(lifecycle.registerIfEligible(18083)).isNull();

        GenericReactiveWebApplicationContext reactiveContext = new GenericReactiveWebApplicationContext();
        reactiveContext.setEnvironment(new MockEnvironment().withProperty("spring.webflux.base-path", "/rx"));
        BootUiInstanceLeaseLifecycle reactiveLifecycle =
                new BootUiInstanceLeaseLifecycle(RunIdentity.start(), new BootUiProperties(), reactiveContext, false);
        assertThat(reactiveLifecycle.resolveEffectiveApiPath(reactiveContext.getEnvironment()))
                .isEqualTo("/rx/bootui/api");
    }

    @Test
    void writesLeaseImmediatelyOnWebServerInitializedEventBeforeApplicationReady() throws Exception {
        servletRunner()
                .withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.cli.instance-discovery.enabled=true",
                        "bootui.cli.instances-dir=" + tempDir.toString(),
                        "spring.application.name=early-web-service",
                        "server.ssl.bundle=dev-tls")
                .run(context -> {
                    BootUiInstanceLeaseLifecycle lifecycle = context.getBean(BootUiInstanceLeaseLifecycle.class);
                    WebServer webServer = new WebServer() {
                        @Override
                        public void start() {}

                        @Override
                        public void stop() {}

                        @Override
                        public int getPort() {
                            return 18099;
                        }
                    };
                    lifecycle.onApplicationEvent(new WebServerInitializedEvent(webServer) {
                        @Override
                        public WebServerApplicationContext getApplicationContext() {
                            return null;
                        }
                    });
                    assertThat(lifecycle.leaseFile()).isNull();

                    Path written = lifecycle.registerIfEligible(18099);
                    assertThat(written).isNotNull().exists();
                    assertThat(Files.readString(written)).contains("\"baseUrl\": \"https://localhost:18099\"");
                });
    }
}
