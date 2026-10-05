package org.springframework.modulith.core;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public final class ApplicationModuleIdentifiers {

    private final List<ApplicationModuleIdentifier> identifiers;

    private ApplicationModuleIdentifiers(List<ApplicationModuleIdentifier> identifiers) {
        this.identifiers = identifiers;
    }

    public static ApplicationModuleIdentifiers of(String... identifiers) {
        return new ApplicationModuleIdentifiers(
                Arrays.stream(identifiers).map(ApplicationModuleIdentifier::new).toList());
    }

    public Stream<ApplicationModuleIdentifier> stream() {
        return identifiers.stream();
    }

    private record ApplicationModuleIdentifier(String identifier) {
        @Override
        public String toString() {
            return identifier;
        }
    }
}
