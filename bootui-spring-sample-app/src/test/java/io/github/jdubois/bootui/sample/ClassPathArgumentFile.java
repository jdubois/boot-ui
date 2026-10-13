package io.github.jdubois.bootui.sample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Passes the test JVM's class path to a child JVM through an argument file: Windows caps a command line at 32 KB. */
final class ClassPathArgumentFile {

    private ClassPathArgumentFile() {}

    static String write(Path directory) throws IOException {
        String classPath = System.getProperty("java.class.path").replace("\\", "\\\\");
        Path file = directory.resolve("classpath.args").toAbsolutePath();
        Files.writeString(file, "-cp \"" + classPath + "\"" + System.lineSeparator());
        return "@" + file;
    }
}
