package example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.jdubois.bootui.cli.ci.CiCollection;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OrdersLifecycleTest {

    @TestHTTPResource
    URI application;

    @Test
    void orders() throws Exception {
        exercise("orders", "[\"order-1\",\"order-2\"]");
    }

    @Test
    void catalog() throws Exception {
        exercise("catalog", "[\"book\",\"pen\"]");
    }

    private void exercise(String contextId, String expectedBody) throws Exception {
        String url = "http://127.0.0.1:" + application.getPort();
        CiCollection.Window window = begin(contextId, url);
        try {
            var request = HttpRequest.newBuilder(URI.create(url + "/" + contextId))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(expectedBody, response.body());
            System.out.println("CI_WORKLOAD_COMPLETED=" + contextId);
            assertFalse("true".equals(System.getenv("CI_EXAMPLE_FAIL_WORKLOAD")), "Requested workload failure");
        } finally {
            if (window != null) {
                window.finish();
            }
        }
    }

    private CiCollection.Window begin(String contextId, String url) throws Exception {
        var keys = List.of("BOOTUI_CI_EVIDENCE_ROOT", "BOOTUI_CI_PLAN_SHA256", "BOOTUI_CI_ASSESSMENT_ID");
        long configured =
                keys.stream().filter(key -> System.getenv(key) != null).count();
        if (configured == 0) {
            if ("true".equals(System.getenv("CI")) || "true".equals(System.getenv("GITHUB_ACTIONS"))) {
                throw new IllegalStateException("CI collection requires the prepared BOOTUI_CI_* plan");
            }
            System.out.println("BootUI CI collection is opt-in; running the local workload without collection.");
            return null;
        }
        if (configured != keys.size()
                || keys.stream().anyMatch(key -> System.getenv(key).isBlank())) {
            throw new IllegalStateException("Set all three BOOTUI_CI_* collection values, or none for local tests");
        }
        var window = CiCollection.begin(
                Path.of(System.getenv(keys.get(0))),
                System.getenv(keys.get(1)),
                System.getenv(keys.get(2)),
                contextId,
                url,
                "/bootui/api",
                30);
        if (!window.attached()) {
            System.out.println("BootUI attach refused: " + window.attachFailureCode() + "; workload still runs.");
        }
        return window;
    }
}
