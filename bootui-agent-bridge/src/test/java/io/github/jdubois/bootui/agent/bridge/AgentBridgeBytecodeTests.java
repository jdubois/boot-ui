package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.api.Test;

/**
 * The bridge runs inside advised {@code java.base} methods, possibly while {@code java.lang.invoke} is still
 * bootstrapping (PLAN-v2 M5 rules): its compiled classes must contain no {@code invokedynamic} (lambdas, method
 * references, string concatenation), no monitor, no {@code synchronized} method, no {@code VarHandle}, and reference
 * nothing outside {@code java.*} and the bridge's own package.
 */
class AgentBridgeBytecodeTests {

    private static final int ASM = Opcodes.ASM9;

    @Test
    void bridgeClassesFollowTheBridgeRules() throws IOException {
        Path classes = Path.of(System.getProperty("bridge.classes", "target/classes"));
        List<String> violations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(path -> path.toString().endsWith(".class")).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            new ClassReader(Files.readAllBytes(file)).accept(new Rules(violations), 0);
        }
        assertThat(violations).isEmpty();
    }

    static final class Rules extends ClassVisitor {

        private final List<String> violations;
        private String owner;

        Rules(List<String> violations) {
            super(ASM);
            this.violations = violations;
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            owner = name;
            type(superName, "super class");
            if (interfaces != null) {
                for (String type : interfaces) {
                    type(type, "interface");
                }
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            descriptor(descriptor, "field " + name);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            String where = owner + "." + name;
            if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                violations.add(where + " is synchronized");
            }
            descriptor(descriptor, where);
            return new MethodVisitor(ASM) {
                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                        violations.add(where + " uses a monitor");
                    }
                }

                @Override
                public void visitInvokeDynamicInsn(
                        String name, String descriptor, Handle bootstrap, Object... arguments) {
                    violations.add(where + " uses invokedynamic (" + bootstrap.getOwner() + ")");
                }

                @Override
                public void visitMethodInsn(
                        int opcode, String type, String method, String descriptor, boolean isInterface) {
                    type(type, where + " calls " + method);
                    descriptor(descriptor, where + " calls " + method);
                }

                @Override
                public void visitFieldInsn(int opcode, String type, String field, String descriptor) {
                    type(type, where + " reads " + field);
                    descriptor(descriptor, where + " reads " + field);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    type(type, where);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type constant) {
                        descriptor(constant.getDescriptor(), where + " loads a class constant");
                    }
                }
            };
        }

        private void descriptor(String descriptor, String where) {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.METHOD) {
                descriptor(type.getReturnType().getDescriptor(), where);
                for (Type argument : type.getArgumentTypes()) {
                    descriptor(argument.getDescriptor(), where);
                }
                return;
            }
            Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
            if (element.getSort() == Type.OBJECT) {
                type(element.getInternalName(), where);
            }
        }

        private void type(String internalName, String where) {
            if (internalName == null) {
                return;
            }
            String name = internalName.startsWith("[")
                    ? Type.getType(internalName).getElementType().getInternalName()
                    : internalName;
            if (name.equals("java/lang/invoke/VarHandle") || name.startsWith("java/lang/invoke/")) {
                violations.add(where + " references " + name);
            } else if (!name.startsWith("java/")
                    && !name.startsWith("io/github/jdubois/bootui/agent/bridge/")
                    && name.length() > 1) {
                violations.add(where + " references " + name);
            }
        }
    }
}
