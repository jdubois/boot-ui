package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.core.BootUiDtos.DevServicesReport;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

class DevServicesControllerTests {

    @Test
    void listReturnsEmptyReportWhenNoDevServicesArePresent() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

            mvc.perform(get("/bootui/api/dev-services"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.services").isEmpty());
        }
        finally {
            context.close();
        }
    }

    @Test
    void onApplicationEventSkipsBrokenDockerComposeEntry() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        try {
            DevServicesController controller = new DevServicesController(context, new BootUiProperties());

            controller.onApplicationEvent(newDockerComposeEvent(List.of(
                    new DockerComposeRunningService("postgres", "postgres:16", "localhost"),
                    new BrokenDockerComposeRunningService())));

            DevServicesReport report = controller.list();
            assertThat(report.total()).isEqualTo(1);
            assertThat(report.services()).singleElement().satisfies(service -> {
                assertThat(service.id()).isEqualTo("compose:postgres");
                assertThat(service.name()).isEqualTo("postgres");
            });
        }
        finally {
            context.close();
        }
    }

    @Test
    void listReturnsMaskedConnectionDetails() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresConnectionDetails", SampleConnectionDetails.class);
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

            mvc.perform(get("/bootui/api/dev-services"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.services[0].id").value("connection:postgresConnectionDetails"))
                    .andExpect(jsonPath("$.services[0].type").value("PostgreSQL"))
                    .andExpect(jsonPath("$.services[0].connectionDetails.jdbcUrl")
                            .value("jdbc:postgresql://******@localhost:5432/app"))
                    .andExpect(jsonPath("$.services[0].connectionDetails.password").value("******"));
        }
        finally {
            context.close();
        }
    }

    @Test
    void listExposesConnectionDetailsUnderFullExposure() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.FULL);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresConnectionDetails", SampleConnectionDetails.class);
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, properties)).build();

            mvc.perform(get("/bootui/api/dev-services"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.services[0].connectionDetails.jdbcUrl")
                            .value("jdbc:postgresql://dbuser:secret@localhost:5432/app"))
                    .andExpect(jsonPath("$.services[0].connectionDetails.password").value("secret"));
        }
        finally {
            context.close();
        }
    }

    @Test
    void listSkipsBrokenConnectionDetailsBean() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresConnectionDetails", SampleConnectionDetails.class);
        context.registerBean("brokenConnectionDetails", BrokenConnectionDetails.class);
        context.refresh();
        try {
            DevServicesReport report = new DevServicesController(context, new BootUiProperties()).list();

            assertThat(report.total()).isEqualTo(1);
            assertThat(report.services()).extracting(service -> service.id())
                    .containsExactly("connection:postgresConnectionDetails");
        }
        finally {
            context.close();
        }
    }

    @Test
    void listIgnoresNullConnectionDetailValues() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("nullableConnectionDetails", NullValueConnectionDetails.class);
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

            mvc.perform(get("/bootui/api/dev-services"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.services[0].connectionDetails.jdbcUrl")
                            .value("jdbc:postgresql://******@localhost:5432/app"))
                    .andExpect(jsonPath("$.services[0].connectionDetails.password").doesNotExist())
                    .andExpect(jsonPath("$.services[0].connectionDetails.username").doesNotExist());
        }
        finally {
            context.close();
        }
    }

    @Test
    void logsReturnsCappedTailForBeanBackedService() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getDevServices().setLogTailBytes(12);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresTestcontainer", FakeTestcontainer.class);
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, properties)).build();

            mvc.perform(get("/bootui/api/dev-services/bean:postgresTestcontainer/logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.truncated").value(true))
                    .andExpect(jsonPath("$.logs").value(containsString("3456789-end")));
        }
        finally {
            context.close();
        }
    }

    @Test
    void logsReturnsErrorReportWhenServiceStops() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("stoppedTestcontainer", StoppedTestcontainer.class);
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

            mvc.perform(get("/bootui/api/dev-services/bean:stoppedTestcontainer/logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andExpect(jsonPath("$.logs").value("Logs are not available because the service is not running."));
        }
        finally {
            context.close();
        }
    }

    @Test
    void logsReturnsErrorReportWhenLogAccessFails() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("failingLogsTestcontainer", FailingLogsTestcontainer.class);
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

            mvc.perform(get("/bootui/api/dev-services/bean:failingLogsTestcontainer/logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andExpect(jsonPath("$.logs").value("Unable to read logs: log access failed"));
        }
        finally {
            context.close();
        }
    }

    @Test
    void restartIsDisabledByDefault() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresTestcontainer", FakeTestcontainer.class);
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

            mvc.perform(post("/bootui/api/dev-services/bean:postgresTestcontainer/restart"))
                    .andExpect(status().isConflict());
        }
        finally {
            context.close();
        }
    }

    @Test
    void restartReturnsNotFoundForMissingService() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, new BootUiProperties())).build();

            mvc.perform(post("/bootui/api/dev-services/bean:missing/restart"))
                    .andExpect(status().isNotFound());
        }
        finally {
            context.close();
        }
    }

    @Test
    void restartCallsStopThenStartWhenExplicitlyEnabled() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getDevServices().setRestartEnabled(true);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("postgresTestcontainer", FakeTestcontainer.class);
        context.refresh();
        try {
            MockMvc mvc = standaloneSetup(new DevServicesController(context, properties)).build();

            mvc.perform(post("/bootui/api/dev-services/bean:postgresTestcontainer/restart"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("restarted"));

            FakeTestcontainer container = context.getBean(FakeTestcontainer.class);
            assertThat(container.restartCalls()).isEqualTo("stop,start");
        }
        finally {
            context.close();
        }
    }

    @Test
    void listSkipsBrokenTestcontainerBean() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setClassLoader(classLoaderWithTestcontainersSupport());
        context.registerBean("postgresTestcontainer", FakeTestcontainer.class);
        context.registerBean("brokenTestcontainer", BrokenTestcontainer.class);
        context.refresh();
        try {
            DevServicesReport report = new DevServicesController(context, new BootUiProperties()).list();

            assertThat(report.total()).isEqualTo(1);
            assertThat(report.services()).extracting(service -> service.id())
                    .containsExactly("bean:postgresTestcontainer");
        }
        finally {
            context.close();
        }
    }

    @Test
    void listFallsBackToBeanNameWhenContainerNameIsBlank() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setClassLoader(classLoaderWithTestcontainersSupport());
        context.registerBean("unnamedTestcontainer", NamelessTestcontainer.class);
        context.refresh();
        try {
            DevServicesReport report = new DevServicesController(context, new BootUiProperties()).list();

            assertThat(report.services()).singleElement().satisfies(service -> {
                assertThat(service.id()).isEqualTo("bean:unnamedTestcontainer");
                assertThat(service.name()).isEqualTo("unnamedTestcontainer");
            });
        }
        finally {
            context.close();
        }
    }

    @Test
    void listTruncatesLargeServiceSets() {
        GenericApplicationContext context = new GenericApplicationContext();
        for (int i = 0; i < 205; i++) {
            context.registerBean("connectionDetails" + i, SampleConnectionDetails.class, SampleConnectionDetails::new);
        }
        context.refresh();
        try {
            DevServicesReport report = new DevServicesController(context, new BootUiProperties()).list();

            assertThat(report.total()).isEqualTo(200);
            assertThat(report.services()).hasSize(200);
        }
        finally {
            context.close();
        }
    }

    static class SampleConnectionDetails implements ConnectionDetails {

        public String getJdbcUrl() {
            return "jdbc:postgresql://dbuser:secret@localhost:5432/app";
        }

        public String getPassword() {
            return "secret";
        }
    }

    static class NullValueConnectionDetails implements ConnectionDetails {

        public String getJdbcUrl() {
            return "jdbc:postgresql://dbuser:secret@localhost:5432/app";
        }

        public String getPassword() {
            return null;
        }

        public String getUsername() {
            return null;
        }
    }

    static class BrokenConnectionDetails implements ConnectionDetails {

        public String getJdbcUrl() {
            throw new IllegalStateException("details unavailable");
        }
    }

    static class FakeTestcontainer {

        private final StringBuilder restartCalls = new StringBuilder();

        public String getDockerImageName() {
            return "postgres:16";
        }

        public String getContainerName() {
            return "postgres";
        }

        public boolean isRunning() {
            return true;
        }

        public String getLogs() {
            return "x".repeat(1100) + "3456789-end";
        }

        public void stop() {
            restartCalls.append("stop");
        }

        public void start() {
            if (!restartCalls.isEmpty()) {
                restartCalls.append(",");
            }
            restartCalls.append("start");
        }

        String restartCalls() {
            return restartCalls.toString();
        }
    }

    static class StoppedTestcontainer extends FakeTestcontainer {

        @Override
        public boolean isRunning() {
            return false;
        }
    }

    static class FailingLogsTestcontainer extends FakeTestcontainer {

        @Override
        public String getLogs() {
            throw new IllegalStateException("log access failed");
        }
    }

    static class BrokenTestcontainer extends FakeTestcontainer {

        @Override
        public String getDockerImageName() {
            throw new IllegalStateException("container missing");
        }
    }

    static class NamelessTestcontainer extends FakeTestcontainer {

        @Override
        public String getContainerName() {
            return "   ";
        }
    }

    static class DockerComposeRunningService {

        private final String name;
        private final String image;
        private final String host;

        DockerComposeRunningService(String name, String image, String host) {
            this.name = name;
            this.image = image;
            this.host = host;
        }

        public String name() {
            return name;
        }

        public String image() {
            return image;
        }

        public String host() {
            return host;
        }

        public Map<String, Object> labels() {
            return Map.of();
        }

        public DockerComposePorts ports() {
            return new DockerComposePorts();
        }
    }

    static class BrokenDockerComposeRunningService extends DockerComposeRunningService {

        BrokenDockerComposeRunningService() {
            super(null, "redis:7", "localhost");
        }

        @Override
        public String name() {
            throw new IllegalStateException("compose entry unavailable");
        }
    }

    static class DockerComposePorts {

        public List<Integer> getAll() {
            return List.of(5432);
        }
    }

    private static ApplicationEvent newDockerComposeEvent(List<?> runningServices) throws Exception {
        Class<?> eventType = loadClass("org.springframework.boot.docker.compose.lifecycle.DockerComposeServicesReadyEvent",
                """
                        package org.springframework.boot.docker.compose.lifecycle;

                        import java.util.List;
                        import org.springframework.context.ApplicationEvent;

                        public class DockerComposeServicesReadyEvent extends ApplicationEvent {

                            private final List<Object> runningServices;

                            public DockerComposeServicesReadyEvent(List<Object> runningServices) {
                                super(runningServices);
                                this.runningServices = runningServices;
                            }

                            public List<Object> getRunningServices() {
                                return this.runningServices;
                            }
                        }
                        """);
        Constructor<?> constructor = eventType.getConstructor(List.class);
        return (ApplicationEvent) constructor.newInstance(runningServices);
    }

    private static ClassLoader classLoaderWithTestcontainersSupport() {
        Class<?> startable = loadClass("org.testcontainers.lifecycle.Startable",
                """
                        package org.testcontainers.lifecycle;

                        public interface Startable {
                        }
                        """);
        return startable.getClassLoader();
    }

    private static Class<?> loadClass(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("system Java compiler").isNotNull();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8);
        MemoryFileManager fileManager = new MemoryFileManager(standardFileManager);
        JavaFileObject sourceFile = new SourceJavaFileObject(className, source);
        Boolean success = compiler.getTask(null, fileManager, diagnostics,
                List.of("-classpath", System.getProperty("java.class.path")), null, List.of(sourceFile)).call();
        assertThat(success)
                .as(diagnostics.getDiagnostics().toString())
                .isTrue();

        InMemoryClassLoader classLoader = new InMemoryClassLoader(DevServicesControllerTests.class.getClassLoader());
        fileManager.compiledClasses().forEach(classLoader::define);
        try {
            return Class.forName(className, true, classLoader);
        }
        catch (ClassNotFoundException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class SourceJavaFileObject extends SimpleJavaFileObject {

        private final String source;

        private SourceJavaFileObject(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class BytecodeJavaFileObject extends SimpleJavaFileObject {

        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        private BytecodeJavaFileObject(String className, JavaFileObject.Kind kind) {
            super(URI.create("bytes:///" + className.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return outputStream;
        }

        private byte[] bytes() {
            return outputStream.toByteArray();
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final LinkedHashMap<String, BytecodeJavaFileObject> compiledClasses = new LinkedHashMap<>();

        private MemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            BytecodeJavaFileObject fileObject = new BytecodeJavaFileObject(className, kind);
            compiledClasses.put(className, fileObject);
            return fileObject;
        }

        private Map<String, byte[]> compiledClasses() {
            LinkedHashMap<String, byte[]> classes = new LinkedHashMap<>();
            compiledClasses.forEach((name, file) -> classes.put(name, file.bytes()));
            return classes;
        }
    }

    private static final class InMemoryClassLoader extends ClassLoader {

        private InMemoryClassLoader(ClassLoader parent) {
            super(parent);
        }

        private void define(String className, byte[] bytecode) {
            defineClass(className, bytecode, 0, bytecode.length);
        }
    }
}
