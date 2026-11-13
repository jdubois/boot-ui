package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.compatibility.PlatformCompatibility;
import io.github.jdubois.bootui.engine.compatibility.PlatformCompatibility.Severity;
import io.quarkus.builder.BuildChain;
import io.quarkus.builder.BuildException;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BootUiPlatformCompatibilityProcessorTest {

    @Test
    void theEnforcedMinimumMatchesTheExtensionDescriptorRatherThanTheBuildPatch() throws IOException {
        Path pom = Path.of("bootui-quarkus/pom.xml");
        if (!Files.isRegularFile(pom)) {
            pom = Path.of("../bootui-quarkus/pom.xml");
        }
        var metadata = Pattern.compile("<requiresQuarkusCore>\\s*([^<]+?)\\s*</requiresQuarkusCore>")
                .matcher(Files.readString(pom));
        assertThat(metadata.find())
                .as("the extension declares its supported core range")
                .isTrue();
        assertThat(metadata.group(1)).isEqualTo("[3.33,)");
        String minimum = metadata.group(1).substring(1, metadata.group(1).indexOf(','));

        assertThat(PlatformCompatibility.quarkus(minimum + ".0"))
                .noneMatch(finding -> finding.severity() == Severity.ERROR);
        assertThat(PlatformCompatibility.quarkus("3.32.999")).anyMatch(finding -> finding.severity() == Severity.ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.16.12.Final", "3.0.0", "3.32.9"})
    void unsupportedPlatformsFailWithAnActionableDiagnostic(String version) throws Exception {
        var fixture = new PlatformFixture(version);

        assertThatThrownBy(fixture::feature)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTUI-COMPAT-010")
                .hasMessageContaining(version)
                .hasMessageContaining("Upgrade the Quarkus platform or remove the BootUI extension.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.33.0", "3.33.99", "3.40.1.1", "3.99.0"})
    void supportedPlatformsAreNotRestrictedToSampledCiPatches(String version) throws Exception {
        assertThat(new PlatformFixture(version).feature().getName()).isEqualTo("bootui");
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.0.0", "5.1.0"})
    void futureGenerationsWarnWithoutPreventingAugmentation(String version) throws Exception {
        assertDiagnostic(version, "BOOTUI-COMPAT-012", Level.WARNING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "(unknown)", "development"})
    void unknownMetadataIsInformationalNotAnError(String version) throws Exception {
        assertDiagnostic(version, "BOOTUI-COMPAT-011", Level.INFO);
    }

    @ParameterizedTest
    @EnumSource(LaunchMode.class)
    void theBuildChainFailsInEveryLaunchModeIncludingProduction(LaunchMode mode) throws Exception {
        var fixture = new PlatformFixture("3.32.9");
        var builder = BuildChain.builder().addInitial(LaunchModeBuildItem.class).addFinal(FeatureBuildItem.class);
        builder.addBuildStep(context -> {
                    context.consume(LaunchModeBuildItem.class);
                    context.produce(fixture.feature());
                })
                .consumes(LaunchModeBuildItem.class)
                .produces(FeatureBuildItem.class)
                .build();
        var execution = builder.build()
                .createExecutionBuilder("platform-compatibility")
                .produce(new LaunchModeBuildItem(mode, Optional.empty(), false, Optional.empty(), false));

        assertThatThrownBy(execution::execute)
                .isInstanceOfSatisfying(
                        BuildException.class,
                        failure -> assertThat(failure.getDiagnostics())
                                .anySatisfy(diagnostic -> assertThat(diagnostic.getThrown())
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("BOOTUI-COMPAT-010")));
    }

    private static void assertDiagnostic(String version, String id, Level severity) throws Exception {
        var records = new ArrayList<LogRecord>();
        var logger = Logger.getLogger(BootUiPlatformCompatibilityProcessor.class.getName());
        var previousLevel = logger.getLevel();
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            assertThat(new PlatformFixture(version).feature().getName()).isEqualTo("bootui");
            assertThat(records).anySatisfy(record -> {
                assertThat(record.getMessage()).contains(id);
                assertThat(record.getLevel().intValue()).isEqualTo(severity.intValue());
            });
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
    }

    /**
     * Uses the real builder Version lookup with isolated metadata, without altering global version state
     * or permitting this processor to link the large console processor or runtime adapter.
     */
    private static final class PlatformFixture extends ClassLoader {

        private static final String PROCESSOR = BootUiPlatformCompatibilityProcessor.class.getName();
        private static final Set<String> ISOLATED_CLASSES = Set.of(PROCESSOR, "io.quarkus.builder.Version");

        private final String version;
        private final Class<?> processor;

        private PlatformFixture(String version) throws ClassNotFoundException {
            super(BootUiPlatformCompatibilityProcessorTest.class.getClassLoader());
            this.version = version;
            this.processor = loadClass(PROCESSOR);
            processor.getDeclaredConstructors();
            processor.getDeclaredFields();
            processor.getDeclaredMethods();
        }

        private FeatureBuildItem feature() {
            try {
                var constructor = processor.getDeclaredConstructor();
                constructor.setAccessible(true);
                var method = processor.getDeclaredMethod("feature");
                method.setAccessible(true);
                return (FeatureBuildItem) method.invoke(constructor.newInstance());
            } catch (InvocationTargetException ex) {
                if (ex.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                if (ex.getCause() instanceof Error cause) {
                    throw cause;
                }
                throw new AssertionError(ex.getCause());
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (ISOLATED_CLASSES.contains(name) || name.startsWith(PROCESSOR + "$")) {
                synchronized (getClassLoadingLock(name)) {
                    var type = findLoadedClass(name);
                    if (type == null) {
                        String path = name.replace('.', '/') + ".class";
                        try (var stream = getParent().getResourceAsStream(path)) {
                            if (stream == null) {
                                throw new ClassNotFoundException(name);
                            }
                            byte[] bytes = stream.readAllBytes();
                            type = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException ex) {
                            throw new ClassNotFoundException(name, ex);
                        }
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }
            if (name.startsWith("io.github.jdubois.bootui.quarkus.")) {
                throw new ClassNotFoundException("The compatibility processor must not link " + name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.equals("io/quarkus/builder/Version.properties")) {
                return new ByteArrayInputStream(("version=" + version + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return super.getResourceAsStream(name);
        }
    }
}
