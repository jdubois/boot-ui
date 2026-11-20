package io.github.jdubois.bootui.engine.restapi.reviewfixtures.base;

import org.springframework.web.bind.annotation.GetMapping;

public class PackagePrivateMappingBase {
    @GetMapping("/package-base")
    String action() {
        return "base";
    }
}
