package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

/**
 * WebFlux twin of {@code BootUiShellGuardFilterTests}: the reactive guard must block exactly the same
 * surface, including under a {@code spring.webflux.base-path} and for encoded spellings of the mount.
 */
class ReactiveBootUiShellGuardFilterTests {

    private static final List<String> DEFAULT_MOUNTS = List.of(BootUiPathNormalizer.DEFAULT_PATH);

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().setComplete();
    };

    @Test
    void blocksThePackagedShellAndItsAssets() {
        assertThat(status("/bootui/index.html", null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/bootui/assets/index-abc123.js", null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/bootui/", null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/bootui", null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/bootui/api/overview", null)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void blocksPercentEncodedAndMatrixParameterSpellingsOfTheMount() {
        // PathPattern matches on the decoded segment value, so these reach the packaged bundle.
        assertThat(status("/%62ootui/index.html", null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/boot%75i/index.html", null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/bootui;version=1/index.html", null)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void blocksTheMountUnderAHostBasePath() {
        assertThat(status("/host/bootui/index.html", "/host")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void blocksEveryConfiguredMount() {
        List<String> mounts = List.of("/bootui", "/static/bootui");
        assertThat(status(mounts, "/static/bootui/index.html", null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(mounts, "/bootui/index.html", null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(mounts, "/static/other.js", null)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void leavesHostApplicationRoutesUntouched() {
        assertThat(status("/api/orders", null)).isEqualTo(HttpStatus.OK);
        assertThat(status("/bootui-console/index.html", null)).isEqualTo(HttpStatus.OK);
        assertThat(status("/host/console/bootui", "/host")).isEqualTo(HttpStatus.OK);
    }

    private static HttpStatus status(String uri, String contextPath) {
        return status(DEFAULT_MOUNTS, uri, contextPath);
    }

    private static HttpStatus status(List<String> mounts, String uri, String contextPath) {
        // method(HttpMethod, URI) rather than get(String): the String overload re-encodes the template,
        // which would turn "%62" into "%2562" and quietly defeat the encoded-path assertions.
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(HttpMethod.GET, URI.create(uri));
        if (contextPath != null) {
            builder = builder.contextPath(contextPath);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);
        new ReactiveBootUiShellGuardFilter(mounts).filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));
        return HttpStatus.valueOf(exchange.getResponse().getStatusCode().value());
    }
}
