package io.github.jdubois.bootui.engine.restapi.phase3.fixes;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller with a class-level {@code @RequestMapping(method = GET)}. The method-level
 * {@code @RequestMapping} has no {@code method} attribute, but it should inherit GET from the
 * class level after the RAPI-MAP-001 fix. The handler must NOT be flagged by MAP-001.
 */
@RestController
@RequestMapping(value = "/type-method", method = RequestMethod.GET)
public class TypeLevelMethodController {

    @RequestMapping("/{id}")
    public String getData(@PathVariable String id) {
        return id;
    }
}
