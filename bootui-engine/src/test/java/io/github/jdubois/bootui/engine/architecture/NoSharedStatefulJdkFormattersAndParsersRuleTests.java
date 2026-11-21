package io.github.jdubois.bootui.engine.architecture;

import static io.github.jdubois.bootui.engine.architecture.ArchitectureGeneratedCodeFixtures.compile;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NoSharedStatefulJdkFormattersAndParsersRuleTests {
    @TempDir
    Path root;

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void flagsNewFamiliesInStaticAndSharedBeanFields(ArchitecturePlatform platform) throws IOException {
        for (boolean sharedBean : List.of(false, true)) {
            JavaClasses classes = compile(
                    root.resolve(Boolean.toString(sharedBean)),
                    "target/classes",
                    Map.of("src/main/java/isolated/Holder.java", """
                            package isolated;
                            %s
                            class Holder {
                                %s java.security.MessageDigest digest;
                                %s javax.crypto.Cipher cipher;
                                %s javax.crypto.Mac mac;
                                %s java.security.Signature signature;
                                %s java.util.regex.Matcher matcher;
                                %s java.text.ChoiceFormat choices;
                            }
                            """.formatted(
                                    sharedBean ? "@jakarta.enterprise.context.ApplicationScoped" : "",
                                    sharedBean ? "" : "static",
                                    sharedBean ? "" : "static",
                                    sharedBean ? "" : "static",
                                    sharedBean ? "" : "static",
                                    sharedBean ? "" : "static",
                                    sharedBean ? "" : "static")));
            ArchitectureContext context = context(classes, platform);
            var result = new NoSharedStatefulJdkFormattersAndParsersRule().evaluate(context);

            assertThat(result.id()).isEqualTo("ARCH-CODE-019");
            assertThat(result.severity()).isEqualTo("MEDIUM");
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.violationCount()).isEqualTo(6);
            for (String field : List.of("digest", "cipher", "mac", "signature", "matcher", "choices")) {
                assertThat(result.sampleViolations()).anyMatch(text -> text.contains("Holder." + field));
            }
            assertThat(context.evidence().requiredUnknown()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void followsImportedApplicationAndThirdPartyAncestryWithoutLoadingThem(ArchitecturePlatform platform)
            throws IOException {
        JavaClasses classes = compile(
                root,
                "target/classes",
                Map.of("src/main/java/vendor/ExternalFormat.java", """
                        package vendor;
                        public class ExternalFormat extends java.text.SimpleDateFormat {}
                        """, "src/main/java/isolated/Holder.java", """
                        package isolated;
                        class Holder {
                            static CustomDate date;
                            static CustomDigest digest;
                            static CustomCipher cipher;
                            static CustomMac mac;
                            static CustomSignature signature;
                            static CustomBuilder builder;
                            static CustomChoice choices;
                            static vendor.ExternalFormat external;
                        }
                        class CustomDate extends vendor.ExternalFormat {}
                        abstract class CustomDigest extends java.security.MessageDigest {
                            CustomDigest() { super("fixture"); }
                        }
                        class CustomCipher extends javax.crypto.Cipher {
                            CustomCipher() { super(null, null, null); }
                        }
                        class CustomMac extends javax.crypto.Mac {
                            CustomMac() { super(null, null, "fixture"); }
                        }
                        abstract class CustomSignature extends java.security.Signature {
                            CustomSignature() { super("fixture"); }
                        }
                        abstract class CustomBuilder extends javax.xml.parsers.DocumentBuilder {}
                        class CustomChoice extends java.text.ChoiceFormat {
                            CustomChoice() { super("0#none"); }
                        }
                        """));
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new ClassLoader(previous) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("isolated.") || name.startsWith("vendor.")) {
                    throw new AssertionError("Application classes must not be loaded: " + name);
                }
                return super.loadClass(name, resolve);
            }
        });
        try {
            ArchitectureContext context = context(classes, platform);
            var result = new NoSharedStatefulJdkFormattersAndParsersRule().evaluate(context);

            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.violationCount()).isEqualTo(8);
            assertThat(result.sampleViolations()).anyMatch(text -> text.contains("vendor.ExternalFormat"));
            assertThat(context.evidence().requiredUnknown()).isFalse();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void jdkStubsAreResolvedWithoutTreatingSafeJdkFieldsAsUnknown() {
        ArchConfiguration.withThreadLocalScope(configuration -> {
            configuration.setResolveMissingDependenciesFromClassPath(false);
            JavaClasses classes;
            try {
                classes = compile(root, "out/unknown", Map.of("src/main/java/isolated/Holder.java", """
                                package isolated;
                                class Holder {
                                    static java.text.ChoiceFormat choices;
                                    static java.util.regex.Pattern pattern;
                                    static java.time.format.DateTimeFormatter dates;
                                    static ThreadLocal<java.security.MessageDigest> digest;
                                    static InheritableThreadLocal<javax.crypto.Cipher> cipher;
                                    static String name;
                                    static int count;
                                }
                                """));
            } catch (IOException ex) {
                throw new AssertionError(ex);
            }
            assertThat(classes.get("isolated.Holder")
                            .getField("choices")
                            .getRawType()
                            .isFullyImported())
                    .isFalse();
            ArchitectureContext context = context(classes, ArchitecturePlatform.SPRING);
            var result = new NoSharedStatefulJdkFormattersAndParsersRule().evaluate(context);

            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.violationCount()).isEqualTo(1);
            assertThat(result.sampleViolations()).singleElement().asString().contains("Holder.choices");
            assertThat(context.evidence().requiredUnknown()).isFalse();
        });
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void inheritedNewFamilyFieldsRemainSharedBeanFindings(ArchitecturePlatform platform) throws IOException {
        JavaClasses classes = compile(root, "target/classes", Map.of("src/main/java/isolated/SharedChild.java", """
                        package isolated;
                        class Base {
                            protected java.security.MessageDigest digest;
                            protected java.util.regex.Matcher matcher;
                        }
                        @jakarta.enterprise.context.ApplicationScoped
                        class SharedChild extends Base {}
                        """));
        ArchitectureContext context = context(classes, platform);
        var result = new NoSharedStatefulJdkFormattersAndParsersRule().evaluate(context);

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anyMatch(text -> text.contains("Base.digest"))
                .anyMatch(text -> text.contains("Base.matcher"));
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void preservesImmutableThreadLocalUnmanagedAndNonSharedScopeControls(ArchitecturePlatform platform)
            throws IOException {
        JavaClasses classes = compile(root, "target/classes", Map.of("src/main/java/isolated/Controls.java", """
                        package isolated;
                        @org.springframework.stereotype.Service
                        class Controls {
                            static final java.time.format.DateTimeFormatter dates =
                                java.time.format.DateTimeFormatter.ISO_DATE;
                            static final java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("a");
                            ThreadLocal<java.security.MessageDigest> digest;
                            ThreadLocal<java.util.regex.Matcher> matcher;
                            jakarta.validation.Validator beanValidator;
                            java.text.Format arbitraryFormat;
                            DigestLookalike lookalike;
                        }
                        class DigestLookalike {}
                        class Unmanaged {
                            java.security.MessageDigest digest;
                            java.util.regex.Matcher matcher;
                        }
                        @org.springframework.stereotype.Service
                        @org.springframework.context.annotation.Scope("prototype")
                        class Prototype extends Unmanaged {}
                        @org.springframework.web.context.annotation.RequestScope
                        class RequestBean extends Unmanaged {}
                        @jakarta.enterprise.context.RequestScoped
                        class CdiRequest extends Unmanaged {}
                        """));
        ArchitectureContext context = context(classes, platform);
        var result = new NoSharedStatefulJdkFormattersAndParsersRule().evaluate(context);

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(context.evidence().usable()).isTrue();
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void missingApplicationAncestryIsIncompleteAndDoesNotEraseKnownFindings() throws IOException {
        for (boolean knownFinding : List.of(false, true)) {
            Path module = root.resolve(Boolean.toString(knownFinding));
            compile(module, "target/classes", Map.of("src/main/java/isolated/Holder.java", """
                            package isolated;
                            class Holder {
                                static Unresolved value;
                                %s
                            }
                            class Unresolved extends java.text.SimpleDateFormat {}
                            """.formatted(
                            knownFinding ? "static java.util.regex.Matcher matcher;" : "")));
            ArchConfiguration.withThreadLocalScope(configuration -> {
                configuration.setResolveMissingDependenciesFromClassPath(false);
                JavaClasses incomplete =
                        new ClassFileImporter().importPath(module.resolve("target/classes/isolated/Holder.class"));
                assertThat(incomplete
                                .get("isolated.Holder")
                                .getField("value")
                                .getRawType()
                                .isFullyImported())
                        .isFalse();
                var report = new ArchitectureScanner(
                                () -> List.of("isolated"),
                                packages -> incomplete,
                                ArchitecturePlatform.SPRING,
                                Clock.systemUTC(),
                                List.of(new NoSharedStatefulJdkFormattersAndParsersRule()))
                        .scan();

                assertThat(report.violationsFound()).isEqualTo(knownFinding ? 1 : 0);
                assertThat(report.evidence().usable()).isEqualTo(knownFinding);
                assertThat(report.evidence().coverageComplete()).isFalse();
                assertThat(report.evidence().limitations())
                        .anyMatch(limit -> limit.contains("ARCH-CODE-019")
                                && limit.contains("required architecture observations could not be resolved"));
            });
        }
    }

    @Test
    void violationRetainsTheOriginalImportedField() throws IOException {
        JavaClasses classes = compile(
                root,
                "target/classes",
                Map.of(
                        "src/main/java/isolated/Holder.java",
                        "package isolated; class Holder { static java.util.regex.Matcher matcher; }"));
        JavaField original = classes.get("isolated.Holder").getField("matcher");
        var rule = new NoSharedStatefulJdkFormattersAndParsersRule();
        var evaluation =
                rule.rule(context(classes, ArchitecturePlatform.SPRING)).evaluate(classes);

        assertThat(evaluation.hasViolation()).isTrue();
        evaluation.handleViolations((Collection<JavaField> fields, String message) -> {
            assertThat(fields).singleElement().isSameAs(original);
        });
    }

    private static ArchitectureContext context(JavaClasses classes, ArchitecturePlatform platform) {
        return new ArchitectureContext(classes, List.of("isolated"), platform);
    }
}
