package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.ConfigPropertySuggestionDto;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ConfigMetadataCatalog {

    private static final String RESOURCE_NAME = "META-INF/spring-configuration-metadata.json";

    private final Map<String, ConfigPropertySuggestionDto> properties;

    ConfigMetadataCatalog(ClassLoader classLoader) {
        this(classLoader, new ObjectMapper());
    }

    ConfigMetadataCatalog(ClassLoader classLoader, ObjectMapper objectMapper) {
        this.properties = Collections.unmodifiableMap(load(classLoader, objectMapper));
    }

    private static Map<String, ConfigPropertySuggestionDto> load(ClassLoader classLoader, ObjectMapper objectMapper) {
        ClassLoader effectiveClassLoader =
                classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        if (effectiveClassLoader == null) {
            effectiveClassLoader = ConfigMetadataCatalog.class.getClassLoader();
        }
        Map<String, ConfigPropertySuggestionDto> result = new LinkedHashMap<>();
        try {
            Enumeration<URL> resources = effectiveClassLoader.getResources(RESOURCE_NAME);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                try (InputStream input = resource.openStream()) {
                    JsonNode propertiesNode = objectMapper.readTree(input).path("properties");
                    if (propertiesNode.isArray()) {
                        for (JsonNode propertyNode : propertiesNode) {
                            addProperty(result, propertyNode, objectMapper);
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read Spring configuration metadata", ex);
        }
        return sortByName(result);
    }

    private static void addProperty(
            Map<String, ConfigPropertySuggestionDto> result, JsonNode propertyNode, ObjectMapper objectMapper) {
        String name = text(propertyNode, "name");
        if (name == null || propertyNode.has("deprecation")) {
            return;
        }
        ConfigPropertySuggestionDto candidate = new ConfigPropertySuggestionDto(
                name,
                text(propertyNode, "type"),
                text(propertyNode, "description"),
                toObject(propertyNode.get("defaultValue"), objectMapper));
        ConfigPropertySuggestionDto current = result.get(name);
        if (current == null || isRicher(candidate, current)) {
            result.put(name, candidate);
        }
    }

    private static boolean isRicher(ConfigPropertySuggestionDto candidate, ConfigPropertySuggestionDto current) {
        return richness(candidate) > richness(current);
    }

    private static int richness(ConfigPropertySuggestionDto suggestion) {
        int score = 0;
        if (suggestion.type() != null) {
            score++;
        }
        if (suggestion.description() != null) {
            score++;
        }
        if (suggestion.defaultValue() != null) {
            score++;
        }
        return score;
    }

    private static Map<String, ConfigPropertySuggestionDto> sortByName(
            Map<String, ConfigPropertySuggestionDto> unsorted) {
        Map<String, ConfigPropertySuggestionDto> sorted = new LinkedHashMap<>();
        unsorted.values().stream()
                .sorted(Comparator.comparing(ConfigPropertySuggestionDto::name))
                .forEach(suggestion -> sorted.put(suggestion.name(), suggestion));
        return sorted;
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asString();
    }

    private static Object toObject(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isNull()) {
            return null;
        }
        return objectMapper.treeToValue(node, Object.class);
    }

    ConfigPropertySuggestionDto get(String name) {
        return properties.get(name);
    }

    List<ConfigPropertySuggestionDto> suggestions() {
        return new ArrayList<>(properties.values());
    }
}
