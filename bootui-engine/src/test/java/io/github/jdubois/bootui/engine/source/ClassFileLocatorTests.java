package io.github.jdubois.bootui.engine.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileLocatorTests {

    @TempDir
    Path workspace;

    /** A loader that, like Quarkus for transformed classes, answers only with an unopenable in-memory URL. */
    private static ClassLoader inMemoryLoader() {
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) throws IOException {
                throw new IOException("in memory");
            }
        };
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                try {
                    return new URL(null, "quarkus:" + name, handler);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    private Path classFile(String relative) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "cafebabe");
        return file;
    }

    @Test
    void usesTheLoaderResourceWhenItIsAReadableLocation() {
        URI located = ClassFileLocator.forScan()
                .locate(ClassFileLocatorTests.class.getName(), ClassFileLocatorTests.class.getClassLoader(), null);

        assertThat(located.getScheme()).isEqualTo("file");
        assertThat(Path.of(located)).isRegularFile().hasFileName("ClassFileLocatorTests.class");
    }

    @Test
    void findsAnInMemoryClassInTheOneClassPathDirectoryThatHoldsIt() throws IOException {
        Path file = classFile("app/target/classes/com/example/Order.class");
        ClassFileLocator locator =
                locator(true, workspace.resolve("elsewhere"), workspace.resolve("app/target/classes"));

        assertThat(locator.locate("com.example.Order", inMemoryLoader(), null)).isEqualTo(file.toUri());
    }

    @Test
    void keepsTheUnopenableLocationWhenNoOrSeveralDirectoriesHoldTheClass() throws IOException {
        classFile("one/com/example/Order.class");
        classFile("two/com/example/Order.class");
        ClassFileLocator ambiguous = locator(true, workspace.resolve("one"), workspace.resolve("two"));
        ClassFileLocator missing = locator(true, workspace.resolve("none"));

        assertThat(ambiguous.locate("com.example.Order", inMemoryLoader(), null).getScheme())
                .isEqualTo("quarkus");
        assertThat(missing.locate("com.example.Order", inMemoryLoader(), null).getScheme())
                .isEqualTo("quarkus");
        assertThat(missing.locate("com.example.Order", null, null)).isNull();
        assertThat(ambiguous.notes()).singleElement().asString().contains("more than one class path directory");
        assertThat(missing.notes()).isEmpty();
    }

    @Test
    void anIncompleteClassPathNeverProvesASingleMatch() throws IOException {
        classFile("app/target/classes/com/example/Order.class");
        ClassFileLocator incomplete = locator(false, workspace.resolve("app/target/classes"));

        assertThat(incomplete
                        .locate("com.example.Order", inMemoryLoader(), null)
                        .getScheme())
                .isEqualTo("quarkus");
        assertThat(incomplete.notes()).singleElement().asString().contains("too large to search completely");
    }

    @Test
    void tooManyLauncherJarsOrAnOversizedManifestLeaveTheSearchIncomplete() throws IOException {
        List<String> jars = new java.util.ArrayList<>();
        for (int index = 0; index <= ClassFileLocator.MAX_LAUNCHER_JARS; index++) {
            jars.add(jar("lib/dependency-" + index + ".jar", null).toString());
        }
        assertThat(ClassFileLocator.classPathDirectories(String.join(java.io.File.pathSeparator, jars))
                        .complete())
                .isFalse();

        Path huge = jar("lib/huge.jar", "x".repeat(ClassFileLocator.MAX_MANIFEST_BYTES));
        ClassFileLocator.ClassPath oversized = ClassFileLocator.classPathDirectories(huge.toString());
        assertThat(oversized.complete()).isFalse();
        assertThat(oversized.directories()).isEmpty();

        Path plain = jar("lib/plain.jar", null);
        assertThat(ClassFileLocator.classPathDirectories(plain.toString()).complete())
                .isTrue();
    }

    @Test
    void dependencyJarsAreNeverReadForManifestsSoAPlainClassPathStaysComplete() throws IOException {
        Path classes = Files.createDirectories(workspace.resolve("app/target/classes"));
        List<String> entries = new java.util.ArrayList<>(List.of(classes.toString()));
        for (int index = 0; index < 20; index++) {
            Path dependency = workspace.resolve("lib/dependency-" + index + ".jar");
            Files.createDirectories(dependency.getParent());
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes()
                    .put(
                            Attributes.Name.CLASS_PATH,
                            workspace.resolve("unrelated").toUri() + "");
            try (OutputStream output = Files.newOutputStream(dependency);
                    JarOutputStream jar = new JarOutputStream(output, manifest)) {
                jar.putNextEntry(new java.util.jar.JarEntry("com/example/A.class"));
                jar.putNextEntry(new java.util.jar.JarEntry("com/example/B.class"));
            }
            entries.add(dependency.toString());
        }
        Files.createDirectories(workspace.resolve("unrelated"));

        ClassFileLocator.ClassPath classPath =
                ClassFileLocator.classPathDirectories(String.join(java.io.File.pathSeparator, entries));

        assertThat(classPath.complete()).isTrue();
        assertThat(classPath.directories())
                .containsExactly(classes.toAbsolutePath().normalize());
    }

    private Path jar(String relative, String classPath) throws IOException {
        Path launcher = workspace.resolve(relative);
        Files.createDirectories(launcher.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classPath != null) manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        try (OutputStream output = Files.newOutputStream(launcher);
                JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.flush();
        }
        return launcher;
    }

    private static ClassFileLocator locator(boolean complete, Path... directories) {
        return new ClassFileLocator(() -> new ClassFileLocator.ClassPath(List.of(directories), complete));
    }

    @Test
    void readsDirectoriesNamedByALauncherJarManifestOnce() throws IOException {
        Path classes = Files.createDirectories(workspace.resolve("app/target/classes"));
        Files.createDirectories(workspace.resolve("lib"));
        Path launcher = workspace.resolve("lib/launcher.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes()
                .put(Attributes.Name.CLASS_PATH, classes.toUri() + " ../missing/ relative-dependency.jar");
        try (OutputStream output = Files.newOutputStream(launcher);
                JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.flush();
        }
        Path direct = Files.createDirectories(workspace.resolve("direct"));

        ClassFileLocator.ClassPath directories = ClassFileLocator.classPathDirectories(
                String.join(java.io.File.pathSeparator, direct.toString(), launcher.toString(), "", "missing.jar"));

        assertThat(directories.directories())
                .containsExactly(direct.toAbsolutePath().normalize(), classes.normalize());
        assertThat(directories.complete()).isTrue();
    }
}
