package io.github.jdubois.bootui.engine.graalvm;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SyntheticCallFixture {

    private SyntheticCallFixture() {}

    static void writeUnsafeAllocator(Path classFile) throws IOException {
        write(
                classFile,
                "io/github/jdubois/bootui/engine/graalvm/fixtures/UnsafeAllocator",
                "sun/misc/Unsafe",
                "allocateInstance",
                "(Ljava/lang/Class;)Ljava/lang/Object;",
                1,
                true,
                false);
    }

    static void write(Path classFile, String className, String ownerName, String methodName, boolean interfaceCall)
            throws IOException {
        write(classFile, className, ownerName, methodName, "()V", 0, false, interfaceCall);
    }

    static void write(
            Path classFile,
            String className,
            String ownerName,
            String methodName,
            String descriptor,
            int argumentCount,
            boolean returnsValue,
            boolean interfaceCall)
            throws IOException {
        Files.createDirectories(classFile.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(classFile))) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(61);
            out.writeShort(14);
            writeUtf8(out, className);
            out.writeByte(7);
            out.writeShort(1);
            writeUtf8(out, "java/lang/Object");
            out.writeByte(7);
            out.writeShort(3);
            writeUtf8(out, "call");
            writeUtf8(out, "()V");
            writeUtf8(out, "Code");
            writeUtf8(out, ownerName);
            out.writeByte(7);
            out.writeShort(8);
            writeUtf8(out, methodName);
            writeUtf8(out, descriptor);
            out.writeByte(12);
            out.writeShort(10);
            out.writeShort(11);
            out.writeByte(interfaceCall ? 11 : 10);
            out.writeShort(9);
            out.writeShort(12);
            out.writeShort(0x0021);
            out.writeShort(2);
            out.writeShort(4);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(1);
            out.writeShort(0x0009);
            out.writeShort(5);
            out.writeShort(6);
            out.writeShort(1);
            out.writeShort(7);
            int codeLength = 1 + argumentCount + (interfaceCall ? 5 : 3) + (returnsValue ? 1 : 0) + 1;
            out.writeInt(12 + codeLength);
            out.writeShort(1 + argumentCount);
            out.writeShort(0);
            out.writeInt(codeLength);
            out.writeByte(0x01);
            for (int i = 0; i < argumentCount; i++) {
                out.writeByte(0x01);
            }
            out.writeByte(interfaceCall ? 0xb9 : 0xb6);
            out.writeShort(13);
            if (interfaceCall) {
                out.writeByte(1 + argumentCount);
                out.writeByte(0);
            }
            if (returnsValue) {
                out.writeByte(0x57);
            }
            out.writeByte(0xb1);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
        }
    }

    private static void writeUtf8(DataOutputStream out, String value) throws IOException {
        out.writeByte(1);
        out.writeUTF(value);
    }
}
