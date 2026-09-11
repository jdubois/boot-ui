package io.github.jdubois.bootui.sample.explorer;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit sample traffic only. The console never invokes these endpoints on page load. */
@RestController
@RequestMapping("/api/explorer-demo")
public class ExplorerDemoController {
    private final ExplorerDemoService service;

    public ExplorerDemoController(ExplorerDemoService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Map<String, Object> product(@PathVariable long id) {
        return service.product(id);
    }

    @GetMapping("/handled")
    public Map<String, Object> handled() {
        try {
            service.fail();
        } catch (ExplorerDemoFailure ignored) {
            return Map.of("handled", true);
        }
        return Map.of("handled", false);
    }

    @GetMapping("/failure")
    public void failure() {
        service.fail();
    }
}
