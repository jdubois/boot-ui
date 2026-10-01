package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes {@link ControllerOrderDto} in a handler signature, for which Spring AOT infers binding hints. */
@RestController
public class OrderController {

    @GetMapping("/orders/latest")
    public ControllerOrderDto latest() {
        return new ControllerOrderDto("1");
    }
}
