package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.sample.insights.EagerDemoOrderSeed;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.datasource.url=jdbc:h2:mem:bootui_workshop;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/workshop-test/application-bootui.properties"
        })
class WorkshopEagerOrdersTest {

    @LocalServerPort
    int port;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void originalRoutePreservesSummariesWithBoundedQueries() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var original = probe.get("/api/insights/eager-orders");
        long originalStatements = statistics.getPrepareStatementCount();

        statistics.clear();
        var control = probe.get("/api/insights/eager-orders/joined");
        long controlStatements = statistics.getPrepareStatementCount();

        assertThat(original.status()).isEqualTo(200);
        assertThat(control.status()).isEqualTo(200);
        assertThat(original.json()).isEqualTo(control.json());
        assertThat(original.json().size()).isEqualTo(EagerDemoOrderSeed.ORDER_COUNT);
        long previousId = Long.MIN_VALUE;
        for (var summary : original.json()) {
            assertThat(summary.properties())
                    .extracting(entry -> entry.getKey())
                    .containsExactlyInAnyOrder("id", "description", "customer");
            assertThat(summary.path("id").asLong()).isGreaterThan(previousId);
            assertThat(summary.path("description").asText()).isNotBlank();
            assertThat(summary.path("customer").asText()).isNotBlank();
            previousId = summary.path("id").asLong();
        }
        assertThat(controlStatements).as("control SELECT budget").isBetween(1L, 2L);
        assertThat(originalStatements).as("original route SELECT budget").isBetween(1L, 2L);
    }
}
