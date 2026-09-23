package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Handle;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Label;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Instruction-level normal form of one method, independent of debug attributes and stack-map frames.
 * Constants, call descriptors, local slots, branch targets, and exception-table entries are all kept.
 */
final class ApiUtilBytecode {
    private ApiUtilBytecode() {}

    static Optional<List<String>> instructions(byte[] classFile, String methodName, String descriptor) {
        List<List<String>> found = new ArrayList<>();
        new ClassReader(classFile)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String signature, String[] exceptions) {
                                if (!name.equals(methodName) || !desc.equals(descriptor)) return null;
                                List<String> tokens = new ArrayList<>();
                                found.add(tokens);
                                return new Normalizer(tokens);
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found.size() == 1 ? Optional.of(List.copyOf(found.get(0))) : Optional.empty();
    }

    private static final class Normalizer extends MethodVisitor {
        private final List<String> tokens;
        private final Map<Label, Integer> labels = new HashMap<>();

        Normalizer(List<String> tokens) {
            super(Opcodes.ASM9);
            this.tokens = tokens;
        }

        private String label(Label label) {
            return "L" + labels.computeIfAbsent(label, ignored -> labels.size());
        }

        @Override
        public void visitLabel(Label label) {
            tokens.add(label(label) + ":");
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            tokens.add("TRY " + label(start) + " " + label(end) + " " + label(handler) + " " + type);
        }

        @Override
        public void visitInsn(int opcode) {
            tokens.add("OP" + opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            tokens.add("OP" + opcode + " " + operand);
        }

        @Override
        public void visitVarInsn(int opcode, int slot) {
            tokens.add("OP" + opcode + " " + slot);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            tokens.add("OP" + opcode + " " + type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            tokens.add("OP" + opcode + " " + owner + "." + name + ":" + descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            tokens.add("OP" + opcode + " " + owner + "." + name + descriptor + (itf ? " itf" : ""));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
            tokens.add("INDY " + name + descriptor);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            tokens.add("OP" + opcode + " " + label(label));
        }

        @Override
        public void visitLdcInsn(Object value) {
            tokens.add("LDC "
                    + (value instanceof Type type
                            ? "type " + type.getDescriptor()
                            : value.getClass().getSimpleName() + " " + value));
        }

        @Override
        public void visitIincInsn(int slot, int increment) {
            tokens.add("IINC " + slot + " " + increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label fallback, Label... targets) {
            tokens.add("TABLESWITCH");
        }

        @Override
        public void visitLookupSwitchInsn(Label fallback, int[] keys, Label[] targets) {
            tokens.add("LOOKUPSWITCH");
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            tokens.add("MULTIANEWARRAY " + descriptor + " " + dimensions);
        }
    }
}
