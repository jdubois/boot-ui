package io.github.jdubois.bootui.autoconfigure.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/mappings")
public class MappingsController {

    private final ObjectProvider<MappingsEndpoint> endpoint;

    public MappingsController(ObjectProvider<MappingsEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping
    public ResponseEntity<ApplicationMappingsDescriptor> mappings() {
        MappingsEndpoint me = endpoint.getIfAvailable();
        if (me == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(me.mappings());
    }
}
