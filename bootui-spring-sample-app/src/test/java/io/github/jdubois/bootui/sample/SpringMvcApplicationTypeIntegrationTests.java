package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;

/**
 * The single {@code bootui-spring-boot-starter} brings no web stack: a Spring MVC application keeps its servlet web
 * server from its own {@code spring-boot-starter-web}. This pins that the sample, which declares it, stays a
 * {@link WebApplicationType#SERVLET} application on Tomcat and still serves BootUI.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/application-type-test-overrides.properties"
        })
class SpringMvcApplicationTypeIntegrationTests {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void springBootDeducesAServletApplication() {
        assertThat(WebApplicationType.deduce()).isEqualTo(WebApplicationType.SERVLET);
        assertThat(new SpringApplication(BootUiSampleApplication.class).getWebApplicationType())
                .isEqualTo(WebApplicationType.SERVLET);
    }

    @Test
    void runsOnATomcatServletServer() {
        assertThat(context).isInstanceOf(ServletWebServerApplicationContext.class);
        assertThat(((ServletWebServerApplicationContext) context).getWebServer())
                .isInstanceOf(TomcatWebServer.class);
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
