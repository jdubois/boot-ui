package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.RabbitReport;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitMessageDtos;
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
 * Read/clear API for the dedicated RabbitMQ panel on Quarkus — the JAX-RS twin of the Spring adapter's
 * {@code RabbitController}, over the same shared engine {@link RabbitActivityRecorder} and the same
 * {@code /bootui/api/rabbitmq} contract.
 *
 * <p>The recorder is always produced (see {@code BootUiEngineProducer#rabbitActivityRecorder}), so this
 * resource never fails even when {@code quarkus-messaging-rabbitmq} is absent; instead it reports the
 * panel unavailable by reading the build-time {@link QuarkusPanelAvailability#RABBIT_PRESENT_KEY} flag,
 * mirroring how the Spring controller checks for a {@code RabbitTemplate} bean. When present, {@code
 * QuarkusRabbitProducerCapture}/{@code QuarkusRabbitConsumerCapture} feed the exact same buffer that
 * already powers Live Activity's {@code MESSAGING} entries (there is only ever one buffer), so this panel
 * and Live Activity are always in sync, and {@link #clear()} clears both views at once.</p>
 *
 * <p>{@code GET} is passive; only {@code DELETE} (clear) mutates state, gated by the shared {@code
 * LocalhostGuard} write floor and the {@code rabbitmq} panel's read-only toggle, exactly as on Spring.</p>
 */
@Path("/bootui/api/rabbitmq")
public class RabbitResource {

    private final RabbitActivityRecorder recorder;
    private final boolean rabbitPresent;
    private final int maxEntries;

    @Inject
    public RabbitResource(RabbitActivityRecorder recorder, Config config) {
        this.recorder = recorder;
        this.rabbitPresent = config.getOptionalValue(QuarkusPanelAvailability.RABBIT_PRESENT_KEY, Boolean.class)
                .orElse(false);
        this.maxEntries = config.getOptionalValue("bootui.rabbitmq.max-entries", Integer.class)
                .orElse(200);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RabbitReport list() {
        if (!rabbitPresent) {
            return RabbitReport.unavailable(
                    "Not available: this application does not use RabbitMQ messaging. Add the"
                            + " quarkus-messaging-rabbitmq extension (with an @Incoming/@Outgoing channel) to"
                            + " enable the RabbitMQ panel.",
                    maxEntries);
        }
        return RabbitMessageDtos.toReport(recorder);
    }

    @DELETE
    public Response clear() {
        recorder.clear();
        return Response.noContent().build();
    }
}
