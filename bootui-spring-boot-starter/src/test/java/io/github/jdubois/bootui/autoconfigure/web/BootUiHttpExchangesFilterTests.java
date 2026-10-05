package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.Include;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BootUiHttpExchangesFilterTests {

    private static MockHttpServletRequest request(String contextPath, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", contextPath + path);
        request.setContextPath(contextPath);
        request.setQueryString(query);
        return request;
    }

    private static List<String> recordedUris(HttpExchangeRepository repository) {
        return repository.findAll().stream()
                .map(HttpExchange::getRequest)
                .map(request -> request.getUri().toString())
                .toList();
    }

    private static void perform(BootUiHttpExchangesFilter filter, MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private static BootUiHttpExchangesFilter filter(
            HttpExchangeRepository repository, BootUiProperties properties, BootUiSelfDataFilter selfDataFilter) {
        return new BootUiHttpExchangesFilter(repository, Set.of(Include.REQUEST_HEADERS), properties, selfDataFilter);
    }

    @Test
    void skipsBootUiRequestsBelowTheContextPathButRecordsApplicationRequests() throws Exception {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesFilter filter = filter(repository, new BootUiProperties(), BootUiSelfDataFilter.defaults());

        perform(filter, request("/app", "/bootui/api/http-exchanges", null));
        perform(filter, request("/app", "/bootui", null));
        perform(filter, request("/app", "/bootuix", null));
        perform(filter, request("/app", "/api/orders", "next=/bootui/api"));

        assertThat(recordedUris(repository))
                .containsExactly("http://localhost/app/api/orders?next=/bootui/api", "http://localhost/app/bootuix");
    }

    @Test
    void honorsCustomUiAndApiMounts() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/console");
        properties.setApiPath("/internal/console-api");
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesFilter filter = filter(repository, properties, BootUiSelfDataFilter.defaults());

        perform(filter, request("", "/console/index.html", null));
        perform(filter, request("", "/internal/console-api/http-exchanges", null));
        perform(filter, request("", "/bootui/api/panels", null));

        assertThat(recordedUris(repository)).containsExactly("http://localhost/bootui/api/panels");
    }

    @Test
    void recordsBootUiRequestsWhenExcludeSelfIsOff() throws Exception {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(10, 25, 1_000L, false);
        BootUiHttpExchangesFilter filter = filter(repository, new BootUiProperties(), BootUiSelfDataFilter.disabled());

        perform(filter, request("", "/bootui/api/panels", null));

        assertThat(recordedUris(repository)).containsExactly("http://localhost/bootui/api/panels");
    }

    @Test
    void recordsEverythingIntoAnApplicationRepositoryOrAnApplicationRecordedRepository() throws Exception {
        InMemoryHttpExchangeRepository applicationRepository = new InMemoryHttpExchangeRepository();
        perform(
                filter(applicationRepository, new BootUiProperties(), BootUiSelfDataFilter.defaults()),
                request("", "/bootui/api/panels", null));
        BootUiHttpExchangeRepository applicationRecorded = new BootUiHttpExchangeRepository(10, 25, 1_000L, true);
        perform(
                filter(applicationRecorded, new BootUiProperties(), BootUiSelfDataFilter.defaults()),
                request("", "/bootui/api/panels", null));

        assertThat(recordedUris(applicationRepository)).containsExactly("http://localhost/bootui/api/panels");
        assertThat(recordedUris(applicationRecorded)).containsExactly("http://localhost/bootui/api/panels");
    }
}
