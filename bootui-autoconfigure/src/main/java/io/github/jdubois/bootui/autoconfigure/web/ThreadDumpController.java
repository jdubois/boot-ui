package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the Thread / Process Viewer panel.
 *
 * <p>{@code GET} returns a filtered, paged snapshot of the JVM's live threads and is passive. The
 * raw text dump is exposed as a {@code POST} so it is treated as an explicit, confirmation-gated
 * action and blocked by {@code PanelAccessFilter} when the panel or BootUI is read-only.</p>
 */
@RestController
@RequestMapping("/bootui/api/threads")
public class ThreadDumpController {

    private final ThreadDumpService service;

    @Autowired
    public ThreadDumpController(BootUiProperties properties) {
        this(new ThreadDumpService(properties));
    }

    ThreadDumpController(ThreadDumpService service) {
        this.service = service;
    }

    @GetMapping
    public ThreadDumpReport threads(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.report(query, state, offset, limit);
    }

    @PostMapping("/download")
    public ResponseEntity<byte[]> download() {
        String dump = service.rawDump();
        if (dump == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] body = dump.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"thread-dump.txt\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }
}
