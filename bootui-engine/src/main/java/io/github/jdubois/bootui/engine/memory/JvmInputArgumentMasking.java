package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.SecretValueDetector;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the live exposure policy to JVM input arguments before they leave the engine.
 *
 * <p>{@code RuntimeMXBean.getInputArguments()} carries system properties exactly as they were typed on the
 * command line, so a {@code -Dspring.datasource.password=…} would otherwise be serialized in clear text by the
 * Live Memory and JVM Tuning panels, their MCP tools and the CLI. Keys stay visible for tuning context and only
 * values are replaced:</p>
 *
 * <ul>
 *   <li>{@link ValueExposure#FULL}, or {@link ValueExposure#MASKED} with {@code bootui.mask-secrets=false}:
 *       arguments are returned unchanged.</li>
 *   <li>{@link ValueExposure#MASKED} (default): a value is masked when {@link SecretMasker} flags its key or
 *       the value itself, for {@code -Dkey=value}, {@code -XX:Name=value} and any other {@code key=value}
 *       argument, and for each {@code key=value} option of a {@code -javaagent:}, {@code -agentlib:} or
 *       {@code -agentpath:} argument. A bare argument that itself looks like a secret is masked whole.</li>
 *   <li>{@link ValueExposure#METADATA_ONLY}: every system property value and every agent option string is
 *       masked; {@code -X}/{@code -XX} JVM flags stay visible because they are the tuning metadata the panel
 *       reviews, and still go through the secret checks above.</li>
 * </ul>
 */
final class JvmInputArgumentMasking {

    /** Fail-closed default when no adapter policy is supplied: mask secret-like values. */
    static final ExposurePolicy MASKED_BY_DEFAULT = new ExposurePolicy() {
        @Override
        public ValueExposure valueExposure() {
            return ValueExposure.MASKED;
        }

        @Override
        public boolean maskSecrets() {
            return true;
        }
    };

    private static final String SYSTEM_PROPERTY_PREFIX = "-D";
    private static final List<String> AGENT_PREFIXES = List.of("-javaagent:", "-agentlib:", "-agentpath:");
    private static final SecretMasker MASKER = new SecretMasker();

    private JvmInputArgumentMasking() {}

    static List<String> mask(List<String> arguments, ExposurePolicy policy) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        ExposurePolicy effective = policy == null ? MASKED_BY_DEFAULT : policy;
        ValueExposure exposure = effective.valueExposure() == null ? ValueExposure.MASKED : effective.valueExposure();
        boolean metadataOnly = exposure == ValueExposure.METADATA_ONLY;
        if (!metadataOnly && (exposure == ValueExposure.FULL || !effective.maskSecrets())) {
            return arguments;
        }
        List<String> masked = new ArrayList<>(arguments.size());
        for (String argument : arguments) {
            if (argument != null) {
                masked.add(mask(argument, metadataOnly));
            }
        }
        return masked;
    }

    static String mask(String argument, boolean metadataOnly) {
        if (argument.startsWith(SYSTEM_PROPERTY_PREFIX)) {
            return maskKeyValue(
                    SYSTEM_PROPERTY_PREFIX, argument.substring(SYSTEM_PROPERTY_PREFIX.length()), metadataOnly);
        }
        for (String prefix : AGENT_PREFIXES) {
            if (argument.startsWith(prefix)) {
                return maskAgent(prefix, argument.substring(prefix.length()), metadataOnly);
            }
        }
        if (argument.indexOf('=') > 0) {
            return maskKeyValue("", argument, false);
        }
        return SecretValueDetector.looksLikeSecret(argument) ? SecretMasker.MASKED_VALUE : argument;
    }

    private static String maskKeyValue(String prefix, String body, boolean maskAll) {
        int separator = body.indexOf('=');
        if (separator < 0) {
            return SecretValueDetector.looksLikeSecret(body) ? prefix + SecretMasker.MASKED_VALUE : prefix + body;
        }
        String key = body.substring(0, separator);
        String value = body.substring(separator + 1);
        if (value.isEmpty() || !(maskAll || MASKER.shouldMask(key, value))) {
            return prefix + body;
        }
        return prefix + key + "=" + SecretMasker.MASKED_VALUE;
    }

    private static String maskAgent(String prefix, String body, boolean maskAll) {
        int separator = body.indexOf('=');
        if (separator < 0) {
            return SecretValueDetector.looksLikeSecret(body) ? prefix + SecretMasker.MASKED_VALUE : prefix + body;
        }
        String target = body.substring(0, separator);
        String options = body.substring(separator + 1);
        if (options.isEmpty()) {
            return prefix + body;
        }
        if (maskAll) {
            return prefix + target + "=" + SecretMasker.MASKED_VALUE;
        }
        String[] parts = options.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int optionSeparator = part.indexOf('=');
            if (optionSeparator > 0) {
                parts[i] = maskKeyValue("", part, false);
            } else if (SecretValueDetector.looksLikeSecret(part)) {
                parts[i] = SecretMasker.MASKED_VALUE;
            }
        }
        return prefix + target + "=" + String.join(",", parts);
    }
}
