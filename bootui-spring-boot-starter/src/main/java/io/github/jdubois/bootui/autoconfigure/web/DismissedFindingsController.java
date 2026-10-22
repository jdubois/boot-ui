package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.DismissedFindingsDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manages the advisor findings the developer dismissed one by one, next to the rules dismissed whole by
 * {@link DismissedRulesController}.
 *
 * <p>Dismissed findings are stored under the {@code dismissedFindings} node of {@code .bootui/boot-ui.yml}; the
 * optional note is user text that is returned only by {@code GET}. The mutations are covered by the global read-only
 * policy and the localhost and cross-site-write floor like every other BootUI write, and there is deliberately no
 * MCP or CLI binding.</p>
 *
 * <p>The request body is read as bytes and parsed here, so that a malformed body is answered with a fixed message:
 * a JSON binding failure would quote the offending text, and with it the note, into the exception diagnostics, and
 * Spring MVC logs a text body it reads at DEBUG, which Log Tail would carry to MCP and the CLI.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/dismissed-findings")
public class DismissedFindingsController {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final DismissedRulesStore store;

    @Autowired
    public DismissedFindingsController(DismissedRulesStore store) {
        this.store = store;
    }

    @GetMapping
    public DismissedFindingsDto list() {
        return dto(store.loadFindings());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public DismissedFindingsDto dismiss(@RequestBody(required = false) byte[] body) {
        JsonNode request = parse(body);
        return dto(store.dismissFinding(
                text(request, "ruleId", false), text(request, "identity", false), text(request, "note", true)));
    }

    @DeleteMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public DismissedFindingsDto restore(@RequestBody(required = false) byte[] body) {
        JsonNode request = parse(body);
        return dto(store.restoreFinding(text(request, "ruleId", false), text(request, "identity", false)));
    }

    private static DismissedFindingsDto dto(DismissedRulesStore.Findings findings) {
        return new DismissedFindingsDto(findings.findings(), findings.ignored());
    }

    private static JsonNode parse(byte[] body) {
        JsonNode node;
        try {
            node = body == null || body.length == 0 ? null : MAPPER.readTree(body);
        } catch (RuntimeException e) {
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
        if (!value.isString()) {
            throw invalid();
        }
        return value.asString();
    }

    private static AdvisorViolationException invalid() {
        return new AdvisorViolationException(
                400, "Request body must be a JSON object with string fields ruleId, identity and an optional note.");
    }
}
