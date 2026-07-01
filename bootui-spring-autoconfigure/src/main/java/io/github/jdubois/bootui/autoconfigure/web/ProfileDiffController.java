package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.ProfilesReport;
import io.github.jdubois.bootui.engine.config.ConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a profile-aware view of the current configuration by delegating to the shared engine
 * {@link ConfigService}, which masks and groups the profile-specific sources supplied by
 * {@code SpringConfigProvider}. Thin transport adapter; all logic lives in the engine.
 */
@RestController
@RequestMapping("/bootui/api/profile-diff")
public class ProfileDiffController {

    private final ConfigService configService;

    public ProfileDiffController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ProfilesReport profiles() {
        return configService.profiles();
    }
}
