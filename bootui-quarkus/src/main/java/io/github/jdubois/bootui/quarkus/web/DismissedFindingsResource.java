package io.github.jdubois.bootui.quarkus.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.DismissedFindingsDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the advisor findings the developer dismissed one by one ({@code GET/POST/DELETE
 * /bootui/api/dismissed-findings}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code DismissedFindingsController}: a thin transport adapter over
 * the shared engine {@link DismissedRulesStore}. The mutations are covered by the global read-only policy
 * ({@code BootUiGlobalWritePolicy}) and the localhost and cross-site-write floor, and there is deliberately no MCP or
 * CLI binding. The request body is read as text and parsed here, so that a malformed body is answered with a fixed
 * message that never quotes the user's note.</p>
 */
@Path("/bootui/api/dismissed-findings")
public class DismissedFindingsResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DismissedRulesStore store;

    @Inject
    public DismissedFindingsResource(DismissedRulesStore store) {
        this.store = store;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DismissedFindingsDto list() {
        return dto(store.loadFindings());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DismissedFindingsDto dismiss(String body) {
        JsonNode request = parse(body);
        return dto(store.dismissFinding(
                text(request, "ruleId", false), text(request, "identity", false), text(request, "note", true)));
    }

    @DELETE
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DismissedFindingsDto restore(String body) {
        JsonNode request = parse(body);
        return dto(store.restoreFinding(text(request, "ruleId", false), text(request, "identity", false)));
    }

    private static DismissedFindingsDto dto(DismissedRulesStore.Findings findings) {
        return new DismissedFindingsDto(findings.findings(), findings.ignored());
    }

    private static JsonNode parse(String body) {
        JsonNode node;
        try {
            node = body == null || body.isBlank() ? null : MAPPER.readTree(body);
        } catch (RuntimeException | java.io.IOException e) {
            node = null;
        }
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        return node;
    }

    private static String text(JsonNode request, String name, boolean optional) {
        JsonNode value = request.get(name);
        if (value == null || value.isNull()) {
            if (optional) {
                return null;
            }
            throw invalid();
        }
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.asText();
    }

    private static AdvisorViolationException invalid() {
        return new AdvisorViolationException(
                400, "Request body must be a JSON object with string fields ruleId, identity and an optional note.");
    }
}
