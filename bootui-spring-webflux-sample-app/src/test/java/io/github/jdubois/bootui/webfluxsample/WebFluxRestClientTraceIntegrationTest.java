package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bootui.enabled=ON", "spring.devtools.restart.enabled=false"})
class WebFluxRestClientTraceIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void instrumentedWebClientCallAppearsInThePanelReport() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> outbound = client.send(
                HttpRequest.newBuilder(uri("/api/sample/rest-client?name=Integration"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> report = client.send(
                HttpRequest.newBuilder(uri("/bootui/api/rest-client-trace"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(outbound.statusCode()).isEqualTo(200);
        assertThat(outbound.body()).contains("Hello, Integration");
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body())
                .contains("\"available\":true")
                .contains("\"host\":\"127.0.0.1\"")
                .contains("\"path\":\"/api/greetings/Integration\"")
                .contains("\"clientType\":\"WebClient\"");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
