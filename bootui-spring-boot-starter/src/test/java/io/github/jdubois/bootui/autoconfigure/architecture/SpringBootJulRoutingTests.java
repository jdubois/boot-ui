package io.github.jdubois.bootui.autoconfigure.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

class SpringBootJulRoutingTests {

    @TempDir
    Path directory;

    @Test
    void defaultBootLoggingRoutesJulRecordsIntoLogbackWithoutManualBridgeInstallation() throws Exception {
        Path output = directory.resolve("boot-jul.log");
        Charset charset = Charset.defaultCharset();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        Path classPathFile = directory.resolve("classpath args.txt").toAbsolutePath();
        Files.writeString(classPathFile, "-cp \"" + classPath + "\"" + System.lineSeparator(), charset);
        // Boot logging changes JVM-global JUL handlers and the Logback context; keep them out of the test JVM.
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-Dfile.encoding=" + charset.name(),
                "@" + classPathFile,
                DefaultLoggingApplication.class.getName());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        boolean exited = false;
        try {
            exited = process.waitFor(60, TimeUnit.SECONDS);
            assertThat(exited).as("bounded Boot logging fixture").isTrue();
            assertThat(process.exitValue()).as("isolated Boot logging fixture").isZero();
            Files.readString(output, charset)
                    .lines()
                    .filter(line -> line.startsWith("JUL_FIXTURE_PROOF="))
                    .forEach(System.out::println);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS))
                        .as("the isolated logging fixture must terminate")
                        .isTrue();
            }
            if (!exited || process.exitValue() != 0) {
                System.err.println("JUL_FIXTURE_COMMAND=" + command);
                System.err.println("JUL_FIXTURE_CLASSPATH_ARGS=" + Files.readString(classPathFile, charset));
                System.err.println("JUL_FIXTURE_CHARSET=" + charset.name());
                System.err.println(
                        "JUL_FIXTURE_STDOUT_BEGIN\n" + Files.readString(output, charset) + "JUL_FIXTURE_STDOUT_END");
            }
        }
    }

    public static class DefaultLoggingApplication {

        public static void main(String[] args) {
            assertThat(SLF4JBridgeHandler.isInstalled())
                    .as("a fresh JVM must not inherit another test's manually installed bridge")
                    .isFalse();
            SpringApplication application = new SpringApplication(EmptyApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(Banner.Mode.OFF);
            application.setLogStartupInfo(false);
            application.setRegisterShutdownHook(false);
            application.setDefaultProperties(Map.of("bootui.enabled", "OFF", "bootui.force-web", "false"));

            try (var ignored = application.run("--spring.config.name=bootui-jul-routing-test")) {
                assertThat(SLF4JBridgeHandler.isInstalled())
                        .as("Spring Boot itself installs the JUL bridge")
                        .isTrue();
                assertThat(Logger.getLogger("").getHandlers())
                        .anySatisfy(handler -> assertThat(handler).isInstanceOf(SLF4JBridgeHandler.class));
                LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
                var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                ListAppender<ILoggingEvent> sink = new ListAppender<>();
                sink.setContext(context);
                sink.start();
                root.addAppender(sink);
                try {
                    Logger jul = Logger.getLogger("bootui.test.defaultBootJulRouting");
                    assertThat(jul.getHandlers()).isEmpty();
                    assertThat(jul.getUseParentHandlers()).isTrue();
                    String marker = "bootui-controlled-jul-routing-marker";

                    jul.warning(marker);

                    assertThat(sink.list)
                            .filteredOn(event -> marker.equals(event.getFormattedMessage()))
                            .singleElement()
                            .satisfies(event -> {
                                assertThat(event.getLoggerName()).isEqualTo(jul.getName());
                                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            });
                    System.out.println("JUL_FIXTURE_PROOF=bridge-before:false,bridge-after:true,logback-marker-count:1"
                            + " LOGGER=" + jul.getName() + " LEVEL=WARN MARKER=" + marker);
                } finally {
                    root.detachAppender(sink);
                    sink.stop();
                }
            }
        }
    }

    static class EmptyApplication {}
}
