package io.github.jdubois.bootui.autoconfigure.restapi.phase3.bad;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two handlers in the same context that use different API versioning strategies (path vs.
 * media-type), triggering RAPI-VER-006.
 */
@RestController
@RequestMapping("/mixed")
public class MixedVersionController {

    // Strategy 1: path versioning (/v1/).
    @GetMapping("/v1/items")
    public String pathVersioned() {
        return "v1";
    }

    // Strategy 2: media-type versioning.
    @GetMapping(value = "/items", produces = "application/vnd.example.v2+json")
    public String mediaTypeVersioned() {
        return "v2";
    }
}
