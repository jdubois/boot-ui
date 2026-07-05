package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.CopilotSessionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reactive (WebFlux) sibling of {@code CopilotController}: identical wiring over the same
 * framework-neutral {@link CopilotSessionStore}. All endpoint behavior lives in
 * {@link ReactiveAgentSessionController}.
 */
@RestController
@RequestMapping("/bootui/api/copilot")
public class ReactiveCopilotController extends ReactiveAgentSessionController {

    @Autowired
    public ReactiveCopilotController(
            @Qualifier("bootUiCopilotSessionStore") ObjectProvider<CopilotSessionStore> storeProvider,
            BootUiExposure exposure) {
        super(storeProvider::getObject, exposure, "Copilot");
    }

    ReactiveCopilotController(CopilotSessionStore store, BootUiProperties properties) {
        super(() -> store, new BootUiExposure(properties), "Copilot");
    }
}
