package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

class ReactiveSecurityHeadersFilterTests {

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().setComplete();
    };

    private ReactiveSecurityHeadersFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ReactiveSecurityHeadersFilter(new BootUiProperties());
    }

    @Test
    void appliesCanonicalHeadersAndNoStoreToApi() {
        MockServerWebExchange exchange = exchange("/bootui/api/overview");

        apply(exchange);

        assertThat(exchange.getResponse().getHeaders().getFirst(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .isEqualTo(BootUiSecurityHeaders.CSP_VALUE);
        assertThat(exchange.getResponse().getHeaders().getFirst(BootUiSecurityHeaders.PERMISSIONS_POLICY))
                .isEqualTo(BootUiSecurityHeaders.PERMISSIONS_POLICY_VALUE);
        assertThat(exchange.getResponse().getHeaders().getFirst(BootUiSecurityHeaders.CACHE_CONTROL))
                .isEqualTo(BootUiSecurityHeaders.NO_STORE);
        assertThat(exchange.getResponse().getHeaders().getFirst(BootUiSecurityHeaders.PRAGMA))
                .isEqualTo(BootUiSecurityHeaders.PRAGMA_NO_CACHE);
    }

    @Test
    void appliesImmutableCachingOnlyToHashedAssets() {
        MockServerWebExchange hashed = exchange("/bootui/assets/index-C2x2BcDS.js");
        MockServerWebExchange unhashed = exchange("/bootui/assets/index.js");

        apply(hashed);
        apply(unhashed);

        assertThat(hashed.getResponse().getHeaders().getFirst(BootUiSecurityHeaders.CACHE_CONTROL))
                .isEqualTo(BootUiSecurityHeaders.IMMUTABLE);
        assertThat(hashed.getResponse().getHeaders().getFirst(BootUiSecurityHeaders.PRAGMA))
                .isNull();
        assertThat(unhashed.getResponse().getHeaders().getFirst(BootUiSecurityHeaders.CACHE_CONTROL))
                .isEqualTo(BootUiSecurityHeaders.NO_CACHE);
    }

    @Test
    void preservesExistingHostSecurityPolicyButOwnsCacheSemantics() {
        MockServerWebExchange exchange = exchange("/bootui/api/overview");
        filter.filter(exchange, hostPolicyChain()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getHeaders().get(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .containsExactly("default-src 'none'");
        assertThat(exchange.getResponse().getHeaders().get(BootUiSecurityHeaders.CACHE_CONTROL))
                .containsExactly(BootUiSecurityHeaders.NO_STORE);
    }

    @Test
    void ownsCacheSemanticsAfterDownstreamCommitCallbacks() {
        MockServerWebExchange exchange = exchange("/bootui/api/overview");
        filter.filter(exchange, hostCommitPolicyChain()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getHeaders().get(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .containsExactly("default-src 'none'");
        assertThat(exchange.getResponse().getHeaders().get(BootUiSecurityHeaders.CACHE_CONTROL))
                .containsExactly(BootUiSecurityHeaders.NO_STORE);
    }

    @Test
    void missingHashedAssetIsNotCachedAsImmutable() {
        MockServerWebExchange exchange = exchange("/bootui/assets/missing-C2x2BcDS.js");

        filter.filter(exchange, notFoundChain()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getHeaders().get(BootUiSecurityHeaders.CACHE_CONTROL))
                .containsExactly(BootUiSecurityHeaders.NO_CACHE);
        assertThat(exchange.getResponse().getHeaders().get(BootUiSecurityHeaders.PRAGMA))
                .containsExactly(BootUiSecurityHeaders.PRAGMA_NO_CACHE);
    }

    @Test
    void skipsNonBootUiPaths() {
        MockServerWebExchange exchange = exchange("/api/hello");

        apply(exchange);

        assertThat(exchange.getResponse().getHeaders().getFirst(BootUiSecurityHeaders.CACHE_CONTROL))
                .isNull();
    }

    @Test
    void runsBeforeTheLocalhostAndPanelFilters() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE);
    }

    private void apply(MockServerWebExchange exchange) {
        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));
    }

    private WebFilterChain hostPolicyChain() {
        return exchange -> {
            exchange.getResponse()
                    .getHeaders()
                    .set(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY, "default-src 'none'");
            exchange.getResponse().getHeaders().set(BootUiSecurityHeaders.CACHE_CONTROL, "public");
            return exchange.getResponse().setComplete();
        };
    }

    private WebFilterChain hostCommitPolicyChain() {
        return exchange -> {
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse()
                        .getHeaders()
                        .set(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY, "default-src 'none'");
                exchange.getResponse().getHeaders().set(BootUiSecurityHeaders.CACHE_CONTROL, "public");
                return reactor.core.publisher.Mono.empty();
            });
            return exchange.getResponse().setComplete();
        };
    }

    private WebFilterChain notFoundChain() {
        return exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        };
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
