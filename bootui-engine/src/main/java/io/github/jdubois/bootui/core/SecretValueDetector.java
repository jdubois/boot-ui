package io.github.jdubois.bootui.core;

import java.util.regex.Pattern;

/**
 * Detects whether a configuration <em>value</em> looks like a secret, independently of its property
 * name. The keyword maskers only inspect property names, so a credential carried by a value under an
 * innocuous key (for example {@code spring.datasource.url=jdbc:postgresql://user:pass@host/db}) would
 * otherwise render in clear text. These patterns are intentionally high-confidence to avoid masking
 * legitimate, non-sensitive values.
 */
public final class SecretValueDetector {

    /** A JSON Web Token: three base64url segments separated by dots, starting with {@code eyJ}. */
    private static final Pattern JWT = Pattern.compile("^eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+$");

    /** A PEM-encoded private key block. */
    private static final Pattern PEM_PRIVATE_KEY = Pattern.compile("BEGIN[A-Z0-9 ]*PRIVATE KEY");

    /** An AWS access key identifier. */
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b");

    /** A URI/JDBC connection string carrying {@code ://user:password@host} userinfo credentials. */
    private static final Pattern URL_USERINFO = Pattern.compile("://[^/?#\\s:@]+:[^/?#\\s@]+@");

    /** A URL/connection string carrying credentials as a query parameter, e.g. {@code ?password=...}. */
    private static final Pattern URL_QUERY_CREDENTIAL = Pattern.compile("(?i)[?&](?:access[-_]?token|refresh[-_]?token"
            + "|id[-_]?token|auth[-_]?token|session[-_]?token|client[-_]?secret|secret[-_]?key|api[-_]?key"
            + "|access[-_]?key|password|passwd|passphrase|pwd|secret|token|credential)=[^&\\s]+");

    private SecretValueDetector() {}

    /**
     * Returns {@code true} when the value matches a high-confidence secret pattern.
     */
    public static boolean looksLikeSecret(Object value) {
        if (!(value instanceof CharSequence sequence)) {
            return false;
        }
        String text = sequence.toString();
        if (text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        return JWT.matcher(trimmed).matches()
                || PEM_PRIVATE_KEY.matcher(text).find()
                || AWS_ACCESS_KEY.matcher(text).find()
                || URL_USERINFO.matcher(text).find()
                || URL_QUERY_CREDENTIAL.matcher(text).find();
    }
}
