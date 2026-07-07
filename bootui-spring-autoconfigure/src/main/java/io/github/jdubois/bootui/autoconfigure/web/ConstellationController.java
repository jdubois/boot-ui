package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.ConstellationReport;
import io.github.jdubois.bootui.engine.constellation.ConstellationService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API for the Constellation panel: a zero-infrastructure map of the local multi-service peer
 * topology. Thin Spring adapter over the framework-neutral {@link ConstellationService} in
 * {@code bootui-engine}; the actual peer HTTP calls are performed by {@link ConstellationHttpPeerClient}.
 *
 * <p>Every call is a fresh, bounded fan-out to the configured {@code bootui.constellation.peers} - no
 * data is cached across requests and no background polling runs when the panel isn't open; the browser
 * re-fetches this endpoint on its own timer, exactly like the other live-data panels. When
 * {@code bootui.constellation.enabled} is {@code false} (the default), no peer is ever contacted,
 * mirroring the fail-closed, opt-in stance the rest of BootUI takes for outbound network calls.</p>
 */
@RestController
@RequestMapping("/bootui/api/constellation")
public class ConstellationController {

    private final ConstellationService service;

    @Autowired
    public ConstellationController(BootUiProperties properties) {
        BootUiProperties.Constellation settings = properties.getConstellation();
        List<String> effectivePeers = settings.isEnabled() ? settings.getPeers() : List.of();
        this.service = ConstellationService.using(
                effectivePeers, settings.getRequestTimeout(), new ConstellationHttpPeerClient());
    }

    ConstellationController(ConstellationService service) {
        this.service = service;
    }

    @GetMapping
    public ConstellationReport constellation() {
        return service.report();
    }
}
