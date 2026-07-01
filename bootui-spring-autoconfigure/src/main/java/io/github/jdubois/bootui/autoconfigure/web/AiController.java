package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.AiChatDetailDto;
import io.github.jdubois.bootui.core.dto.AiChatSummaryDto;
import io.github.jdubois.bootui.core.dto.AiOverviewDto;
import io.github.jdubois.bootui.core.dto.AiTokenSeriesDto;
import io.github.jdubois.bootui.engine.telemetry.AiUsageService;
import io.github.jdubois.bootui.engine.telemetry.AiUsageSettings;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only API for the BootUI AI Usage panel. Thin Spring adapter over the framework-neutral
 * {@link AiUsageService} in {@code bootui-engine}.
 *
 * <p>The data is derived from the OTLP spans accumulated in {@link TelemetryStore}. Spring AI and
 * LangChain4j both emit the OTel GenAI semantic-conventions spans needed here automatically; no
 * additional configuration is required.</p>
 */
@RestController
@RequestMapping("/bootui/api/ai")
public class AiController {

    private final AiUsageService service;

    public AiController(TelemetryStore store, BootUiProperties properties) {
        this.service = new AiUsageService(
                store,
                () -> new AiUsageSettings(
                        properties.getTelemetry().isEnabled(),
                        properties.getAi().getMaxRecentChats(),
                        properties.getAi().getTokenSeriesMinutes(),
                        properties.getAi().isShowContentCaptureBanner()),
                System::currentTimeMillis);
    }

    @GetMapping("/overview")
    public AiOverviewDto overview() {
        return service.overview();
    }

    @GetMapping("/chats")
    public List<AiChatSummaryDto> chats(
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit) {
        return service.chats(limit);
    }

    @GetMapping("/chats/{spanId}")
    public AiChatDetailDto chatDetail(@PathVariable String spanId) {
        return service.chatDetail(spanId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "chat span " + spanId + " not found"));
    }

    @GetMapping("/tokens")
    public AiTokenSeriesDto tokens(@RequestParam(name = "minutes", required = false) Integer minutes) {
        return service.tokens(minutes);
    }
}
