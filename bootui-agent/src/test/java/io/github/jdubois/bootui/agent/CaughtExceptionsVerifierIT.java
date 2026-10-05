package io.github.jdubois.bootui.agent;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static org.assertj.core.api.Assertions.assertThat;

import bootuicaughtapp.ExitAdvice;
import io.github.jdubois.bootui.agent.bridge.CaughtExceptions;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;

/**
 * The caught-exceptions visit (PLAN-v2 M5-6a) on real frameworks' bytecode, verified by the JVM it runs on (CI runs it
 * on 17, 21, and the newest): every class of Spring Framework, Hibernate ORM, Jackson, Netty, Vert.x, Quarkus, and,
 * compiled by kotlinc, Kotlin's standard library and coroutines (whose state machines reload spilled values into their
 * locals) is
 * defined and linked (so verified) without the visit, with it alone, and with it beneath an advice that checks every
 * frame, each in its own class loader. A class that links without the visit must link with it; a class the advice
 * accepts alone must be accepted with the visit too, except where the visit's frame has a {@code TOP} parameter, which
 * the agent handles by transforming that class again without the visit, and which must stay rare.
 */
class CaughtExceptionsVerifierIT {

    /**
     * The jars whose classes are transformed by default, by file-name prefix: one of each compiler's and framework's
     * style, small enough for the Java 17 build's parallel modules. {@code -Dbootui.agent.verifier-stress=full} adds
     * {@link #FULL}.
     */
    private static final List<String> DEFAULT_TARGETS = List.of(
            "spring-core-",
            "spring-context-",
            "spring-webmvc-",
            "jackson-databind-",
            "netty-handler-",
            "vertx-core-",
            "quarkus-core-",
            "kotlinx-coroutines-core-");

    /** Every jar of the full run, the one that measured 20,522 classes on JDK 17, 21, and 26. */
    private static final List<String> FULL = List.of(
            "spring-core-",
            "spring-beans-",
            "spring-context-",
            "spring-aop-",
            "spring-web-",
            "spring-webmvc-",
            "hibernate-core-",
            "jackson-databind-",
            "jackson-core-",
            "netty-buffer-",
            "netty-common-",
            "netty-transport-",
            "netty-handler-",
            "netty-codec-",
            "vertx-core-",
            "vertx-web-",
            "quarkus-core-",
            "quarkus-vertx-http-",
            "arc-",
            "kotlin-stdlib-",
            "kotlinx-coroutines-core-");

    /** The most classes of all targets the advice may refuse only with the visit, per thousand. */
    private static final int MAX_REFUSED_PER_THOUSAND = 5;

    @Test
    void everyFrameworkClassThatVerifiesWithoutTheVisitVerifiesWithIt() throws Exception {
        boolean full = "full".equals(System.getProperty("bootui.agent.verifier-stress"));
        List<String> prefixes = full ? FULL : DEFAULT_TARGETS;
        List<File> targets = new ArrayList<>();
        List<URL> others = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            File file = new File(entry);
            if (!file.isFile() || !file.getName().endsWith(".jar")) {
                if (file.isDirectory()) {
                    others.add(file.toURI().toURL());
                }
                continue;
            }
            if (prefixes.stream().anyMatch(file.getName()::startsWith)) {
                targets.add(file);
            } else {
                others.add(file.toURI().toURL());
            }
        }
        assertThat(targets)
                .as("the frameworks' jars on the test class path")
                .hasSizeGreaterThanOrEqualTo(full ? 15 : DEFAULT_TARGETS.size());

        Map<String, byte[]> classes = classes(targets);
        assertThat(classes).as("classes read").hasSizeGreaterThan(full ? 10_000 : 3_000);
        int sitesBefore = CaughtExceptions.siteCount();

        Map<String, String> control = link(classes, others, bytes -> bytes);
        Map<String, String> visited = link(classes, others, CaughtExceptionsVisitTests::visitAlone);
        int sites = CaughtExceptions.siteCount() - sitesBefore;

        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> entry : visited.entrySet()) {
            if (!control.containsKey(entry.getKey())) {
                broken.add(entry.getKey() + ": " + entry.getValue());
            }
        }
        System.out.printf(
                "Verifier stress test: %d classes from %d jars, %d link without the visit, %d sites registered%n",
                classes.size(), targets.size(), classes.size() - control.size(), sites);
        assertThat(broken)
                .as("classes linking without the visit but not with it")
                .isEmpty();
        assertThat(sites).as("handlers instrumented").isGreaterThan(full ? 5_000 : 1_000);

        // Beneath an advice checking every frame, as the code paths' does: never a verifier failure, and refusals rare.
        // One transformation per class with the visit; only a class refused with it is transformed without it.
        int refused = 0;
        int advised = 0;
        List<String> refusals = new ArrayList<>();
        // A class the advice refuses by itself stays as it is, so every class its neighbors need is still there.
        Map<String, byte[]> both = new LinkedHashMap<>(classes);
        ClassFileLocator locator = new ClassFileLocator.Simple(classes);
        ClassFileLocator withJdk = new ClassFileLocator.Compound(
                locator, ClassFileLocator.ForClassLoader.of(new URLClassLoader(others.toArray(new URL[0]), null)));
        TypePool pool = TypePool.Default.WithLazyResolution.of(withJdk);
        for (String name : classes.keySet()) {
            byte[] together = transform(name, withJdk, pool, true);
            if (together == null) {
                byte[] alone = transform(name, withJdk, pool, false);
                if (alone == null) {
                    // The advice refuses this class by itself.
                    continue;
                }
                refused++;
                if (refusals.size() < 20) {
                    refusals.add(name);
                }
                // The agent transforms such a class again without the visit.
                together = alone;
            }
            advised++;
            both.put(name, together);
        }
        Map<String, String> beneath = link(both, others, Function.identity());
        List<String> brokenBeneath = new ArrayList<>();
        Map<String, byte[]> suspects = new LinkedHashMap<>();
        for (String name : beneath.keySet()) {
            if (!control.containsKey(name)) {
                // Linked without anything: the advice alone decides whether the visit is to blame.
                byte[] alone = transform(name, withJdk, pool, false);
                if (alone != null) {
                    suspects.put(name, alone);
                }
            }
        }
        Map<String, byte[]> withSuspects = new LinkedHashMap<>(classes);
        withSuspects.putAll(suspects);
        Map<String, String> adviceAlone =
                suspects.isEmpty() ? Map.of() : link(withSuspects, others, Function.identity(), suspects.keySet());
        for (String name : suspects.keySet()) {
            if (!adviceAlone.containsKey(name)) {
                brokenBeneath.add(name + ": " + beneath.get(name));
            }
        }
        System.out.printf(
                "Beneath an advice: %d classes advised, %d refused only with the visit %s%n",
                advised, refused, refusals);
        assertThat(brokenBeneath)
                .as("advised classes linking without the visit but not with it")
                .isEmpty();
        assertThat(refused * 1000L)
                .as("classes refused only with the visit: %s", refusals)
                .isLessThanOrEqualTo((long) MAX_REFUSED_PER_THOUSAND * advised);
    }

    /** The class files of {@code jars}, by binary name: no module or package descriptors, no versioned entries. */
    private static Map<String, byte[]> classes(List<File> jars) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (File jar : jars) {
            try (JarFile file = new JarFile(jar)) {
                Enumeration<JarEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class")
                            || name.startsWith("META-INF/")
                            || name.endsWith("module-info.class")
                            || name.endsWith("package-info.class")) {
                        continue;
                    }
                    try (InputStream in = file.getInputStream(entry)) {
                        classes.putIfAbsent(
                                name.substring(0, name.length() - ".class".length())
                                        .replace('/', '.'),
                                in.readAllBytes());
                    }
                }
            }
        }
        return classes;
    }

    /**
     * Defines and links each class in a fresh class loader holding all of them, transformed: the classes that failed,
     * with why. Linking verifies; {@code getDeclaredMethods} links without initializing.
     */
    private static Map<String, String> link(
            Map<String, byte[]> classes, List<URL> others, Function<byte[], byte[]> transformation) throws IOException {
        return link(classes, others, transformation, classes.keySet());
    }

    /** {@link #link(Map, List, Function)}, checking only {@code names}, their dependencies defined as needed. */
    private static Map<String, String> link(
            Map<String, byte[]> classes,
            List<URL> others,
            Function<byte[], byte[]> transformation,
            java.util.Collection<String> names)
            throws IOException {
        Map<String, String> failed = new LinkedHashMap<>();
        try (URLClassLoader parent =
                new URLClassLoader(others.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Defining loader = new Defining(classes, transformation, parent);
            for (String name : names) {
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    type.getDeclaredMethods();
                } catch (Throwable ex) {
                    failed.put(name, ex.getClass().getName() + ": " + ex.getMessage());
                }
            }
        }
        return failed;
    }

    /** The class beneath the frame-checking advice, with the visit last when asked, or {@code null} when refused. */
    private static byte[] transform(String name, ClassFileLocator locator, TypePool pool, boolean visit) {
        try {
            TypeDescription type = pool.describe(name).resolve();
            var builder = new ByteBuddy()
                    .decorate(type, locator)
                    .visit(Advice.to(ExitAdvice.class)
                            .on(isMethod().and(not(isAbstract())).and(not(isNative()))));
            if (visit) {
                builder = builder.visit(new CaughtExceptionsVisit());
            }
            return builder.make().getBytes();
        } catch (Throwable ex) {
            return null;
        }
    }

    /** Defines the given classes, child first, transformed; every other class comes from the parent. */
    static final class Defining extends ClassLoader {

        private final Map<String, byte[]> classes;
        private final Function<byte[], byte[]> transformation;

        Defining(Map<String, byte[]> classes, Function<byte[], byte[]> transformation, ClassLoader parent) {
            super(parent);
            this.classes = classes;
            this.transformation = transformation;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                byte[] bytes = type == null ? classes.get(name) : null;
                if (bytes != null) {
                    byte[] transformed = transformation.apply(bytes);
                    type = defineClass(name, transformed, 0, transformed.length);
                }
                if (type == null) {
                    if (name.startsWith("bootuicaughtapp.")) {
                        // The advice's own class, which advised code references: the test's copy.
                        return ExitAdvice.class.getClassLoader().loadClass(name);
                    }
                    return super.loadClass(name, resolve);
                }
                return type;
            }
        }
    }
}
