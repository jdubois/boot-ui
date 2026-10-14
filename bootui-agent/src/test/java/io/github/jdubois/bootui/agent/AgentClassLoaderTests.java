package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.bytebuddy.ByteBuddy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentClassLoaderTests {

    @TempDir
    Path directory;

    @Test
    void definesEveryClassUnderAPrefixAndNoOther() throws Exception {
        Path path = directory.resolve("agent.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            add(jar, "warm.demo.Alpha");
            add(jar, "warm.demo.sub.Beta");
            add(jar, "warm.other.Gamma");
        }
        try (JarFile file = new JarFile(path.toFile())) {
            AgentClassLoader loader = new AgentClassLoader(file, path.toString(), null);

            assertThat(loader.define("warm.demo.")).isEqualTo(2);
            assertThat(loader.define("warm.missing.")).isZero();
            assertThat(loader.loadClass("warm.demo.Alpha").getClassLoader()).isSameAs(loader);
        }
    }

    private static void add(JarOutputStream jar, String name) throws IOException {
        byte[] bytes = new ByteBuddy().subclass(Object.class).name(name).make().getBytes();
        jar.putNextEntry(new JarEntry("inst/" + name.replace('.', '/') + ".classdata"));
        OutputStream out = jar;
        out.write(bytes);
        jar.closeEntry();
    }
}
