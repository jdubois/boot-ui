package io.github.jdubois.bootui.engine.restapi.phase2.bad;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller that intentionally trips the Phase 2 REST API Advisor additions and tightened rules:
 * RAPI-MAP-008 (mutation without an id), RAPI-RESP-008 (CREATED body with no Location),
 * RAPI-VER-005 (inconsistent produces), RAPI-DTO-003 (non-GET raw collection), and RAPI-VALID-001
 * (bare {@code @NotNull} on a request body).
 */
@RestController
@RequestMapping("/api/items")
public class Phase2BadController {

    @DeleteMapping
    public void deleteEverything() {
        // RAPI-MAP-008: DELETE on the collection path with no {id}.
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDto create(@RequestBody ItemDto body) {
        // RAPI-RESP-008: CREATED plain body cannot set Location.
        // RAPI-VER-005: serializes a body but declares no produces.
        return body;
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ItemDto get(@PathVariable String id) {
        // Declares produces, so the controller qualifies for the RAPI-VER-005 consistency check.
        return new ItemDto(id);
    }

    @PutMapping("/{id}")
    public void replace(@PathVariable String id, @RequestBody ItemDto body) {
        // RAPI-VALID-001: @RequestBody with no @Valid/@Validated is unvalidated.
    }

    @PostMapping("/search")
    public List<ItemDto> search(@RequestBody ItemDto query) {
        return List.of();
    }
}
