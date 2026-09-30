package io.github.jdubois.bootui.engine.support;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    private static final String SECRET_KEY_NAME = "(?:password|passwd|pwd|secret|token|api[-_]?key|apikey|"
            + "authorization|credential|access[-_]?key|client[-_]?secret|private[-_]?key)";

    /** A secret-like key and its separator, exactly as masked before authorization schemes were recognized. */
    private static final String SECRET_KEY = "[\"']?" + SECRET_KEY_NAME + "[\"']?\\s*[=:]\\s*[\"']?";

    /** An optional quote, also in its escaped form, as in {@code {\"Authorization\":\"Basic ...\"}}. */
    private static final String QUOTE = "(?:\\\\?[\"'])?";

    private static final String AUTHORIZATION_KEY = QUOTE + "authorization" + QUOTE + "\\s*[=:]\\s*" + QUOTE;

    /** Also accepts a header label, as in {@code Authorization header: Basic ...}, in front of a known scheme. */
    private static final String AUTHORIZATION_HEADER_KEY =
            QUOTE + "authorization(?:[ _-]?header)?" + QUOTE + "\\s*[=:]\\s*" + QUOTE;

    /**
     * The opening of a multi-valued header, as in {@code Authorization=[Bearer ...]}, or of a JSON array, which a
     * pretty-printer may space or break across lines.
     */
    private static final String OPENING_BRACKET = "(?:\\[\\s*+" + QUOTE + ")?+";

    /**
     * Authorization schemes whose name stays visible before a masked credential: the IANA HTTP Authentication Scheme
     * Registry, plus the widespread {@code NTLM}, {@code Token}, and {@code AWS4-HMAC-SHA256}.
     */
    private static final String AUTHORIZATION_SCHEME = "(?:aws4-hmac-sha256|basic|bearer|concealed|digest|dpop|gnap|"
            + "hoba|mutual|negotiate|ntlm|oauth|privatetoken|scram-sha-1|scram-sha-256|token|vapid)";

    /** A scheme, the whitespace after it, and an optional opening quote around its credential. */
    private static final String SCHEME = AUTHORIZATION_SCHEME + "[ \\t]++[\"']?";

    /**
     * One {@code name=value} or {@code name="value"} parameter of a Digest, OAuth, or AWS-style credential. A quoted
     * value honours backslash escapes and runs to the end of the line when its closing quote is missing, and a value
     * opened by an escaped quote runs to the next unescaped quote, so a malformed value is over-masked, never cut short.
     * An RFC 8187 extended value, as in {@code username*=UTF-8''J%C3%A4s}, is covered too. An unquoted value is never a scheme followed by its own credential, as in {@code X-Token=Bearer ...}, which is
     * left for its own match.
     */
    private static final String AUTH_PARAM = "[A-Za-z0-9_.~+*-]++[ \\t]*+=[ \\t]*+"
            + "(?:\"(?:[^\"\\\\\\r\\n]|\\\\.)*+\"?|\\\\\"(?:[^\"\\\\\\r\\n]|\\\\.)*+"
            + "|[A-Za-z0-9!#$&+.^_`|~-]*+'[A-Za-z0-9-]*+'[^\\s,\"'\\\\)\\]}]++"
            + "|(?!" + AUTHORIZATION_SCHEME + "[ \\t])[^\\s,\"'\\\\)\\]}]++)";

    /**
     * The credential after a scheme: a comma-separated parameter list, which may wrap after a comma, or else one token.
     * The token is never a secret-like key and its separator, as in {@code X-Api-Key: ...}, or a scheme followed by
     * its own credential, which would otherwise be left outside the mask. Possessive quantifiers keep the match linear and iterative, with no limit on
     * the number of parameters.
     */
    private static final String CREDENTIAL = "(?:" + AUTH_PARAM + "(?:[ \\t]*+,\\s*+" + AUTH_PARAM + ")*+"
            + "|(?![^\\s=:]{0,64}?" + SECRET_KEY_NAME + QUOTE + "\\s*[=:]|" + AUTHORIZATION_SCHEME + "[ \\t])"
            + "[^\\s\"',;&)\\]}\\\\]++)";

    /** The separator between two values of a multi-valued header, as in {@code [Basic ..., Basic ...]}. */
    private static final String LIST_SEPARATOR = "[\"']?[ \\t]*+,\\s*+" + QUOTE;

    /**
     * Group 1 keeps an {@code authorization} key and a recognized scheme, and the credential after it is masked; group
     * 2 holds any further values of a multi-valued header, each masked the same way. Group 3 keeps an
     * {@code authorization} key, and an unrecognized scheme word, which starts with a letter, is masked with its
     * credential, because a custom scheme cannot be told apart from a bare credential followed by more text. Group 4 keeps any other secret-like
     * key, and a value that starts with a scheme is masked with its credential, so a password that happens to be a
     * scheme name is never shown. Group 5 keeps any other secret-like key, and the first whitespace-free value token is
     * masked, exactly as before schemes were recognized.
     */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile("(?i)"
            + "(" + AUTHORIZATION_HEADER_KEY + OPENING_BRACKET + SCHEME + ")" + CREDENTIAL
            + "((?:" + LIST_SEPARATOR + SCHEME + CREDENTIAL + ")*+)"
            + "|(" + AUTHORIZATION_KEY + OPENING_BRACKET + ")[A-Za-z][^\\s\"',;&)\\]}\\\\]*+[ \\t]++[\"']?" + CREDENTIAL
            + "|(" + SECRET_KEY + ")" + SCHEME + CREDENTIAL
            + "|(" + SECRET_KEY + ")[^\\s\"',;&)]+");

    /** One further value of a multi-valued header, matched back to back from the start of group 2. */
    private static final Pattern LIST_VALUE = Pattern.compile("(?i)\\G(" + LIST_SEPARATOR + SCHEME + ")" + CREDENTIAL);

    /**
     * A credential after {@code Bearer}, {@code Basic}, {@code Negotiate}, or {@code NTLM} with no key before it, as
     * in {@code sending Bearer eyJ...}. Whether it is masked depends on its shape, see
     * {@link #isBareCredential(String, String)}. Other schemes are only recognized after a secret-like key, because
     * their names are common words.
     */
    private static final Pattern BARE_CREDENTIAL =
            Pattern.compile("(?i)(?<![A-Za-z0-9_-])(bearer|basic|negotiate|ntlm)[ \\t]++([A-Za-z0-9._~+/-]++=*+)");

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
     *   <li>The whole credential after a recognized authorization scheme following an {@code authorization} key,
     *       keeping the scheme: {@code Authorization: Bearer ******}, {@code "authorization": "Basic ******"}, or
     *       {@code Proxy-Authorization: Digest ******}, where every comma-separated parameter of a Digest-style
     *       credential and every value of a multi-valued header is covered.</li>
     *   <li>Both an unrecognized scheme word and its credential after an {@code authorization} key, as in
     *       {@code Authorization: ******} for {@code Authorization: SSWS ...}, and both a scheme and its credential
     *       after any other secret-like key, as in {@code X-Auth-Token: ******}.</li>
     *   <li>A credential after a {@code Bearer}, {@code Basic}, {@code Negotiate}, or {@code NTLM} scheme with no key
     *       before it, as in {@code sending Bearer ******}, when its shape shows it is one.</li>
     * </ul>
     *
     * <p>The scan is not line-bound, so it covers every line of multi-line text, but a scheme and its credential must
     * share a line. Text with nothing to mask is returned as the same instance.</p>
     */
    public static String maskSecretAssignments(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return maskBareCredentials(SECRET_ASSIGNMENT.matcher(text).replaceAll(MessageExposure::maskAssignment));
    }

    private static String maskAssignment(MatchResult result) {
        if (result.group(1) == null) {
            String kept = result.group(3) != null
                    ? result.group(3)
                    : result.group(4) != null ? result.group(4) : result.group(5);
            return Matcher.quoteReplacement(kept + SecretMasker.MASKED_VALUE);
        }
        String furtherValues = result.group(2).isEmpty()
                ? ""
                : LIST_VALUE
                        .matcher(result.group(2))
                        .replaceAll(value -> Matcher.quoteReplacement(value.group(1) + SecretMasker.MASKED_VALUE));
        return Matcher.quoteReplacement(result.group(1) + SecretMasker.MASKED_VALUE + furtherValues);
    }

    private static String maskBareCredentials(String text) {
        Matcher matcher = BARE_CREDENTIAL.matcher(text);
        StringBuilder masked = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && matcher.find(from)) {
            if (isBareCredential(matcher.group(1), matcher.group(2))) {
                if (masked == null) {
                    masked = new StringBuilder(text.length());
                }
                masked.append(text, copied, matcher.start(2)).append(SecretMasker.MASKED_VALUE);
                copied = matcher.end();
                from = matcher.end();
            } else {
                // Resume after the scheme, so a rejected candidate cannot hide a scheme inside it.
                from = matcher.end(1);
            }
        }
        return masked == null
                ? text
                : masked.append(text, copied, text.length()).toString();
    }

    /**
     * Whether {@code candidate} after a scheme with no key before it is unambiguously a credential. A {@code Basic}
     * credential must decode from Base64 to printable {@code user:password} text. Any other is token-shaped: at least
     * twenty characters, or at least eight including a digit, so prose such as {@code missing Bearer token} or
     * {@code Basic auth is enabled} is left alone.
     */
    static boolean isBareCredential(String scheme, String candidate) {
        if (!"basic".equalsIgnoreCase(scheme)) {
            return candidate.length() >= 20
                    || (candidate.length() >= 8 && candidate.chars().anyMatch(Character::isDigit));
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(candidate.replace('-', '+').replace('_', '/'));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String credentials = new String(decoded, StandardCharsets.UTF_8);
        return credentials.indexOf(':') > 0
                && credentials.chars().noneMatch(c -> c == '\uFFFD' || Character.isISOControl(c));
    }
}
