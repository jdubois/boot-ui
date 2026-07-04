package io.github.jdubois.bootui.engine.restapi.newrules.deprecation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A handler annotated {@code @Deprecated} with no accompanying {@code @Operation(deprecated = true)}
 * — {@code @Deprecated} only communicates to compile-time Java consumers, so an HTTP client calling
 * this endpoint directly gets no deprecation signal at all. Must be flagged by RAPI-DOC-003 (new
 * rule, Part 2 #2) once OpenAPI annotations are present on the host classpath, and SKIPPED otherwise.
 */
@RestController
public class DeprecatedWidgetController {

    @Deprecated
    @GetMapping("/legacy-widgets")
    public String legacyWidgets() {
        return "widgets";
    }
}
