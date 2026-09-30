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
        ClassFileLocator locator = new ClassFileLocator(
                () -> List.of(workspace.resolve("elsewhere"), workspace.resolve("app/target/classes")));

        assertThat(locator.locate("com.example.Order", inMemoryLoader(), null)).isEqualTo(file.toUri());
    }

    @Test
    void keepsTheUnopenableLocationWhenNoOrSeveralDirectoriesHoldTheClass() throws IOException {
        classFile("one/com/example/Order.class");
        classFile("two/com/example/Order.class");
        ClassFileLocator ambiguous =
                new ClassFileLocator(() -> List.of(workspace.resolve("one"), workspace.resolve("two")));
        ClassFileLocator missing = new ClassFileLocator(() -> List.of(workspace.resolve("none")));

        assertThat(ambiguous.locate("com.example.Order", inMemoryLoader(), null).getScheme())
                .isEqualTo("quarkus");
        assertThat(missing.locate("com.example.Order", inMemoryLoader(), null).getScheme())
                .isEqualTo("quarkus");
        assertThat(missing.locate("com.example.Order", null, null)).isNull();
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

        List<Path> directories = ClassFileLocator.classPathDirectories(
                String.join(java.io.File.pathSeparator, direct.toString(), launcher.toString(), "", "missing.jar"));

        assertThat(directories).containsExactly(direct.toAbsolutePath().normalize(), classes.normalize());
    }
}
