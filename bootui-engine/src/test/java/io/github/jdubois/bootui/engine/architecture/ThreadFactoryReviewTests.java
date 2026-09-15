package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Handle;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Type;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.architecture.kotlinfixtures.KotlinNonFactoryThreadLambda;
import io.github.jdubois.bootui.engine.architecture.kotlinfixtures.KotlinScheduledThreadFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreadFactoryReviewTests {
    @TempDir
    Path directory;

    @Test
    void aRemainingFactoryCannotAccountForARemovedNonFactoryCallOnTheSameLine() throws IOException {
        Class<?> fixture = NoDirectThreadInstantiationRuleTests.MixedLambdaConstruction.class;
        byte[] original = bytes(fixture);
        Path source = Files.write(directory.resolve("MixedLambdaConstruction.class"), original);
        JavaClasses classes = new ClassFileImporter().importPath(source);
        assertThat(evaluate(classes).violationCount()).isEqualTo(1);

        String[] removedBody = new String[1];
        new ClassReader(original)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            String name, String descriptor, Handle bootstrap, Object... args) {
                                        if (name.equals("get")) {
                                            removedBody[0] = ((Handle) args[1]).getName();
                                        }
                                    }
                                };
                            }
                        },
                        0);
        assertThat(removedBody[0]).isNotNull();
        ClassWriter replacement = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(original)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, replacement) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                MethodVisitor method =
                                        super.visitMethod(access, name, descriptor, signature, exceptions);
                                if (!name.equals(removedBody[0])) {
                                    return method;
                                }
                                method.visitCode();
                                method.visitInsn(Opcodes.ACONST_NULL);
                                method.visitInsn(Opcodes.ARETURN);
                                method.visitMaxs(0, 0);
                                method.visitEnd();
                                return null;
                            }
                        },
                        0);
        Files.write(source, replacement.toByteArray());

        ArchitectureContext context = context(classes);
        assertThat(new NoDirectThreadInstantiationRule().evaluate(context).status())
                .isEqualTo("ERROR");
        assertThat(context.evidence().usable()).isFalse();
    }

    @Test
    void theSupplementalReaderSupportsJava27LikeTheSelectedArchUnitVersion() throws IOException {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes(NoDirectThreadInstantiationRuleTests.MixedConstruction.class))
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public void visit(
                                    int version,
                                    int access,
                                    String name,
                                    String signature,
                                    String superName,
                                    String[] interfaces) {
                                super.visit(Opcodes.V27, access, name, signature, superName, interfaces);
                            }
                        },
                        0);
        ArchitectureRuleResultDto result = evaluate(importBytes("Java27.class", writer.toByteArray()));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
    }

    @Test
    void olderKotlinHyphenatedSamBodiesStillRequireAFactoryBinding() throws IOException {
        for (Class<?> fixture : List.of(KotlinScheduledThreadFactory.class, KotlinNonFactoryThreadLambda.class)) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(bytes(fixture))
                    .accept(
                            new ClassVisitor(Opcodes.ASM9, writer) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    return new MethodVisitor(
                                            Opcodes.ASM9,
                                            super.visitMethod(
                                                    access,
                                                    name.replace("$lambda$", "$lambda-"),
                                                    descriptor,
                                                    signature,
                                                    exceptions)) {
                                        @Override
                                        public void visitInvokeDynamicInsn(
                                                String name, String descriptor, Handle bootstrap, Object... args) {
                                            Object[] rewritten = args.clone();
                                            if (rewritten.length > 1 && rewritten[1] instanceof Handle implementation) {
                                                rewritten[1] = new Handle(
                                                        implementation.getTag(),
                                                        implementation.getOwner(),
                                                        implementation.getName().replace("$lambda$", "$lambda-"),
                                                        implementation.getDesc(),
                                                        implementation.isInterface());
                                            }
                                            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, rewritten);
                                        }
                                    };
                                }
                            },
                            0);
            ArchitectureRuleResultDto result =
                    evaluate(importBytes(fixture.getSimpleName() + ".class", writer.toByteArray()));
            boolean factory = fixture == KotlinScheduledThreadFactory.class;
            assertThat(result.status()).isEqualTo(factory ? "PASS" : "VIOLATION");
            assertThat(result.violationCount()).isEqualTo(factory ? 0 : 1);
        }
    }

    @Test
    void aBootstrapOnlySubtypeIsResolvedEvenWithoutAnArchUnitDependency() {
        Class<?> fixture = NoDirectThreadInstantiationRuleTests.LocalSubtypeFactory.class;
        JavaClasses classes = new ClassFileImporter().importClasses(fixture);
        assertThat(classes.get(fixture).getDirectDependenciesFromSelf())
                .noneMatch(dependency -> dependency
                        .getTargetClass()
                        .getName()
                        .equals(NoDirectThreadInstantiationRuleTests.CustomThreadFactory.class.getName()));
        assertThat(evaluate(classes).status()).isEqualTo("PASS");
    }

    @Test
    void theImportedSubtypeGraphDoesNotNeedAnotherResourceLookup() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(
                        NoDirectThreadInstantiationRuleTests.LocalSubtypeFactory.class,
                        NoDirectThreadInstantiationRuleTests.CustomThreadFactory.class);
        ThreadFactoryTypeHierarchy hierarchy = new ThreadFactoryTypeHierarchy(classes);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(null) {});
            assertThat(hierarchy.isAssignableTo(
                            NoDirectThreadInstantiationRuleTests.CustomThreadFactory.class.getName(),
                            ThreadFactory.class))
                    .isTrue();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void intersectionFactoryUsesAnAdditionalInterfaceRatherThanThePrimarySamType() throws IOException {
        Class<?> fixture = NoDirectThreadInstantiationRuleTests.IntersectionFactory.class;
        List<String> primaryInterfaces = new java.util.ArrayList<>();
        new ClassReader(bytes(fixture))
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            String name, String descriptor, Handle bootstrap, Object... args) {
                                        assertThat(bootstrap.getName()).isEqualTo("altMetafactory");
                                        primaryInterfaces.add(
                                                Type.getReturnType(descriptor).getClassName());
                                    }
                                };
                            }
                        },
                        0);
        assertThat(primaryInterfaces)
                .containsExactly(NoDirectThreadInstantiationRuleTests.OtherFactory.class.getName());
        assertThat(evaluate(new ClassFileImporter().importClasses(fixture)).status())
                .isEqualTo("PASS");
    }

    private JavaClasses importBytes(String name, byte[] bytes) throws IOException {
        return new ClassFileImporter().importPath(Files.write(directory.resolve(name), bytes));
    }

    private static byte[] bytes(Class<?> fixture) throws IOException {
        try (InputStream input =
                fixture.getResourceAsStream("/" + fixture.getName().replace('.', '/') + ".class")) {
            return input.readAllBytes();
        }
    }

    private static ArchitectureContext context(JavaClasses classes) {
        return new ArchitectureContext(
                classes, List.of(ThreadFactoryReviewTests.class.getPackageName()), ArchitecturePlatform.SPRING);
    }

    private static ArchitectureRuleResultDto evaluate(JavaClasses classes) {
        return new NoDirectThreadInstantiationRule().evaluate(context(classes));
    }
}
