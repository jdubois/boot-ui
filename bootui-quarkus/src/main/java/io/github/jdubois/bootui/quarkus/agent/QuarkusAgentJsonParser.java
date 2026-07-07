package io.github.jdubois.bootui.quarkus.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.spi.agent.AgentJson;
import io.github.jdubois.bootui.spi.agent.AgentJsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Quarkus agent JSON parser over Jackson 2 ({@code com.fasterxml.jackson}, from {@code
 * quarkus-rest-jackson}). The twin of the Spring {@code SpringAgentJsonParser}: it keeps the shared
 * engine session store free of any Jackson dependency by handing it neutral {@link AgentJson} trees, so
 * both adapters render byte-identical payloads.
 */
public class QuarkusAgentJsonParser implements AgentJsonParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AgentJson parseTree(Path file) throws IOException {
        return wrap(objectMapper.readTree(file.toFile()));
    }

    @Override
    public AgentJson parseLine(String line) {
        try {
            return wrap(objectMapper.readTree(line));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Malformed JSON line", ex);
        }
    }

    private static AgentJson wrap(JsonNode node) {
        return node == null ? null : new JacksonAgentJson(node);
    }

    private record JacksonAgentJson(JsonNode node) implements AgentJson {

        @Override
        public AgentJson get(String fieldName) {
            return wrap(node.get(fieldName));
        }

        @Override
        public AgentJson get(int index) {
            return wrap(node.get(index));
        }

        @Override
        public boolean isNull() {
            return node.isNull();
        }

        @Override
        public boolean isArray() {
            return node.isArray();
        }

        @Override
        public boolean isObject() {
            return node.isObject();
        }

        @Override
        public boolean isString() {
            return node.isTextual();
        }

        @Override
        public boolean isBoolean() {
            return node.isBoolean();
        }

        @Override
        public int size() {
            return node.size();
        }

        @Override
        public boolean canConvertToLong() {
            return node.canConvertToLong();
        }

        @Override
        public String asString() {
            return node.isValueNode() ? node.asText() : null;
        }

        @Override
        public long asLong() {
            return node.asLong();
        }

        @Override
        public boolean asBoolean() {
            return node.asBoolean();
        }

        @Override
        public List<Map.Entry<String, AgentJson>> properties() {
            List<Map.Entry<String, AgentJson>> entries = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                entries.add(Map.entry(e.getKey(), (AgentJson) new JacksonAgentJson(e.getValue())));
            }
            return entries;
        }

        @Override
        public String toPrettyString() {
            return node.toPrettyString();
        }
    }
}
