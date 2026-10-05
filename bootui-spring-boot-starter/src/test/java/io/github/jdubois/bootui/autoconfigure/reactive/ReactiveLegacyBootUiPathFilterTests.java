package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

class ReactiveLegacyBootUiPathFilterTests {

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().setComplete();
    };

    @Test
    void blocksInternalMountWhenCustomPathIsActive() {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/console");
        ReactiveLegacyBootUiPathFilter filter = new ReactiveLegacyBootUiPathFilter(properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/host/bootui/assets/app.js").contextPath("/host"));

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void leavesDefaultPathAndHostRoutesUntouched() {
        ReactiveLegacyBootUiPathFilter defaultFilter = new ReactiveLegacyBootUiPathFilter(new BootUiProperties());
        MockServerWebExchange defaultExchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/bootui/assets/app.js"));
        BootUiProperties customProperties = new BootUiProperties();
        customProperties.setPath("/console");
        ReactiveLegacyBootUiPathFilter customFilter = new ReactiveLegacyBootUiPathFilter(customProperties);
        MockServerWebExchange hostExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders"));

        defaultFilter.filter(defaultExchange, OK_CHAIN).block(Duration.ofSeconds(5));
        customFilter.filter(hostExchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(defaultExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hostExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
