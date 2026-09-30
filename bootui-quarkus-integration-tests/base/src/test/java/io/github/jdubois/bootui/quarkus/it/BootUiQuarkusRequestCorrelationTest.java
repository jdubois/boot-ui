package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Proves exact request correlation on Quarkus without OpenTelemetry ({@code docs/PLAN-v2.md} §5.1, M1-2): the request
 * id BootUI reports while a request runs, on the event loop and after the hop to a worker thread, is the id of the
 * exchange it captures for that request, even when identical requests overlap.
 */
@QuarkusTest
class BootUiQuarkusRequestCorrelationTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void aWorkerThreadSeesTheRequestIdOfItsExchange() {
        String[] observed = probe().get("/it/correlation/worker").body().split("\\|");

        assertThat(observed[0]).as("served on a worker thread").doesNotContain("eventloop");
        assertThat(observed[1]).matches("[0-9a-f]{16}");
        assertThat(exchangeIds("/it/correlation/worker")).contains(observed[1]);
    }

    @Test
    void theEventLoopSeesTheRequestIdOfItsExchange() {
        String[] observed = probe().get("/it/correlation/event-loop").body().split("\\|");

        assertThat(observed[0]).as("served on the event loop").contains("eventloop");
        assertThat(observed[1]).matches("[0-9a-f]{16}");
        assertThat(exchangeIds("/it/correlation/event-loop")).contains(observed[1]);
    }

    @Test
    void simultaneousIdenticalRequestsEachKeepTheirOwnId() throws Exception {
        int concurrency = 16;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<String> observed = new ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                results.add(executor.submit(() -> {
                    go.await();
                    return probe().get("/it/correlation/worker").body().split("\\|")[1];
                }));
            }
            go.countDown();
            for (Future<String> result : results) {
                observed.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(observed).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(exchangeIds("/it/correlation/worker")).containsAll(observed);
    }

    private List<String> exchangeIds(String path) {
        JsonNode report = probe().get("/bootui/api/http-exchanges?limit=200").json();
        List<String> ids = new ArrayList<>();
        for (JsonNode exchange : report.path("exchanges")) {
            if (path.equals(exchange.path("path").asText())) {
                String id = exchange.path("id").asText();
                assertThat(exchange.path("requestId").asText())
                        .as("the request id is the exchange id")
                        .isEqualTo(id);
                ids.add(id);
            }
        }
        return ids;
    }
}
