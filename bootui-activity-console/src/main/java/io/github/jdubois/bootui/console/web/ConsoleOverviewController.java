package io.github.jdubois.bootui.console.web;

import io.github.jdubois.bootui.console.safety.ConsoleSafetyProperties;
import io.github.jdubois.bootui.core.BootUiInfo;
import io.github.jdubois.bootui.core.dto.ActivationStatus;
import io.github.jdubois.bootui.core.dto.OverviewDto;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal {@code GET /bootui/api/overview}, the console's self-contained equivalent of the host-adapter
 * {@code OverviewController} (see the package-level Javadoc for why it is not reused directly). Needed
 * independent of the Live-Activity-only panel manifest: the SPA shell's chrome/CSRF-cookie priming
 * depends on this endpoint responding, regardless of which panels are marked available.
 *
 * <p>Unlike a host application, the console has no conditional activation logic of its own &mdash; it
 * <em>is</em> BootUI, running unconditionally &mdash; so {@link ActivationStatus#enabled()} is always
 * {@code true} here.
 */
@RestController
@RequestMapping("/bootui/api/overview")
public class ConsoleOverviewController {

    private final Environment environment;

    private final ConsoleSafetyProperties safetyProperties;

    public ConsoleOverviewController(Environment environment, ConsoleSafetyProperties safetyProperties) {
        this.environment = environment;
        this.safetyProperties = safetyProperties;
    }

    @GetMapping
    public OverviewDto overview() {
        String name = environment.getProperty("spring.application.name", "bootui-activity-console");
        Integer port =
                parseInt(environment.getProperty("local.server.port", environment.getProperty("server.port", "8079")));

        return new OverviewDto(
                BootUiInfo.VERSION,
                name,
                "Spring Boot",
                SpringBootVersion.getVersion(),
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                Arrays.asList(environment.getActiveProfiles()),
                Arrays.asList(environment.getDefaultProfiles()),
                "REACTIVE",
                port,
                null,
                environment.getProperty("spring.webflux.base-path", ""),
                null,
                new ActivationStatus(
                        true,
                        !safetyProperties.isAllowNonLocalhost(),
                        "BootUI Activity Console is a dedicated Live Activity aggregator; it always runs "
                                + "with BootUI enabled.",
                        List.of()),
                null);
    }

    private Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
