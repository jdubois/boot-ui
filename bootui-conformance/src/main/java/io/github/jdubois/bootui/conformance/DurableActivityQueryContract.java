package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.util.List;
import org.assertj.core.api.SoftAssertions;

/** Shared REST query validation against a running app with durable Live Activity enabled. */
public final class DurableActivityQueryContract {

    private static final String ACTIVITY = "/bootui/api/activity";
    private static final String JOURNAL = ACTIVITY + "?source=journal";

    private DurableActivityQueryContract() {}

    public static void acceptsValidQueriesAndRejectsInvalidQueries(String baseUrl) {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl);
        assertPersistentPage(probe.get(JOURNAL), "omitted cursor");
        assertPersistentPage(probe.get(JOURNAL + "&cursor="), "empty cursor");
        assertPersistentPage(probe.get(JOURNAL + "&cursor=%20%09"), "blank cursor");
        assertPersistentPage(probe.get(JOURNAL + "&cursor=0"), "zero cursor");
        assertPersistentPage(probe.get(JOURNAL + "&cursor=%2B9223372036854775807"), "encoded maximum cursor");
        assertPersistentPage(probe.get(JOURNAL + "&q=" + "a".repeat(2_000)), "text at the maximum length");

        SoftAssertions.assertSoftly(errors -> {
            for (String cursor : List.of("not-a-cursor", "-1", "%201%20", "9223372036854775808")) {
                errors.assertThat(probe.get(JOURNAL + "&cursor=" + cursor).status())
                        .as("malformed cursor: %s", cursor)
                        .isEqualTo(400);
            }
            for (String filter : List.of("application", "run", "instance", "type", "severity", "requestId")) {
                errors.assertThat(probe.get(JOURNAL + "&" + filter + "=" + "a".repeat(129))
                                .status())
                        .as("overlong %s", filter)
                        .isEqualTo(400);
            }
            for (String filter : List.of("q", "route")) {
                errors.assertThat(probe.get(JOURNAL + "&" + filter + "=" + "a".repeat(2_001))
                                .status())
                        .as("overlong %s", filter)
                        .isEqualTo(400);
            }
            errors.assertThat(
                            probe.get(JOURNAL + "&namespace=another-namespace").status())
                    .as("cross-namespace reads remain disabled by default")
                    .isEqualTo(400);
            Response buffers = probe.get(ACTIVITY + "?source=buffers&cursor=not-a-cursor&q=" + "a".repeat(2_001));
            errors.assertThat(buffers.status())
                    .as("panel buffers bypass durable query validation")
                    .isEqualTo(200);
        });
    }

    private static void assertPersistentPage(Response response, String query) {
        assertThat(response.status()).as("%s status", query).isEqualTo(200);
        assertThat(response.json().path("pageInfo").path("persistent").asBoolean())
                .as("%s must use the durable backend, not the in-memory fallback", query)
                .isTrue();
    }
}
