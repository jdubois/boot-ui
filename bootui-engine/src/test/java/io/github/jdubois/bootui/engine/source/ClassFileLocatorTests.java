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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileLocatorTests {

    @TempDir
    Path workspace;

    private static final URLStreamHandler IN_MEMORY = new URLStreamHandler() {
        @Override
        protected URLConnection openConnection(URL url) throws IOException {
            throw new IOException("in memory");
        }
    };

    private static URL inMemory(String name) {
        try {
            return new URL(null, "quarkus:" + name, IN_MEMORY);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * A loader that, like Quarkus for transformed classes, serves the class itself only from memory, while its
     * package resolves to the given roots.
     */
    private static ClassLoader loader(List<URL> packageRoots) {
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return inMemory(name);
            }

            @Override
            public Enumeration<URL> getResources(String name) {
                return name.endsWith("/") ? Collections.enumeration(packageRoots) : Collections.emptyEnumeration();
            }
        };
    }

    private Path classFile(String relative) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "cafebabe");
        return file;
    }

    private URL directory(String relative) throws IOException {
        return Files.createDirectories(workspace.resolve(relative)).toUri().toURL();
    }

    @Test
    void usesTheLoaderResourceWhenItIsAReadableLocation() {
        ClassFileLocator locator = ClassFileLocator.forScan();
        URI located = locator.locate(
                ClassFileLocatorTests.class.getName(), ClassFileLocatorTests.class.getClassLoader(), null);

        assertThat(located.getScheme()).isEqualTo("file");
        assertThat(Path.of(located)).isRegularFile().hasFileName("ClassFileLocatorTests.class");
        assertThat(locator.notes()).isEmpty();
    }

    @Test
    void findsAnInMemoryClassInTheOnePackageRootThatHoldsIt() throws IOException {
        Path file = classFile("app/target/classes/com/example/Order.class");
        ClassFileLocator locator = ClassFileLocator.forScan();
        ClassLoader loader = loader(
                List.of(directory("other/target/classes/com/example"), directory("app/target/classes/com/example")));

        assertThat(locator.locate("com.example.Order", loader, null)).isEqualTo(file.toUri());
        assertThat(locator.notes()).isEmpty();
    }

    @Test
    void aPackageRootThatCannotBeReadLeavesTheClassUnproven() throws IOException {
        classFile("app/target/classes/com/example/Order.class");
        ClassFileLocator locator = ClassFileLocator.forScan();
        ClassLoader loader = loader(List.of(inMemory("com/example/"), directory("app/target/classes/com/example")));

        assertThat(locator.locate("com.example.Order", loader, null).getScheme())
                .isEqualTo("quarkus");
        assertThat(locator.notes()).singleElement().asString().contains("within the lookup bounds");
    }

    @Test
    void keepsTheInMemoryLocationWhenTwoRootsHoldTheClass() throws IOException {
        classFile("one/com/example/Order.class");
        classFile("two/com/example/Order.class");
        ClassFileLocator locator = ClassFileLocator.forScan();
        ClassLoader loader = loader(List.of(directory("one/com/example"), directory("two/com/example")));

        assertThat(locator.locate("com.example.Order", loader, null).getScheme())
                .isEqualTo("quarkus");
        assertThat(locator.notes()).singleElement().asString().contains("found in more than one location");
    }

    @Test
    void anArchiveRootHoldingTheSameClassMakesTheMatchAmbiguous() throws IOException {
        classFile("app/target/classes/com/example/Order.class");
        Path jar = workspace.resolve("lib/copy.jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream output = Files.newOutputStream(jar);
                JarOutputStream archive = new JarOutputStream(output)) {
            archive.putNextEntry(new JarEntry("com/example/"));
            archive.putNextEntry(new JarEntry("com/example/Order.class"));
            archive.write(new byte[] {1});
        }
        URL archiveRoot = new URL("jar:" + jar.toUri() + "!/com/example/");
        ClassFileLocator locator = ClassFileLocator.forScan();

        URI located = locator.locate(
                "com.example.Order", loader(List.of(directory("app/target/classes/com/example"), archiveRoot)), null);

        assertThat(located.getScheme()).isEqualTo("quarkus");
        assertThat(locator.notes()).singleElement().asString().contains("more than one location");
        assertThat(ClassFileLocator.forScan()
                        .locate("com.example.Missing", loader(List.of(archiveRoot)), null)
                        .getScheme())
                .isEqualTo("quarkus");
    }

    @Test
    void tooManyPackageRootsLeaveTheClassUnproven() throws IOException {
        classFile("app/target/classes/com/example/Order.class");
        List<URL> roots = new ArrayList<>();
        for (int index = 0; index < ClassFileLocator.MAX_PACKAGE_ROOTS; index++) {
            roots.add(directory("empty" + index + "/com/example"));
        }
        roots.add(directory("app/target/classes/com/example"));
        ClassFileLocator locator = ClassFileLocator.forScan();

        assertThat(locator.locate("com.example.Order", loader(roots), null).getScheme())
                .isEqualTo("quarkus");
        assertThat(locator.notes()).singleElement().asString().contains("within the lookup bounds");
    }

    @Test
    void aClassWithoutAnyLoaderResourceHasNoLocation() {
        ClassLoader empty = new ClassLoader(null) {};

        assertThat(ClassFileLocator.forScan().locate("com.example.Order", empty, null))
                .isNull();
        assertThat(ClassFileLocator.forScan().locate("com.example.Order", null, null))
                .isNull();
    }
}
