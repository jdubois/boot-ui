package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import bootui.packaged.threadfactory.NewerClassFile;
import bootui.packaged.threadfactory.ThreadFactoryProbe;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreadFactoryExecutableJarIT {
    @TempDir
    Path directory;

    @Test
    void scansRealBootNestedResourcesUsingArchUnitsEmbeddedReaderWithoutBundlingAnotherCopy() throws Exception {
        Path executable = directory.resolve("probe.jar");
        try (JarFile sample = new JarFile(System.getProperty("sample.jar"))) {
            var manifest = sample.getManifest();
            manifest.getMainAttributes().putValue("Start-Class", ThreadFactoryProbe.class.getName());
            manifest.getMainAttributes().remove(new Attributes.Name("Spring-Boot-Classpath-Index"));
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(executable), manifest)) {
                var entries = sample.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().equals(JarFile.MANIFEST_NAME)
                            || entry.getName().equals("BOOT-INF/classpath.idx")
                            || entry.getName().startsWith("BOOT-INF/lib/asm-")) {
                        continue;
                    }
                    if (entry.getName().startsWith("BOOT-INF/lib/bootui-engine-")) {
                        try (JarInputStream engine = new JarInputStream(sample.getInputStream(entry))) {
                            for (JarEntry engineEntry; (engineEntry = engine.getNextJarEntry()) != null; ) {
                                assertThat(engineEntry.getName())
                                        .as("the engine must not bundle its own ASM copy")
                                        .doesNotContain("/asm/");
                            }
                        }
                    }
                    JarEntry copy = new JarEntry(entry.getName());
                    if (entry.getMethod() == JarEntry.STORED) {
                        copy.setMethod(JarEntry.STORED);
                        copy.setSize(entry.getSize());
                        copy.setCrc(entry.getCrc());
                    }
                    output.putNextEntry(copy);
                    try (InputStream input = sample.getInputStream(entry)) {
                        input.transferTo(output);
                    }
                    output.closeEntry();
                }
                addProbe(output, ThreadFactoryProbe.class, false);
                addProbe(output, NewerClassFile.class, true);
                byte[] oldAsm = Files.readAllBytes(Path.of(System.getProperty("host.asm.jar")));
                CRC32 checksum = new CRC32();
                checksum.update(oldAsm);
                JarEntry hostAsm = new JarEntry("BOOT-INF/lib/asm-9.8.jar");
                hostAsm.setMethod(JarEntry.STORED);
                hostAsm.setSize(oldAsm.length);
                hostAsm.setCrc(checksum.getValue());
                output.putNextEntry(hostAsm);
                output.write(oldAsm);
                output.closeEntry();
            }
        }

        Path log = directory.resolve("probe.log");
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar",
                        executable.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("packaged probe completes")
                    .isTrue();
            String output = Files.readString(log);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("THREAD_FACTORY_PACKAGED_OK", "jar:nested:");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void addProbe(JarOutputStream output, Class<?> type, boolean java27) throws Exception {
        String path = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream("/" + path)) {
            byte[] bytes = input.readAllBytes();
            if (java27) {
                // Static analysis must support the class-file versions ArchUnit imports, without
                // loading those classes on the Java 17/25 JVM running the packaged probe.
                bytes[6] = 0;
                bytes[7] = 71;
            }
            output.putNextEntry(new JarEntry("BOOT-INF/classes/" + path));
            output.write(bytes);
            output.closeEntry();
        }
    }
}
