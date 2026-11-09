package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
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
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@SpringBootTest(
        classes = {BootUiSampleApplication.class, SpringHttpExchangeStreamingTest.Fixture.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/streaming-capture/application-bootui.properties",
            "bootui.claude-code.enabled=OFF",
            "management.httpexchanges.recording.include=request-headers,response-headers,time-taken"
        })
@ExtendWith(OutputCaptureExtension.class)
class SpringHttpExchangeStreamingTest {

    private static final String BODY = "%PDF-1.4\n" + "x".repeat(200_000);

    @LocalServerPort
    int port;

    @Autowired
    RecordingRepository repository;

    @Autowired
    Fixture fixture;

    @Test
    void streamsTheFullPdfDespiteMalformedMetadataAndRepositoryFailures(CapturedOutput output) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (String anomaly : new String[] {"null-header", "metadata", "repository"}) {
            var response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/recording-stream/" + anomaly))
                            .timeout(Duration.ofSeconds(10))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/pdf");
            assertThat(response.body()).isEqualTo(BODY.getBytes(StandardCharsets.US_ASCII));
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(fixture.metadataReads.get()).isEqualTo(2);
            assertThat(repository.failedAdds.get()).isEqualTo(1);
            assertThat(repository.findAll()).anySatisfy(exchange -> {
                assertThat(exchange.getRequest().getUri().getPath()).endsWith("/null-header");
                assertThat(exchange.getResponse().getHeaders()).doesNotContainKey(null);
            });
        });
        assertThat(fixture.errorDispatches.get()).isZero();
        assertThat(output).doesNotContain("Cannot render error page", "secret-capture-message");
    }

    static class RecordingRepository extends InMemoryHttpExchangeRepository {
        final AtomicInteger failedAdds = new AtomicInteger();

        @Override
        public void add(HttpExchange exchange) {
            if (exchange.getRequest().getUri().getPath().endsWith("/repository")) {
                failedAdds.incrementAndGet();
                throw new IllegalStateException("secret-capture-message");
            }
            super.add(exchange);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.web.bind.annotation.RestController
    static class Fixture {
        final AtomicInteger metadataReads = new AtomicInteger();
        final AtomicInteger errorDispatches = new AtomicInteger();

        @Bean
        RecordingRepository streamingRepository() {
            return new RecordingRepository();
        }

        @Bean
        FilterRegistrationBean<Filter> anomalousRecordingMetadata() {
            Filter filter = (request, response, chain) -> {
                HttpServletRequest http = (HttpServletRequest) request;
                if (http.getDispatcherType() == DispatcherType.ERROR) {
                    errorDispatches.incrementAndGet();
                }
                String path = http.getRequestURI();
                if (http.getDispatcherType() == DispatcherType.REQUEST
                        && (path.endsWith("/null-header") || path.endsWith("/metadata"))) {
                    chain.doFilter(request, new HttpServletResponseWrapper((HttpServletResponse) response) {
                        @Override
                        public Collection<String> getHeaderNames() {
                            metadataReads.incrementAndGet();
                            if (path.endsWith("/metadata")) {
                                throw new IllegalStateException("secret-capture-message");
                            }
                            var names = new ArrayList<>(super.getHeaderNames());
                            names.add(null);
                            return names;
                        }
                    });
                } else {
                    chain.doFilter(request, response);
                }
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
            registration.addUrlPatterns("/api/recording-stream/*", "/error");
            return registration;
        }

        @GetMapping("/api/recording-stream/{anomaly}")
        ResponseEntity<StreamingResponseBody> download(@PathVariable String anomaly) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(out -> out.write(BODY.getBytes(StandardCharsets.US_ASCII)));
        }
    }
}
