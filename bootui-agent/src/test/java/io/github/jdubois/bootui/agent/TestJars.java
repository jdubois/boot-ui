package io.github.jdubois.bootui.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * Jars built from the test classes for the forked-JVM tests, so their classes come from a code source that is not a
 * test root, as an application's or a library's do.
 */
final class TestJars {

    private TestJars() {}

    /** A jar of the test classes under {@code directory} (such as {@code bootuiinventoryapp}), minus {@code excluded}. */
    static Path jar(String name, String directory, List<String> excluded) throws IOException {
        Files.createDirectories(ChildJvm.WORK);
        Path jar = ChildJvm.WORK.resolve(name);
        Path root = ChildJvm.TEST_CLASSES;
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file);
                Stream<Path> walk = Files.walk(root.resolve(directory))) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                String entry = root.relativize(path).toString().replace('\\', '/');
                if (excluded.stream().anyMatch(entry::startsWith)) {
                    continue;
                }
                out.putNextEntry(new JarEntry(entry));
                out.write(Files.readAllBytes(path));
                out.closeEntry();
            }
        }
        return jar;
    }

    /**
     * The inventory tests' class path: the application jar, a jar of the generated {@link #HUGE} class, a jar of the
     * package a refine adds, then one jar per library package.
     */
    static String inventoryClassPath() throws IOException {
        StringBuilder classPath = new StringBuilder(
                jar("inventory-app.jar", "bootuiinventoryapp", List.of("bootuiinventoryapp/TestRootOnly"))
                        .toString());
        classPath.append(java.io.File.pathSeparator).append(hugeJar());
        classPath
                .append(java.io.File.pathSeparator)
                .append(jar("inventory-extra.jar", "bootuiinventoryextra", List.of()));
        for (String library : List.of("used", "unused", "bootui", "reentrant", "after")) {
            classPath
                    .append(java.io.File.pathSeparator)
                    .append(jar("inventory-lib-" + library + ".jar", "bootuiinventorylib/" + library, List.of()));
        }
        return classPath.toString();
    }

    /** The code-paths tests' class path: the application jar of bean classes. */
    static String codePathsClassPath() throws IOException {
        return jar("codepaths-app.jar", "bootuicodepathsapp", List.of()).toString();
    }

    /** A claimed class whose method {@code big()} is so close to the JVM's 64 KB limit that no advice fits in it. */
    static final String HUGE = "bootuiinventoryapp/Huge";

    /** Bytes of {@code nop} in {@code Huge.big()}: with its {@code iconst_1; ireturn}, 65,530 of 65,535. */
    static final int HUGE_NOPS = 65_528;

    /** A code-paths bean class whose method {@code tight()} fits the inventory's advice but not the code paths' too. */
    static final String TIGHT = "bootuicodepathsapp/Tight";

    /**
     * Bytes left under the JVM's 64 KB code limit in {@code Tight.tight()}: the inventory's advice takes about 24 of
     * them, both sensors' advice about 64.
     */
    static final int TIGHT_MARGIN = 40;

    /** A jar holding {@link #TIGHT}: a public constructor and {@code public int tight()} answering 1. */
    static Path tightJar() throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, TIGHT, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor tight = writer.visitMethod(Opcodes.ACC_PUBLIC, "tight", "()I", null, null);
        tight.visitCode();
        // With its iconst_1 and ireturn, TIGHT_MARGIN bytes short of 65,535.
        for (int i = 0; i < 65_535 - 2 - TIGHT_MARGIN; i++) {
            tight.visitInsn(Opcodes.NOP);
        }
        tight.visitInsn(Opcodes.ICONST_1);
        tight.visitInsn(Opcodes.IRETURN);
        tight.visitMaxs(0, 0);
        tight.visitEnd();
        writer.visitEnd();
        Files.createDirectories(ChildJvm.WORK);
        Path jar = ChildJvm.WORK.resolve("codepaths-tight.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry(TIGHT + ".class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        return jar;
    }

    /** A jar holding {@link #HUGE}: {@code static int big()} answering 1 and {@code static int small()} answering 2. */
    static Path hugeJar() throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HUGE,
                null,
                "java/lang/Object",
                null);
        MethodVisitor big = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "big", "()I", null, null);
        big.visitCode();
        for (int i = 0; i < HUGE_NOPS; i++) {
            big.visitInsn(Opcodes.NOP);
        }
        big.visitInsn(Opcodes.ICONST_1);
        big.visitInsn(Opcodes.IRETURN);
        big.visitMaxs(0, 0);
        big.visitEnd();
        MethodVisitor small = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "small", "()I", null, null);
        small.visitCode();
        small.visitInsn(Opcodes.ICONST_2);
        small.visitInsn(Opcodes.IRETURN);
        small.visitMaxs(0, 0);
        small.visitEnd();
        writer.visitEnd();
        Files.createDirectories(ChildJvm.WORK);
        Path jar = ChildJvm.WORK.resolve("inventory-huge.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry(HUGE + ".class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        return jar;
    }
}
