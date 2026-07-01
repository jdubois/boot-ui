package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints backing the BootUI Copilot panel.
 *
 * <p>Read-only by design. The default response payload contains only allowlisted,
 * sanitized fields - never raw prompts, tool arguments, command output, or diffs.
 * The {@code /raw} endpoint is the single opt-in escape hatch for inspecting the
 * source JSON locally, and is gated by {@code bootui.copilot.allow-raw-reveal} and
 * the existing {@code bootui.expose-values} setting.
 *
 * <p>All endpoint behavior lives in {@link AgentSessionController}.
 */
@RestController
@RequestMapping("/bootui/api/copilot")
public class CopilotController extends AgentSessionController {

    @Autowired
    public CopilotController(
            @Qualifier("bootUiCopilotSessionStore") ObjectProvider<CopilotSessionStore> storeProvider,
            BootUiExposure exposure) {
        super(storeProvider::getObject, exposure, "Copilot");
    }

    CopilotController(CopilotSessionStore store, BootUiProperties properties) {
        super(() -> store, new BootUiExposure(properties), "Copilot");
    }
}
