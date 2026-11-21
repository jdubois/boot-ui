package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.engine.archunit.ArchUnitBytecodeImports;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Copies compiled fixture classes into a Maven-style output directory, so a rule that depends on where a class was
 * compiled ({@code target/classes} versus {@code target/test-classes}) sees the layout under test rather than the
 * test-classes directory every fixture is actually compiled into.
 */
final class CompiledOutputFixtures {

    private CompiledOutputFixtures() {}

    static JavaClasses importFrom(Path moduleRoot, String outputDirectory, Class<?>... classes) {
        return new ClassFileImporter().importPath(copyInto(moduleRoot, outputDirectory, classes));
    }

    static ArchUnitBytecodeImports.Result importWithBytecodeFrom(
            Path moduleRoot, String outputDirectory, Class<?>... classes) {
        return ArchUnitBytecodeImports.importPath(copyInto(moduleRoot, outputDirectory, classes));
    }

    static Path copyInto(Path moduleRoot, String outputDirectory, Class<?>... classes) {
        Path output = moduleRoot.resolve(outputDirectory);
        try {
            for (Class<?> type : classes) {
                String resource = type.getName().replace('.', '/') + ".class";
                Path target = output.resolve(resource);
                try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
                    assertThat(in).as(resource).isNotNull();
                    copyVerified(target, in.readAllBytes());
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return output;
    }

    static JavaClasses stageMain(Path module, JavaClasses classes) {
        Path output = module.resolve("target/classes");
        for (var type : classes) {
            var source = type.getSource().orElseThrow();
            assertThat(source.getUri().getScheme()).isEqualTo("file");
            String filename = source.getFileName().orElseThrow();
            String language = filename.endsWith(".kt") ? "kotlin" : "java";
            Path relative = Path.of(type.getPackageName().replace('.', '/')).resolve(filename);
            Path originalSource = Path.of("src/test").resolve(language).resolve(relative);
            assertThat(originalSource).isRegularFile();
            try {
                copyVerified(
                        output.resolve(type.getName().replace('.', '/') + ".class"),
                        Files.readAllBytes(Path.of(source.getUri())));
                copyVerified(
                        module.resolve("src/main").resolve(language).resolve(relative),
                        Files.readAllBytes(originalSource));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return new ClassFileImporter().importPath(output);
    }

    static ArchitectureClassImporter importer(Path output) {
        return new ArchitectureClassImporter() {
            @Override
            public JavaClasses importPackages(java.util.List<String> packages) {
                return new ClassFileImporter().importPath(output);
            }

            @Override
            public ArchUnitBytecodeImports.Result importWithBytecode(java.util.List<String> packages) {
                return ArchUnitBytecodeImports.importPath(output);
            }
        };
    }

    private static void copyVerified(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            assertThat(Files.readAllBytes(target))
                    .as("Repeated fixture staging must preserve bytes: %s", target)
                    .containsExactly(bytes);
        } else {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        }
    }
}
