package io.github.jdubois.bootui.engine.restapi.phase3.bad;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller that intentionally trips the Phase 3 REST API Advisor additions:
 *
 * <ul>
 *   <li>RAPI-MAP-009: duplicate path-variable token in one template</li>
 *   <li>RAPI-MAP-010: catch-all pattern in mapping</li>
 *   <li>RAPI-MAP-011: resource nesting deeper than 3 collection/{id} pairs</li>
 *   <li>RAPI-NAME-004: static path segment with a format extension suffix</li>
 *   <li>RAPI-VALID-004: {@code @RequestParam Map} catch-all binding</li>
 *   <li>RAPI-DTO-005: response DTO with java.util.Date fields</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/phase3")
public class Phase3BadController {

    // RAPI-MAP-009: duplicate {id} token in a single path template.
    @GetMapping("/categories/{id}/items/{id}")
    public String getDuplicateId(@PathVariable String id) {
        return id;
    }

    // RAPI-MAP-010: catch-all wildcard pattern (/**).
    @GetMapping("/**")
    public String catchAll() {
        return "catch-all";
    }

    // RAPI-MAP-011: nesting depth 4 (> 3 collection/{id} pairs).
    @GetMapping("/a/{aId}/b/{bId}/c/{cId}/d/{dId}")
    public String deepNesting(
            @PathVariable String aId, @PathVariable String bId, @PathVariable String cId, @PathVariable String dId) {
        return aId;
    }

    // RAPI-NAME-004: path segment ends with a format suffix (.json).
    @GetMapping("/reports.json")
    public String jsonSuffix() {
        return "{}";
    }

    // RAPI-VALID-004: @RequestParam Map<String, String> accepts unbounded query parameters.
    @GetMapping("/search")
    public List<String> searchWithMap(@RequestParam Map<String, String> params) {
        return List.of();
    }

    // RAPI-DTO-005: response DTO has java.util.Date fields.
    @GetMapping("/dated/{id}")
    public DateFieldDto getDated(@PathVariable String id) {
        return new DateFieldDto();
    }
}
