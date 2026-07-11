package io.github.jdubois.bootui.engine.safety;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Framework-neutral bearer-token authentication for non-loopback BootUI API requests.
 *
 * <p>A configured token is used verbatim. When none is configured, a 256-bit token is generated for
 * the lifetime of the application. Loopback callers bypass authentication; unknown and non-loopback
 * callers must present the token in either the {@code Authorization} header or BootUI's HTTP-only
 * browser-session cookie.</p>
 */
public final class ApiTokenAuthenticator {

    public static final String SESSION_COOKIE_NAME = "BOOTUI_SESSION";
    public static final String AUTHENTICATION_REQUIRED_MESSAGE = "BootUI authentication required";

    private static final String BEARER_PREFIX = "Bearer ";
    public static final String AUTHENTICATION_CHALLENGE = BEARER_PREFIX + "realm=\"BootUI\"";
    private static final int TOKEN_BYTES = 32;

    private final String token;
    private final boolean generated;

    public ApiTokenAuthenticator(String configuredToken) {
        this(configuredToken, new SecureRandom());
    }

    ApiTokenAuthenticator(String configuredToken, SecureRandom random) {
        if (configuredToken == null || configuredToken.isBlank()) {
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            this.generated = true;
        } else {
            this.token = configuredToken;
            this.generated = false;
        }
    }

    public boolean isAuthorized(String remoteAddress, String authorizationHeader, String cookieHeader) {
        return isLoopback(remoteAddress)
                || matches(extractBearerToken(authorizationHeader))
                || matches(extractSessionCookie(cookieHeader));
    }

    public boolean isLoopback(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public boolean generated() {
        return generated;
    }

    public String token() {
        return token;
    }

    private boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static String extractBearerToken(String header) {
        if (header == null || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }

    private static String extractSessionCookie(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        for (String cookie : header.split(";")) {
            String candidate = cookie.trim();
            String prefix = SESSION_COOKIE_NAME + "=";
            if (candidate.startsWith(prefix)) {
                return candidate.substring(prefix.length());
            }
        }
        return null;
    }
}
