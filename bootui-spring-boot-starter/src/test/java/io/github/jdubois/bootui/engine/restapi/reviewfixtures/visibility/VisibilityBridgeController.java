package io.github.jdubois.bootui.engine.restapi.reviewfixtures.visibility;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VisibilityBridgeController extends VisibilityBridgeBase {
    @GetMapping("/visibility-own")
    public String own() {
        return "own";
    }
}

class VisibilityBridgeBase {
    @GetMapping("/visibility-base")
    public String read() {
        return "base";
    }
}
