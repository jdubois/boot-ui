package io.github.jdubois.bootui.autoconfigure.restapi.phase3.good;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A clean controller that should pass all Phase 3 REST API Advisor additions:
 *
 * <ul>
 *   <li>RAPI-MAP-009: no duplicate path-variable tokens</li>
 *   <li>RAPI-MAP-010: no catch-all patterns</li>
 *   <li>RAPI-MAP-011: nesting depth ≤ 3</li>
 *   <li>RAPI-NAME-004: no format-extension suffixes</li>
 *   <li>RAPI-NAME-001 fix: /blog/post/{id} is not a verb-path (post is ambiguous noun)</li>
 *   <li>RAPI-NAME-002 fix: /history returning a collection is not flagged (uncountable)</li>
 *   <li>RAPI-VALID-004: uses explicit typed @RequestParam, not a Map catch-all</li>
 *   <li>RAPI-DTO-005: response record uses java.time types</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class Phase3GoodController {

    record ItemDto(String id, String name, Instant createdAt) {}

    // Deep but not exceeding 3 levels of nesting.
    @GetMapping(value = "/users/{userId}/orders/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ItemDto getOrder(@PathVariable String userId, @PathVariable String orderId) {
        return new ItemDto(orderId, "order", Instant.now());
    }

    // Paginated collection read — no catch-all, no duplicate tokens.
    @GetMapping(value = "/items", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<ItemDto> listItems(Pageable pageable) {
        return Page.empty(pageable);
    }

    // Explicit typed query parameter — not a Map catch-all.
    @GetMapping(value = "/items/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ItemDto> search(@RequestParam String query) {
        return List.of();
    }

    // /blog/post/{id}: "post" is ambiguous (noun), should not be flagged as a verb path.
    @GetMapping(value = "/blog/post/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ItemDto getBlogPost(@PathVariable String id) {
        return new ItemDto(id, "post", Instant.now());
    }

    // /history: uncountable noun, should not be flagged as non-plural collection path.
    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ItemDto> getHistory() {
        return List.of();
    }
}
