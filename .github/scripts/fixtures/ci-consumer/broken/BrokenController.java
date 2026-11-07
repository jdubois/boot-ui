package example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BrokenController {

    @GetMapping("/broken/{id}")
    String broken(@PathVariable("bootui-ci-private-finding-payload") String value) {
        return value;
    }
}
