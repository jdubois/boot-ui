package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.SecretValueDetector;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
 *       argument, and for each nested comma-separated {@code key=value} option, including those of a
 *       {@code -javaagent:}, {@code -agentlib:} or {@code -agentpath:} argument. The shell commands of
 *       {@code -XX:OnError} and {@code -XX:OnOutOfMemoryError} are always masked, since they are opaque and may
 *       embed credentials no pattern recognizes. A bare argument that itself looks like a secret is masked
 *       whole.</li>
 *   <li>{@link ValueExposure#METADATA_ONLY}: every system property value and every agent option string is
 *       masked. {@code -X}/{@code -XX} flags stay visible for tuning, and so do their values when they are a
 *       plain token such as {@code 75}, {@code 512m} or {@code summary}; free-text values such as paths,
 *       commands or {@code -Xlog} selections, and standalone operands, are masked.</li>
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
    private static final String XX_PREFIX = "-XX:";
    private static final List<String> AGENT_PREFIXES = List.of("-javaagent:", "-agentlib:", "-agentpath:");
    private static final List<String> COMMAND_OPTIONS = List.of("-XX:OnError=", "-XX:OnOutOfMemoryError=");

    /** A value with no path, command, list or nested assignment structure: numbers, sizes, enum names. */
    private static final Pattern PLAIN_TOKEN = Pattern.compile("[A-Za-z0-9_.+%-]{1,64}");

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
        for (String prefix : COMMAND_OPTIONS) {
            if (argument.startsWith(prefix)) {
                return argument.length() == prefix.length() ? argument : prefix + SecretMasker.MASKED_VALUE;
            }
        }
        if (metadataOnly) {
            return maskJvmOptionMetadata(argument);
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
        if (value.isEmpty()) {
            return prefix + body;
        }
        if (maskAll || MASKER.shouldMask(key, value)) {
            return prefix + key + "=" + SecretMasker.MASKED_VALUE;
        }
        return prefix + key + "=" + maskOptions(value);
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
        if (maskAll || SecretValueDetector.looksLikeSecret(options)) {
            return prefix + target + "=" + SecretMasker.MASKED_VALUE;
        }
        return prefix + target + "=" + maskOptions(options);
    }

    /** Masks each secret {@code key=value} or secret-looking part of a comma-separated option list. */
    private static String maskOptions(String options) {
        String[] parts = options.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int separator = part.indexOf('=');
            if (separator > 0) {
                String value = part.substring(separator + 1);
                if (!value.isEmpty() && MASKER.shouldMask(part.substring(0, separator), value)) {
                    parts[i] = part.substring(0, separator + 1) + SecretMasker.MASKED_VALUE;
                }
            } else if (SecretValueDetector.looksLikeSecret(part)) {
                parts[i] = SecretMasker.MASKED_VALUE;
            }
        }
        return String.join(",", parts);
    }

    /**
     * Keeps an option's name and a plain-token value; masks any other value. {@code -XX:} and {@code --} options
     * carry their value after {@code =}, other {@code -X} and launcher options after the first {@code :} or
     * {@code =}. An argument that is not an option is an operand of the previous one and is masked whole.
     */
    private static String maskJvmOptionMetadata(String argument) {
        if (!argument.startsWith("-")) {
            return SecretMasker.MASKED_VALUE;
        }
        int separator;
        if (argument.startsWith(XX_PREFIX) || argument.startsWith("--")) {
            separator = argument.indexOf('=');
        } else {
            int colon = argument.indexOf(':');
            int equals = argument.indexOf('=');
            separator = colon < 0 ? equals : equals < 0 ? colon : Math.min(colon, equals);
        }
        if (separator < 0) {
            String name = argument.startsWith(XX_PREFIX) ? argument.substring(XX_PREFIX.length()) : argument;
            return PLAIN_TOKEN.matcher(name).matches() ? argument : SecretMasker.MASKED_VALUE;
        }
        String key = argument.substring(0, separator);
        String value = argument.substring(separator + 1);
        if (value.isEmpty() || (PLAIN_TOKEN.matcher(value).matches() && !MASKER.shouldMask(key, value))) {
            return argument;
        }
        return argument.substring(0, separator + 1) + SecretMasker.MASKED_VALUE;
    }
}
