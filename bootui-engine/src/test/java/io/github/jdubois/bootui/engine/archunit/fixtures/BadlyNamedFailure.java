package io.github.jdubois.bootui.engine.archunit.fixtures;

/**
 * A throwable whose simple name does not end in {@code Exception}, extending {@link RuntimeException} only
 * indirectly resolvable via the JDK class hierarchy. Used to prove the engine's ArchUnit import still
 * resolves missing dependencies from the classpath: with resolution preserved, ArchUnit can walk this
 * type's super-class chain up to {@link Exception}; with resolution disabled it could not, and the
 * curated rule {@code ARCH-CODE-010 (areAssignableTo(Exception.class))} would silently stop firing.
 */
public class BadlyNamedFailure extends RuntimeException {

    public BadlyNamedFailure(String message) {
        super(message);
    }
}
