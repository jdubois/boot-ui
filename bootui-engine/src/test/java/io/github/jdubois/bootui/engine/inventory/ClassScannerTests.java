package io.github.jdubois.bootui.engine.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.inventory.CodeInventoryHistory.KeptRun;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassScannerTests {

    @TempDir
    Path temp;

    private static Map<String, byte[]> service(String greeting, String extraMethod) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.OrderService", """
                package shop;
                public class OrderService {
                    public String greet(String name) { return "%s" + name; }
                    public int total(int a, int b) { return a + b; }
                    %s
                }
                """.formatted(greeting, extraMethod));
        sources.put("shop.web.OrderController", """
                package shop.web;
                public class OrderController {
                    private final shop.OrderService service = new shop.OrderService();
                    public String get() { return service.greet("x"); }
                }
                """);
        return Compiler.compile(sources, "-g");
    }

    private Path classes(Map<String, byte[]> compiled) throws Exception {
        Path root = temp.resolve("target/classes");
        Compiler.write(root, compiled);
        return root;
    }

    private static URLClassLoader loader(Path... roots) throws Exception {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            urls[i] = roots[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    private static ClassScanner.Result scan(ClassLoader loader, List<String> packages, int max, ScanCache cache) {
        return ClassScanner.scan(loader, packages, max, Duration.ofSeconds(30), cache, System::nanoTime, List.of());
    }

    @Test
    void scansTheClaimedPackagesOfDirectoriesAndJarsButNotTestRoots() throws Exception {
        Path root = classes(service("Hello, ", ""));
        Path tests = temp.resolve("target/test-classes");
        Compiler.write(
                tests,
                Compiler.compile(Map.of("shop.OrderServiceTests", "package shop; public class OrderServiceTests {}")));
        Path jar = temp.resolve("lib/shop-extra.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("shop/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("shop/extra/"));
            out.closeEntry();
            Map<String, byte[]> extra = Compiler.compile(
                    Map.of("shop.extra.Coupon", "package shop.extra; public class Coupon { int off() { return 5; } }"));
            out.putNextEntry(new JarEntry("shop/extra/Coupon.class"));
            out.write(extra.get("shop.extra.Coupon"));
            out.closeEntry();
        }
        Path other = temp.resolve("other/classes");
        Compiler.write(other, Compiler.compile(Map.of("vendor.Lib", "package vendor; public class Lib {}")));

        try (URLClassLoader loader = loader(root, tests, jar, other)) {
            ClassScanner.Result result = scan(loader, List.of("shop"), 100, null);

            assertThat(result.status()).isEqualTo(ClassScanner.COMPLETE);
            assertThat(result.classes())
                    .containsOnlyKeys("shop.OrderService", "shop.web.OrderController", "shop.extra.Coupon");
            assertThat(result.roots()).hasSize(2).noneMatch(ClassScanner::testRoot);
            assertThat(result.classes().get("shop.extra.Coupon").root()).startsWith("jar:file:");
        }
    }

    @Test
    void skipsMalformedClassFilesAndStopsAtItsLimit() throws Exception {
        Path root = classes(service("Hello, ", ""));
        Files.write(root.resolve("shop/Broken.class"), new byte[] {(byte) 0xCA, (byte) 0xFE, 1, 2});

        try (URLClassLoader loader = loader(root)) {
            ClassScanner.Result all = scan(loader, List.of("shop"), 100, null);
            assertThat(all.skipped()).isEqualTo(1);
            assertThat(all.classes()).hasSize(2);
            // The class it could not parse is neither counted nor compared: the scan is not complete.
            assertThat(all.status()).isEqualTo(ClassScanner.PARTIAL);
            assertThat(all.reason()).contains("1 class file could not be parsed");
            assertThat(KeptRun.of(1, all).covers("shop.Broken")).isFalse();
            assertThat(KeptRun.of(1, all).covers("shop.OrderService")).isTrue();

            ClassScanner.Result limited = scan(loader, List.of("shop"), 1, null);
            assertThat(limited.status()).isEqualTo(ClassScanner.PARTIAL);
            assertThat(limited.reason()).contains("bootui.code-inventory.max-classes");
            assertThat(limited.classes()).hasSize(1);
        }
    }

    @Test
    void aStoppedClockMakesTheScanPartial() throws Exception {
        Path root = classes(service("Hello, ", ""));
        long[] now = {0};
        try (URLClassLoader loader = loader(root)) {
            ClassScanner.Result result = ClassScanner.scan(
                    loader, List.of("shop"), 100, Duration.ofMillis(1), null, () -> now[0] += 2_000_000, List.of());

            assertThat(result.status()).isEqualTo(ClassScanner.PARTIAL);
            assertThat(result.reason()).contains("scan-timeout");
        }
    }

    @Test
    void reusesTheHashesOfUnchangedFiles() throws Exception {
        Path root = classes(service("Hello, ", ""));
        ScanCache cache = new ScanCache(100);
        try (URLClassLoader loader = loader(root)) {
            ClassScanner.Result first = scan(loader, List.of("shop"), 100, cache);
            ClassScanner.Result second = scan(loader, List.of("shop"), 100, cache);

            assertThat(first.reused()).isZero();
            assertThat(second.reused()).isEqualTo(2);
            assertThat(second.classes().get("shop.OrderService").hashes())
                    .isEqualTo(first.classes().get("shop.OrderService").hashes());
        }
    }

    @Test
    void twoRunsListExactlyTheChangedAddedAndRemovedMethods() throws Exception {
        CodeInventoryHistory history = new CodeInventoryHistory(null, new ScanCache(100));
        Path root = classes(service("Hello, ", "public int removed() { return 1; }"));
        try (URLClassLoader loader = loader(root)) {
            ClassScanner.Result first = scan(loader, List.of("shop"), 100, history.cache());
            history.record("dev:shop", KeptRun.of(1, first));
            assertThat(CodeChanges.diff(first, history.previous("dev:shop", 1))).isEqualTo(CodeChanges.none());
        }
        // The developer edits greet, removes one method and adds another; only OrderService is recompiled.
        Map<String, byte[]> edited = service("Hi, ", "public int added() { return 2; }");
        Path file = root.resolve("shop/OrderService.class");
        Files.write(file, edited.get("shop.OrderService"));
        Files.setLastModifiedTime(
                file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));

        try (URLClassLoader loader = loader(root)) {
            ClassScanner.Result second = scan(loader, List.of("shop"), 100, history.cache());
            assertThat(second.reused()).isEqualTo(1);
            history.record("dev:shop", KeptRun.of(2, second));

            CodeChanges changes = CodeChanges.diff(second, history.previous("dev:shop", 2));

            assertThat(changes.previousRun()).isTrue();
            assertThat(changes.partial()).isFalse();
            assertThat(changes.kinds())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "shop.OrderService#greet(Ljava/lang/String;)Ljava/lang/String;", CodeChanges.CHANGED,
                            "shop.OrderService#added()I", CodeChanges.ADDED));
            assertThat(changes.removed()).isEqualTo(1);
            assertThat(history.mixed("dev:shop", 2)).isFalse();
        }
    }

    @Test
    void comparesOnlyClassesBothScansCovered() throws Exception {
        CodeInventoryHistory history = new CodeInventoryHistory(null, new ScanCache(100));
        Path root = classes(service("Hello, ", ""));
        try (URLClassLoader loader = loader(root)) {
            // The first scan stopped after one class: the other is neither added nor compared.
            ClassScanner.Result first = scan(loader, List.of("shop"), 1, null);
            history.record("dev:shop", KeptRun.of(1, first));
            ClassScanner.Result second = scan(loader, List.of("shop"), 100, null);

            CodeChanges changes = CodeChanges.diff(second, history.previous("dev:shop", 2));

            assertThat(changes.kinds()).isEmpty();
            assertThat(changes.partial()).isTrue();
        }
    }

    @Test
    void anotherApplicationsRunBetweenTwoRunsMarksThemMixed() throws Exception {
        CodeInventoryHistory history = new CodeInventoryHistory(null, new ScanCache(100));
        Path root = classes(service("Hello, ", ""));
        try (URLClassLoader loader = loader(root)) {
            ClassScanner.Result scan = scan(loader, List.of("shop"), 100, null);
            history.record("dev:shop", KeptRun.of(1, scan));
            history.record("test:other", KeptRun.of(2, scan));

            assertThat(history.mixed("dev:shop", 3)).isTrue();
            history.record("dev:shop", KeptRun.of(3, scan));
            assertThat(history.mixed("dev:shop", 3)).isTrue();
            assertThat(history.previous("dev:shop", 3).generation()).isEqualTo(1);
            assertThat(history.previous("test:other", 2)).isNull();
        }
    }

    @Test
    void anUnparsableClassIsNeverComparedNorCountedAsRemoved() throws Exception {
        CodeInventoryHistory history = new CodeInventoryHistory(null, new ScanCache(100));
        Path root = classes(service("Hello, ", ""));
        try (URLClassLoader loader = loader(root)) {
            history.record("dev:shop", KeptRun.of(1, scan(loader, List.of("shop"), 100, null)));
        }
        // OrderController's class file is now unreadable, as when a build half-wrote it.
        Files.write(root.resolve("shop/web/OrderController.class"), new byte[] {(byte) 0xCA, (byte) 0xFE, 1});
        try (URLClassLoader loader = loader(root)) {
            ClassScanner.Result second = scan(loader, List.of("shop"), 100, null);

            CodeChanges changes = CodeChanges.diff(second, history.previous("dev:shop", 2));

            assertThat(changes.kinds()).isEmpty();
            assertThat(changes.partial()).isTrue();
            assertThat(changes.removed())
                    .as("unknown, not the unreadable class's methods")
                    .isEqualTo(-1);
        }
    }

    @Test
    void aJarEntryRewrittenWithTheSameSizeAndTimeIsParsedAgain() throws Exception {
        Path jar = temp.resolve("lib/shop.jar");
        Files.createDirectories(jar.getParent());
        // Same length, same entry time (DOS times are two-second precise), other bytes.
        writeJar(jar, service("Hello, ", "").get("shop.OrderService"), 1_600_000_000_000L);
        ScanCache cache = new ScanCache(100);
        ClassScanner.Result first;
        try (URLClassLoader loader = loader(jar)) {
            first = scan(loader, List.of("shop"), 100, cache);
        }
        byte[] edited = service("Hallo, ", "").get("shop.OrderService");
        assertThat(edited).hasSameSizeAs(service("Hello, ", "").get("shop.OrderService"));
        writeJar(jar, edited, 1_600_000_000_000L);
        try (URLClassLoader loader = loader(jar)) {
            ClassScanner.Result second = scan(loader, List.of("shop"), 100, cache);

            assertThat(second.reused()).isZero();
            assertThat(second.classes().get("shop.OrderService").hashes())
                    .isNotEqualTo(first.classes().get("shop.OrderService").hashes());
        }
    }

    private static void writeJar(Path jar, byte[] orderService, long time) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry directory = new JarEntry("shop/");
            directory.setTime(time);
            out.putNextEntry(directory);
            out.closeEntry();
            JarEntry entry = new JarEntry("shop/OrderService.class");
            entry.setTime(time);
            out.putNextEntry(entry);
            out.write(orderService);
            out.closeEntry();
        }
    }

    @Test
    void aClassPathJarWithoutDirectoryEntriesIsFoundAndCheckedOncePerChange() throws Exception {
        Path jar = temp.resolve("lib/flat.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            // No shop/ entry: the class loader's getResources("shop") cannot find this jar.
            out.putNextEntry(new JarEntry("shop/extra/Coupon.class"));
            out.write(Compiler.compile(Map.of(
                            "shop.extra.Coupon", "package shop.extra; public class Coupon { int off() { return 5; } }"))
                    .get("shop.extra.Coupon"));
            out.closeEntry();
        }
        Path other = temp.resolve("lib/other.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(other))) {
            out.putNextEntry(new JarEntry("vendor/Lib.class"));
            out.write(new byte[] {1});
            out.closeEntry();
        }
        ScanCache cache = new ScanCache(100);
        List<String> classPath = List.of(jar.toString(), other.toString());
        try (URLClassLoader loader = loader(jar, other)) {
            ClassScanner.Result result = ClassScanner.scan(
                    loader, List.of("shop"), 100, Duration.ofSeconds(30), cache, System::nanoTime, classPath);

            assertThat(result.classes()).containsOnlyKeys("shop.extra.Coupon");
            assertThat(cache.jars()).as("each class-path jar's answer is kept").isEqualTo(2);
            ClassScanner.scan(loader, List.of("shop"), 100, Duration.ofSeconds(30), cache, System::nanoTime, classPath);
            assertThat(cache.jars()).as("and reused while the jar is unchanged").isEqualTo(2);
        }
    }

    @Test
    void findingTheRootsStopsWhenTheScanIsCancelled() throws Exception {
        Path root = classes(service("Hello, ", ""));
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        ClassLoader counting = new URLClassLoader(new URL[] {root.toUri().toURL()}, null) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
                asked.incrementAndGet();
                return super.getResources(name);
            }
        };

        ClassScanner.Result result = ClassScanner.scan(
                counting,
                List.of("shop", "other", "third"),
                100,
                Duration.ofSeconds(30),
                null,
                System::nanoTime,
                List.of(root.toString()),
                () -> true);

        assertThat(asked.get()).isZero();
        assertThat(result.status()).isEqualTo(ClassScanner.PARTIAL);
        assertThat(result.reason()).contains("cancelled");
        assertThat(result.classes()).isEmpty();
    }

    @Test
    void aLateScanOfAnOlderRunNeverReordersTheHistory() throws Exception {
        CodeInventoryHistory history = new CodeInventoryHistory(null, new ScanCache(100));
        Path root = classes(service("Hello, ", ""));
        try (URLClassLoader loader = loader(root)) {
            ClassScanner.Result scan = scan(loader, List.of("shop"), 100, null);
            assertThat(history.record("dev:shop", KeptRun.of(4, scan))).isTrue();
            assertThat(history.record("dev:shop", KeptRun.of(5, scan))).isTrue();
            long version = history.version();

            assertThat(history.record("dev:shop", KeptRun.of(3, scan))).isFalse();

            assertThat(history.version()).isEqualTo(version);
            assertThat(history.previous("dev:shop", 5).generation()).isEqualTo(4);
            assertThat(history.previous("dev:shop", 6).generation()).isEqualTo(5);
        }
    }

    @Test
    void aRenumberedLambdaOrAnonymousClassIsNotAChange() throws Exception {
        String noisy = """
                package shop;
                public class Noisy {
                    %1$s
                    public Runnable first() {
                        %2$s
                        return () -> System.out.println("first");
                    }
                    public Object anonymous() {
                        return new Object() {
                            @Override
                            public String toString() { return "anonymous"; }
                        };
                    }
                }
                """;
        String zero = """
                public Object zero() {
                    return new Object() {
                        @Override
                        public String toString() { return "zero"; }
                    };
                }
                """;
        String early = "Runnable early = () -> System.out.println(\"early\"); early.run();";
        CodeInventoryHistory history = new CodeInventoryHistory(null, new ScanCache(100));
        Path root = temp.resolve("noisy/classes");
        Compiler.write(root, Compiler.compile(Map.of("shop.Noisy", noisy.formatted("", "")), "-g"));
        try (URLClassLoader loader = loader(root)) {
            history.record("dev:shop", KeptRun.of(1, scan(loader, List.of("shop"), 100, null)));
        }
        Compiler.write(root, Compiler.compile(Map.of("shop.Noisy", noisy.formatted(zero, early)), "-g"));
        try (URLClassLoader loader = loader(root)) {
            ClassScanner.Result second = scan(loader, List.of("shop"), 100, null);
            assertThat(second.classes()).containsKeys("shop.Noisy$1", "shop.Noisy$2");

            CodeChanges changes = CodeChanges.diff(second, history.previous("dev:shop", 2));

            // What the developer wrote: zero(), its anonymous class (now Noisy$1), and first()'s new lambda. The
            // renumbered lambda (now lambda$first$1) and anonymous class (now Noisy$2) are not changes.
            assertThat(changes.kinds())
                    .containsEntry("shop.Noisy#zero()Ljava/lang/Object;", CodeChanges.ADDED)
                    .containsEntry("shop.Noisy#first()Ljava/lang/Runnable;", CodeChanges.CHANGED)
                    .containsEntry("shop.Noisy$1#toString()Ljava/lang/String;", CodeChanges.CHANGED)
                    .containsEntry("shop.Noisy#lambda$first$0()V", CodeChanges.CHANGED)
                    .doesNotContainKeys(
                            "shop.Noisy#anonymous()Ljava/lang/Object;",
                            "shop.Noisy#lambda$first$1()V",
                            "shop.Noisy$2#toString()Ljava/lang/String;");
            assertThat(changes.removed())
                    .as("the renumbered methods are matched, not removed")
                    .isZero();
        }
    }

    @Test
    void keepsAtMostOneMegabyteOfPairs() {
        assertThat(CodeInventoryHistory.MAX_PAIRS * 12L).isLessThanOrEqualTo(CodeInventoryHistory.MAX_PAIR_BYTES);
    }
}
