package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the Heap Dump panel.
 *
 * <p>Reads are passive and cheap. Capture, analyze, and delete are mutating actions exposed via
 * POST, so they are blocked by {@code PanelAccessFilter} when the panel or BootUI is read-only.
 * Raw {@code .hprof} download is disabled by default and returns 404 unless explicitly enabled,
 * because the dump file contains unmasked secrets.</p>
 */
@RestController
@RequestMapping("/bootui/api/heap-dump")
public class HeapDumpController {

    private final HeapDumpService service;

    public HeapDumpController(HeapDumpService service) {
        this.service = service;
    }

    @GetMapping
    public HeapDumpReport report(
            @RequestParam(name = "filter", defaultValue = "") String filter,
            @RequestParam(name = "smartFilter", defaultValue = "") String smartFilter) {
        return service.report(filter, smartFilter);
    }

    @PostMapping("/capture")
    public HeapDumpReport capture(@RequestParam(name = "live", defaultValue = "true") boolean live) {
        return service.capture(live);
    }

    @PostMapping("/analyze")
    public HeapDumpReport analyze() {
        return service.analyze();
    }

    @PostMapping("/delete")
    public HeapDumpReport delete(@RequestParam(name = "name") String name) {
        return service.delete(name);
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam(name = "name") String name) {
        if (!service.rawDownloadAllowed()) {
            return ResponseEntity.notFound().build();
        }
        Path file = service.resolveExisting(name);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
