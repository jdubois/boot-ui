package io.github.jdubois.bootui.engine.telemetry;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SpanAttributeDto;
import io.github.jdubois.bootui.core.dto.SpanEventDto;
import io.github.jdubois.bootui.engine.support.MessageExposure;
import io.github.jdubois.bootui.engine.support.SensitiveNames;
import io.github.jdubois.bootui.engine.support.UriMasking;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The value-exposure rule for span data the Traces panel, the per-request profile, the AI Framework chat detail, and
 * their MCP and CLI projections return. Spans are stored raw, so a live change to {@code bootui.expose-values} or
 * {@code bootui.mask-secrets} applies to the next read; resolve the rule with {@link #current(ExposurePolicy)} once
 * per response.
 *
 * <p>Each value is classified by its attribute key and reuses the rule its sibling panel already applies:</p>
 *
 * <ul>
 *   <li><strong>Free-form text</strong>: the span status message and the {@code exception.message},
 *       {@code exception.stacktrace}, {@code error.message}, and generative-AI content attributes (prompts,
 *       completions, input and output messages, system instructions, tool call arguments and results, and vector
 *       query content and returned documents) follow
 *       {@link MessageExposure}, exactly as the Exceptions and Log Tail panels do: omitted under
 *       {@link ValueExposure#METADATA_ONLY}, scrubbed of secret-like assignments and authorization credentials under
 *       {@link ValueExposure#MASKED}, and verbatim under {@link ValueExposure#FULL}.</li>
 *   <li><strong>URLs</strong>: {@code url.full}, {@code http.url}, {@code http.target}, {@code url.query}, and
 *       {@code url.path} follow {@link UriMasking}, as the HTTP Exchanges and REST Client panels do.</li>
 *   <li><strong>Header and bound-parameter values</strong>: {@code http.request.header.*},
 *       {@code http.response.header.*}, and {@code db.query.parameter.*} values are omitted under
 *       {@link ValueExposure#METADATA_ONLY}, and a header whose name looks sensitive is masked under
 *       {@link ValueExposure#MASKED}.</li>
 *   <li><strong>Any other string</strong> is masked wholesale when its key looks sensitive
 *       ({@link SensitiveNames}), and otherwise scrubbed of secret-like assignments, unless the policy is
 *       {@link ValueExposure#FULL}. Strings inside list values are treated the same way, and each entry of a key-value
 *       map value is classified by its full dotted key. Numbers and booleans are left as they are, so token counts and
 *       status codes stay visible.</li>
 * </ul>
 *
 * <p>Keys, types, span and event names, ids, kinds, and timings are metadata and are never changed. An omitted value is
 * {@code null}, keeping its key and type so the shape of the span stays diagnosable. Instances are immutable and
 * thread-safe.</p>
 */
public final class SpanValueExposure {

    private static final Set<String> TEXT_KEYS = Set.of(
            "exception.message",
            "exception.stacktrace",
            "error.message",
            "gen_ai.prompt",
            "gen_ai.completion",
            "gen_ai.input.messages",
            "gen_ai.output.messages",
            "gen_ai.system_instructions",
            "gen_ai.tool.call.arguments",
            "gen_ai.tool.call.result",
            "spring.ai.tool.call.arguments",
            "spring.ai.tool.call.result",
            "db.vector.query.content",
            "db.vector.query.response.documents");

    /** Indexed generative-AI content, such as {@code gen_ai.prompt.0.content}, follows the free-form text rule too. */
    private static final List<String> TEXT_PREFIXES = List.of("gen_ai.prompt.", "gen_ai.completion.");

    private static final Set<String> URI_KEYS = Set.of("url.full", "http.url", "http.target");

    private static final List<String> HEADER_PREFIXES = List.of("http.request.header.", "http.response.header.");

    private static final List<String> PARAMETER_PREFIXES = List.of("db.query.parameter.", "db.operation.parameter.");

    private static final Object OMITTED = new Object();

    private final ValueExposure exposure;

    private final boolean maskSecrets;

    private final MessageExposure messages;

    private SpanValueExposure(ValueExposure exposure, boolean maskSecrets, MessageExposure messages) {
        this.exposure = exposure;
        this.maskSecrets = maskSecrets;
        this.messages = messages;
    }

    /**
     * The rule the policy prescribes right now. A {@code null} exposure mode is treated as
     * {@link ValueExposure#MASKED}, so an unresolved policy never reveals values.
     */
    public static SpanValueExposure current(ExposurePolicy policy) {
        ValueExposure read = policy.valueExposure();
        ValueExposure exposure = read == null ? ValueExposure.MASKED : read;
        boolean maskSecrets = policy.maskSecrets();
        // One snapshot per response, so a concurrent change cannot mix two modes in one trace.
        ExposurePolicy snapshot = new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
        return new SpanValueExposure(exposure, maskSecrets, MessageExposure.current(snapshot));
    }

    /** Applies the free-form text rule to a span status message. */
    public String statusMessage(String statusMessage) {
        return messages.apply(statusMessage);
    }

    /** Applies the rule to every attribute, preserving keys, types, and order. */
    public List<SpanAttributeDto> attributes(Map<String, AttributeValue> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return List.of();
        }
        List<SpanAttributeDto> out = new ArrayList<>(attributes.size());
        for (Map.Entry<String, AttributeValue> entry : attributes.entrySet()) {
            AttributeValue value = entry.getValue();
            String type = value == null ? null : value.type();
            Object raw = value == null ? null : value.value();
            out.add(new SpanAttributeDto(entry.getKey(), type, value(entry.getKey(), raw)));
        }
        return out;
    }

    /** Applies the rule to the attributes of every event, preserving event names and offsets. */
    public List<SpanEventDto> events(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<SpanEventDto> out = new ArrayList<>(events.size());
        for (NormalizedEvent event : events) {
            out.add(new SpanEventDto(event.name(), event.timeOffsetNanos(), attributes(event.attributes())));
        }
        return out;
    }

    /** Masks the {@code ;name=value} matrix parameters a request path may carry, as the HTTP Exchanges panel does. */
    public String path(String path) {
        return UriMasking.maskPath(path, maskSecrets, exposure);
    }

    Object value(String key, Object value) {
        if (value == null || key == null) {
            return value;
        }
        String name = key.toLowerCase(Locale.ROOT);
        if (TEXT_KEYS.contains(name) || suffixAfter(name, TEXT_PREFIXES) != null) {
            return messages.omitsText() ? null : leaves(value, messages::apply);
        }
        if (URI_KEYS.contains(name)) {
            return leaves(value, uri -> UriMasking.maskUri(uri, maskSecrets, exposure));
        }
        if (name.equals("url.query")) {
            return exposure == ValueExposure.METADATA_ONLY
                    ? null
                    : leaves(value, query -> UriMasking.maskQueryString(query, maskSecrets, exposure));
        }
        if (name.equals("url.path")) {
            return leaves(value, this::path);
        }
        String header = suffixAfter(name, HEADER_PREFIXES);
        if (header != null) {
            if (exposure == ValueExposure.METADATA_ONLY) {
                return null;
            }
            if (masksByName() && SensitiveNames.isSensitive(header)) {
                return leaves(value, ignored -> SecretMasker.MASKED_VALUE);
            }
            return leaves(value, this::scrub);
        }
        if (suffixAfter(name, PARAMETER_PREFIXES) != null && exposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(value(key, element));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            // A key-value list attribute: each entry is classified by its full dotted key, like a flat attribute.
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(entry.getKey(), value(key + "." + entry.getKey(), entry.getValue()));
            }
            return out;
        }
        if (masksByName() && SensitiveNames.isSensitive(key)) {
            return leaves(value, ignored -> SecretMasker.MASKED_VALUE);
        }
        return leaves(value, this::scrub);
    }

    /**
     * Whether a value is masked because of its name. {@link ValueExposure#METADATA_ONLY} always does, matching
     * {@link UriMasking}; {@link ValueExposure#MASKED} defers to {@code bootui.mask-secrets}.
     */
    private boolean masksByName() {
        if (exposure == ValueExposure.METADATA_ONLY) {
            return true;
        }
        return maskSecrets && exposure != ValueExposure.FULL;
    }

    /** Scrubs secret-like assignments out of a value that is not free-form text but may still echo one. */
    private String scrub(String value) {
        return masksByName() ? MessageExposure.maskSecretAssignments(value) : value;
    }

    private static String suffixAfter(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * Applies {@code rule} to every string leaf of a value, through nested lists and key-value maps, leaving numbers
     * and booleans as they are. The whole value is omitted when the rule omits any of its strings.
     */
    private static Object leaves(Object value, UnaryOperator<String> rule) {
        Object out = leaf(value, rule);
        return out == OMITTED ? null : out;
    }

    private static Object leaf(Object value, UnaryOperator<String> rule) {
        if (value instanceof String text) {
            String applied = rule.apply(text);
            return applied == null ? OMITTED : applied;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                Object applied = leaf(element, rule);
                if (applied == OMITTED) {
                    return OMITTED;
                }
                out.add(applied);
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object applied = leaf(entry.getValue(), rule);
                if (applied == OMITTED) {
                    return OMITTED;
                }
                out.put(entry.getKey(), applied);
            }
            return out;
        }
        return value;
    }
}
