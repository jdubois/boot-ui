package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Regression test for issue #456 ("localhost redirected you too many times").
 *
 * <p>Boots the sample app with the {@code redirectloop} profile active, which registers
 * {@link io.github.jdubois.bootui.sample.config.TrailingSlashHandlerFilter} &mdash; the exact
 * Spring {@code UrlHandlerFilter} setup the reporter runs to strip trailing slashes. When BootUI
 * answered {@code GET /bootui} with a {@code 302 -> /bootui/}, that combination looped forever.</p>
 *
 * <p>BootUI now serves the console at both {@code /bootui} and {@code /bootui/} (no redirect), so
 * these assertions fail if the trailing-slash redirect is ever reintroduced.</p>
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev,redirectloop",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-redirectloop-test-overrides.properties"
        })
class BootUiSampleApplicationRedirectLoopProfileTests {

    @LocalServerPort
    int port;

    @Test
    void bootuiRootIsServedDirectlyWithoutRedirect() {
        // Redirects disabled: a 302 here (the old behaviour) would be the first leg of the #456 loop.
        ResponseEntity<String> response =
                noFollowClient().get().uri("/bootui").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getStatusCode().is3xxRedirection()).isFalse();
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML))
                .isTrue();
        // The injected <base href> is what lets the SPA load from the no-slash URL.
        assertThat(response.getBody()).contains("<base href=\"/bootui/\"");
    }

    @Test
    void bootuiTrailingSlashIsServedWithoutRedirect() {
        ResponseEntity<String> response =
                noFollowClient().get().uri("/bootui/").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<base href=\"/bootui/\"");
    }

    @Test
    void bootuiDoesNotLoopWhenFollowingRedirects() {
        // A following client throws (too many redirects) if the #456 loop is reintroduced.
        RestClient client = followClient();

        assertThatCode(() -> {
                    ResponseEntity<String> root =
                            client.get().uri("/bootui").retrieve().toEntity(String.class);
                    assertThat(root.getStatusCode()).isEqualTo(HttpStatus.OK);
                    ResponseEntity<String> slash =
                            client.get().uri("/bootui/").retrieve().toEntity(String.class);
                    assertThat(slash.getStatusCode()).isEqualTo(HttpStatus.OK);
                })
                .doesNotThrowAnyException();
    }

    private RestClient noFollowClient() {
        return client(Redirect.NEVER);
    }

    private RestClient followClient() {
        return client(Redirect.NORMAL);
    }

    private RestClient client(Redirect redirectPolicy) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(redirectPolicy)
                        .proxy(new NoProxySelector())
                        .build()))
                // Never throw on non-2xx — tests inspect the status directly.
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
                .build();
    }

    private static final class NoProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {}
    }
}
