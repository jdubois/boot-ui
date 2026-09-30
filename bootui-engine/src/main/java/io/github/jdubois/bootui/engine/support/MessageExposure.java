package io.github.jdubois.bootui.engine.support;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The value-exposure rule for free-form text an application wrote itself: exception messages, log messages, and
 * container log output. The Exceptions, Log Tail, and Dev Services panels all apply this one rule, so the three
 * surfaces mask identically and a pattern fix reaches every one of them at once.
 *
 * <ul>
 *   <li>{@link ValueExposure#METADATA_ONLY} omits the text entirely.</li>
 *   <li>{@link ValueExposure#MASKED}, the default, replaces the value of each secret-like {@code key=value} or
 *       {@code key: value} assignment with {@link SecretMasker#MASKED_VALUE} and keeps the key, unless
 *       {@code bootui.mask-secrets=false}.</li>
 *   <li>{@link ValueExposure#FULL} returns the text verbatim.</li>
 * </ul>
 *
 * <p>Only assignments to a secret-like key are detected, and only their first whitespace-free value token is masked.
 * Bare tokens, credentials embedded in connection strings, and the credential that follows an authorization scheme,
 * as in {@code Authorization: Bearer <token>} where only {@code Bearer} is masked, are not, so masked text is safer to
 * show, not guaranteed secret-free.</p>
 *
 * <p>Resolve the rule with {@link #current(ExposurePolicy)} at read time, once per response or streamed line, so a
 * live change to {@code bootui.expose-values} or {@code bootui.mask-secrets} applies to the next read without a
 * restart. Instances are immutable and thread-safe.</p>
 */
public final class MessageExposure {

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|pwd|secret|token|api[-_]?key|apikey|authorization|credential|"
                    + "access[-_]?key|client[-_]?secret|private[-_]?key)[\"']?\\s*[=:]\\s*[\"']?)([^\\s\"',;&)]+)");

    private static final MessageExposure OMIT = new MessageExposure(true, false);

    private static final MessageExposure MASK = new MessageExposure(false, true);

    private static final MessageExposure VERBATIM = new MessageExposure(false, false);

    private final boolean omitted;

    private final boolean masked;

    private MessageExposure(boolean omitted, boolean masked) {
        this.omitted = omitted;
        this.masked = masked;
    }

    /**
     * The rule the policy prescribes right now. A {@code null} exposure mode is treated as
     * {@link ValueExposure#MASKED}, so an unresolved policy never reveals text.
     */
    public static MessageExposure current(ExposurePolicy policy) {
        ValueExposure valueExposure = policy.valueExposure();
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            return OMIT;
        }
        if (valueExposure == ValueExposure.FULL) {
            return VERBATIM;
        }
        return policy.maskSecrets() ? MASK : VERBATIM;
    }

    /** Whether this rule withholds the text entirely ({@link ValueExposure#METADATA_ONLY}). */
    public boolean omitsText() {
        return omitted;
    }

    /**
     * Applies this rule to one piece of text.
     *
     * @return {@code null} when the rule omits text or {@code text} is {@code null}; the text with secret-like
     *     assignments masked when the rule masks; otherwise {@code text} unchanged
     */
    public String apply(String text) {
        if (omitted || text == null) {
            return null;
        }
        return masked ? maskSecretAssignments(text) : text;
    }

    /**
     * Replaces the first whitespace-free value token of every secret-like assignment in {@code text}, such as
     * {@code password=...}, {@code "apiKey": "..."}, or {@code token: ...}, with {@link SecretMasker#MASKED_VALUE},
     * keeping the key and separator. The scan is not line-bound, so it covers every line of multi-line text.
     */
    public static String maskSecretAssignments(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return SECRET_ASSIGNMENT
                .matcher(text)
                .replaceAll(result -> Matcher.quoteReplacement(result.group(1) + SecretMasker.MASKED_VALUE));
    }
}
