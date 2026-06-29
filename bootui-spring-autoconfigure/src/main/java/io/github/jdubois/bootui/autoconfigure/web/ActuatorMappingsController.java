package io.github.jdubois.bootui.autoconfigure.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backwards-compatibility endpoint that returns Actuator's raw {@link ApplicationMappingsDescriptor}
 * at {@code GET /bootui/api/mappings}.
 *
 * <p>The BootUI UI does not consume this endpoint — it uses {@code /bootui/api/mappings/flat}, served by
 * the framework-neutral {@link MappingsController}, which applies BootUI's self-data filtering and
 * returns stable DTOs. This controller is the single touch-point for the Actuator
 * {@link MappingsEndpoint} on the read path, so it is registered only inside the
 * {@code @ConditionalOnClass}-gated mappings backend configuration in {@code BootUiEngineConfiguration};
 * its types are therefore never linked in an Actuator-absent application.</p>
 */
@RestController
@RequestMapping("/bootui/api/mappings")
public class ActuatorMappingsController {

    private final ObjectProvider<MappingsEndpoint> endpoint;

    public ActuatorMappingsController(ObjectProvider<MappingsEndpoint> endpoint) {
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
