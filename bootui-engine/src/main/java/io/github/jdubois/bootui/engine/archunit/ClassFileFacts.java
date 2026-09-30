package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;

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
}
