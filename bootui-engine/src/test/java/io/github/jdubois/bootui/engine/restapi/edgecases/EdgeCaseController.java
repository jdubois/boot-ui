package io.github.jdubois.bootui.engine.restapi.edgecases;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controller whose handlers each intentionally trip one of the newer REST API Advisor rules
 * (RAPI-MAP-006/007, RAPI-RESP-005/006/007, RAPI-VALID-003, RAPI-VER-002/004). Kept in a sibling
 * package so it does not perturb the aggregate assertions over the {@code fixtures} packages.
 */
@RestController
@RequestMapping("/api/v2/widgets")
public class EdgeCaseController {

    // RAPI-MAP-006: @PathVariable name has no matching {token} in the path.
    @GetMapping("/{id}")
    public WidgetDto getWidget(@PathVariable("widgetId") String id) {
        return new WidgetDto(id, "widget");
    }

    // RAPI-MAP-007: @RequestBody on a DELETE handler.
    @DeleteMapping("/{id}")
    public void removeWidget(@PathVariable String id, @RequestBody WidgetDto body) {
        // no-op
    }

    // RAPI-RESP-005: GET handler returns void (empty 200 OK).
    @GetMapping("/ping")
    public void ping() {
        // no-op
    }

    // RAPI-RESP-006: 204 No Content declared but a body is returned.
    @GetMapping("/latest")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public WidgetDto latest() {
        return new WidgetDto("1", "latest");
    }

    // RAPI-RESP-007: @ResponseStatus is ignored alongside ResponseEntity.
    @GetMapping("/first")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<WidgetDto> first() {
        return ResponseEntity.ok(new WidgetDto("1", "first"));
    }

    // RAPI-VALID-003: optional primitive @RequestParam can throw 500 when omitted.
    @GetMapping("/scan")
    public WidgetDto scan(@RequestParam(required = false) int limit) {
        return new WidgetDto(String.valueOf(limit), "scan");
    }

    // RAPI-VER-002: mutating endpoint with a body declares no consumes media type.
    @PostMapping("/new")
    public WidgetDto createWidget(@RequestBody WidgetDto body) {
        return body;
    }

    // RAPI-VER-004: PATCH declares a non-JSON, non-patch-specific consumes media type.
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_XML_VALUE)
    public WidgetDto patchWidget(@PathVariable String id, @RequestBody WidgetDto body) {
        return body;
    }

    // RAPI-VER-004: plain application/json PATCH is a legitimate partial-update pattern (RFC 5789
    // §2 does not mandate merge-patch+json/json-patch+json specifically) and must PASS.
    @PatchMapping(value = "/{id}/rename", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WidgetDto patchWidgetJson(@PathVariable String id, @RequestBody WidgetDto body) {
        return body;
    }
}
