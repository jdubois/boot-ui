package io.github.jdubois.bootui.engine.archunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import io.github.jdubois.bootui.engine.architecture.ProxyReceiverEvidenceTests;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ArchUnitBytecodeImportsTests {
    private static final ArchUnitBytecodeImports.Limits LIMITS =
            new ArchUnitBytecodeImports.Limits(1024 * 1024, 4L * 1024 * 1024, 32);

    @TempDir
    Path directory;

    @Test
    void capturedBytesAreExactlyTheInputParsedEvenWhenTheResourceChangesBeforeParsing() throws Exception {
        byte[] before = bytes();
        byte[] after = replacement();
        Path source = Files.write(directory.resolve("Changed.class"), before);
        var root = new ArchUnitBytecodeImports.Root(source.toUri(), () -> new ByteArrayInputStream(before) {
            @Override
            public void close() throws IOException {
                Files.write(source, after);
            }
        });

        var imported = process(List.of(root), LIMITS);
        JavaClass type = imported.classes().get(Fixture.class);

        assertTarget(type, "helper");
        assertThat(imported.bytecode().bytesOf(type))
                .hasValueSatisfying(value -> assertThat(value).isEqualTo(before));
        assertThat(Files.readAllBytes(source)).isEqualTo(after);
        assertThat(type.getSource().orElseThrow().getUri()).isEqualTo(source.toUri());
        assertTarget(new ClassFileImporter().importPath(source).get(Fixture.class), "replacement");
    }

    @Test
    void normalLocalImportsPreserveTheirOriginalUriAndDefendTheFrozenCopy() throws Exception {
        byte[] expected = bytes();
        Path source = Files.write(directory.resolve("Original.class"), expected);
        var imported = ArchUnitBytecodeImports.importPath(source);
        JavaClass type = imported.classes().get(Fixture.class);

        assertThat(type.getSource().orElseThrow().getUri()).isEqualTo(source.toUri());
        byte[] copy = imported.bytecode().bytesOf(type).orElseThrow();
        copy[0] = 0;
        assertThat(imported.bytecode().bytesOf(type))
                .hasValueSatisfying(value -> assertThat(value).isEqualTo(expected));
        JavaClass differentImport =
                ArchUnitBytecodeImports.importPath(source).classes().get(Fixture.class);
        assertThat(imported.bytecode().bytesOf(differentImport)).isEmpty();
    }

    @Test
    void duplicateUriWithChangedBytesWithholdsProofForTheEarlierImportedClass() throws Exception {
        URI uri = directory.resolve("Duplicate.class").toUri();
        var imported = process(List.of(root(uri, bytes()), root(uri, replacement())), LIMITS);
        JavaClass type = imported.classes().get(Fixture.class);

        assertTarget(type, "helper");
        assertThat(imported.bytecode().bytesOf(type)).isEmpty();
    }

    @Test
    void duplicateUnchangedReadsCanRetainProof() throws Exception {
        URI uri = directory.resolve("Unchanged.class").toUri();
        byte[] bytes = bytes();
        var imported = process(List.of(root(uri, bytes), root(uri, bytes)), LIMITS);

        assertThat(imported.bytecode().bytesOf(imported.classes().get(Fixture.class)))
                .hasValueSatisfying(value -> assertThat(value).isEqualTo(bytes));
    }

    @Test
    void anUnverifiedDuplicateReadCannotLeaveOrRestoreProofForAnEarlierImport() throws Exception {
        URI uri = directory.resolve("UnreadableDuplicate.class").toUri();
        byte[] original = bytes();
        var failing = new ArchUnitBytecodeImports.Root(uri, () -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable fixture");
            }
        });
        var imported = process(List.of(root(uri, original), failing, root(uri, original)), LIMITS);

        assertTarget(imported.classes().get(Fixture.class), "helper");
        assertThat(imported.bytecode().bytesOf(imported.classes().get(Fixture.class)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"class", "aggregate", "entries"})
    void budgetsRemoveOnlyOptionalProofWithoutLosingImportedClasses(String budget) throws Exception {
        byte[] bytes = bytes();
        var limits =
                switch (budget) {
                    case "class" -> new ArchUnitBytecodeImports.Limits(bytes.length - 1, bytes.length * 2L, 10);
                    case "aggregate" -> new ArchUnitBytecodeImports.Limits(bytes.length + 1, bytes.length - 1L, 10);
                    case "entries" -> new ArchUnitBytecodeImports.Limits(bytes.length + 1, bytes.length * 2L, 0);
                    default -> throw new AssertionError(budget);
                };
        var imported = process(List.of(root(directory.resolve("Budget.class").toUri(), bytes)), limits);
        JavaClass type = imported.classes().get(Fixture.class);

        assertTarget(type, "helper");
        assertThat(imported.bytecode().bytesOf(type)).isEmpty();
    }

    @Test
    void unavailableHookFallsBackToThePublicImporterAndDoesNotLeakConfiguration() throws Exception {
        Path source = Files.write(directory.resolve("Fallback.class"), bytes());
        var resolverBefore = ArchConfiguration.get().getClassResolver();
        var argumentsBefore = ArchConfiguration.get().getClassResolverArguments();
        boolean md5Before = ArchConfiguration.get().md5InClassSourcesEnabled();

        var imported = ArchUnitBytecodeImports.importLocations(
                List.of(Location.of(source)),
                () -> {
                    throw new NoSuchMethodException("unsupported shape");
                },
                LIMITS);
        JavaClass type = imported.classes().get(Fixture.class);

        assertTarget(type, "helper");
        assertThat(imported.bytecode().bytesOf(type)).isEmpty();
        assertThat(type.getSource().orElseThrow().getUri()).isEqualTo(source.toUri());
        assertThat(ArchConfiguration.get().getClassResolver()).isEqualTo(resolverBefore);
        assertThat(ArchConfiguration.get().getClassResolverArguments()).isEqualTo(argumentsBefore);
        assertThat(ArchConfiguration.get().md5InClassSourcesEnabled()).isEqualTo(md5Before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sourceCloseFailureFallsBackWithoutOmittingTheClassOrItsLegacyFinding(boolean runtime) throws Exception {
        byte[] valid = bytes();
        Path source = Files.write(directory.resolve("CloseFailure.class"), valid);
        AtomicInteger closes = new AtomicInteger();
        var imported = ArchUnitBytecodeImports.importLocations(
                List.of(Location.of(source)),
                () -> new ArchUnitBytecodeImports.Hook() {
                    @Override
                    List<ArchUnitBytecodeImports.Root> roots(
                            ClassFileImporter importer, Collection<Location> locations, int limit) {
                        return List.of(new ArchUnitBytecodeImports.Root(
                                source.toUri(), () -> new ByteArrayInputStream(valid) {
                                    @Override
                                    public void close() throws IOException {
                                        closes.incrementAndGet();
                                        if (runtime) throw new IllegalStateException("fixture close failure");
                                        throw new IOException("fixture close failure");
                                    }
                                }));
                    }
                },
                LIMITS);

        assertThat(closes).hasValue(1);
        assertThat(imported.classes()).hasSize(1);
        JavaClass type = imported.classes().get(Fixture.class);
        assertTarget(type, "helper");
        assertThat(type.getSource().orElseThrow().getUri()).isEqualTo(source.toUri());
        assertThat(imported.bytecode()).isSameAs(ArchUnitBytecodeImports.Bytecode.EMPTY);
        var finding = ProxyReceiverEvidenceTests.evaluate(imported);
        assertThat(finding.status()).isEqualTo("VIOLATION");
        assertThat(finding.violationCount()).isEqualTo(1);
    }

    @Test
    void aDiscoveryBudgetFallbackStillImportsEveryClassThroughTheNormalPath() throws Exception {
        Path source = Files.write(directory.resolve("DiscoveryBudget.class"), bytes());
        var limits = new ArchUnitBytecodeImports.Limits(1024 * 1024, 4L * 1024 * 1024, 0);

        var imported = ArchUnitBytecodeImports.importLocations(
                List.of(Location.of(source)), ArchUnitBytecodeImports.Hook::new, limits);

        assertTarget(imported.classes().get(Fixture.class), "helper");
        assertThat(imported.bytecode().bytesOf(imported.classes().get(Fixture.class)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void headerRejectionRemainsFailClosedWithOrWithoutTheOptionalHook(boolean supported) throws Exception {
        byte[] malformed = bytes();
        malformed[0] = 0;
        Path source = Files.write(directory.resolve("InvalidHeader.class"), malformed);
        ArchUnitBytecodeImports.HookFactory factory = supported
                ? ArchUnitBytecodeImports.Hook::new
                : () -> {
                    throw new NoSuchMethodException("unsupported shape");
                };

        assertThatThrownBy(() -> ArchUnitBytecodeImports.importLocations(List.of(Location.of(source)), factory, LIMITS))
                .isInstanceOf(IncompleteClassImportException.class)
                .hasMessageContaining("invalid class-file header: 1");
    }

    @Test
    void clearingTheMutableImportScopeDoesNotRetainOrMutateProofFromAnotherScope() throws Exception {
        var capture = new ArchUnitBytecodeImports.Capture(LIMITS);
        var classes = new ArchUnitBytecodeImports.Hook()
                .process(List.of(root(directory.resolve("Scoped.class").toUri(), bytes())), capture);
        var frozen = capture.finish(classes);
        JavaClass type = classes.get(Fixture.class);

        capture.clear();

        assertThat(capture.finish(classes).bytesOf(type)).isEmpty();
        assertThat(frozen.bytesOf(type)).isPresent();
    }

    @Test
    void concurrentImportsWithTheSameUriHaveIndependentProofAndClearTheirCaptureScope() throws Exception {
        URI uri = directory.resolve("Concurrent.class").toUri();
        byte[] first = bytes();
        byte[] second = replacement();
        CompletableFuture<ArchUnitBytecodeImports.Result> left =
                CompletableFuture.supplyAsync(() -> uncheckedProcess(root(uri, first)));
        CompletableFuture<ArchUnitBytecodeImports.Result> right =
                CompletableFuture.supplyAsync(() -> uncheckedProcess(root(uri, second)));
        var a = left.join();
        var b = right.join();
        JavaClass aType = a.classes().get(Fixture.class);
        JavaClass bType = b.classes().get(Fixture.class);

        assertTarget(aType, "helper");
        assertTarget(bType, "replacement");
        assertThat(a.bytecode().bytesOf(aType))
                .hasValueSatisfying(value -> assertThat(value).isEqualTo(first));
        assertThat(b.bytecode().bytesOf(bType))
                .hasValueSatisfying(value -> assertThat(value).isEqualTo(second));
        assertThat(a.bytecode().bytesOf(bType)).isEmpty();
        assertThat(b.bytecode().bytesOf(aType)).isEmpty();
    }

    @Test
    void fatalErrorsFromActualReaderInputsAreNotSwallowedByReflectionOrFallback() throws IOException {
        AssertionError failure = new AssertionError("input failed");
        var root = new ArchUnitBytecodeImports.Root(
                directory.resolve("Fatal.class").toUri(), () -> {
                    throw failure;
                });

        assertThatThrownBy(() -> process(List.of(root), LIMITS)).isSameAs(failure);
        byte[] valid = bytes();
        var closeFailure = new ArchUnitBytecodeImports.Root(
                directory.resolve("FatalClose.class").toUri(), () -> new ByteArrayInputStream(valid) {
                    @Override
                    public void close() {
                        throw failure;
                    }
                });
        assertThatThrownBy(() -> process(List.of(closeFailure), LIMITS)).isSameAs(failure);
        assertThatThrownBy(() -> ArchUnitBytecodeImports.importLocations(
                        List.of(),
                        () -> {
                            throw failure;
                        },
                        LIMITS))
                .isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file://remote-host/application/Foo.class",
                "jar:file://remote-host/application.jar!/Foo.class",
                "nested://remote-host/application.jar/!BOOT-INF/classes/Foo.class",
                "jar:nested://remote-host/application.jar/!BOOT-INF/classes/!/Foo.class",
                "jar:jar:file://remote-host/application.jar!/inner.jar!/Foo.class",
                "file:////remote-host/application/Foo.class",
                "file:/%5C%5Cremote-host/application/Foo.class",
                "jar:file:/%5C%5Cremote-host/application.jar!/Foo.class",
                "jar:https://remote-host/application.jar!/Foo.class"
            })
    void unsafeCaptureLocationsNeverConstructAConnection(String location) throws Exception {
        AtomicInteger connections = new AtomicInteger();
        URL resource = new URL(null, location, new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                connections.incrementAndGet();
                throw new AssertionError("No remote connection is permitted");
            }
        });
        var capture = new ArchUnitBytecodeImports.Capture(LIMITS);

        assertThatThrownBy(() -> capture.open(new ArchUnitBytecodeImports.Root(resource.toURI(), resource::openStream)))
                .isInstanceOf(RuntimeException.class);
        assertThat(connections).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file:/application/Foo.class",
                "file:///application/Foo.class",
                "jar:file:/application.jar!/Foo.class",
                "jrt:/java.base/java/lang/Object.class",
                "nested:/application.jar/!BOOT-INF/classes/Foo.class",
                "jar:nested:/application.jar/!BOOT-INF/lib/library.jar!/Foo.class"
            })
    void supportedAuthorityFreeLocationsRemainLocal(String location) {
        assertThat(ArchUnitBytecodeImports.isLocalResource(URI.create(location)))
                .isTrue();
    }

    @Test
    void localJarImportsRetainOriginalArchiveLocations() throws Exception {
        byte[] expected = bytes();
        Path archive = directory.resolve("Application.jar");
        String entry = Fixture.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry(entry));
            output.write(expected);
            output.closeEntry();
        }
        URI uri = URI.create("jar:" + archive.toUri() + "!/" + entry);
        var connection = (JarURLConnection) uri.toURL().openConnection();
        // Own ArchUnit's cached JAR handle so Windows can delete the temporary archive.
        try (JarFile cachedJar = connection.getJarFile()) {
            URI normalUri = new ClassFileImporter()
                    .importLocations(List.of(Location.of(uri)))
                    .get(Fixture.class)
                    .getSource()
                    .orElseThrow()
                    .getUri();
            var imported = ArchUnitBytecodeImports.importLocations(
                    List.of(Location.of(uri)), ArchUnitBytecodeImports.Hook::new, LIMITS);
            JavaClass type = imported.classes().get(Fixture.class);

            assertThat(type.getSource().orElseThrow().getUri()).isEqualTo(normalUri);
            assertThat(imported.bytecode().bytesOf(type))
                    .hasValueSatisfying(value -> assertThat(value).isEqualTo(expected));
        }
        Files.delete(archive);
    }

    private static ArchUnitBytecodeImports.Root root(URI uri, byte[] bytes) {
        return new ArchUnitBytecodeImports.Root(uri, () -> new ByteArrayInputStream(bytes));
    }

    private static ArchUnitBytecodeImports.Result process(
            List<ArchUnitBytecodeImports.Root> roots, ArchUnitBytecodeImports.Limits limits) throws Exception {
        var capture = new ArchUnitBytecodeImports.Capture(limits);
        try {
            var classes = new ArchUnitBytecodeImports.Hook().process(roots, capture);
            return new ArchUnitBytecodeImports.Result(classes, capture.finish(classes));
        } finally {
            capture.clear();
        }
    }

    private static ArchUnitBytecodeImports.Result uncheckedProcess(ArchUnitBytecodeImports.Root root) {
        try {
            return process(List.of(root), LIMITS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertTarget(JavaClass type, String target) {
        assertThat(type.getMethodCallsFromSelf().stream()
                        .filter(call -> call.getOrigin().getName().equals("caller")))
                .singleElement()
                .satisfies(call -> assertThat(call.getTarget().getName()).isEqualTo(target));
    }

    private static byte[] bytes() throws IOException {
        try (InputStream input =
                Fixture.class.getResourceAsStream("/" + Fixture.class.getName().replace('.', '/') + ".class")) {
            assertThat(input).isNotNull();
            return input.readAllBytes();
        }
    }

    private static byte[] replacement() throws IOException {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes())
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                return new MethodVisitor(
                                        Opcodes.ASM9,
                                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String name, String descriptor, boolean itf) {
                                        super.visitMethodInsn(
                                                opcode,
                                                owner,
                                                name.equals("helper") ? "replacement" : name,
                                                descriptor,
                                                itf);
                                    }
                                };
                            }
                        },
                        0);
        return writer.toByteArray();
    }

    static class Fixture {
        void caller() {
            helper();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void helper() {}

        void replacement() {}
    }
}
