package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.otlp.AttributeValue;
import io.github.jdubois.bootui.autoconfigure.otlp.NormalizedSpan;

/**
 * Heuristics for recognizing Spring AI / OTel GenAI spans inside the BootUI
 * telemetry store. Centralized so the Traces and AI controllers stay in sync.
 *
 * <p>Spring AI emits spans following the OTel GenAI semantic conventions:</p>
 * <ul>
 *   <li>Chat: {@code gen_ai.operation.name=chat} and {@code gen_ai.system=<provider>}.</li>
 *   <li>Embeddings: {@code gen_ai.operation.name=embeddings}.</li>
 *   <li>Tool execution: {@code gen_ai.operation.name=execute_tool} or {@code spring.ai.tool}.</li>
 *   <li>Vector store: {@code db.system=spring_ai_vector_store} or scope contains "vectorstore".</li>
 * </ul>
 */
public final class AiSpanRecognizer {

    private AiSpanRecognizer() {
    }

    public static boolean isAi(NormalizedSpan span) {
        return isChat(span) || isEmbedding(span) || isToolCall(span) || isVectorOperation(span);
    }

    public static boolean isChat(NormalizedSpan span) {
        String op = stringAttr(span, "gen_ai.operation.name");
        if (op == null) {
            return false;
        }
        return "chat".equalsIgnoreCase(op) || "text_completion".equalsIgnoreCase(op);
    }

    public static boolean isEmbedding(NormalizedSpan span) {
        String op = stringAttr(span, "gen_ai.operation.name");
        return op != null && "embeddings".equalsIgnoreCase(op);
    }

    public static boolean isToolCall(NormalizedSpan span) {
        String op = stringAttr(span, "gen_ai.operation.name");
        if (op != null && ("execute_tool".equalsIgnoreCase(op) || "tool".equalsIgnoreCase(op))) {
            return true;
        }
        String name = span.name();
        if (name == null) {
            return false;
        }
        return name.startsWith("spring.ai.tool") || name.startsWith("execute_tool");
    }

    public static boolean isVectorOperation(NormalizedSpan span) {
        String dbSystem = stringAttr(span, "db.system");
        if (dbSystem != null && (dbSystem.startsWith("spring_ai") || dbSystem.contains("vector"))) {
            return true;
        }
        String op = stringAttr(span, "db.operation.name");
        String collection = stringAttr(span, "db.collection.name");
        if (op != null && collection != null) {
            // covers spring-ai vector store spans regardless of db.system naming
            String scope = span.scope();
            if (scope != null && scope.toLowerCase().contains("vectorstore")) {
                return true;
            }
        }
        return false;
    }

    public static String provider(NormalizedSpan span) {
        String provider = stringAttr(span, "gen_ai.provider.name");
        if (provider != null && !provider.isBlank()) {
            return provider;
        }
        return stringAttr(span, "gen_ai.system");
    }

    public static String requestModel(NormalizedSpan span) {
        String model = stringAttr(span, "gen_ai.request.model");
        if (model == null) {
            model = stringAttr(span, "gen_ai.request.model_name");
        }
        return model;
    }

    public static String responseModel(NormalizedSpan span) {
        String model = stringAttr(span, "gen_ai.response.model");
        if (model == null) {
            model = stringAttr(span, "gen_ai.response.model_name");
        }
        return model;
    }

    public static Long inputTokens(NormalizedSpan span) {
        Long v = longAttr(span, "gen_ai.usage.input_tokens");
        if (v == null) {
            v = longAttr(span, "gen_ai.usage.prompt_tokens");
        }
        return v;
    }

    public static Long outputTokens(NormalizedSpan span) {
        Long v = longAttr(span, "gen_ai.usage.output_tokens");
        if (v == null) {
            v = longAttr(span, "gen_ai.usage.completion_tokens");
        }
        return v;
    }

    public static Long totalTokens(NormalizedSpan span) {
        Long v = longAttr(span, "gen_ai.usage.total_tokens");
        if (v != null) {
            return v;
        }
        Long in = inputTokens(span);
        Long out = outputTokens(span);
        if (in == null && out == null) {
            return null;
        }
        return (in == null ? 0L : in) + (out == null ? 0L : out);
    }

    public static String finishReason(NormalizedSpan span) {
        AttributeValue av = span.attributes() != null
                ? span.attributes().get("gen_ai.response.finish_reasons") : null;
        if (av == null) {
            return stringAttr(span, "gen_ai.response.finish_reason");
        }
        Object value = av.value();
        if (value instanceof java.util.List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            return String.valueOf(list.get(0));
        }
        return av.asString();
    }

    public static String toolName(NormalizedSpan span) {
        String name = stringAttr(span, "gen_ai.tool.name");
        if (name == null) {
            name = stringAttr(span, "spring.ai.tool.name");
        }
        if (name == null && span.name() != null) {
            String n = span.name();
            int sep = n.indexOf(' ');
            if (sep > 0 && sep < n.length() - 1) {
                return n.substring(sep + 1).trim();
            }
        }
        return name;
    }

    public static String vectorCollection(NormalizedSpan span) {
        String c = stringAttr(span, "db.collection.name");
        if (c == null) {
            c = stringAttr(span, "spring.ai.vectorstore.collection.name");
        }
        return c;
    }

    public static String vectorOperation(NormalizedSpan span) {
        String op = stringAttr(span, "db.operation.name");
        if (op == null) {
            op = stringAttr(span, "spring.ai.vectorstore.operation");
        }
        return op;
    }

    private static String stringAttr(NormalizedSpan span, String key) {
        if (span.attributes() == null) {
            return null;
        }
        AttributeValue av = span.attributes().get(key);
        return av != null ? av.asString() : null;
    }

    private static Long longAttr(NormalizedSpan span, String key) {
        if (span.attributes() == null) {
            return null;
        }
        AttributeValue av = span.attributes().get(key);
        return av != null ? av.asLong() : null;
    }
}
