package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.BeanGraphReport;
import io.github.jdubois.bootui.core.dto.BeanList;
import io.github.jdubois.bootui.engine.beans.BeansService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Framework-neutral Beans controller. It serves {@code GET /bootui/api/beans} by delegating to the engine
 * {@link BeansService}, which sorts, classification/free-text filters and pages the mapped, classified,
 * self-filtered beans supplied by the (optional) {@code BeanProvider}.
 *
 * <p>Actuator types are confined to the gated {@code SpringBeanProvider}, so this controller carries no
 * optional dependency and is always registered: when no beans backend is available the service returns an
 * empty list. The {@code beans(...)} method signature is preserved because {@code BootUiMcpTools}'
 * {@code get_beans} tool invokes it directly.</p>
 */
@RestController
@RequestMapping("/bootui/api/beans")
public class BeansController {

    private final BeansService beansService;

    public BeansController(BeansService beansService) {
        this.beansService = beansService;
    }

    @GetMapping
    public BeanList beans(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "classification", required = false) String classification,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return beansService.beans(query, classification, offset, limit);
    }

    @GetMapping("/graph")
    public BeanGraphReport graph(
            @RequestParam(name = "focus", required = false) String focus,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return beansService.graph(focus, limit);
    }
}
