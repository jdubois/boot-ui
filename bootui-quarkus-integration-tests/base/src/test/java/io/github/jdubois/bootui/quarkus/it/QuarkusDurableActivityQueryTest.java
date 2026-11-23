package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.conformance.DurableActivityQueryContract;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(QuarkusDurableActivityQueryTest.DurableActivityProfile.class)
class QuarkusDurableActivityQueryTest {

    public static class DurableActivityProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "bootui.overrides-file", "target/durable-activity-query/application-bootui.properties",
                    "bootui.activity.persistence.enabled", "true",
                    "bootui.activity.persistence.namespace-id", "durable-activity-query",
                    "bootui.activity.persistence.data-source-mode", "DEDICATED",
                    "bootui.activity.persistence.dedicated-jdbc-url",
                            "jdbc:h2:mem:durable-activity-query;DB_CLOSE_DELAY=-1");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Test
    void validatesDurableQueriesOverHttp() {
        DurableActivityQueryContract.acceptsValidQueriesAndRejectsInvalidQueries(baseUrl.toExternalForm());
    }
}
