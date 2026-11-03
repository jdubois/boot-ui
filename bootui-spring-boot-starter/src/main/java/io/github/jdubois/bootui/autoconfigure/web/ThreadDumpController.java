package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import io.github.jdubois.bootui.core.dto.ThreadAnalysisReport;
import io.github.jdubois.bootui.core.dto.ThreadCaptureArtifact;
import io.github.jdubois.bootui.core.dto.ThreadCaptureList;
import io.github.jdubois.bootui.core.dto.ThreadCapturePage;
import io.github.jdubois.bootui.core.dto.ThreadCaptureSummary;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.core.dto.ThreadLockReport;
import io.github.jdubois.bootui.core.dto.ThreadObservationReport;
import io.github.jdubois.bootui.core.dto.ThreadObservationRequest;
import io.github.jdubois.bootui.engine.threads.ThreadAnalysisException;
import io.github.jdubois.bootui.engine.threads.ThreadAnalysisService;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/threads")
public class ThreadDumpController {

    private final ThreadDumpService service;
    private final ThreadAnalysisService analysis;

    public ThreadDumpController(ThreadDumpService service, ThreadAnalysisService analysis) {
        this.service = service;
        this.analysis = analysis;
    }

    public ThreadAnalysisService analysisService() {
        return analysis;
    }

    @GetMapping
    public ThreadDumpReport threads(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(defaultValue = "true") boolean includeBootUi) {
        return service.report(query, state, offset, limit, includeBootUi);
    }

    public ThreadDumpReport threads(String query, String state, Integer offset, Integer limit) {
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

    @PostMapping("/captures")
    public ThreadCaptureSummary capture() {
        return analysis.capture();
    }

    @GetMapping("/captures")
    public ThreadCaptureList captures(@RequestParam(required = false) Integer limit) {
        return analysis.list(limit);
    }

    @GetMapping({"/captures/{id}", "/captures/{id}/contention"})
    public ThreadAnalysisReport analysis(
            @PathVariable String id,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        return analysis.analysis(id, offset, limit);
    }

    @GetMapping("/captures/{id}/threads")
    public ThreadCapturePage capturedThreads(
            @PathVariable String id,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "true") boolean includeBootUi) {
        return analysis.threads(id, query, state, sort, direction, offset, limit, includeBootUi);
    }

    @GetMapping("/captures/{id}/threads/{observationId}")
    public CapturedThreadDto thread(@PathVariable String id, @PathVariable String observationId) {
        if (!observationId.startsWith(id + ":")) {
            throw new ThreadAnalysisException(400, "Thread observation does not belong to this capture.");
        }
        return analysis.thread(observationId);
    }

    @GetMapping("/captures/{id}/locks/{lockId}")
    public ThreadLockReport lock(
            @PathVariable String id,
            @PathVariable String lockId,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        return analysis.lock(id, lockId, offset, limit);
    }

    @PostMapping("/captures/clear")
    public Map<String, Long> clearCaptures() {
        return Map.of("clearedCaptures", analysis.clear());
    }

    @PostMapping("/captures/{id}/export")
    public ResponseEntity<ThreadCaptureArtifact> export(@PathVariable String id) {
        ThreadCaptureArtifact artifact = analysis.export(id);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bootui-thread-capture-"
                                + artifact.capture().id() + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(artifact);
    }

    @PostMapping("/observations")
    public ThreadObservationReport observe(
            @RequestBody(required = false) ThreadObservationRequest request,
            @RequestParam(defaultValue = "true") boolean includeBootUi) {
        return analysis.observe(
                request == null ? null : request.samples(),
                request == null ? null : request.intervalMillis(),
                includeBootUi);
    }

    @GetMapping("/observations/{id}")
    public ThreadObservationReport observation(
            @PathVariable String id,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "true") boolean includeBootUi) {
        return analysis.observation(id, offset, limit, includeBootUi);
    }

    @PostMapping("/observations/{id}/cancel")
    public ThreadObservationReport cancelObservation(
            @PathVariable String id, @RequestParam(defaultValue = "true") boolean includeBootUi) {
        return analysis.cancel(id, includeBootUi);
    }

    @ExceptionHandler(ThreadAnalysisException.class)
    public ResponseEntity<Map<String, String>> analysisFailure(ThreadAnalysisException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("error", exception.getMessage()));
    }
}
