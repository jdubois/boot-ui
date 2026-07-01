package io.github.jdubois.bootui.engine.restapi.phase3.fixes;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller with a class-level {@code @ResponseStatus} and a method that returns
 * {@code ResponseEntity}. After the RAPI-RESP-007 fix, this should NOT be flagged — only
 * method-level {@code @ResponseStatus} combined with {@code ResponseEntity} is misleading.
 */
@RestController
@RequestMapping("/class-status")
@ResponseStatus(HttpStatus.CREATED)
public class ClassStatusController {

    @GetMapping("/{id}")
    public ResponseEntity<String> get(@PathVariable String id) {
        return ResponseEntity.ok(id);
    }
}
