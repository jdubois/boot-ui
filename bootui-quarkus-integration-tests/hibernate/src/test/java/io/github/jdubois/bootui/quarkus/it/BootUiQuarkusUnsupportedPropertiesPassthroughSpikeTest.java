package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/**
 * Live-runtime spike (HIB-CONFIG-005, see docs/HIBERNATE-CHECKS.md) proving that Quarkus' generic
 * {@code quarkus.hibernate-orm.unsupported-properties."..."} escape hatch — the fallback
 * {@code QuarkusHibernatePropertyLookup} uses for {@code hibernate.order_inserts}/{@code hibernate.order_updates},
 * which have no first-class {@code quarkus.hibernate-orm.*} equivalent — actually reaches vanilla Hibernate ORM's
 * own bootstrapped {@link org.hibernate.boot.spi.SessionFactoryOptions}, not just that BootUI's own property
 * lookup reads the SmallRye Config value back. Bytecode inspection of the shipped
 * {@code quarkus-hibernate-orm-deployment} jar already showed {@code FastBootMetadataBuilder} merges
 * {@code unsupported-properties} verbatim into Hibernate's build-time settings; this test is the corroborating
 * live-boot proof: it unwraps the actual {@link EntityManagerFactory} to a real Hibernate {@link SessionFactory}
 * and reads {@code isOrderInsertsEnabled()}/{@code isOrderUpdatesEnabled()} directly off it, then separately
 * confirms the BootUI-side read-back (HIB-CONFIG-005 does not fire) so both halves of the pipeline are pinned.
 */
@QuarkusTest
@TestProfile(BootUiQuarkusUnsupportedPropertiesPassthroughSpikeTest.OrderedBatchingProfile.class)
class BootUiQuarkusUnsupportedPropertiesPassthroughSpikeTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @Inject
    EntityManagerFactory entityManagerFactory;

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void unsupportedPropertiesPassthroughReachesHibernatesOwnSessionFactoryOptions() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);

        assertThat(sessionFactory.getSessionFactoryOptions().isOrderInsertsEnabled())
                .as("quarkus.hibernate-orm.unsupported-properties.\"hibernate.order_inserts\"=true must reach"
                        + " Hibernate's own SessionFactoryOptions, not just BootUI's property lookup")
                .isTrue();
        assertThat(sessionFactory.getSessionFactoryOptions().isOrderUpdatesEnabled())
                .as("quarkus.hibernate-orm.unsupported-properties.\"hibernate.order_updates\"=true must reach"
                        + " Hibernate's own SessionFactoryOptions, not just BootUI's property lookup")
                .isTrue();
    }

    @Test
    void hibConfig005DoesNotFalsePositiveWhenOrderingIsSetViaTheUnsupportedPropertiesPassthrough() {
        Response scan = probe().post("/bootui/api/hibernate/scan", JSON_HEADERS);
        assertThat(scan.status()).as("POST /bootui/api/hibernate/scan status").isEqualTo(200);

        List<String> violationIds = new ArrayList<>();
        for (JsonNode node : scan.json().path("results")) {
            violationIds.add(node.path("id").asText());
        }
        assertThat(violationIds)
                .as("hibernate.order_inserts/order_updates reached via the unsupported-properties passthrough"
                        + " (with batching also configured via quarkus.hibernate-orm.jdbc.statement-batch-size)"
                        + " must be read back so HIB-CONFIG-005 does not false-positive")
                .doesNotContain("HIB-CONFIG-005");
    }

    public static final class OrderedBatchingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.hibernate-orm.jdbc.statement-batch-size",
                    "25",
                    "quarkus.hibernate-orm.unsupported-properties.\"hibernate.order_inserts\"",
                    "true",
                    "quarkus.hibernate-orm.unsupported-properties.\"hibernate.order_updates\"",
                    "true");
        }
    }
}
