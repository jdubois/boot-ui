package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import net.bytebuddy.ByteBuddy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentClassLoaderTests {
    private static final String BUDDY = "io.github.jdubois.bootui.agent.shaded.bytebuddy.";

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

    @Test
    void packedClassesAndEveryResourceApiPreserveTheOriginalBytes() throws Exception {
        byte[] bytes = type("Alpha");
        Path path = packed(List.of("Alpha"), List.of(bytes), null);
        try (JarFile file = new JarFile(path.toFile())) {
            AgentClassLoader loader = new AgentClassLoader(file, path.toString(), null);
            String name = (BUDDY + "Alpha").replace('.', '/') + ".class";
            var url = loader.getResource(name);
            assertThat(url).isNotNull();
            try (var in = url.openStream()) {
                assertThat(in.readAllBytes()).containsExactly(bytes);
            }
            try (var in = loader.getResourceAsStream(name)) {
                assertThat(in.readAllBytes()).containsExactly(bytes);
            }
            assertThat(java.util.Collections.list(loader.getResources(name))).hasSize(1);
            assertThat(loader.loadClass(BUDDY + "Alpha").getClassLoader()).isSameAs(loader);
            assertThat(loader.getResource(name.replace("Alpha", "Absent"))).isNull();
            assertThatThrownBy(() -> loader.loadClass(BUDDY + "Absent")).isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void definesSortedPackedClassesIncludingCrossShardSupertypes() throws Exception {
        var parent =
                new ByteBuddy().subclass(Object.class).name(BUDDY + "ZParent").make();
        Class<?> loadedParent = parent.load(getClass().getClassLoader()).getLoaded();
        byte[] child = new ByteBuddy()
                .subclass(loadedParent)
                .name(BUDDY + "AChild")
                .make()
                .getBytes();
        Path path = packed(List.of("AChild", "ZParent"), List.of(child, parent.getBytes()), null);
        try (JarFile file = new JarFile(path.toFile())) {
            AgentClassLoader loader = new AgentClassLoader(file, path.toString(), null);
            assertThat(loader.define(BUDDY)).isEqualTo(2);
            assertThat(loader.loadClass(BUDDY + "AChild").getSuperclass())
                    .isSameAs(loader.loadClass(BUDDY + "ZParent"));
            assertThat(loader.define(BUDDY)).isEqualTo(2);
        }
        CRC32 childCrc = new CRC32();
        childCrc.update(child);
        String index = "BOOTUI-SHARDS-1\n2\n" + child.length + "\t" + childCrc.getValue() + "\n"
                + parent.getBytes().length + "\t0\nAChild\t0\t0\t" + child.length + "\nZParent\t1\t0\t"
                + parent.getBytes().length + "\n";
        Path corrupt = packed(List.of("AChild", "ZParent"), List.of(child, parent.getBytes()), index);
        try (JarFile file = new JarFile(corrupt.toFile())) {
            AgentClassLoader loader = new AgentClassLoader(file, corrupt.toString(), null);
            assertThatThrownBy(() -> loader.define(BUDDY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Corrupt agent shard: 1");
        }
    }

    @Test
    void warmupSkipsMissingExternalApisButNotMissingAgentClasses() throws Exception {
        for (String parentName : List.of("shardfixture.optional.Dispatcher", BUDDY + "MissingParent")) {
            Class<?> parent = new ByteBuddy()
                    .subclass(Object.class)
                    .name(parentName)
                    .make()
                    .load(getClass().getClassLoader())
                    .getLoaded();
            byte[] child = new ByteBuddy()
                    .subclass(parent)
                    .name(BUDDY + "OptionalChild")
                    .make()
                    .getBytes();
            Path path = packed(List.of("OptionalChild"), List.of(child), null);
            try (JarFile file = new JarFile(path.toFile())) {
                AgentClassLoader loader = new AgentClassLoader(file, path.toString(), null);
                if (parentName.startsWith(BUDDY)) {
                    assertThatThrownBy(() -> loader.define(BUDDY)).isInstanceOf(IllegalStateException.class);
                } else {
                    assertThat(loader.define(BUDDY)).isZero();
                }
            }
        }
    }

    @Test
    void aMalformedIndexIsNeverReadByUnrelatedOrDormantLookups() throws Exception {
        Path path = packed(List.of("Alpha"), List.of(type("Alpha")), "invalid");
        try (JarFile file = new JarFile(path.toFile())) {
            AgentClassLoader loader = new AgentClassLoader(file, path.toString(), null);
            assertThat(loader.getResource("not/ByteBuddy.class")).isNull();
            assertThat(loader.loadClass("java.lang.String")).isSameAs(String.class);
            assertThat(loader.define("not.bytebuddy.")).isZero();
            var field = AgentClassLoader.class.getDeclaredField("shards");
            field.setAccessible(true);
            assertThat(field.get(loader)).isNull();
            assertThatThrownBy(() -> loader.loadClass(BUDDY + "Alpha"))
                    .isInstanceOf(ClassNotFoundException.class)
                    .hasRootCauseMessage("Unknown agent shard format");
            String resource = (BUDDY + "Alpha").replace('.', '/') + ".class";
            assertThatThrownBy(() -> loader.getResource(resource)).isInstanceOf(java.io.UncheckedIOException.class);
            assertThatThrownBy(() -> loader.getResourceAsStream(resource))
                    .isInstanceOf(java.io.UncheckedIOException.class);
            assertThatThrownBy(() -> loader.getResources(resource)).isInstanceOf(java.io.UncheckedIOException.class);
        }
    }

    @Test
    void rejectsTruncatedDuplicateUnsortedAndOutOfBoundsIndexes() throws Exception {
        for (String index : List.of(
                "BOOTUI-SHARDS-1\n",
                "BOOTUI-SHARDS-1\n257\n",
                "BOOTUI-SHARDS-1\n1\n262145\t0\n",
                "BOOTUI-SHARDS-1\n1\n1\t0\nAlpha\t0\t0\t2\n",
                "BOOTUI-SHARDS-1\n1\n2\t0\nAlpha\t0\t0\t1\nAlpha\t0\t1\t1\n",
                "BOOTUI-SHARDS-1\n1\n2\t0\nZ\t0\t0\t1\nA\t0\t1\t1\n",
                "BOOTUI-SHARDS-1\n1\n1\t0\n../Alpha\t0\t0\t1\n",
                "BOOTUI-SHARDS-1\n1\n1\t0\nAlpha\t0\t-1\t1\n",
                "BOOTUI-SHARDS-1\n1\n1\t0\nAlpha\t1\t0\t1\n")) {
            Path path = packed(List.of("Alpha"), List.of(type("Alpha")), index);
            try (JarFile file = new JarFile(path.toFile())) {
                AgentClassLoader loader = new AgentClassLoader(file, path.toString(), null);
                assertThatThrownBy(() -> loader.loadClass(BUDDY + "Alpha"))
                        .as(index)
                        .isInstanceOf(ClassNotFoundException.class)
                        .hasCauseInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void rejectsCorruptAndOversizedShardsAndDoesNotMaskWarmupFailure() throws Exception {
        for (String index : List.of(
                "BOOTUI-SHARDS-1\n1\n1\t0\nAlpha\t0\t0\t1\n", "BOOTUI-SHARDS-1\n1\n100\t0\nAlpha\t0\t0\t100\n")) {
            Path path = packed(List.of("Alpha"), List.of(type("Alpha")), index);
            try (JarFile file = new JarFile(path.toFile())) {
                AgentClassLoader loader = new AgentClassLoader(file, path.toString(), null);
                assertThatThrownBy(() -> loader.define(BUDDY))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Cannot warm");
            }
        }
        byte[] bytes = type("Alpha");
        String staleChecksum = "BOOTUI-SHARDS-1\n1\n" + bytes.length + "\t0\nAlpha\t0\t0\t" + bytes.length + "\n";
        Path path = packed(List.of("Alpha"), List.of(bytes), staleChecksum);
        try (JarFile file = new JarFile(path.toFile())) {
            AgentClassLoader loader = new AgentClassLoader(file, path.toString(), null);
            assertThatThrownBy(() -> loader.define(BUDDY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Corrupt agent shard: 0");
        }
    }

    @Test
    void concurrentClassAndResourceReadsDefineEachClassOnceWithABoundedCache() throws Exception {
        List<String> names = new ArrayList<>();
        List<byte[]> bytes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            names.add("Type" + (char) ('A' + i));
            bytes.add(type(names.get(i)));
        }
        Path path = packed(names, bytes, null);
        try (JarFile file = new JarFile(path.toFile())) {
            AgentClassLoader loader = new AgentClassLoader(file, path.toString(), null);
            var pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Void>> work = new ArrayList<>();
                for (int thread = 0; thread < 16; thread++) {
                    work.add(() -> {
                        for (int i = 0; i < names.size(); i++) {
                            assertThat(loader.loadClass(BUDDY + names.get(i)).getClassLoader())
                                    .isSameAs(loader);
                            try (var in =
                                    loader.getResourceAsStream((BUDDY + names.get(i)).replace('.', '/') + ".class")) {
                                assertThat(in.readAllBytes()).containsExactly(bytes.get(i));
                            }
                        }
                        return null;
                    });
                }
                for (var result : pool.invokeAll(work, 30, TimeUnit.SECONDS)) {
                    result.get();
                }
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
            var field = AgentClassLoader.class.getDeclaredField("shards");
            field.setAccessible(true);
            Object store = field.get(loader);
            var cache = store.getClass().getDeclaredField("cache");
            cache.setAccessible(true);
            assertThat((Map<?, ?>) cache.get(store)).hasSize(4);
        }
    }

    private static byte[] type(String suffix) {
        return new ByteBuddy()
                .subclass(Object.class)
                .name(BUDDY + suffix)
                .make()
                .getBytes();
    }

    private Path packed(List<String> names, List<byte[]> bytes, String override) throws IOException {
        Path path = Files.createTempFile(directory, "packed-", ".jar");
        StringBuilder index =
                new StringBuilder("BOOTUI-SHARDS-1\n").append(names.size()).append('\n');
        for (byte[] b : bytes) {
            CRC32 crc = new CRC32();
            crc.update(b);
            index.append(b.length).append('\t').append(crc.getValue()).append('\n');
        }
        for (int i = 0; i < names.size(); i++) {
            index.append(names.get(i))
                    .append('\t')
                    .append(i)
                    .append("\t0\t")
                    .append(bytes.get(i).length)
                    .append('\n');
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("inst/byte-buddy/index"));
            jar.write((override == null ? index.toString() : override).getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            for (int i = 0; i < bytes.size(); i++) {
                jar.putNextEntry(new JarEntry("inst/byte-buddy/" + i + ".bin"));
                jar.write(bytes.get(i));
                jar.closeEntry();
            }
        }
        return path;
    }

    private static void add(JarOutputStream jar, String name) throws IOException {
        byte[] bytes = new ByteBuddy().subclass(Object.class).name(name).make().getBytes();
        jar.putNextEntry(new JarEntry("inst/" + name.replace('.', '/') + ".classdata"));
        OutputStream out = jar;
        out.write(bytes);
        jar.closeEntry();
    }
}
