package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shared deny-list both {@link io.github.jdubois.bootui.engine.exceptions.ExceptionStore}'s
 * exception location and {@code SqlTraceRecorder}'s SQL call-site capture rely on to pick the first
 * "application frame" out of a stack trace.
 */
class StackFramePrefixesTests {

    private static final String BOOTUI_ROOT_PACKAGE = "io.github.jdubois.bootui";

    /** Second-level packages that hold the sample applications, which are application code on purpose. */
    private static final Set<String> SAMPLE_PACKAGES = Set.of("sample", "webfluxsample");

    private static final Path BOOTUI_SOURCE_PATH = Path.of("java", "io", "github", "jdubois", "bootui");

    @Test
    void treatsNullAsNotApplicationCode() {
        assertThat(StackFramePrefixes.isFrameworkClass(null)).isTrue();
    }

    @Test
    void recognizesJdkAndCommonFrameworkPackages() {
        assertThat(StackFramePrefixes.isFrameworkClass("java.sql.Statement")).isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("javax.sql.DataSource")).isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("jakarta.persistence.EntityManager"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("jdk.internal.reflect.NativeMethodAccessorImpl"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("sun.reflect.GeneratedMethodAccessor1"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("com.sun.proxy.$Proxy42"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.springframework.web.servlet.DispatcherServlet"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.apache.tomcat.util.net.NioEndpoint"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.hibernate.engine.spi.SessionImpl"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("com.zaxxer.hikari.pool.HikariPool"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.junit.jupiter.engine.execution.ExecutableInvoker"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.netty.channel.nio.NioEventLoop"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.vertx.core.impl.ContextImpl"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.quarkus.runtime.Application"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.jboss.threads.EnhancedQueueExecutor"))
                .isTrue();
    }

    @Test
    void recognizesEveryBootUiModulePackageAsNotApplicationCode() {
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.core.dto.SqlTraceEntryDto"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.spi.BeanProvider"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass(
                        "io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass(
                        "io.github.jdubois.bootui.quarkus.sqltrace.BootUiHibernateStatementInspector"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.quarkus.it.SomeIntegrationTest"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.client.BootUiClient"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.cli.BootUiCli"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.conformance.BootUiApiContractCatalog"))
                .isTrue();
    }

    @Test
    void treatsTheSampleApplicationsAsApplicationCode() {
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.sample.catalog.SampleCatalog"))
                .isFalse();
        assertThat(StackFramePrefixes.isFrameworkClass(
                        "io.github.jdubois.bootui.sample.restclient.SampleRestClientCaptureResource"))
                .isFalse();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.webfluxsample.notes.NoteRepository"))
                .isFalse();
    }

    @Test
    void matchesBootUiModulesOnWholePackageSegmentsOnly() {
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.engineering.Report"))
                .isFalse();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.clients.OrderClient"))
                .isFalse();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.Application"))
                .isFalse();
    }

    @Test
    void treatsAnythingElseAsApplicationCode() {
        assertThat(StackFramePrefixes.isFrameworkClass("com.example.app.OrderRepository"))
                .isFalse();
        assertThat(StackFramePrefixes.isFrameworkClass("com.acme.billing.InvoiceService"))
                .isFalse();
    }

    /**
     * Guards the explicit module list against drift: every {@code io.github.jdubois.bootui.<package>} in the
     * reactor must be either a listed BootUI module, whose frames are never a call site, or a known sample
     * application, whose frames are. A new module package that is missing from the list would otherwise
     * silently surface BootUI's own frames as the host application's call sites.
     */
    @Test
    void classifiesEveryBootUiPackageInTheReactor() {
        Path root = repositoryRoot();
        Set<String> packages = bootUiSecondLevelPackages(root);

        assertThat(packages)
                .as("BootUI packages found under %s", root)
                .contains("engine", "autoconfigure", "quarkus", "sample");
        for (String name : packages) {
            String probe = BOOTUI_ROOT_PACKAGE + "." + name + ".Probe";
            if (SAMPLE_PACKAGES.contains(name)) {
                assertThat(StackFramePrefixes.isFrameworkClass(probe))
                        .as("sample package %s.%s must count as application code", BOOTUI_ROOT_PACKAGE, name)
                        .isFalse();
            } else {
                assertThat(StackFramePrefixes.isFrameworkClass(probe))
                        .as(
                                "package %s.%s is neither a BootUI module listed in"
                                        + " StackFramePrefixes.BOOTUI_MODULE_PREFIXES nor a sample package listed in"
                                        + " StackFramePrefixesTests.SAMPLE_PACKAGES",
                                BOOTUI_ROOT_PACKAGE, name)
                        .isTrue();
            }
        }
    }

    private static Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path candidate : new Path[] {workingDirectory, workingDirectory.getParent()}) {
            if (candidate != null
                    && Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("bootui-engine"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("The BootUI repository root could not be located from " + workingDirectory);
    }

    private static Set<String> bootUiSecondLevelPackages(Path root) {
        Set<String> packages = new TreeSet<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("bootui-"))
                    .toList()) {
                Files.walkFileTree(module, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
                            throws IOException {
                        String name = dir.getFileName().toString();
                        if (name.startsWith(".") || name.equals("node_modules") || name.equals("target")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (dir.endsWith(BOOTUI_SOURCE_PATH)) {
                            try (Stream<Path> children = Files.list(dir)) {
                                for (Path child : children.toList()) {
                                    if (Files.isDirectory(child) && containsJavaSource(child)) {
                                        packages.add(child.getFileName().toString());
                                    } else if (child.toString().endsWith(".java")) {
                                        // A class directly in the root package matches no module prefix.
                                        packages.add(child.getFileName().toString());
                                    }
                                }
                            }
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return packages;
    }

    private static boolean containsJavaSource(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.anyMatch(path -> path.toString().endsWith(".java"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
