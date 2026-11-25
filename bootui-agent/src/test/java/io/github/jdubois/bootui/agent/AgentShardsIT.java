package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the actual publication layout, not only synthetic shard fixtures. */
class AgentShardsIT {
    private static final String PREFIX = "io/github/jdubois/bootui/agent/shaded/bytebuddy/";

    @TempDir
    Path directory;

    @Test
    void theBuildPackerIsDeterministicAndRejectsOversizedClasses() throws Exception {
        Path layout = directory.resolve("layout");
        Path classes = layout.resolve(PREFIX);
        Files.createDirectories(classes);
        // A base name sorts before its nested name after removing ".class", not before it in filesystem order.
        Files.write(classes.resolve("Alpha.class"), new byte[70_000]);
        Files.write(classes.resolve("Alpha$Nested.class"), new byte[70_000]);
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        assertThat(pack(layout, first)).isZero();
        assertThat(pack(layout, second)).isZero();
        for (String file : List.of("index", "0.bin", "1.bin")) {
            assertThat(Files.readAllBytes(first.resolve(file)))
                    .containsExactly(Files.readAllBytes(second.resolve(file)));
        }
        assertThat(Files.readString(first.resolve("index")))
                .contains("Alpha\t0\t0\t70000\nAlpha$Nested\t1\t0\t70000\n");
        Files.write(classes.resolve("Huge.class"), new byte[192 * 1024 + 1]);
        assertThat(pack(layout, directory.resolve("bad"))).isNotZero();
        assertThat(Files.readString(directory.resolve("pack.log"))).contains("Oversized or empty Byte Buddy class");
        Files.delete(classes.resolve("Huge.class"));
        Files.write(classes.resolve("Bad.Name.class"), new byte[1]);
        assertThat(pack(layout, directory.resolve("invalid-name"))).isNotZero();
        assertThat(Files.readString(directory.resolve("pack.log"))).contains("Unsupported Byte Buddy class name");
    }

    private int pack(Path layout, Path output) throws Exception {
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "src/build/java/PackAgentShards.java",
                        layout.toString(),
                        output.toString())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("pack.log").toFile())
                .start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS))
                    .as("build-only source launcher")
                    .isTrue();
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void everyPackedClassAndResourceMatchesTheShadedInputWithoutLoadingClasses() throws Exception {
        try (JarFile original = new JarFile("target/bootui-agent-shaded.jar");
                CountingJarFile packed = new CountingJarFile(ChildJvm.AGENT)) {
            AgentClassLoader loader =
                    new AgentClassLoader(packed, ChildJvm.AGENT.toString(), ClassLoader.getPlatformClassLoader());
            assertThat(loader.getResource("not/ByteBuddy.class")).isNull();
            assertThat(loader.loadClass("java.lang.String")).isSameAs(String.class);
            assertThat(packed.payloadReads).isZero();
            int classes = 0;
            for (JarEntry entry : Collections.list(original.entries())) {
                if (!entry.getName().startsWith(PREFIX) || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream expected = original.getInputStream(entry);
                        InputStream actual = loader.getResource(entry.getName()).openStream()) {
                    assertThat(actual.readAllBytes()).as(entry.getName()).containsExactly(expected.readAllBytes());
                }
                classes++;
            }
            assertThat(classes).isGreaterThan(2000);
            assertThat(packed.payloadReads).isGreaterThan(1);
            assertThat(cacheBytes(loader)).isLessThanOrEqualTo(4L * 256 * 1024);
            assertThat(Files.size(ChildJvm.AGENT)).isLessThan(2_900_000);
        }
    }

    @Test
    void warmupDefinesTheSameClassesAsFlatPackagingWithBoundedInflation() throws Exception {
        Path flat = flatAgent();
        int expected;
        try (JarFile jar = new JarFile(flat.toFile())) {
            expected = new AgentClassLoader(jar, flat.toString(), ClassLoader.getPlatformClassLoader())
                    .define(PREFIX.replace('/', '.'));
        }
        try (JarFile jar = new JarFile(ChildJvm.AGENT.toFile())) {
            AgentClassLoader loader =
                    new AgentClassLoader(jar, ChildJvm.AGENT.toString(), ClassLoader.getPlatformClassLoader());
            assertThat(loader.define(PREFIX.replace('/', '.'))).isEqualTo(expected);
            assertThat(expected).isGreaterThan(2000);
            long inflations = counter(loader, "inflations");
            long inflatedBytes = counter(loader, "inflatedBytes");
            assertThat(inflatedBytes)
                    .as("sorted warm-up must not thrash the bounded cache")
                    .isLessThan(32L * 1024 * 1024);
            assertThat(cacheBytes(loader)).isLessThanOrEqualTo(4L * 256 * 1024);
            assertThat(loader.define(PREFIX.replace('/', '.'))).isEqualTo(expected);
            assertThat(counter(loader, "inflations"))
                    .as("only unavailable optional dispatchers may retry; defined classes need no shard bytes")
                    .isLessThanOrEqualTo(inflations + 4);
            System.out.printf(
                    "Packed Byte Buddy: %d classes, %d inflations, %d inflated bytes, %d cached bytes%n",
                    expected, inflations, inflatedBytes, cacheBytes(loader));
        }
        List<Long> flatTimes = new ArrayList<>();
        List<Long> packedTimes = new ArrayList<>();
        for (int iteration = 0; iteration < 7; iteration++) {
            // Alternate the order so JIT/cache warm-up does not always favor the same layout.
            if (iteration % 2 == 0) {
                flatTimes.add(warm(flat));
                packedTimes.add(warm(ChildJvm.AGENT));
            } else {
                packedTimes.add(warm(ChildJvm.AGENT));
                flatTimes.add(warm(flat));
            }
        }
        Collections.sort(flatTimes);
        Collections.sort(packedTimes);
        System.out.printf(
                "Byte Buddy define-only median, same JVM (not first-claim latency): flat=%.2f ms, packed=%.2f ms%n",
                flatTimes.get(3) / 1e6, packedTimes.get(3) / 1e6);
    }

    @Test
    void coldClaimsWorkInSmallHeapsForBothLayoutsWithoutEagerDormantLoading() throws Exception {
        Path flat = flatAgent();
        List<Long> flatTimes = new ArrayList<>();
        List<Long> packedTimes = new ArrayList<>();
        for (int iteration = 0; iteration < 5; iteration++) {
            for (Path agent : iteration % 2 == 0 ? List.of(flat, ChildJvm.AGENT) : List.of(ChildJvm.AGENT, flat)) {
                long start = System.nanoTime();
                ChildJvm.Output output =
                        ChildJvm.run(List.of(ChildJvm.javaAgent(agent), "-Xmx64m"), "self-test", "inventory");
                long elapsed = System.nanoTime() - start;
                assertThat(output.exitCode()).as(output.toString()).isZero();
                assertThat(output.value("SENSOR_inventory"))
                        .as(output.toString())
                        .contains("state=installed", "selfTestPassed=true");
                (agent.equals(flat) ? flatTimes : packedTimes).add(elapsed);
            }
        }
        Collections.sort(flatTimes);
        Collections.sort(packedTimes);
        System.out.printf(
                "Cold JVM + inventory claim + self-test median, -Xmx64m: flat=%.2f ms, packed=%.2f ms%n",
                flatTimes.get(2) / 1e6, packedTimes.get(2) / 1e6);
        ChildJvm.Output dormant = ChildJvm.run(
                List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-Xmx64m", "-Xlog:class+load=info"), "claim-only");
        assertThat(dormant.exitCode()).as(dormant.toString()).isZero();
        assertThat(dormant.value("INSTALLER")).isEqualTo("{state=none}");
        assertThat(dormant.text()).doesNotContain("shaded.bytebuddy", "AgentClassLoader$ShardStore");
    }

    @Test
    void corruptOrMissingPackedMetadataFailsTheSensorWithoutFailingTheHost() throws Exception {
        for (boolean missingIndex : List.of(false, true)) {
            Path damaged = directory.resolve(missingIndex ? "missing-index.jar" : "corrupt-shard.jar");
            try (JarFile original = new JarFile(ChildJvm.AGENT.toFile());
                    JarOutputStream out = new JarOutputStream(Files.newOutputStream(damaged))) {
                for (JarEntry entry : Collections.list(original.entries())) {
                    if (missingIndex && entry.getName().equals("inst/byte-buddy/index")) {
                        continue;
                    }
                    out.putNextEntry(new JarEntry(entry.getName()));
                    try (InputStream in = original.getInputStream(entry)) {
                        byte[] bytes = in.readAllBytes();
                        if (!missingIndex && entry.getName().equals("inst/byte-buddy/0.bin")) {
                            bytes[0] ^= 1;
                        }
                        out.write(bytes);
                    }
                    out.closeEntry();
                }
            }
            ChildJvm.Output output = ChildJvm.run(List.of(ChildJvm.javaAgent(damaged), "-Xmx64m"), "shard-claim");
            assertThat(output.exitCode()).as(output.toString()).isZero();
            assertThat(output.value("APPLICATION_ALIVE")).isEqualTo("hello shards");
            if (missingIndex) {
                assertThat(output.value("STATUS")).contains("sensors=[]", "agent claim failed:");
            } else {
                assertThat(output.value("SENSOR_inventory"))
                        .as(output.toString())
                        .contains("state=failed", "transformed=0", "Cannot warm the packed Byte Buddy classes");
            }
            try (JarFile jar = new JarFile(damaged.toFile())) {
                AgentClassLoader loader =
                        new AgentClassLoader(jar, damaged.toString(), ClassLoader.getPlatformClassLoader());
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> loader.define(PREFIX.replace('/', '.')))
                        .isInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage(
                                missingIndex
                                        ? "Missing agent payload: inst/byte-buddy/index"
                                        : "Corrupt agent shard: 0");
            }
        }
    }

    private static long warm(Path path) throws Exception {
        try (JarFile jar = new JarFile(path.toFile())) {
            long start = System.nanoTime();
            new AgentClassLoader(jar, path.toString(), ClassLoader.getPlatformClassLoader())
                    .define(PREFIX.replace('/', '.'));
            return System.nanoTime() - start;
        }
    }

    private Path flatAgent() throws Exception {
        Path flat = directory.resolve("flat-agent.jar");
        try (JarFile packed = new JarFile(ChildJvm.AGENT.toFile());
                JarFile original = new JarFile("target/bootui-agent-shaded.jar");
                JarOutputStream out = new JarOutputStream(Files.newOutputStream(flat))) {
            out.setLevel(9);
            for (JarEntry entry : Collections.list(packed.entries())) {
                if (!entry.getName().startsWith("inst/byte-buddy/")) {
                    out.putNextEntry(new JarEntry(entry.getName()));
                    try (InputStream in = packed.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
            for (JarEntry entry : Collections.list(original.entries())) {
                if (entry.getName().startsWith(PREFIX) && entry.getName().endsWith(".class")) {
                    out.putNextEntry(new JarEntry("inst/"
                            + entry.getName().substring(0, entry.getName().length() - 6) + ".classdata"));
                    try (InputStream in = original.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
        }
        return flat;
    }

    private static Object store(AgentClassLoader loader) throws Exception {
        var field = AgentClassLoader.class.getDeclaredField("shards");
        field.setAccessible(true);
        return field.get(loader);
    }

    private static long counter(AgentClassLoader loader, String name) throws Exception {
        Object store = store(loader);
        var field = store.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(store);
    }

    private static long cacheBytes(AgentClassLoader loader) throws Exception {
        Object store = store(loader);
        var field = store.getClass().getDeclaredField("cache");
        field.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) field.get(store);
        return cache.values().stream()
                .mapToLong(bytes -> ((byte[]) bytes).length)
                .sum();
    }

    private static final class CountingJarFile extends JarFile {
        private int payloadReads;

        private CountingJarFile(Path path) throws Exception {
            super(path.toFile());
        }

        @Override
        public InputStream getInputStream(java.util.zip.ZipEntry entry) throws java.io.IOException {
            if (entry.getName().startsWith("inst/byte-buddy/")) {
                payloadReads++;
            }
            return super.getInputStream(entry);
        }
    }
}
