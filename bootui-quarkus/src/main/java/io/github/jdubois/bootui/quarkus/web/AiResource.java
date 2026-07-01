package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.AiChatDetailDto;
import io.github.jdubois.bootui.core.dto.AiChatSummaryDto;
import io.github.jdubois.bootui.core.dto.AiOverviewDto;
import io.github.jdubois.bootui.core.dto.AiTokenSeriesDto;
import io.github.jdubois.bootui.engine.telemetry.AiUsageService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * JAX-RS resource for the BootUI AI Usage panel. The Quarkus analogue of the Spring adapter's
 * {@code AiController}: a thin, read-only delegator over the framework-neutral {@link AiUsageService} in
 * {@code bootui-engine}, exposing the exact same {@code /bootui/api/ai/**} contract.
 *
 * <p>The data is derived from the OTLP spans accumulated in the engine {@code TelemetryStore}. Spring AI
 * and LangChain4j both emit the OTel GenAI semantic-conventions spans this panel reads, so no additional
 * configuration is required beyond having {@code quarkus-opentelemetry} on the application classpath to
 * capture them.</p>
 */
@Path("/bootui/api/ai")
public class AiResource {

    private final AiUsageService service;

    @Inject
    public AiResource(AiUsageService service) {
        this.service = service;
    }

    @GET
    @Path("/overview")
    @Produces(MediaType.APPLICATION_JSON)
    public AiOverviewDto overview() {
        return service.overview();
    }

    @GET
    @Path("/chats")
    @Produces(MediaType.APPLICATION_JSON)
    public List<AiChatSummaryDto> chats(@QueryParam("limit") @DefaultValue("100") int limit) {
        return service.chats(limit);
    }

    @GET
    @Path("/chats/{spanId}")
    @Produces(MediaType.APPLICATION_JSON)
    public AiChatDetailDto chatDetail(@PathParam("spanId") String spanId) {
        return service.chatDetail(spanId)
                .orElseThrow(() -> new NotFoundException("chat span " + spanId + " not found"));
    }

    @GET
    @Path("/tokens")
    @Produces(MediaType.APPLICATION_JSON)
    public AiTokenSeriesDto tokens(@QueryParam("minutes") Integer minutes) {
        return service.tokens(minutes);
    }
}
