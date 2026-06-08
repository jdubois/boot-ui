package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints backing the BootUI Claude Code panel.
 *
 * <p>All endpoint behavior lives in {@link AgentSessionController}.
 */
@RestController
@RequestMapping("/bootui/api/claude-code")
public class ClaudeCodeController extends AgentSessionController {

    @Autowired
    public ClaudeCodeController(
            @Qualifier("bootUiClaudeCodeSessionStore") ObjectProvider<ClaudeCodeSessionStore> storeProvider,
            BootUiExposure exposure) {
        super(storeProvider::getObject, exposure, "Claude Code");
    }

    ClaudeCodeController(ClaudeCodeSessionStore store, BootUiProperties properties) {
        super(() -> store, new BootUiExposure(properties), "Claude Code");
    }
}
