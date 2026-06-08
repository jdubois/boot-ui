package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.DismissedRulesDto;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages the set of advisor rule IDs that have been dismissed by the developer.
 *
 * <p>Dismissed rules are stored in {@code .bootui/dismissed-rules.yaml} and are
 * purely a local, developer-facing preference. They are never sent to any external
 * service.</p>
 */
@RestController
@RequestMapping("/bootui/api/dismissed-rules")
public class DismissedRulesController {

    private final DismissedRulesStore store;

    @Autowired
    public DismissedRulesController(DismissedRulesStore store) {
        this.store = store;
    }

    @GetMapping
    public DismissedRulesDto list() {
        return new DismissedRulesDto(List.copyOf(store.load()));
    }

    @PostMapping("/{ruleId}")
    public ResponseEntity<DismissedRulesDto> dismiss(@PathVariable String ruleId) {
        return ResponseEntity.ok(new DismissedRulesDto(List.copyOf(store.dismiss(ruleId))));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<DismissedRulesDto> restore(@PathVariable String ruleId) {
        return ResponseEntity.ok(new DismissedRulesDto(List.copyOf(store.restore(ruleId))));
    }
}
