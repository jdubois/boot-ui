package io.github.jdubois.bootui.quarkus.sample.chat;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * LangChain4j AI service backed by Ollama (configured under {@code quarkus.langchain4j.ollama.*}). Drives
 * the AI Usage panel via the GenAI spans the LangChain4j + OpenTelemetry integration emits.
 */
@RegisterAiService
public interface AiAssistant {

    @SystemMessage("You are a concise assistant. Reply in two sentences or less.")
    String chat(@UserMessage String message);
}
