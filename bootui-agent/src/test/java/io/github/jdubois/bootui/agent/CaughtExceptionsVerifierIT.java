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
 * on 17, 21, and the newest): every class of Spring Framework, Hibernate ORM, Jackson, Netty, Vert.x, and Quarkus is
 * defined and linked (so verified) without the visit, with it alone, and with it beneath an advice that checks every
 * frame, each in its own class loader. A class that links without the visit must link with it; a class the advice
 * accepts alone must be accepted with the visit too, except where the visit's frame has a {@code TOP} parameter, which
 * the agent handles by transforming that class again without the visit, and which must stay rare.
 */
class CaughtExceptionsVerifierIT {

    /** The jars whose classes are transformed, by file-name prefix. */
    private static final List<String> TARGETS = List.of(
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
            "arc-");

    /** The most classes of all targets the advice may refuse only with the visit, per thousand. */
    private static final int MAX_REFUSED_PER_THOUSAND = 5;

    @Test
    void everyFrameworkClassThatVerifiesWithoutTheVisitVerifiesWithIt() throws Exception {
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
            if (TARGETS.stream().anyMatch(file.getName()::startsWith)) {
                targets.add(file);
            } else {
                others.add(file.toURI().toURL());
            }
        }
        assertThat(targets).as("the frameworks' jars on the test class path").hasSizeGreaterThanOrEqualTo(15);

        Map<String, byte[]> classes = classes(targets);
        assertThat(classes).as("classes read").hasSizeGreaterThan(10_000);
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
        assertThat(broken).as("classes linking without the visit but not with it").isEmpty();
        assertThat(sites).as("handlers instrumented").isGreaterThan(5_000);

        // Beneath an advice checking every frame, as the code paths' does: never a verifier failure, and refusals rare.
        int refused = 0;
        List<String> refusals = new ArrayList<>();
        Map<String, byte[]> advised = new LinkedHashMap<>();
        Map<String, byte[]> both = new LinkedHashMap<>();
        ClassFileLocator locator = new ClassFileLocator.Simple(classes);
        ClassFileLocator withJdk = new ClassFileLocator.Compound(
                locator, ClassFileLocator.ForClassLoader.of(new URLClassLoader(others.toArray(new URL[0]), null)));
        TypePool pool = TypePool.Default.WithLazyResolution.of(withJdk);
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            byte[] alone = transform(entry.getKey(), withJdk, pool, false);
            if (alone == null) {
                continue;
            }
            advised.put(entry.getKey(), alone);
            byte[] together = transform(entry.getKey(), withJdk, pool, true);
            if (together == null) {
                refused++;
                if (refusals.size() < 20) {
                    refusals.add(entry.getKey());
                }
                // The agent transforms such a class again without the visit.
                together = alone;
            }
            both.put(entry.getKey(), together);
        }
        Map<String, String> advisedControl = link(advised, others, Function.identity());
        Map<String, String> advisedVisited = link(both, others, Function.identity());
        List<String> brokenBeneath = new ArrayList<>();
        for (Map.Entry<String, String> entry : advisedVisited.entrySet()) {
            if (!advisedControl.containsKey(entry.getKey())) {
                brokenBeneath.add(entry.getKey() + ": " + entry.getValue());
            }
        }
        System.out.printf(
                "Beneath an advice: %d classes advised, %d refused only with the visit %s%n",
                advised.size(), refused, refusals);
        assertThat(brokenBeneath).as("advised classes linking without the visit but not with it").isEmpty();
        assertThat(refused * 1000L)
                .as("classes refused only with the visit: %s", refusals)
                .isLessThanOrEqualTo((long) MAX_REFUSED_PER_THOUSAND * advised.size());
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
                                name.substring(0, name.length() - ".class".length()).replace('/', '.'),
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
        Map<String, String> failed = new LinkedHashMap<>();
        try (URLClassLoader parent = new URLClassLoader(others.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Defining loader = new Defining(classes, transformation, parent);
            for (String name : classes.keySet()) {
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
                    .visit(Advice.to(ExitAdvice.class).on(isMethod().and(not(isAbstract())).and(not(isNative()))));
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
