package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Handle;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Label;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.engine.archunit.ArchUnitBytecodeImports;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class ProxyReceiverEvidenceTests {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"truncated-header", "truncated-body", "invalid-constant-pool"})
    void malformedOptionalBytecodeRetainsTheLegacyFindingWithoutACoverageGap(String corruption) throws IOException {
        byte[] original = bytes(ProxyFixture.class);
        Path source = Files.write(directory.resolve("ProxyFixture.class"), original);
        JavaClasses classes = new ClassFileImporter().importPath(source);
        byte[] replacement =
                switch (corruption) {
                    case "truncated-header" -> Arrays.copyOf(original, 4);
                    case "truncated-body" -> Arrays.copyOf(original, new ClassReader(original).header + 8);
                    case "invalid-constant-pool" -> {
                        byte[] malformed = original.clone();
                        malformed[10] = 0;
                        yield malformed;
                    }
                    default -> throw new AssertionError(corruption);
                };
        Files.write(source, replacement);
        assertThat(ProxyReceiverEvidence.parse(classes.get(ProxyFixture.class), replacement))
                .isSameAs(ProxyReceiverEvidence.NONE);
        ArchitectureContext context = context(classes);

        ArchitectureRuleResultDto result = new NoSelfInvocationOfProxiedMethodsRule().evaluate(context);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void aBootstrapMethodRefPointingAtAMethodTypeRetainsTheLegacyFinding() throws IOException {
        byte[] original = bytes(ProxyFixture.class);
        Path source = Files.write(directory.resolve("BootstrapMethodRef.class"), original);
        JavaClasses classes = new ClassFileImporter().importPath(source);
        byte[] malformed = bootstrapMethodRefToMethodType(original);
        assertThatThrownBy(() -> new ClassReader(malformed)
                        .accept(
                                new ClassVisitor(Opcodes.ASM9) {
                                    @Override
                                    public MethodVisitor visitMethod(
                                            int access,
                                            String name,
                                            String descriptor,
                                            String signature,
                                            String[] exceptions) {
                                        return new MethodVisitor(Opcodes.ASM9) {};
                                    }
                                },
                                0))
                .isInstanceOf(ClassCastException.class);
        Files.write(source, malformed);
        assertThat(ProxyReceiverEvidence.parse(classes.get(ProxyFixture.class), malformed))
                .isSameAs(ProxyReceiverEvidence.NONE);
        ArchitectureContext context = context(classes);

        ArchitectureRuleResultDto result = new NoSelfInvocationOfProxiedMethodsRule().evaluate(context);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void replacingValidBytesCannotClearAThisCallFromThePreviousImport() throws IOException {
        byte[] proxy = bytes(ProxyFixture.class);
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(proxy)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                MethodVisitor method =
                                        super.visitMethod(access, name, descriptor, signature, exceptions);
                                if (!name.equals("caller")) return method;
                                return new MethodVisitor(Opcodes.ASM9, method) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String name, String descriptor) {
                                        super.visitFieldInsn(opcode, owner, name, descriptor);
                                        if (opcode == Opcodes.GETFIELD && name.equals("self")) {
                                            // Keep the same field access, call signature and source line, but call on
                                            // this.
                                            super.visitInsn(Opcodes.POP);
                                            super.visitVarInsn(Opcodes.ALOAD, 0);
                                        }
                                    }
                                };
                            }
                        },
                        0);
        Path source = Files.write(directory.resolve("Recompiled.class"), writer.toByteArray());
        var imported = ArchUnitBytecodeImports.importPath(source);
        assertThat(evaluate(imported).violationCount()).isEqualTo(1);

        Files.write(source, proxy);
        ArchitectureContext previous = context(imported);
        ArchitectureRuleResultDto result = new NoSelfInvocationOfProxiedMethodsRule().evaluate(previous);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(previous.evidence().requiredUnknown()).isFalse();
        assertThat(evaluate(ArchUnitBytecodeImports.importPath(source)).status())
                .isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void capturedProxyBytesRemainUsableAfterTheBackingResourceIsDeleted() throws IOException {
        Path source = Files.write(directory.resolve("Deleted.class"), bytes(ProxyFixture.class));
        var imported = ArchUnitBytecodeImports.importPath(source);
        Files.delete(source);

        assertThat(evaluate(imported).status()).isEqualTo(ArchitectureRuleSupport.PASS);
    }

    @Test
    void aPlainCustomImportWithoutCapturedBytesRetainsTheHeuristic() {
        ArchitectureContext context = context(new ClassFileImporter().importClasses(ProxyFixture.class));

        assertThat(new NoSelfInvocationOfProxiedMethodsRule().evaluate(context).violationCount())
                .isEqualTo(2);
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void explicitPrivateAndFinalTargetsRemainFindingsEvenThroughARecognizedReceiver() {
        AtomicInteger reads = new AtomicInteger();
        ArchitectureContext context = context(ArchUnitBytecodeImports.importClasses(NonInterceptableTargets.class));

        ArchitectureRuleResultDto result = new NoSelfInvocationOfProxiedMethodsRule((type, captured) -> {
                    reads.incrementAndGet();
                    return ProxyReceiverEvidence.read(type, captured);
                })
                .evaluate(context);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(4);
        assertThat(reads).hasValue(0);
    }

    @Test
    void specialCallsAndSpecialMethodHandlesCannotDischargeTheLegacySuspicion() throws IOException {
        assertThat(evaluate(ArchUnitBytecodeImports.importClasses(ProxyFixture.class))
                        .status())
                .isEqualTo(ArchitectureRuleSupport.PASS);
        String owner = ProxyFixture.class.getName().replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes(ProxyFixture.class))
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                return new MethodVisitor(
                                        Opcodes.ASM9,
                                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String target, String name, String descriptor, boolean itf) {
                                        super.visitMethodInsn(
                                                target.equals(owner) && name.equals("callee")
                                                        ? Opcodes.INVOKESPECIAL
                                                        : opcode,
                                                target,
                                                name,
                                                descriptor,
                                                itf);
                                    }

                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            String name, String descriptor, Handle bootstrap, Object... args) {
                                        Object[] rewritten = args.clone();
                                        if (rewritten.length > 1
                                                && rewritten[1] instanceof Handle implementation
                                                && implementation.getOwner().equals(owner)
                                                && implementation.getName().equals("callee")) {
                                            rewritten[1] = new Handle(
                                                    Opcodes.H_INVOKESPECIAL,
                                                    owner,
                                                    implementation.getName(),
                                                    implementation.getDesc(),
                                                    implementation.isInterface());
                                        }
                                        super.visitInvokeDynamicInsn(name, descriptor, bootstrap, rewritten);
                                    }
                                };
                            }
                        },
                        0);
        var classes = ArchUnitBytecodeImports.importPath(
                Files.write(directory.resolve("SpecialDispatch.class"), writer.toByteArray()));

        ArchitectureRuleResultDto result = evaluate(classes);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
    }

    @Test
    void anOrdinaryMethodReferenceDoesNotFoldItsBodyIntoTheReferencingMethod() throws IOException {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes(ReferenceOrigins.class))
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                return new MethodVisitor(
                                        Opcodes.ASM9,
                                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                    @Override
                                    public void visitLineNumber(int line, Label start) {
                                        super.visitLineNumber(1, start);
                                    }
                                };
                            }
                        },
                        0);
        var classes = ArchUnitBytecodeImports.importPath(
                Files.write(directory.resolve("ReferenceOrigins.class"), writer.toByteArray()));

        ArchitectureRuleResultDto result = evaluate(classes);

        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(violation -> assertThat(violation).contains("ReferenceOrigins.helper()"))
                .anySatisfy(violation -> assertThat(violation).contains("ReferenceOrigins.directLambda()"))
                .noneMatch(violation -> violation.contains("ReferenceOrigins.referToHelper()")
                        || violation.contains("ReferenceOrigins.proxyLambda()"));
    }

    @Test
    void rawObjectOtherAndWildcardProvidersDoNotEstablishSelfProxyEvidence() {
        ArchitectureRuleResultDto result = evaluate(ArchUnitBytecodeImports.importClasses(UnprovenProviders.class));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(4);
    }

    @Test
    void aClassWithNoQualifyingProxyTargetNeverRequestsOptionalBytecode() {
        AtomicInteger reads = new AtomicInteger();
        ArchitectureContext context = context(ArchUnitBytecodeImports.importClasses(NoProxyTargets.class));

        ArchitectureRuleResultDto result = new NoSelfInvocationOfProxiedMethodsRule((type, captured) -> {
                    reads.incrementAndGet();
                    return ProxyReceiverEvidence.NONE;
                })
                .evaluate(context);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(context.evidence().usable()).isTrue();
        assertThat(context.evidence().requiredUnknown()).isFalse();
        assertThat(reads).hasValue(0);
    }

    @Test
    void qualifyingTargetsShareOneLazyReadPerClass() {
        AtomicInteger reads = new AtomicInteger();
        ArchitectureRuleResultDto result = new NoSelfInvocationOfProxiedMethodsRule((type, captured) -> {
                    reads.incrementAndGet();
                    return ProxyReceiverEvidence.read(type, captured);
                })
                .evaluate(context(ArchUnitBytecodeImports.importClasses(ProxyFixture.class)));

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(reads).hasValue(1);
    }

    private static byte[] bytes(Class<?> fixture) throws IOException {
        try (InputStream input =
                fixture.getResourceAsStream("/" + fixture.getName().replace('.', '/') + ".class")) {
            assertThat(input).isNotNull();
            return input.readAllBytes();
        }
    }

    private static byte[] bootstrapMethodRefToMethodType(byte[] original) {
        ClassReader reader = new ClassReader(original);
        int methodType = 0;
        for (int index = 1; index < reader.getItemCount(); index++) {
            int item = reader.getItem(index);
            if (item > 0 && reader.readByte(item - 1) == 16) {
                methodType = index;
                break;
            }
        }
        assertThat(methodType).as("CONSTANT_MethodType entry").isPositive();
        int cursor = reader.header + 6;
        cursor += 2 + 2 * reader.readUnsignedShort(cursor);
        for (int table = 0; table < 2; table++) {
            int members = reader.readUnsignedShort(cursor);
            cursor += 2;
            for (int member = 0; member < members; member++) {
                int attributes = reader.readUnsignedShort(cursor + 6);
                cursor += 8;
                for (int attribute = 0; attribute < attributes; attribute++) {
                    cursor += 6 + reader.readInt(cursor + 2);
                }
            }
        }
        int attributes = reader.readUnsignedShort(cursor);
        cursor += 2;
        char[] chars = new char[reader.getMaxStringLength()];
        for (int attribute = 0; attribute < attributes; attribute++) {
            if ("BootstrapMethods".equals(reader.readUTF8(cursor, chars)) && reader.readUnsignedShort(cursor + 6) > 0) {
                byte[] malformed = original.clone();
                malformed[cursor + 8] = (byte) (methodType >>> 8);
                malformed[cursor + 9] = (byte) methodType;
                return malformed;
            }
            cursor += 6 + reader.readInt(cursor + 2);
        }
        throw new AssertionError("Fixture has no BootstrapMethods entry");
    }

    private static ArchitectureRuleResultDto evaluate(JavaClasses classes) {
        return new NoSelfInvocationOfProxiedMethodsRule().evaluate(context(classes));
    }

    /** Lets importer-failure controls assert the real advisor outcome without exposing production internals. */
    public static ArchitectureRuleResultDto evaluate(ArchUnitBytecodeImports.Result imported) {
        return new NoSelfInvocationOfProxiedMethodsRule().evaluate(context(imported));
    }

    private static ArchitectureContext context(ArchUnitBytecodeImports.Result imported) {
        return new ArchitectureContext(
                imported,
                imported.classes().stream()
                        .map(type -> type.getPackageName())
                        .distinct()
                        .toList(),
                ArchitecturePlatform.SPRING);
    }

    private static ArchitectureContext context(JavaClasses classes) {
        return new ArchitectureContext(
                classes, List.of(ProxyReceiverEvidenceTests.class.getPackageName()), ArchitecturePlatform.SPRING);
    }

    static class ProxyFixture {
        @Autowired
        @Lazy
        ProxyFixture self;

        void caller() {
            self.callee();
        }

        Runnable reference() {
            return self::callee;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void callee() {}
    }

    static class NonInterceptableTargets {
        @Autowired
        @Lazy
        NonInterceptableTargets self;

        void privateCall() {
            self.privateCallee();
        }

        Runnable privateReference() {
            return self::privateCallee;
        }

        void finalCall() {
            self.finalCallee();
        }

        Runnable finalReference() {
            return self::finalCallee;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        private void privateCallee() {}

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public final void finalCallee() {}
    }

    static class ReferenceOrigins {
        @Autowired
        @Lazy
        ReferenceOrigins self;

        void helper() {
            this.callee();
        }

        Runnable referToHelper() {
            self.callee();
            return this::helper;
        }

        Runnable directLambda() {
            return () -> this.callee();
        }

        Runnable proxyLambda() {
            return () -> self.callee();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void callee() {}
    }

    static class UnprovenProviders {
        @Autowired
        @SuppressWarnings("rawtypes")
        ObjectProvider raw;

        @Autowired
        ObjectProvider<Object> objects;

        @Autowired
        ObjectProvider<String> other;

        @Autowired
        ObjectProvider<? extends UnprovenProviders> wildcard;

        void raw() {
            ((UnprovenProviders) raw.getObject()).callee();
        }

        void objects() {
            ((UnprovenProviders) objects.getObject()).callee();
        }

        Runnable otherReference() {
            return ((UnprovenProviders) (Object) other.getObject())::callee;
        }

        void wildcard() {
            wildcard.getObject().callee();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void callee() {}
    }

    static class NoProxyTargets {
        void caller() {
            helper();
        }

        private void helper() {}
    }
}
