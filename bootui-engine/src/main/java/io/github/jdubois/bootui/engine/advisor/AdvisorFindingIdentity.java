package io.github.jdubois.bootui.engine.advisor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds the canonical identity of an advisor finding: a stable, structure-derived key such as
 * {@code v1:unit=main|entity=com.acme.Order|member=lines}.
 *
 * <p>A rule derives its parts from the code, schema or setting a finding is about, never from display text, sample
 * order, values or free-form messages, so the identity contains no secrets and survives a wording change. The result
 * is ASCII from the charset {@value #CHARSET_DESCRIPTION} and at most {@value #MAX_LENGTH} characters:
 * {@code <version>:<part>|<part>|...}, where each part is percent-encoded as UTF-8 for every character outside
 * {@code [A-Za-z0-9._:=/$-]}, so {@code |}, {@code %}, spaces, {@code #}, quotes and non-ASCII are always encoded.
 * A longer key keeps its first {@value #KEPT_PREFIX} characters, then {@code ~} and the first 16 hex characters of the
 * SHA-256 of the full key.</p>
 *
 * <p>The version prefix names the key semantics of the emitting rule; a rule changes it whenever its key changes
 * meaning.</p>
 */
public final class AdvisorFindingIdentity {

    public static final int MAX_LENGTH = 200;
    static final int KEPT_PREFIX = 180;
    static final String CHARSET_DESCRIPTION = "[A-Za-z0-9._:=|/$%~-]";

    /** The charset an identity is restricted to; exactly what a store of identities must validate. */
    public static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:=|/$%~-]{1,200}");

    private static final Pattern VERSION = Pattern.compile("v[0-9]{1,3}");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private AdvisorFindingIdentity() {}

    /**
     * @param version the rule's key version, for example {@code v1}
     * @param parts the structural parts, such as {@code unit=main}; a {@code null} part counts as empty
     */
    public static String of(String version, String... parts) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Advisor identity version must look like v1.");
        }
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("Advisor identity needs at least one part.");
        }
        StringBuilder key = new StringBuilder(version).append(':');
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) key.append('|');
            key.append(encode(parts[i]));
        }
        return limit(key.toString());
    }

    /** One part, percent-encoded exactly as {@link #of} encodes it. */
    public static String encode(String part) {
        String value = Objects.toString(part, "");
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isPlain(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return out.toString();
    }

    /** The key version of {@code identity}, such as {@code v1}, or {@code null} for an unkeyed finding. */
    public static String version(String identity) {
        if (identity == null) return null;
        int colon = identity.indexOf(':');
        return colon <= 0 ? null : identity.substring(0, colon);
    }

    /** The first part of {@code identity}, which a rule uses for its scope (unit or datasource); {@code null} if unkeyed. */
    public static String scope(String identity) {
        if (identity == null) return null;
        int colon = identity.indexOf(':');
        if (colon < 0) return null;
        int bar = identity.indexOf('|', colon);
        return identity.substring(colon + 1, bar < 0 ? identity.length() : bar);
    }

    private static boolean isPlain(int c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '.'
                || c == '_'
                || c == ':'
                || c == '='
                || c == '/'
                || c == '$'
                || c == '-';
    }

    private static String limit(String key) {
        if (key.length() <= MAX_LENGTH) return key;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(HEX[(digest[i] & 0xFF) >> 4]).append(HEX[digest[i] & 0xF]);
            }
            return key.substring(0, KEPT_PREFIX) + '~' + hex.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK.", ex);
        }
    }
}
