package io.github.jdubois.bootui.engine.support;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The value-exposure rule for free-form text an application wrote itself: exception messages, log messages, and
 * container log output. The Exceptions, Log Tail, and Dev Services panels all apply this one rule, so the three
 * surfaces mask identically and a pattern fix reaches every one of them at once.
 *
 * <ul>
 *   <li>{@link ValueExposure#METADATA_ONLY} omits the text entirely.</li>
 *   <li>{@link ValueExposure#MASKED}, the default, masks secret-like assignments and authorization credentials, as
 *       {@link #maskSecretAssignments(String)} describes, unless {@code bootui.mask-secrets=false}.</li>
 *   <li>{@link ValueExposure#FULL} returns the text verbatim.</li>
 * </ul>
 *
 * <p>Only secrets with a recognizable shape are detected: an assignment to a secret-like key, or a credential after
 * an authorization scheme. Bare tokens and credentials embedded in connection strings are not, so masked text is
 * safer to show, not guaranteed secret-free.</p>
 *
 * <p>Resolve the rule with {@link #current(ExposurePolicy)} at read time, once per response or streamed line, so a
 * live change to {@code bootui.expose-values} or {@code bootui.mask-secrets} applies to the next read without a
 * restart. Instances are immutable and thread-safe.</p>
 */
public final class MessageExposure {

    private static final String SECRET_KEY = "[\"']?(?:password|passwd|pwd|secret|token|api[-_]?key|apikey|"
            + "authorization|credential|access[-_]?key|client[-_]?secret|private[-_]?key)[\"']?\\s*[=:]\\s*[\"']?";

    private static final String AUTHORIZATION_KEY = "[\"']?authorization[\"']?\\s*[=:]\\s*[\"']?";

    /** The opening of a multi-valued header, as in {@code Authorization=[Bearer ...]} or {@code ["Bearer ..."]}. */
    private static final String OPENING_BRACKET = "(?:\\[[\"']?)?+";

    /**
     * Authorization schemes whose name stays visible before a masked credential: the IANA HTTP Authentication Scheme
     * Registry, plus the widespread {@code NTLM}, {@code Token}, and {@code AWS4-HMAC-SHA256}.
     */
    private static final String AUTHORIZATION_SCHEME = "(?:aws4-hmac-sha256|basic|bearer|concealed|digest|dpop|gnap|"
            + "hoba|mutual|negotiate|ntlm|oauth|privatetoken|scram-sha-1|scram-sha-256|token|vapid)";

    /** One {@code name=value} or {@code name="value"} parameter of a Digest, OAuth, or AWS-style credential. */
    private static final String AUTH_PARAM =
            "[A-Za-z0-9_.~+-]++[ \\t]*+=[ \\t]*+(?:\\\\?\"[^\"\\r\\n]*+\"?|[^\\s,\"'\\\\)\\]}]++)";

    /**
     * The credential after a scheme: a comma-separated parameter list, or else one token. Possessive quantifiers and
     * a bounded parameter count keep matching linear and the regex stack shallow.
     */
    private static final String CREDENTIAL =
            "(?:" + AUTH_PARAM + "(?:[ \\t]*+,[ \\t]*+" + AUTH_PARAM + "){0,31}" + "|[^\\s\"',;&)\\]}\\\\]++)";

    /**
     * Group 1 keeps the key and a recognized scheme, and the credential after it is masked. Group 2 keeps an
     * {@code authorization} key, and an unrecognized scheme word is masked with its credential, because a custom
     * scheme cannot be told apart from a bare credential followed by more text. Group 3 keeps any other secret-like
     * key, and the first whitespace-free value token is masked, exactly as before schemes were recognized.
     */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile("(?i)"
            + "(" + SECRET_KEY + OPENING_BRACKET + AUTHORIZATION_SCHEME + "[ \\t]++)" + CREDENTIAL
            + "|(" + AUTHORIZATION_KEY + OPENING_BRACKET + ")[^\\s\"',;&)\\]}\\\\]++[ \\t]++" + CREDENTIAL
            + "|(" + SECRET_KEY + ")[^\\s\"',;&)]+");

    private static final String TOKEN68 = "[A-Za-z0-9._~+/-]";

    /**
     * A {@code Bearer} credential with no key before it, as in {@code sending Bearer eyJ...}. The credential must be
     * token-shaped, at least eight characters with a digit or at least twenty, so prose such as
     * {@code missing Bearer token} is left alone. Other schemes are only recognized after a secret-like key, because
     * their names are common words.
     */
    private static final Pattern BARE_BEARER_CREDENTIAL = Pattern.compile("(?i)((?<![A-Za-z0-9_-])bearer[ \\t]++)"
            + "(?=" + TOKEN68 + "{20}|" + TOKEN68 + "{0,63}[0-9])" + TOKEN68 + "{8,}+=*+");

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
     *     assignments and authorization credentials masked when the rule masks; otherwise {@code text} unchanged
     */
    public String apply(String text) {
        if (omitted || text == null) {
            return null;
        }
        return masked ? maskSecretAssignments(text) : text;
    }

    /**
     * Masks secrets in {@code text} with {@link SecretMasker#MASKED_VALUE}, keeping what identifies them:
     *
     * <ul>
     *   <li>The first whitespace-free value token of a secret-like assignment, such as {@code password=...},
     *       {@code "apiKey": "..."}, or {@code token: ...}.</li>
     *   <li>The whole credential when that value starts with a recognized authorization scheme, which stays visible:
     *       {@code Authorization: Bearer ******}, {@code "authorization": "Basic ******"}, or
     *       {@code Proxy-Authorization: Digest ******}, where every comma-separated parameter of a Digest-style
     *       credential is covered.</li>
     *   <li>Both an unrecognized scheme word and its credential after an {@code authorization} key, as in
     *       {@code Authorization: ******} for {@code Authorization: SSWS ...}.</li>
     *   <li>A token-shaped credential after a {@code Bearer} with no key before it, as in
     *       {@code sending Bearer ******}.</li>
     * </ul>
     *
     * <p>The scan is not line-bound, so it covers every line of multi-line text, but a scheme and its credential must
     * share a line. Text with nothing to mask is returned as the same instance.</p>
     */
    public static String maskSecretAssignments(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String assignmentsMasked = SECRET_ASSIGNMENT.matcher(text).replaceAll(MessageExposure::maskAssignment);
        return BARE_BEARER_CREDENTIAL
                .matcher(assignmentsMasked)
                .replaceAll(result -> Matcher.quoteReplacement(result.group(1) + SecretMasker.MASKED_VALUE));
    }

    private static String maskAssignment(MatchResult result) {
        String kept = result.group(1);
        if (kept == null) {
            kept = result.group(2) != null ? result.group(2) : result.group(3);
        }
        return Matcher.quoteReplacement(kept + SecretMasker.MASKED_VALUE);
    }
}
