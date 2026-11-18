package io.github.jdubois.bootui.engine.restapi.reviewfixtures.child;

import io.github.jdubois.bootui.engine.restapi.reviewfixtures.base.PackagePrivateMappingBase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PackagePrivateMappingController extends PackagePrivateMappingBase {
    @GetMapping("/package-child")
    public String action() {
        return "child";
    }
}
