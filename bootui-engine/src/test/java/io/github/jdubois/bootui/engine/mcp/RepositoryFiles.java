package io.github.jdubois.bootui.engine.mcp;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates repository files from a test, whether the build runs from the repository root or from the
 * module directory.
 */
final class RepositoryFiles {

    private RepositoryFiles() {}

    static Path root() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path candidate :
                new Path[] {workingDirectory, workingDirectory.resolve("..").normalize()}) {
            if (Files.isDirectory(candidate.resolve("skills/bootui"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("the repository root could not be located from " + workingDirectory);
    }

    static Path file(String relativePath) {
        Path candidate = root().resolve(relativePath);
        if (!Files.exists(candidate)) {
            throw new IllegalStateException(relativePath + " could not be located from " + root());
        }
        return candidate;
    }
}
