package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Proves on a real Quarkus request that a REST worker goes back to its pool metered for nothing
 * ({@code docs/PLAN-v2.md} §5.11, M4-4, D17), so the next thing it does there — unrelated work, or a task an agent
 * propagated for another request — is not charged to the request that already finished, and its JFR samples are not
 * joined to it.
 *
 * <p>Two orderings matter and both are covered. An ordinary response is written by the worker itself, so Vert.x runs
 * the body-end handler inline on it and taking the request already closes the worker's segment there. A {@code File}
 * response is streamed with {@code sendFile}: the chain finishes on the worker while the body is still outstanding,
 * and only BootUI's completion close releases the worker at that point — the body-end take happens later, on the
 * event loop.</p>
 *
 * <p>{@link SegmentProbeResource} reports both readings from inside the request, through a completion callback it
 * registers after BootUI's.</p>
 */
@QuarkusTest
class BootUiQuarkusWorkerSegmentReleaseTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void aWorkerWritingAnOrdinaryResponseIsReleasedWithTheRequest() {
        assertReleased("entity");
    }

    @Test
    void aWorkerWhoseResponseBodyOutlivesItsChainIsReleasedWhenTheChainCompletes() {
        assertReleased("file");
    }

    /**
     * Calls the probe, then reads back what its worker was metered for inside the resource method and once Quarkus
     * completed the request on that same thread.
     */
    private void assertReleased(String probe) {
        BootUiHttpProbe probes = new BootUiHttpProbe(baseUrl.toExternalForm());
        assertThat(probes.get("/it/segments/" + probe).status()).isEqualTo(200);

        String[] observed = probes.get("/it/segments/observed/" + probe).body().split("\\|");

        assertThat(observed).as("the probe recorded a reading for %s", probe).hasSize(3);
        assertThat(observed[0])
                .as("the worker is metered for its own request while it runs the resource method")
                .matches("[0-9a-f]{16}");
        assertThat(observed[2])
                .as("Quarkus completed the request on the worker that ran the chain")
                .isEqualTo("true");
        assertThat(observed[1])
                .as("the worker goes back to its pool metered for nothing")
                .isEqualTo("none");
    }
}
