package io.github.jdubois.bootui.sample;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@ConditionalOnBean(ChatModel.class)
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem("You are a concise assistant. Reply in two sentences or less.")
                .build();
    }

    @PostMapping
    public Map<String, String> chat(@RequestBody ChatRequest request) {
        String reply = chatClient.prompt()
                .user(request.message())
                .call()
                .content();
        return Map.of("reply", reply);
    }

    public record ChatRequest(String message) {
    }
}
