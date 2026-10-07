package io.github.jdubois.bootui.engine.websocket;

import io.github.jdubois.bootui.engine.support.BootUiThreadLocal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Turns a framework WebSocket session or subscription identifier into an opaque, stable identifier.
 *
 * <p>Raw identifiers address a live connection: Spring's STOMP {@code simpSessionId} is exactly what a
 * frame needs to target a session, and Quarkus' connection id is what {@code OpenConnections} resolves.
 * BootUI therefore never serializes one. The hash is stable for the lifetime of the process, so the UI can
 * still correlate an activity entry with its session and subscription, but it cannot be used to address
 * anything.</p>
 *
 * <p>The digest is keyed with a per-process random salt. Frameworks are free to mint short or sequential
 * session identifiers, and an unsalted digest of such a small domain is trivially reversible by dictionary;
 * the salt removes that. It is regenerated on every restart, which is acceptable because the identifiers it
 * protects do not outlive the process either.</p>
 */
public final class WebSocketSessionIds {

    private static final int HASH_LENGTH = 32;

    private static final byte[] SALT = newSalt();

    /**
     * Digests are reused per thread: this runs once per captured frame on the application's own dispatch
     * threads, and {@code MessageDigest.getInstance} performs a provider lookup that has no place in a hot
     * path BootUI is only observing.
     */
    private static final ThreadLocal<MessageDigest> DIGEST = BootUiThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    });

    private WebSocketSessionIds() {}

    /** Returns a truncated salted SHA-256 hex hash of {@code rawId}, or {@code null} when {@code rawId} is null. */
    public static String opaque(String rawId) {
        if (rawId == null) {
            return null;
        }
        MessageDigest digest = DIGEST.get();
        digest.reset();
        digest.update(SALT);
        byte[] hash = digest.digest(rawId.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(HASH_LENGTH);
        for (int i = 0; i < hash.length && hex.length() < HASH_LENGTH; i++) {
            hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return hex.toString();
    }

    private static byte[] newSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }
}
