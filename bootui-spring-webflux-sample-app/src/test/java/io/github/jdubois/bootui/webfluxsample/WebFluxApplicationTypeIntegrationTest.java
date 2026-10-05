package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.reactor.netty.NettyWebServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * The single {@code bootui-spring-boot-starter} brings no web stack, so a WebFlux application that adds it stays a
 * reactive application on Netty. Before 2.0 the servlet starter, put on a WebFlux application, made Spring Boot pick
 * {@link WebApplicationType#SERVLET}; the starter's enforcer rule keeps the servlet stack out of its dependencies,
 * and this test pins the outcome on the application that depends on it.
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/application-type-test-overrides.properties"
        })
class WebFluxApplicationTypeIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void springBootDeducesAReactiveApplication() {
        assertThat(WebApplicationType.deduce()).isEqualTo(WebApplicationType.REACTIVE);
        assertThat(new SpringApplication(BootUiWebfluxSampleApplication.class).getWebApplicationType())
                .isEqualTo(WebApplicationType.REACTIVE);
    }

    @Test
    void noServletStackIsOnTheClasspath() {
        for (String servletClass : new String[] {
            "jakarta.servlet.Servlet",
            "org.springframework.web.servlet.DispatcherServlet",
            "org.apache.catalina.startup.Tomcat",
            "org.springframework.boot.tomcat.TomcatWebServer"
        }) {
            assertThat(ClassUtils.isPresent(servletClass, getClass().getClassLoader()))
                    .as(servletClass)
                    .isFalse();
        }
    }

    @Test
    void runsOnAReactiveNettyServer() {
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext.class);
        assertThat(((ReactiveWebServerApplicationContext) context).getWebServer())
                .isInstanceOf(NettyWebServer.class);
    }

    @Test
    void servesTheBootUiConsole() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bootui/"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<title>BootUI</title>").contains("id=\"app\"");
    }
}
