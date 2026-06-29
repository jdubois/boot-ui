package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.core.dto.ConfigOverrideRequest;
import io.github.jdubois.bootui.core.dto.ConfigOverrideResult;
import io.github.jdubois.bootui.core.dto.ConfigReport;
import io.github.jdubois.bootui.engine.config.ConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration panel controller. The read path delegates to the shared engine {@link ConfigService}
 * (enumeration, masking, sort/filter/page) built over {@code SpringConfigProvider}; the runtime-override
 * write path ({@code POST}/{@code DELETE /overrides}) stays Spring-only, backed by
 * {@link ConfigOverrideService}, because it mutates a Spring-bootstrap property source. The {@code list(...)}
 * signature is preserved because {@code BootUiMcpTools}' {@code get_config} tool invokes it directly.
 */
@RestController
@RequestMapping("/bootui/api/config")
public class ConfigController {

    private final ConfigService configService;

    private final ConfigOverrideService overrideService;

    public ConfigController(ConfigService configService, ConfigOverrideService overrideService) {
        this.configService = configService;
        this.overrideService = overrideService;
    }

    @GetMapping
    public ConfigReport list(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "source", required = false) String sourceFilter,
            @RequestParam(name = "overridesOnly", required = false, defaultValue = "false") boolean overridesOnly,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return configService.list(query, sourceFilter, overridesOnly, offset, limit);
    }

    @PostMapping("/overrides")
    public ConfigOverrideResult put(@RequestBody ConfigOverrideRequest request) {
        if (request == null || request.name() == null) {
            throw new IllegalArgumentException("'name' must be provided");
        }
        return overrideService.put(request.name(), request.value());
    }

    @DeleteMapping("/overrides/{name}")
    public ConfigOverrideResult delete(@PathVariable String name) {
        return overrideService.remove(name);
    }
}
