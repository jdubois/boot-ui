package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.DismissedRulesDto;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * JAX-RS resource for the developer-dismissed advisor rule IDs ({@code GET/POST/DELETE
 * /bootui/api/dismissed-rules}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code DismissedRulesController}: a thin transport
 * adapter over the shared engine {@link DismissedRulesStore}, which persists the dismissed rule IDs under
 * the {@code dismissedRules} node of {@code .bootui/boot-ui.yml}. The set is a purely local, developer-
 * facing preference and is never sent to any external service.</p>
 *
 * <p>The {@code POST}/{@code DELETE} mutations are state-changing, so they are covered by the same
 * localhost write floor every {@code /bootui/api/**} request passes through: {@code BootUiQuarkusSafetyFilter}
 * rejects non-loopback callers and cross-site state-changing requests before routing, mirroring the Spring
 * adapter's protection (where {@code /dismissed-rules} is likewise not a per-panel-gated route).</p>
 */
@Path("/bootui/api/dismissed-rules")
public class DismissedRulesResource {

    private final DismissedRulesStore store;

    @Inject
    public DismissedRulesResource(DismissedRulesStore store) {
        this.store = store;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DismissedRulesDto list() {
        return new DismissedRulesDto(List.copyOf(store.load()));
    }

    @POST
    @Path("/{ruleId}")
    @Produces(MediaType.APPLICATION_JSON)
    public DismissedRulesDto dismiss(@PathParam("ruleId") String ruleId) {
        return new DismissedRulesDto(List.copyOf(store.dismiss(ruleId)));
    }

    @DELETE
    @Path("/{ruleId}")
    @Produces(MediaType.APPLICATION_JSON)
    public DismissedRulesDto restore(@PathParam("ruleId") String ruleId) {
        return new DismissedRulesDto(List.copyOf(store.restore(ruleId)));
    }
}
