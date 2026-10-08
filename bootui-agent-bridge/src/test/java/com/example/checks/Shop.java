package com.example.checks;

import io.github.jdubois.bootui.agent.bridge.SecuritySinks;
import java.io.ObjectInputStream;
import java.util.function.Supplier;
import org.acme.crypto.Digests;

/** An application's calls into the security-sinks checks, as the advised JDK methods would make them. */
public final class Shop {

    private Shop() {}

    public static void hash(String algorithm) {
        SecuritySinks.digest(algorithm);
    }

    public static void encrypt(String transformation) {
        SecuritySinks.cipher(transformation);
    }

    public static void hashThroughLibrary(String algorithm) {
        Digests.digest(algorithm);
    }

    /** Another application method asking the same library helper. */
    public static void etag(String algorithm) {
        Digests.digest(algorithm);
    }

    public static long reading(ObjectInputStream stream) {
        return SecuritySinks.reading(stream, 0L);
    }

    /** A call inside the stream's own read, as a {@code HashMap}'s entries are read. */
    public static long readingNested(ObjectInputStream stream, long depth) {
        return SecuritySinks.reading(stream, depth);
    }

    public static void read(long token, Throwable thrown) {
        SecuritySinks.read(token, thrown);
    }

    public static void trust(Object[] managers) {
        SecuritySinks.sslInit(managers);
    }

    public static void verifier(Object verifier) {
        SecuritySinks.defaultVerifier(verifier);
    }

    public static void factory(Object factory) {
        SecuritySinks.defaultFactory(factory);
    }

    public static void verifierThroughLibrary(Object verifier) {
        Digests.verifier(verifier);
    }

    /** A hostname verifier of the application, a lambda. */
    public static Supplier<String> lambda() {
        return () -> "verifier";
    }

    /** A trust manager of the application. */
    public static final class TrustAll {}
}
