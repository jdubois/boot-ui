package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.scheduler.SampleScheduledJobs;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus Scheduled Tasks panel light-up end to end on an app that <strong>does</strong> have the
 * {@code quarkus-scheduler} extension on its classpath: the {@code @Scheduled} methods of
 * {@link SampleScheduledJobs} are discovered at build time by the deployment processor's Jandex scan, recorded
 * into the synthetic {@code QuarkusScheduledTasks} bean, mapped by {@code QuarkusScheduledTaskProvider} onto the
 * neutral {@code ScheduledTaskDto} contract, and surfaced on {@code GET /bootui/api/scheduled} — with the panel
 * reported available in the manifest.
 *
 * <p>This is the scheduler-<em>present</em> half of the coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the scheduler-<em>absent</em> path (the panel is
 * reported unavailable, {@code GET /bootui/api/scheduled} renders {@code schedulingPresent=false}).</p>
 */
@QuarkusTest
class BootUiQuarkusScheduledTasksTest {

    private static final String JOBS = SampleScheduledJobs.class.getName();

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void scheduledPanelListsTheAnnotatedTasks() {
        Response response = probe().get("/bootui/api/scheduled");
        assertThat(response.status()).as("GET /bootui/api/scheduled status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/scheduled content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();
        assertThat(root.path("schedulingPresent").asBoolean(false))
                .as("with quarkus-scheduler present the report is available")
                .isTrue();
        assertThat(root.path("total").asInt(0))
                .as("all three @Scheduled methods are captured")
                .isGreaterThanOrEqualTo(3);

        JsonNode cron = taskByRunnable(root, JOBS + "#cronJob");
        assertThat(cron.path("triggerType").asText()).as("cron trigger type").isEqualTo("CRON");
        assertThat(cron.path("expression").asText())
                .as("cron expression captured verbatim")
                .isEqualTo("0 0 0 * * ?");

        JsonNode every = taskByRunnable(root, JOBS + "#everyJob");
        assertThat(every.path("triggerType").asText()).as("every trigger type").isEqualTo("FIXED_RATE");
        assertThat(every.path("expression").asText())
                .as("every=30s parsed to milliseconds")
                .isEqualTo("30000");
        assertThat(every.path("timeUnit").asText())
                .as("whole-second interval renders in seconds")
                .isEqualTo("s");

        JsonNode delayed = taskByRunnable(root, JOBS + "#delayedEveryJob");
        assertThat(delayed.path("triggerType").asText())
                .as("delayed every trigger type")
                .isEqualTo("FIXED_RATE");
        assertThat(delayed.path("expression").asText())
                .as("every=1h parsed to milliseconds")
                .isEqualTo("3600000");
        assertThat(delayed.path("initialDelayMs").asLong(-1))
                .as("delayed=10s string initial delay parsed to milliseconds")
                .isEqualTo(10000);
    }

    @Test
    void scheduledPanelIsReportedAvailable() {
        Response response = probe().get("/bootui/api/panels");
        assertThat(response.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode panel = panelById(response.json(), "scheduled");
        assertThat(panel.path("available").asBoolean(false))
                .as("the Scheduled Tasks panel is available when quarkus-scheduler is present")
                .isTrue();
    }

    private static JsonNode taskByRunnable(JsonNode report, String runnable) {
        for (JsonNode task : report.path("tasks")) {
            if (runnable.equals(task.path("runnable").asText(null))) {
                return task;
            }
        }
        throw new AssertionError("No scheduled task with runnable " + runnable + " in " + report);
    }

    private static JsonNode panelById(JsonNode manifest, String id) {
        for (JsonNode panel : manifest.path("panels")) {
            if (id.equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        throw new AssertionError("No panel with id " + id + " in manifest " + manifest);
    }
}
