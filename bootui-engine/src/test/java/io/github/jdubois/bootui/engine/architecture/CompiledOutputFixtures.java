package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies compiled fixture classes into a Maven-style output directory, so a rule that depends on where a class was
 * compiled ({@code target/classes} versus {@code target/test-classes}) sees the layout under test rather than the
 * test-classes directory every fixture is actually compiled into.
 */
final class CompiledOutputFixtures {

    private CompiledOutputFixtures() {}

    static JavaClasses importFrom(Path moduleRoot, String outputDirectory, Class<?>... classes) {
        Path output = moduleRoot.resolve(outputDirectory);
        try {
            for (Class<?> type : classes) {
                String resource = type.getName().replace('.', '/') + ".class";
                Path target = output.resolve(resource);
                Files.createDirectories(target.getParent());
                try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
                    Files.copy(in, target);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new ClassFileImporter().importPath(output);
    }
}
