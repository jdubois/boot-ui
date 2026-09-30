package io.github.jdubois.bootui.engine.support;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    private static final String BRACKET = "\\[\\s*+" + QUOTE;

    /**
     * Authorization schemes whose name stays visible before a masked credential: the IANA HTTP Authentication Scheme
     * Registry, plus the widespread {@code NTLM}, {@code Token}, and {@code AWS4-HMAC-SHA256}.
     */
    private static final String AUTHORIZATION_SCHEME = "(?:aws4-hmac-sha256|basic|bearer|concealed|digest|dpop|gnap|"
            + "hoba|mutual|negotiate|ntlm|oauth|privatetoken|scram-sha-1|scram-sha-256|token|vapid)";

    /** A scheme, the whitespace after it, and an optional opening quote, also escaped, around its credential. */
    private static final String SCHEME = AUTHORIZATION_SCHEME + "[ \\t]++" + QUOTE;

    /**
     * One {@code name=value} or {@code name="value"} parameter of a Digest, OAuth, or AWS-style credential. A quoted
     * value honors backslash escapes and runs to the end of the line when its closing quote is missing, so a malformed
     * value is over-masked, never cut short. A value quoted with escaped quotes, at any depth of JSON nesting, closes
     * at an escaped quote followed by a comma, a quote, a bracket, whitespace, or the end. An RFC 8187 extended value,
     * as in {@code username*=UTF-8''J%C3%A4s}, is covered too. An unquoted value is never a scheme followed by its own
     * credential, as in {@code X-Token=Bearer ...}, which is left for its own match.
     */
    private static final String AUTH_PARAM = "[A-Za-z0-9_.~+*-]++[ \\t]*+=[ \\t]*+"
            + "(?:\"(?:[^\"\\\\\\r\\n]|\\\\.)*+\"?"
            + "|\\\\++\"(?:[^\"\\\\\\r\\n]|\\\\++[^\"\\\\\\r\\n]|\\\\++\"(?![ \\t]*+,|\\\\*+[\"']|[\\]})\\s]|$))*+"
            + "(?:\\\\++\")?"
            + "|[A-Za-z0-9!#$&+.^_`|~-]*+'[A-Za-z0-9-]*+'[^\\s,\"'\\\\)\\]}]++"
            + "|(?!" + AUTHORIZATION_SCHEME + "[ \\t])[^\\s,\"'\\\\)\\]}]++)";

    /**
     * A secret-like key and its separator ahead, as in {@code X-Api-Key: ...}, where {@code =} followed by {@code =},
     * whitespace, or the end is Base64 padding rather than a separator.
     */
    private static final String SECRET_KEY_AHEAD =
            "[^\\s=:]{0,64}?(?<![A-Za-z0-9+/])" + SECRET_KEY_NAME + QUOTE + "\\s*(?::|=(?![=\\s]|$))";

    /**
     * The credential after a scheme: a comma-separated parameter list, which may wrap after a comma, or else one token.
     * The token is never a secret-like key and its separator, as in {@code X-Api-Key: ...}, or a scheme followed by
     * its own credential, which would otherwise be left outside the mask. Possessive quantifiers keep the match linear
     * and iterative, with no limit on the number of parameters.
     */
    private static final String CREDENTIAL = "(?:" + AUTH_PARAM + "(?:[ \\t]*+,\\s*+" + AUTH_PARAM + ")*+"
            + "|(?!" + SECRET_KEY_AHEAD + "|" + AUTHORIZATION_SCHEME + "[ \\t])"
            + "[^\\s\"',;&)\\]}\\\\]++)";

    /** An authorization scheme BootUI does not recognize, which starts with a letter, and the whitespace after it. */
    private static final String CUSTOM_SCHEME = "[A-Za-z][^\\s\"',;&)\\]}\\\\]*+[ \\t]++" + QUOTE;

    /**
     * A value of a multi-valued header with no scheme. It is never the key of a following assignment, and never starts
     * with a secret-like assignment, which is left for its own match.
     */
    private static final String LIST_TOKEN = "(?![^\\s\"',;&)\\]}\\\\:=]++(?:\\\\?[\"']\\s*+[:=]|\\s*+:)|"
            + SECRET_KEY_AHEAD + ")[^\\s\"',;&)\\]}\\\\]++";

    /**
     * Groups 1, 3, and 5 keep an {@code authorization} key, and groups 2, 4, and 6 hold the bracket that opens a
     * multi-valued header after it. Group 1 also keeps a recognized scheme, and the credential after it is masked.
     * After group 3, an unrecognized scheme is masked with its credential, because a custom scheme cannot be told apart
     * from a bare credential followed by more text. After group 5, the first value of a list is masked, unless it is a
     * secret-like assignment, which is then left for its own match. Group 7 keeps
     * any other secret-like key, and a value that starts with a scheme is masked with its credential, so a password
     * that happens to be a scheme name is never shown. Group 8 keeps any other secret-like key, and the first
     * whitespace-free value token is masked, exactly as before schemes were recognized.
     */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile("(?i)"
            + "(" + AUTHORIZATION_HEADER_KEY + "(" + BRACKET + ")?+" + SCHEME + ")" + CREDENTIAL
            + "|(" + AUTHORIZATION_KEY + "(" + BRACKET + ")?+)" + CUSTOM_SCHEME + CREDENTIAL
            + "|(" + AUTHORIZATION_KEY + "(" + BRACKET + "))(?:" + LIST_TOKEN + "|(?=" + SECRET_KEY_AHEAD + "))"
            + "|(" + SECRET_KEY + ")" + SCHEME + CREDENTIAL
            + "|(" + SECRET_KEY + ")[^\\s\"',;&)]+");

    /**
     * One further value of an authorization header after a comma, which is never the key of a following assignment.
     * Group 1 is the separator, with the quote that closes the previous value in group 2 and the quote that opens this
     * one in group 3, and group 4 keeps a recognized scheme.
     */
    private static final Pattern FURTHER_VALUE = Pattern.compile("(?i)((\\\\?[\"'])?[ \\t]*+,\\s*+(\\\\?[\"'])?)"
            + "(?![^\"'\\\\\\r\\n]{0,256}+\\\\?[\"']\\s*+[:=])"
            + "(?:(" + SCHEME + ")" + CREDENTIAL + "|" + CUSTOM_SCHEME + CREDENTIAL + "|" + LIST_TOKEN + ")");

    /**
     * A credential after {@code Bearer}, {@code Basic}, {@code Negotiate}, or {@code NTLM} with no key before it, as
     * in {@code sending Bearer eyJ...}. Whether it is masked depends on its shape, see
     * {@link #bareCredentialLength(String, String)}. Other schemes are only recognized after a secret-like key, because
     * their names are common words.
     */
    private static final Pattern BARE_CREDENTIAL = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])(bearer|basic|negotiate|ntlm)[ \\t]++(?:\\\\?[\"'])?" + "([A-Za-z0-9._~+/-]++=*+)");

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
        return maskBareCredentials(maskAssignments(text));
    }

    private static String maskAssignments(String text) {
        Matcher assignment = SECRET_ASSIGNMENT.matcher(text);
        Matcher further = null;
        StringBuilder masked = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && assignment.find(from)) {
            if (masked == null) {
                masked = new StringBuilder(text.length());
            }
            int end = assignment.end();
            boolean valueLeft = assignment.group(5) != null && end == assignment.end(5);
            masked.append(text, copied, assignment.start()).append(kept(assignment));
            if (!valueLeft) {
                masked.append(SecretMasker.MASKED_VALUE);
            }
            if (!valueLeft
                    && (assignment.group(1) != null || assignment.group(3) != null || assignment.group(5) != null)) {
                // Further values are masked only in a list: after a bracket, or between quoted values.
                boolean list =
                        assignment.group(2) != null || assignment.group(4) != null || assignment.group(6) != null;
                if (further == null) {
                    further = FURTHER_VALUE.matcher(text).useTransparentBounds(true);
                }
                while (end < text.length()
                        && further.region(end, text.length()).lookingAt()
                        && (list || (further.group(2) != null && further.group(3) != null))) {
                    masked.append(further.group(1))
                            .append(further.group(4) != null ? further.group(4) : "")
                            .append(SecretMasker.MASKED_VALUE);
                    end = further.end();
                }
            }
            copied = end;
            from = end;
        }
        return masked == null
                ? text
                : masked.append(text, copied, text.length()).toString();
    }

    private static String kept(Matcher assignment) {
        for (int group : new int[] {1, 3, 5, 7}) {
            if (assignment.group(group) != null) {
                return assignment.group(group);
            }
        }
        return assignment.group(8);
    }

    private static String maskBareCredentials(String text) {
        Matcher matcher = BARE_CREDENTIAL.matcher(text);
        StringBuilder masked = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && matcher.find(from)) {
            int length = bareCredentialLength(matcher.group(1), matcher.group(2));
            if (length > 0) {
                if (masked == null) {
                    masked = new StringBuilder(text.length());
                }
                masked.append(text, copied, matcher.start(2)).append(SecretMasker.MASKED_VALUE);
                copied = matcher.start(2) + length;
                from = copied;
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
     * How much of {@code candidate}, after a scheme with no key before it, is unambiguously a credential, or {@code 0}
     * when it is not one, so prose such as {@code missing Bearer token}, {@code Basic auth is enabled}, or
     * {@code unable to negotiate TLS_AES_128_GCM_SHA256} is left alone. A {@code Bearer} credential is token-shaped: at
     * least twenty characters, or at least eight including a digit. Any other is its leading Base64 text, so trailing
     * punctuation stays visible, and must decode to what the scheme carries: printable {@code user:password} text for
     * {@code Basic}, an NTLM message for {@code NTLM}, and an NTLM message or a SPNEGO token for {@code Negotiate}.
     */
    static int bareCredentialLength(String scheme, String candidate) {
        if ("bearer".equalsIgnoreCase(scheme)) {
            boolean tokenShaped = candidate.length() >= 20
                    || (candidate.length() >= 8 && candidate.chars().anyMatch(Character::isDigit));
            return tokenShaped ? candidate.length() : 0;
        }
        int base64End = 0;
        while (base64End < candidate.length() && isBase64(candidate.charAt(base64End))) {
            base64End++;
        }
        int end = base64End;
        while (end < candidate.length() && candidate.charAt(end) == '=') {
            end++;
        }
        String base64 = candidate.substring(0, base64End).replace('-', '+').replace('_', '/');
        if ("basic".equalsIgnoreCase(scheme)) {
            String credentials = decode(base64);
            boolean userAndPassword = credentials != null
                    && credentials.indexOf(':') > 0
                    && credentials.chars().noneMatch(c -> c == '\uFFFD' || Character.isISOControl(c));
            return userAndPassword ? end : 0;
        }
        // Only the first 12 bytes are checked, so a truncated or oddly padded token is still recognized.
        if (base64.length() < 16) {
            return 0;
        }
        byte[] head = Base64.getDecoder().decode(base64.substring(0, 16));
        boolean ntlmMessage = new String(head, 0, 8, StandardCharsets.ISO_8859_1).equals("NTLMSSP\0");
        boolean spnegoToken =
                "negotiate".equalsIgnoreCase(scheme) && (head[0] == (byte) 0x60 || head[0] == (byte) 0xA1);
        return ntlmMessage || spnegoToken ? end : 0;
    }

    private static String decode(String base64) {
        try {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isBase64(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '+'
                || c == '/'
                || c == '-'
                || c == '_';
    }
}
