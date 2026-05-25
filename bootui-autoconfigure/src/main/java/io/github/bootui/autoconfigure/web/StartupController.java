package io.github.bootui.autoconfigure.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.startup.StartupEndpoint;
import org.springframework.boot.actuate.startup.StartupEndpoint.StartupDescriptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/startup")
public class StartupController {

    private final ObjectProvider<StartupEndpoint> endpoint;

    public StartupController(ObjectProvider<StartupEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping
    public ResponseEntity<StartupDescriptor> startup() {
        StartupEndpoint se = endpoint.getIfAvailable();
        if (se == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(se.startupSnapshot());
    }
}
