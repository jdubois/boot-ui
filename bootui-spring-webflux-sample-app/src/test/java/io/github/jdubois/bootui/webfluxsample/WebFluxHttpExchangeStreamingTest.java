package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Flux;

@SpringBootTest(
        classes = {BootUiWebfluxSampleApplication.class, WebFluxHttpExchangeStreamingTest.Fixture.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/streaming-capture/application-bootui.properties",
            "bootui.claude-code.enabled=OFF"
        })
@ExtendWith(OutputCaptureExtension.class)
class WebFluxHttpExchangeStreamingTest {

    private static final String CHUNK = "%PDF-1.4\n" + "x".repeat(100_000);

    @LocalServerPort
    int port;

    @Autowired
    RecordingRepository repository;

    @Test
    void streamsTheFullPdfDespiteARepositoryFailureDuringCommit(CapturedOutput output) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        var response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/recording-stream"))
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(response.body()).isEqualTo((CHUNK + CHUNK).getBytes(StandardCharsets.US_ASCII));
        assertThat(repository.failedAdds.get()).isEqualTo(1);
        assertThat(output)
                .contains("BootUI skipped an HTTP exchange capture (IllegalStateException)")
                .doesNotContain("secret-capture-message", "Error finishing response", "Error has been observed");
    }

    static class RecordingRepository extends InMemoryHttpExchangeRepository {
        final AtomicInteger failedAdds = new AtomicInteger();

        @Override
        public void add(HttpExchange exchange) {
            if (exchange.getRequest().getUri().getPath().equals("/api/recording-stream")) {
                failedAdds.incrementAndGet();
                throw new IllegalStateException("secret-capture-message");
            }
            super.add(exchange);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.web.bind.annotation.RestController
    static class Fixture {
        @Bean
        RecordingRepository streamingRepository() {
            return new RecordingRepository();
        }

        @GetMapping("/api/recording-stream")
        ResponseEntity<Flux<byte[]>> download() {
            byte[] chunk = CHUNK.getBytes(StandardCharsets.US_ASCII);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(Flux.just(chunk, chunk));
        }
    }
}
