package io.github.jdubois.bootui.engine.archunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import io.github.jdubois.bootui.engine.archunit.fixtures.BadlyNamedFailure;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchUnitClassImportsTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.engine.archunit.fixtures";

    @Test
    void configuresOpenableLocationResolverDuringImport() {
        String[] activeDuringImport = new String[1];

        ArchUnitClassImports.importPackages(List.of(FIXTURES), packages -> {
            activeDuringImport[0] = ArchConfiguration.get().getClassResolver().orElse(null);
            return new ClassFileImporter().importPackages(packages);
        });

        assertThat(activeDuringImport[0]).isEqualTo(OpenableLocationClassResolver.class.getName());
    }

    @Test
    void doesNotLeakResolverConfigurationIntoGlobalStateAfterImport() {
        Optional<String> resolverBefore = ArchConfiguration.get().getClassResolver();
        List<String> argumentsBefore = ArchConfiguration.get().getClassResolverArguments();

        ArchUnitClassImports.importPackages(List.of(FIXTURES));

        assertThat(ArchConfiguration.get().getClassResolver()).isEqualTo(resolverBefore);
        assertThat(ArchConfiguration.get().getClassResolverArguments()).isEqualTo(argumentsBefore);
    }

    @Test
    void doesNotLeakResolverConfigurationIntoGlobalStateWhenImportThrows() {
        Optional<String> resolverBefore = ArchConfiguration.get().getClassResolver();
        List<String> argumentsBefore = ArchConfiguration.get().getClassResolverArguments();
        RuntimeException boom = new RuntimeException("import failed");

        assertThatThrownBy(() -> ArchUnitClassImports.importPackages(List.of(FIXTURES), packages -> {
                    throw boom;
                }))
                .isSameAs(boom);

        assertThat(ArchConfiguration.get().getClassResolver()).isEqualTo(resolverBefore);
        assertThat(ArchConfiguration.get().getClassResolverArguments()).isEqualTo(argumentsBefore);
    }

    @Test
    void preservesClasspathDependencyResolution() {
        // Importing only the fixtures package still resolves the JDK super-class chain of BadlyNamedFailure
        // (RuntimeException -> Exception) from the classpath. If classpath resolution were disabled the
        // chain would stop at an unresolved stub and isAssignableTo(Exception) would be false, silently
        // changing the curated ARCH-CODE-010 finding. This is the regression guard for that.
        JavaClasses classes = ArchUnitClassImports.importPackages(List.of(FIXTURES));

        JavaClass badlyNamedFailure = classes.get(BadlyNamedFailure.class);
        assertThat(badlyNamedFailure.isAssignableTo(Exception.class)).isTrue();
        assertThat(badlyNamedFailure.isAssignableTo(RuntimeException.class)).isTrue();
    }

    @Test
    void acceptsSupportedApplicationHeadersIncludingTheCurrentReaderBoundary() throws Exception {
        try (ApplicationClasses application = new ApplicationClasses()) {
            application.write("Java17", 61);
            application.write("Java27", 71);

            JavaClasses classes = application.importClasses();

            assertThat(classes)
                    .extracting(JavaClass::getName)
                    .containsExactlyInAnyOrder(application.className("Java17"), application.className("Java27"));
        }
    }

    @Test
    void rejectsMixedSupportedAndUnsupportedClassesInsteadOfReturningThePartialImport() throws Exception {
        Optional<String> resolverBefore = ArchConfiguration.get().getClassResolver();
        List<String> argumentsBefore = ArchConfiguration.get().getClassResolverArguments();
        try (ApplicationClasses application = new ApplicationClasses()) {
            application.write("Java17", 61);
            application.write("Java28", 72);

            // The real importer swallows the Java 28 reader failure and returns a non-empty partial set.
            JavaClasses unchecked =
                    application.inClassLoader(() -> new ClassFileImporter().importPackages(application.packageName));
            assertThat(unchecked).extracting(JavaClass::getName).containsExactly(application.className("Java17"));

            assertThatThrownBy(application::importClasses)
                    .isInstanceOf(IncompleteClassImportException.class)
                    .hasMessageContaining("1 of 2 class resources rejected")
                    .hasMessageContaining("unsupported class-file version: 1")
                    .hasMessageContaining("[72]")
                    .hasMessageNotContaining(application.root.toAbsolutePath().toString());
            assertThat(ArchConfiguration.get().getClassResolver()).isEqualTo(resolverBefore);
            assertThat(ArchConfiguration.get().getClassResolverArguments()).isEqualTo(argumentsBefore);

            Files.delete(application.classFile("Java28"));
            assertThat(application.importClasses())
                    .extracting(JavaClass::getName)
                    .containsExactly(application.className("Java17"));
        }
    }

    @Test
    void rejectsAnEntirelyUnsupportedPackageRatherThanTreatingItAsEmpty() throws Exception {
        try (ApplicationClasses application = new ApplicationClasses()) {
            application.write("Java28", 72);

            assertThatThrownBy(application::importClasses)
                    .isInstanceOf(IncompleteClassImportException.class)
                    .hasMessageContaining("1 of 1 class resources rejected")
                    .hasMessageContaining("unsupported class-file version: 1");
        }
    }

    @Test
    void preservesAGenuinelyEmptyPackage() throws Exception {
        try (ApplicationClasses application = new ApplicationClasses()) {
            assertThat(application.importClasses()).isEmpty();
        }
    }

    @Test
    void reportsInvalidAndShortHeadersTogether() throws Exception {
        try (ApplicationClasses application = new ApplicationClasses()) {
            application.write("Java17", 61);
            Files.write(application.classFile("Invalid"), new byte[8]);
            Files.write(application.classFile("Short"), new byte[] {(byte) 0xca, (byte) 0xfe});

            assertThatThrownBy(application::importClasses)
                    .isInstanceOf(IncompleteClassImportException.class)
                    .hasMessageContaining("2 of 3 class resources rejected")
                    .hasMessageContaining("invalid class-file header: 1")
                    .hasMessageContaining("truncated class-file header: 1");
        }
    }

    @Test
    void reportsReadErrorsWithoutExposingResourcePaths() {
        ClassFileImportCheck check = new ClassFileImportCheck();
        Path missing = Path.of("target", "missing-" + UUID.randomUUID(), "Absent.class");

        assertThat(check.includes(Location.of(missing))).isFalse();
        assertThatThrownBy(check::verify)
                .isInstanceOf(IncompleteClassImportException.class)
                .hasMessageContaining("unreadable class resource: 1")
                .hasMessageNotContaining(missing.toString());
    }

    @Test
    void boundsDiagnosticsEvenWhenManyClassFileVersionsAreUnsupported() throws Exception {
        try (ApplicationClasses application = new ApplicationClasses()) {
            for (int major = 72; major < 92; major++) {
                application.write("Java" + major, major);
            }

            assertThatThrownBy(application::importClasses)
                    .isInstanceOf(IncompleteClassImportException.class)
                    .hasMessageContaining("20 of 20 class resources rejected")
                    .hasMessageContaining("unsupported class-file version: 20")
                    .hasMessageContaining("up to 4 shown")
                    .satisfies(failure -> assertThat(failure.getMessage()).hasSizeLessThan(600));
        }
    }

    @Test
    void checksTheImportersJarEntryLocations() throws Exception {
        try (ApplicationClasses application = new ApplicationClasses()) {
            application.write("Java17", 61);
            application.write("Java28", 72);
            Path archive = application.root.resolve("application.jar");
            String packagePath = application.packageName.replace('.', '/') + "/";
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive))) {
                jar.putNextEntry(new JarEntry(packagePath));
                jar.closeEntry();
                for (String name : List.of("Java17", "Java28")) {
                    jar.putNextEntry(new JarEntry(packagePath + name + ".class"));
                    Files.copy(application.classFile(name), jar);
                    jar.closeEntry();
                }
            }

            try (URLClassLoader loader =
                    new URLClassLoader(new URL[] {archive.toUri().toURL()}, application.originalClassLoader)) {
                assertThatThrownBy(() -> application.inClassLoader(
                                loader, () -> ArchUnitClassImports.importPackages(List.of(application.packageName))))
                        .isInstanceOf(IncompleteClassImportException.class)
                        .hasMessageContaining("1 of 2 class resources rejected")
                        .hasMessageContaining("[72]");
            }
        }
    }

    private static final class ApplicationClasses implements AutoCloseable {

        private final Path root =
                Path.of("target", "archunit-import-checks", UUID.randomUUID().toString());
        private final String packageName =
                "bootui.importcheck.p" + UUID.randomUUID().toString().replace("-", "");
        private final ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        private final URLClassLoader loader;

        private ApplicationClasses() throws IOException {
            Files.createDirectories(root.resolve(packageName.replace('.', '/')));
            loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, originalClassLoader);
        }

        private String className(String name) {
            return packageName + "." + name;
        }

        private Path classFile(String name) {
            return root.resolve(className(name).replace('.', '/') + ".class");
        }

        private void write(String name, int major) throws IOException {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(major, Opcodes.ACC_PUBLIC, className(name).replace('.', '/'), null, "java/lang/Object", null);
            Files.write(classFile(name), writer.toByteArray());
        }

        private JavaClasses importClasses() {
            return inClassLoader(() -> ArchUnitClassImports.importPackages(List.of(packageName)));
        }

        private <T> T inClassLoader(Supplier<T> action) {
            return inClassLoader(loader, action);
        }

        private <T> T inClassLoader(ClassLoader classLoader, Supplier<T> action) {
            Thread.currentThread().setContextClassLoader(classLoader);
            try {
                return action.get();
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }

        @Override
        public void close() throws IOException {
            loader.close();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
