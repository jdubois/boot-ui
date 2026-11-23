package io.github.jdubois.bootui.webfluxsample;

import io.github.jdubois.bootui.conformance.DurableActivityQueryContract;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.overrides-file=target/durable-activity-query/application-bootui.properties",
            "bootui.activity.persistence.enabled=true",
            "bootui.activity.persistence.namespace-id=durable-activity-query",
            "bootui.activity.persistence.data-source-mode=DEDICATED",
            "bootui.activity.persistence.dedicated-jdbc-url=jdbc:h2:mem:durable-activity-query;DB_CLOSE_DELAY=-1"
        })
class WebFluxDurableActivityQueryTest {

    @LocalServerPort
    int port;

    @Test
    void validatesDurableQueriesOverHttp() {
        DurableActivityQueryContract.acceptsValidQueriesAndRejectsInvalidQueries("http://localhost:" + port);
    }
}
