package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.core.dto.CodeInventoryAgentReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryChangesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryDependenciesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodsReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryReport;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Code Inventory panel ({@code docs/PLAN-v2.md} §5.15), shared by the Spring MVC and WebFlux adapters: which of the
 * application's methods this run executed, which changed since the previous run, and which dependencies loaded classes.
 * Reads only what the BootUI agent's inventory sensor recorded and the scan of the application's class files that its
 * run started; it starts no scan of its own, network call, or mutation. Without the agent every read answers
 * unavailable with the Java Agent panel's reason.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/code-inventory")
public class CodeInventoryController {

    private final CodeInventoryService service;

    public CodeInventoryController(CodeInventoryService service) {
        this.service = service;
    }

    @GetMapping
    public CodeInventoryReport report() {
        return service.report();
    }

    @GetMapping("/changes")
    public CodeInventoryChangesReport changes(
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.changes(offset, limit);
    }

    @GetMapping("/methods")
    public CodeInventoryMethodsReport methods(
            @RequestParam(name = "package", required = false) String packageName,
            @RequestParam(name = "class", required = false) String className,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.methods(packageName, className, status, offset, limit);
    }

    @GetMapping("/dependencies")
    public CodeInventoryDependenciesReport dependencies(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.dependencies(status, offset, limit);
    }

    /** Code Inventory for agents: {@code get_code_inventory} and {@code bootui code inventory}. */
    public CodeInventoryAgentReport agentReport(String query, Integer limit) {
        return service.agentReport(query, limit);
    }

    /** Why Code Inventory is unavailable, or {@code null}; the panel's availability. */
    public String unavailableReason() {
        return service.unavailableReason();
    }
}
