package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.thirdparty.org.objectweb.asm.AnnotationVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.FieldVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.TypePath;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The debug facts of one class file that neither ArchUnit's domain model nor reflection expose: the recorded
 * {@code SourceFile} name and the {@code SourceDebugExtension} (a Kotlin SMAP). Read only during an explicit
 * scan, through ArchUnit's embedded ASM, from at most {@value #MAX_CLASS_BYTES} bytes.
 *
 * @param sourceFile the recorded source file name, or {@code null}
 * @param sourceMap the recorded source debug extension, or {@code null} when the class has none
 */
public record ClassFileFacts(String sourceFile, String sourceMap) {

    static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;

    /** Reads the facts of the class file at {@code classFile}, or returns empty when it cannot be read. */
    public static Optional<ClassFileFacts> read(URI classFile) {
        if (classFile == null) return Optional.empty();
        try {
            java.net.URLConnection connection = classFile.toURL().openConnection();
            // Do not pin a shared archive handle open beyond this one bounded read.
            connection.setUseCaches(false);
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_CLASS_BYTES + 1);
                if (bytes.length > MAX_CLASS_BYTES) return Optional.empty();
                return Optional.of(parse(bytes));
            }
        } catch (IOException | RuntimeException | LinkageError ex) {
            return Optional.empty();
        }
    }

    /** Reads the facts of one class file from {@code input}, which the caller closes, or returns empty. */
    public static Optional<ClassFileFacts> read(InputStream input) {
        if (input == null) return Optional.empty();
        try {
            byte[] bytes = input.readNBytes(MAX_CLASS_BYTES + 1);
            if (bytes.length > MAX_CLASS_BYTES) return Optional.empty();
            return Optional.of(parse(bytes));
        } catch (IOException | RuntimeException | LinkageError ex) {
            return Optional.empty();
        }
    }

    static ClassFileFacts parse(byte[] bytes) {
        String[] facts = new String[2];
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitSource(String source, String debug) {
                                facts[0] = source;
                                facts[1] = debug;
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return new ClassFileFacts(facts[0], facts[1]);
    }

    /**
     * Reads the compiled {@code MethodParameters} names of every method in {@code classFile}, keyed by
     * {@code name + descriptor}. A synthetic, mandated, blank, or {@code argN} parameter name is recorded as
     * {@code ""}, preserving parameter indices. Returns an empty map when the class file cannot be read or
     * was compiled without {@code -parameters}.
     */
    public static Map<String, List<String>> methodParameterNames(URI classFile) {
        if (classFile == null) return Map.of();
        try {
            java.net.URLConnection connection = classFile.toURL().openConnection();
            connection.setUseCaches(false);
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_CLASS_BYTES + 1);
                if (bytes.length > MAX_CLASS_BYTES) return Map.of();
                return parseMethodParameters(bytes);
            }
        } catch (IOException | RuntimeException | LinkageError ex) {
            return Map.of();
        }
    }

    static Map<String, List<String>> parseMethodParameters(byte[] bytes) {
        Map<String, List<String>> methods = new LinkedHashMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                List<String> names = new ArrayList<>();
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitParameter(String paramName, int paramAccess) {
                                        if (paramName == null
                                                || paramName.isBlank()
                                                || (paramAccess & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_MANDATED)) != 0
                                                || isSyntheticArgName(paramName)) {
                                            names.add("");
                                        } else {
                                            names.add(paramName);
                                        }
                                    }

                                    @Override
                                    public void visitEnd() {
                                        if (!names.isEmpty()) {
                                            methods.put(name + descriptor, List.copyOf(names));
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return Map.copyOf(methods);
    }

    private static boolean isSyntheticArgName(String name) {
        if (!name.startsWith("arg") || name.length() <= 3) {
            return false;
        }
        for (int i = 3; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads {@code TYPE_USE} annotations on fields and zero-argument methods of {@code classFile}, keyed by
     * {@code "field:" + fieldName} and {@code "method:" + methodName}, with binary class names as values.
     */
    public static Map<String, Set<String>> memberTypeAnnotations(URI classFile) {
        if (classFile == null) return Map.of();
        try {
            java.net.URLConnection connection = classFile.toURL().openConnection();
            connection.setUseCaches(false);
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_CLASS_BYTES + 1);
                if (bytes.length > MAX_CLASS_BYTES) return Map.of();
                return parseMemberTypeAnnotations(bytes);
            }
        } catch (IOException | RuntimeException | LinkageError ex) {
            return Map.of();
        }
    }

    static Map<String, Set<String>> parseMemberTypeAnnotations(byte[] bytes) {
        Map<String, Set<String>> annotations = new LinkedHashMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public FieldVisitor visitField(
                                    int access, String name, String descriptor, String signature, Object value) {
                                return new FieldVisitor(Opcodes.ASM9) {
                                    @Override
                                    public AnnotationVisitor visitTypeAnnotation(
                                            int typeRef, TypePath typePath, String desc, boolean visible) {
                                        recordTypeAnnotation(annotations, "field:" + name, typePath, desc);
                                        return null;
                                    }
                                };
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (descriptor == null || !descriptor.startsWith("()")) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public AnnotationVisitor visitTypeAnnotation(
                                            int typeRef, TypePath typePath, String desc, boolean visible) {
                                        recordTypeAnnotation(annotations, "method:" + name, typePath, desc);
                                        return null;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return Map.copyOf(annotations);
    }

    private static void recordTypeAnnotation(
            Map<String, Set<String>> target, String key, TypePath typePath, String descriptor) {
        if (descriptor != null && descriptor.length() > 2 && descriptor.startsWith("L") && descriptor.endsWith(";")) {
            String className = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
            target.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(className);
            String pathSuffix = typePath == null ? "root" : typePath.toString();
            target.computeIfAbsent(key + ":" + pathSuffix, ignored -> new LinkedHashSet<>())
                    .add(className);
        }
    }
}
