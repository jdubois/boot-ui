package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.DevToolsActionResult;
import io.github.jdubois.bootui.core.BootUiDtos.DevToolsRestartRequest;
import io.github.jdubois.bootui.core.BootUiDtos.DevToolsStatus;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/devtools")
public class DevToolsController {

    private final DevToolsBridge devTools;

    public DevToolsController(DevToolsBridge devTools) {
        this.devTools = devTools;
    }

    @GetMapping
    public DevToolsStatus status() {
        return devTools.status();
    }

    @PostMapping("/livereload")
    public ResponseEntity<DevToolsActionResult> triggerLiveReload() {
        DevToolsActionResult result = devTools.triggerLiveReload();
        return ResponseEntity.status(statusFor(result)).body(result);
    }

    @PostMapping("/restart")
    public ResponseEntity<DevToolsActionResult> restart(@RequestBody(required = false) DevToolsRestartRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            return ResponseEntity.badRequest().body(new DevToolsActionResult("restart", "confirmation_required",
                    "Restart requires explicit confirmation."));
        }
        DevToolsActionResult result = devTools.scheduleRestart();
        return ResponseEntity.status(statusFor(result)).body(result);
    }

    private HttpStatus statusFor(DevToolsActionResult result) {
        return switch (result.status()) {
            case "scheduled" -> HttpStatus.ACCEPTED;
            case "unavailable", "already_pending" -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", ex.getMessage() == null ? "DevTools action failed" : ex.getMessage()));
    }
}
