package io.github.jdubois.bootui.autoconfigure.web;

/**
 * Detects which AI framework is present on the application classpath so the AI
 * Usage panel can describe its availability and tailor its setup guidance.
 *
 * <p>BootUI does not depend on any AI framework directly; it only probes for a
 * well-known marker class. Both Spring AI and LangChain4j emit OpenTelemetry
 * GenAI semantic-convention spans, so the actual panel data is collected the
 * same way regardless of which framework is in use (see
 * {@link AiSpanRecognizer}).</p>
 */
public final class AiFrameworkDetector {

    /** Marker class shipped by Spring AI. */
    static final String SPRING_AI_CLASS = "org.springframework.ai.chat.client.ChatClient";

    /**
     * Marker classes shipped by LangChain4j. The core chat abstraction was named
     * {@code ChatLanguageModel} in early versions and {@code ChatModel} from 1.0
     * onwards, so both are probed for cross-version compatibility.
     */
    static final String[] LANGCHAIN4J_CLASSES = {
        "dev.langchain4j.model.chat.ChatModel", "dev.langchain4j.model.chat.ChatLanguageModel"
    };

    private AiFrameworkDetector() {}

    /** Returns {@code true} when Spring AI is on the classpath. */
    public static boolean isSpringAiPresent() {
        return isClassPresent(SPRING_AI_CLASS);
    }

    /** Returns {@code true} when LangChain4j is on the classpath. */
    public static boolean isLangChain4jPresent() {
        for (String className : LANGCHAIN4J_CLASSES) {
            if (isClassPresent(className)) {
                return true;
            }
        }
        return false;
    }

    /** Returns {@code true} when any supported AI framework is on the classpath. */
    public static boolean isAnyPresent() {
        return isSpringAiPresent() || isLangChain4jPresent();
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, AiFrameworkDetector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }
}
