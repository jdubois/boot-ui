package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

final class ArchitectureGeneratedCodeFixtures {
    private ArchitectureGeneratedCodeFixtures() {}

    static JavaClasses compile(Path module, String output, Map<String, String> sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Regression fixtures require a JDK").isNotNull();
        List<Path> files = new ArrayList<>();
        for (var entry : sources.entrySet()) {
            files.add(write(module.resolve(entry.getKey()), entry.getValue()));
        }
        Path classes = Files.createDirectories(module.resolve(output));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean compiled = compiler.getTask(
                            null,
                            manager,
                            diagnostics,
                            List.of(
                                    "--release",
                                    "17",
                                    "-parameters",
                                    "-classpath",
                                    System.getProperty(
                                            "surefire.test.class.path", System.getProperty("java.class.path")),
                                    "-d",
                                    classes.toString()),
                            null,
                            manager.getJavaFileObjectsFromPaths(files))
                    .call();
            assertThat(compiled)
                    .as("Fixture compiler diagnostics: %s", diagnostics.getDiagnostics())
                    .isTrue();
        }
        return new ClassFileImporter().importPath(classes);
    }

    /**
     * Opens the JDK URL-cached handle that ArchUnit reuses when importing {@code jar}. Closing it evicts the cache
     * entry, so Windows can delete the file afterwards.
     */
    static JarFile cachedJar(Path jar) throws IOException {
        var connection = (JarURLConnection)
                URI.create("jar:" + jar.toUri() + "!/").toURL().openConnection();
        return connection.getJarFile();
    }

    static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    static String apiUtil(String packageName) {
        return "package " + packageName + "; public class ApiUtil {"
                + " public static void setExampleResponse() { throw new RuntimeException(); } }";
    }
}
