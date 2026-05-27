package io.github.jdubois.bootui.sample;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ObjectProvider<ChatClient.Builder> builderProvider) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        this.chatClient = (builder != null)
                ? builder.defaultSystem("You are a concise assistant. Reply in two sentences or less.")
                        .build()
                : null;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> chat(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message must not be blank."));
        }
        if (chatClient == null) {
            return ResponseEntity.status(503)
                    .body(
                            Map.of(
                                    "error",
                                    "Spring AI ChatClient is not configured. Ensure Ollama auto-configuration is enabled and a model is reachable."));
        }
        String message = request.message().trim();
        String reply = chatClient.prompt().user(message).call().content();
        return ResponseEntity.ok(Map.of("reply", reply == null ? "" : reply));
    }

    public record ChatRequest(String message) {}
}
