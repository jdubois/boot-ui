package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.web.BootUiHttpExchangeRepository;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.Include;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class BootUiHttpExchangesWebFilterTests {

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().setComplete();
    };

    private static void perform(BootUiHttpExchangesWebFilter filter, MockServerHttpRequest request) {
        filter.filter(MockServerWebExchange.from(request), OK_CHAIN).block(Duration.ofSeconds(5));
    }

    private static List<String> recordedPaths(BootUiHttpExchangeRepository repository) {
        return repository.findAll().stream()
                .map(HttpExchange::getRequest)
                .map(request -> request.getUri().getRawPath()
                        + (request.getUri().getRawQuery() == null
                                ? ""
                                : "?" + request.getUri().getRawQuery()))
                .toList();
    }

    @Test
    void skipsBootUiRequestsBelowTheBasePathButRecordsApplicationRequests() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesWebFilter filter = new BootUiHttpExchangesWebFilter(
                repository, Set.of(Include.REQUEST_HEADERS), new BootUiProperties(), BootUiSelfDataFilter.defaults());

        perform(
                filter,
                MockServerHttpRequest.get("/app/bootui/api/http-exchanges")
                        .contextPath("/app")
                        .build());
        perform(
                filter,
                MockServerHttpRequest.get("/app/api/orders?next=/bootui/api")
                        .contextPath("/app")
                        .build());
        perform(filter, MockServerHttpRequest.get("/bootuix").build());

        assertThat(recordedPaths(repository)).containsExactly("/bootuix", "/app/api/orders?next=/bootui/api");
    }

    @Test
    void recordsBootUiRequestsWhenExcludeSelfIsOffOrTheApplicationRecords() {
        BootUiHttpExchangeRepository owned = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        perform(
                new BootUiHttpExchangesWebFilter(
                        owned, Set.of(), new BootUiProperties(), BootUiSelfDataFilter.disabled()),
                MockServerHttpRequest.get("/bootui/api/panels").build());
        BootUiHttpExchangeRepository applicationRecorded = new BootUiHttpExchangeRepository(10, 25, 1_000L, true);
        perform(
                new BootUiHttpExchangesWebFilter(
                        applicationRecorded, Set.of(), new BootUiProperties(), BootUiSelfDataFilter.defaults()),
                MockServerHttpRequest.get("/bootui/api/panels").build());

        assertThat(recordedPaths(owned)).containsExactly("/bootui/api/panels");
        assertThat(recordedPaths(applicationRecorded)).containsExactly("/bootui/api/panels");
    }

    @Test
    void passesSkippedRequestsThroughTheChain() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesWebFilter filter = new BootUiHttpExchangesWebFilter(
                repository, Set.of(), new BootUiProperties(), BootUiSelfDataFilter.defaults());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/bootui/api/panels"));

        filter.filter(exchange, served -> {
                    served.getResponse().setStatusCode(HttpStatus.ACCEPTED);
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(repository.findAll()).isEmpty();
    }
}
