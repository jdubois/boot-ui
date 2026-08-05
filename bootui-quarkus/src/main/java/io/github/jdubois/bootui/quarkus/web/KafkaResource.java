package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.KafkaReport;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaMessageDtos;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;

/**
 * Read/clear API for the dedicated Kafka panel on Quarkus — the JAX-RS twin of the Spring adapter's
 * {@code KafkaController}, over the same shared engine {@link KafkaActivityRecorder} and the same
 * {@code /bootui/api/kafka} contract.
 *
 * <p>The recorder is always produced (see {@code BootUiEngineProducer#kafkaActivityRecorder}), so this
 * resource never fails even when {@code quarkus-messaging-kafka} is absent; instead it reports the panel
 * unavailable by reading the build-time {@link QuarkusPanelAvailability#KAFKA_PRESENT_KEY} flag, mirroring
 * how the Spring controller checks for a {@code KafkaTemplate} bean. When present, {@code
 * QuarkusKafkaProducerCapture}/{@code QuarkusKafkaConsumerCapture} feed the exact same buffer that already
 * powers Live Activity's {@code MESSAGING} entries (there is only ever one buffer), so this panel and Live
 * Activity are always in sync, and {@link #clear()} clears both views at once.</p>
 *
 * <p>{@code GET} is passive; only {@code DELETE} (clear) mutates state, gated by the shared {@code
 * LocalhostGuard} write floor and the {@code kafka} panel's read-only toggle, exactly as on Spring.</p>
 */
@Path("/bootui/api/kafka")
public class KafkaResource {

    private final KafkaActivityRecorder recorder;
    private final boolean kafkaPresent;
    private final int maxEntries;

    @Inject
    public KafkaResource(KafkaActivityRecorder recorder, Config config) {
        this.recorder = recorder;
        this.kafkaPresent = config.getOptionalValue(QuarkusPanelAvailability.KAFKA_PRESENT_KEY, Boolean.class)
                .orElse(false);
        this.maxEntries = config.getOptionalValue("bootui.kafka.max-entries", Integer.class)
                .orElse(200);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public KafkaReport list() {
        if (!kafkaPresent) {
            return KafkaReport.unavailable("No Kafka messaging channel is present", maxEntries);
        }
        return KafkaMessageDtos.toReport(recorder);
    }

    @DELETE
    public Response clear() {
        recorder.clear();
        return Response.noContent().build();
    }
}
