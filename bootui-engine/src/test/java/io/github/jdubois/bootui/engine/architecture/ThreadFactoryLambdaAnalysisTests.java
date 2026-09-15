package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class ThreadFactoryLambdaAnalysisTests {
    private static final Class<?> FIXTURE = NoDirectThreadInstantiationRuleTests.MixedConstruction.class;

    @TempDir
    Path directory;

    @Test
    void distinguishesFactoryAndOrdinaryConstructionWithoutDebugMetadata() throws IOException {
        JavaClasses classes = new ClassFileImporter().importPath(writeFixture(false));
        ArchitectureContext context = context(classes);
        ArchitectureRuleResultDto result = new NoDirectThreadInstantiationRule().evaluate(context);

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("mixed()");
        assertThat(context.evidence().usable()).isTrue();
        assertThat(context.evidence().requiredUnknown()).isFalse();
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(FIXTURE.getPackageName()),
                ignored -> classes,
                ArchitecturePlatform.SPRING,
                Clock.systemUTC(),
                List.of(new NoDirectThreadInstantiationRule()));
        var report = scanner.scan();
        assertThat(report.violationDetails().total()).isEqualTo(1);
        assertThat(report.violationDetails().retained()).isEqualTo(1);
        assertThat(scanner.ruleViolations(
                                "ARCH-CODE-017", report.violationDetails().scanId(), 0, 10)
                        .violations())
                .containsExactlyElementsOf(result.sampleViolations());
    }

    @Test
    void readsPackagedApplicationBytecodeWithoutLoadingIt() throws IOException {
        Path jar = directory.resolve("application.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(FIXTURE.getName().replace('.', '/') + ".class"));
            output.write(fixtureBytes(true));
            output.closeEntry();
        }
        var connection = (JarURLConnection)
                URI.create("jar:" + jar.toUri() + "!/").toURL().openConnection();
        JavaClasses classes;
        // ArchUnit reopens the JAR through the URL cache; own that handle so Windows can delete it.
        try (JarFile input = connection.getJarFile()) {
            classes = new ClassFileImporter().importJar(input);
        }
        ArchitectureRuleResultDto result = new NoDirectThreadInstantiationRule().evaluate(context(classes));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        Files.delete(jar);
    }

    @Test
    void unreadableRequiredBytecodeProducesAnAnalysisErrorRatherThanACleanScan() throws IOException {
        Path source = writeFixture(true);
        JavaClasses classes = new ClassFileImporter().importPath(source);
        Files.delete(source);
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(FIXTURE.getPackageName()),
                ignored -> classes,
                ArchitecturePlatform.SPRING,
                Clock.systemUTC(),
                List.of(new NoDirectThreadInstantiationRule(), new NoLegacyDateTimeRule()));

        var report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.analysisErrors()).singleElement().satisfies(error -> {
            assertThat(error.id()).isEqualTo("ARCH-CODE-017");
            assertThat(error.status()).isEqualTo("ERROR");
            assertThat(error.sampleViolations())
                    .singleElement()
                    .asString()
                    .contains("UncheckedIOException")
                    .doesNotContain(directory.toString());
        });
    }

    @Test
    void malformedRequiredBytecodeDoesNotBecomeAnExemption() throws IOException {
        Path source = writeFixture(true);
        JavaClasses classes = new ClassFileImporter().importPath(source);
        Files.write(source, new byte[0]);
        ArchitectureContext context = context(classes);
        assertThat(new NoDirectThreadInstantiationRule().evaluate(context).status())
                .isEqualTo("ERROR");
        assertThat(context.evidence().usable()).isFalse();
        assertThat(context.evidence().evaluated()).isFalse();
    }

    @Test
    void bytecodeChangedAfterImportDoesNotSilentlyDropObservedCalls() throws IOException {
        Path source = writeFixture(true);
        JavaClasses classes = new ClassFileImporter().importPath(source);
        ClassWriter replacement = new ClassWriter(0);
        replacement.visit(
                Opcodes.V17, Opcodes.ACC_PUBLIC, FIXTURE.getName().replace('.', '/'), null, "java/lang/Object", null);
        replacement.visitEnd();
        Files.write(source, replacement.toByteArray());

        ArchitectureContext context = context(classes);
        assertThat(new NoDirectThreadInstantiationRule().evaluate(context).status())
                .isEqualTo("ERROR");
        assertThat(context.evidence().usable()).isFalse();
    }

    @Test
    void missingResourceMetadataIsExplicitlyUnavailable() {
        JavaClasses classes = new ClassFileImporter().importClasses(FIXTURE);
        JavaClass original = classes.get(FIXTURE);
        JavaClass missing = mock(JavaClass.class);
        when(missing.getConstructorCallsFromSelf()).thenReturn(original.getConstructorCallsFromSelf());
        when(missing.getSource()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new ThreadFactoryLambdaAnalysis(classes).violations(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Thread construction bytecode is unavailable");
    }

    private Path writeFixture(boolean debug) throws IOException {
        return Files.write(directory.resolve("MixedConstruction.class"), fixtureBytes(debug));
    }

    private static byte[] fixtureBytes(boolean debug) throws IOException {
        try (InputStream input =
                FIXTURE.getResourceAsStream("/" + FIXTURE.getName().replace('.', '/') + ".class")) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(input).accept(writer, debug ? 0 : ClassReader.SKIP_DEBUG);
            return writer.toByteArray();
        }
    }

    private static ArchitectureContext context(JavaClasses classes) {
        return new ArchitectureContext(classes, List.of(FIXTURE.getPackageName()), ArchitecturePlatform.SPRING);
    }
}
