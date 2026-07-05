package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.ClaudeCodeSessionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reactive (WebFlux) sibling of {@code ClaudeCodeController}: identical wiring over the same
 * framework-neutral {@link ClaudeCodeSessionStore}. All endpoint behavior lives in
 * {@link ReactiveAgentSessionController}.
 */
@RestController
@RequestMapping("/bootui/api/claude-code")
public class ReactiveClaudeCodeController extends ReactiveAgentSessionController {

    @Autowired
    public ReactiveClaudeCodeController(
            @Qualifier("bootUiClaudeCodeSessionStore") ObjectProvider<ClaudeCodeSessionStore> storeProvider,
            BootUiExposure exposure) {
        super(storeProvider::getObject, exposure, "Claude Code");
    }

    ReactiveClaudeCodeController(ClaudeCodeSessionStore store, BootUiProperties properties) {
        super(() -> store, new BootUiExposure(properties), "Claude Code");
    }
}
