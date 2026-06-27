package io.github.jdubois.bootui.engine.restapi.phase2.good;

import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller that should pass all Phase 2 REST API Advisor additions and the tightened existing
 * rules (RAPI-MAP-008, RAPI-RESP-008, RAPI-VER-005, RAPI-DTO-003, RAPI-VALID-001).
 */
@RestController
@RequestMapping("/api/orders")
public class Phase2GoodController {

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderDto get(@PathVariable String id) {
        return new OrderDto(id);
    }

    @PutMapping(
            value = "/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderDto replace(@PathVariable String id, @Validated @RequestBody OrderDto body) {
        // Item-targeted mutation: path carries {id}.
        return body;
    }

    @PutMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderDto updateMine(@Validated @RequestBody OrderDto body) {
        // Singleton resource: no {id} needed.
        return body;
    }

    @DeleteMapping(value = "/bulk", produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderDto bulkDelete(@Validated @RequestBody OrderDto body) {
        // Explicit collection-wide mutation: named "bulk".
        return body;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderDto> create(@Validated @RequestBody OrderDto body) {
        // Creation sets Location through ResponseEntity.created(...).
        return ResponseEntity.created(URI.create("/api/orders/" + body.id())).body(body);
    }
}
