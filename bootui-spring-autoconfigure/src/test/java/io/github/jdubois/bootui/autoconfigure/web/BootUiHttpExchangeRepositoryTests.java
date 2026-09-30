package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.servlet.actuate.web.exchanges.HttpExchangesFilter;

class BootUiHttpExchangeRepositoryTests {

    private static HttpExchange exchange(String path, int status, long durationMs) {
        return new HttpExchange(
                Instant.parse("2026-06-03T09:15:00Z"),
                new HttpExchange.Request(URI.create("http://localhost:8080" + path), "127.0.0.1", "GET", Map.of()),
                new HttpExchange.Response(status, Map.of()),
                null,
                null,
                Duration.ofMillis(durationMs));
    }

    private static String path(HttpExchange exchange) {
        return exchange.getRequest().getUri().getPath();
    }

    @Test
    void floodOfSuccessesKeepsRecentServerErrorsAndSlowExchangesUpToTheReservedShare() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(6, 50, 1_000L, false);
        repository.add(exchange("/server-error", 500, 5));
        repository.add(exchange("/slow", 200, 1_000));
        repository.add(exchange("/not-found", 404, 5));
        repository.add(exchange("/unavailable", 503, 5));
        repository.add(exchange("/older-failure", 500, 5));
        for (int i = 0; i < 100; i++) {
            repository.add(exchange("/ok-" + i, 200, 999));
        }

        assertThat(repository.findAll())
                .extracting(BootUiHttpExchangeRepositoryTests::path)
                .containsExactly("/ok-99", "/ok-98", "/ok-97", "/older-failure", "/unavailable", "/slow");
        assertThat(repository.retention(repository.snapshot()))
                .isEqualTo(new CaptureRetentionDto(false, 6, 3, 6, 3, 99L, 1_000L));
    }

    @Test
    void zeroSlowThresholdReservesOnlyServerErrors() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(3, 50, 0L, false);
        repository.add(exchange("/server-error", 500, 5));
        repository.add(exchange("/very-slow", 200, 60_000));
        repository.add(exchange("/ok-1", 200, 1));
        repository.add(exchange("/ok-2", 200, 1));

        assertThat(repository.findAll())
                .extracting(BootUiHttpExchangeRepositoryTests::path)
                .containsExactly("/ok-2", "/ok-1", "/server-error");
        assertThat(repository.retention(repository.snapshot()).slowThresholdMillis())
                .isZero();
    }

    @Test
    void capacityOfOneKeepsTheNewestExchange() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(1, 50, 1_000L, false);
        repository.add(exchange("/server-error", 500, 5));
        repository.add(exchange("/ok", 200, 5));

        assertThat(repository.findAll())
                .extracting(BootUiHttpExchangeRepositoryTests::path)
                .containsExactly("/ok");
        assertThat(repository.retention(repository.snapshot()).reservedCapacity())
                .isZero();
    }

    @Test
    void keepsEveryExchangeItIsGivenBecauseTheRecordingFilterDecidesWhatIsSelf() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(5, 0, 1_000L, false);
        repository.add(exchange("/api/orders", 200, 5));
        repository.add(exchange("/api/orders?next=/bootui", 500, 5));

        assertThat(repository.findAll())
                .extracting(exchange -> exchange.getRequest().getUri().toString())
                .containsExactly("http://localhost:8080/api/orders?next=/bootui", "http://localhost:8080/api/orders");
    }

    @Test
    void applicationRecordingKeepsPlainOldestFirstRetentionAndReportsItAsApplicationManaged() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(2, 50, 1_000L, true);
        repository.add(exchange("/server-error", 500, 5));
        repository.add(exchange("/bootui/api/panels", 200, 5));
        repository.add(exchange("/ok", 200, 5));

        assertThat(repository.ownsRetention()).isFalse();
        assertThat(repository.findAll())
                .extracting(BootUiHttpExchangeRepositoryTests::path)
                .containsExactly("/ok", "/bootui/api/panels");
        assertThat(repository.retention(repository.snapshot())).isEqualTo(CaptureRetentionDto.applicationManaged(2));
    }

    @Test
    void detectsAnApplicationRecordingFilterFromBeanDefinitionsOnly() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "bootUiHttpExchangesFilter", new RootBeanDefinition(BootUiHttpExchangesFilter.class));
        assertThat(BootUiHttpExchangeRepository.isRecordedByApplication(
                        beanFactory, HttpExchangesFilter.class, "bootUiHttpExchangesFilter"))
                .isFalse();

        beanFactory.registerBeanDefinition(
                "applicationHttpExchangesFilter", new RootBeanDefinition(HttpExchangesFilter.class));
        assertThat(BootUiHttpExchangeRepository.isRecordedByApplication(
                        beanFactory, HttpExchangesFilter.class, "bootUiHttpExchangesFilter"))
                .isTrue();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    void ignoresNullExchanges() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(2, 50, 1_000L, false);
        repository.add(null);

        assertThat(repository.findAll()).isEmpty();
    }
}
