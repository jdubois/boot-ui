package io.github.jdubois.bootui.engine.crac;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Handle;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Label;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Type;
import io.github.jdubois.bootui.engine.archunit.ArchUnitBytecodeImports;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Syntactic receiver evidence, not dataflow or checkpoint certification. Recognizes adjacent
 * {@code this.field.cleanup()}, {@code this.privateHelper()}, and constructor
 * {@code Core.getGlobalContext().register(this)} instructions in the exact imported bytes.
 * Labels and every other instruction break a candidate expression; aliases are unsupported.
 */
final class CracFieldCleanupEvidence {
    private static final System.Logger LOGGER = System.getLogger(CracFieldCleanupEvidence.class.getName());
    private static final CracFieldCleanupEvidence UNAVAILABLE = new CracFieldCleanupEvidence(Map.of(), false);
    private static final Set<String> CONTEXTS = Set.of("org/crac/Context", "javax/crac/Context", "jdk/crac/Context");
    private static final Set<String> CLEANUP_METHODS =
            Set.of("cancel", "close", "destroy", "disconnect", "shutdown", "shutdownGracefully", "shutdownNow", "stop");
    private final Map<MethodKey, MethodEvidence> methods;
    private final boolean available;

    private CracFieldCleanupEvidence(Map<MethodKey, MethodEvidence> methods, boolean available) {
        this.methods = Map.copyOf(methods);
        this.available = available;
    }

    static CracFieldCleanupEvidence read(JavaClass owner, ArchUnitBytecodeImports.Bytecode captured) {
        return captured.bytesOf(owner).map(bytes -> parse(owner, bytes)).orElse(UNAVAILABLE);
    }

    private static CracFieldCleanupEvidence parse(JavaClass owner, byte[] bytes) {
        Map<MethodKey, MethodEvidence> methods = new HashMap<>();
        Map<String, JavaField> fields = new HashMap<>();
        owner.getFields().forEach(field -> fields.put(field.getName(), field));
        try {
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(
                                        int version,
                                        int access,
                                        String name,
                                        String signature,
                                        String superName,
                                        String[] interfaces) {
                                    if (!owner.getName().replace('.', '/').equals(name)) {
                                        throw new IllegalArgumentException(
                                                "Cleanup bytecode does not match the imported class");
                                    }
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    MethodEvidence evidence = new MethodEvidence(access);
                                    methods.put(new MethodKey(name, descriptor), evidence);
                                    return new Expressions(owner.getName().replace('.', '/'), fields, evidence);
                                }
                            },
                            ClassReader.SKIP_FRAMES);
            return new CracFieldCleanupEvidence(methods, true);
        } catch (IllegalArgumentException | IndexOutOfBoundsException | NegativeArraySizeException ex) {
            LOGGER.log(System.Logger.Level.DEBUG, "Field cleanup evidence unavailable for " + owner.getName(), ex);
            return UNAVAILABLE;
        }
    }

    boolean available() {
        return available;
    }

    boolean cleans(JavaCodeUnit callback, JavaField field) {
        return cleans(callback, field, false);
    }

    boolean cleansWithoutControlFlow(JavaCodeUnit callback, JavaField field) {
        return cleans(callback, field, true);
    }

    private boolean cleans(JavaCodeUnit callback, JavaField field, boolean linearOnly) {
        return cleans(
                new MethodKey(callback.getName(), callback.getDescriptor()),
                field.getName(),
                new HashSet<>(),
                linearOnly);
    }

    private boolean cleans(MethodKey key, String field, Set<MethodKey> visited, boolean linearOnly) {
        if (visited.size() >= 64 || !visited.add(key)) return false;
        MethodEvidence method = methods.get(key);
        if (method == null || linearOnly && !method.linear) return false;
        if (method.fields.contains(field)) return true;
        for (MethodKey helper : method.helpers) {
            MethodEvidence target = methods.get(helper);
            if (target != null
                    && (target.access & Opcodes.ACC_PRIVATE) != 0
                    && (target.access & Opcodes.ACC_STATIC) == 0
                    && cleans(helper, field, visited, linearOnly)) return true;
        }
        return false;
    }

    boolean selfRegistersInEveryConstructor(String contextType) {
        String context = contextType.replace('.', '/');
        boolean constructor = false;
        for (var entry : methods.entrySet()) {
            if (!"<init>".equals(entry.getKey().name())) continue;
            constructor = true;
            MethodEvidence method = entry.getValue();
            if (!method.linear || !method.registrations.contains(context)) return false;
        }
        return constructor;
    }

    private record MethodKey(String name, String descriptor) {}

    private static final class MethodEvidence {
        final int access;
        final Set<String> fields = new HashSet<>();
        final Set<MethodKey> helpers = new HashSet<>();
        final Set<String> registrations = new HashSet<>();
        boolean linear = true;
        boolean returned;

        MethodEvidence(int access) {
            this.access = access;
        }
    }

    private static final class Expressions extends MethodVisitor {
        private final String ownerName;
        private final Map<String, JavaField> fields;
        private final MethodEvidence evidence;
        private boolean thisOverwritten;
        private boolean thisReceiver;
        private JavaField field;
        private String globalContext;
        private String registrationContext;

        Expressions(String ownerName, Map<String, JavaField> fields, MethodEvidence evidence) {
            super(Opcodes.ASM9);
            this.ownerName = ownerName;
            this.fields = fields;
            this.evidence = evidence;
        }

        private void instruction() {
            if (evidence.returned) evidence.linear = false;
        }

        private void clear() {
            thisReceiver = false;
            field = null;
            globalContext = null;
            registrationContext = null;
        }

        @Override
        public void visitVarInsn(int opcode, int slot) {
            instruction();
            String context = globalContext;
            clear();
            if (slot == 0 && opcode != Opcodes.ALOAD) thisOverwritten = true;
            if (opcode == Opcodes.ALOAD
                    && slot == 0
                    && !thisOverwritten
                    && (evidence.access & Opcodes.ACC_STATIC) == 0) {
                thisReceiver = true;
                registrationContext = context;
            }
            if (opcode == Opcodes.RET) evidence.linear = false;
        }

        @Override
        public void visitFieldInsn(int opcode, String targetOwner, String name, String descriptor) {
            instruction();
            boolean receiver = thisReceiver;
            clear();
            if (opcode == Opcodes.GETFIELD && receiver && ownerName.equals(targetOwner)) {
                field = fields.get(name);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String targetOwner, String name, String descriptor, boolean itf) {
            instruction();
            boolean instanceCall = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
            if (instanceCall
                    && field != null
                    && CLEANUP_METHODS.contains(name)
                    && Type.getArgumentTypes(descriptor).length == 0
                    && field.getRawType().isAssignableTo(targetOwner.replace('/', '.'))) {
                evidence.fields.add(field.getName());
            }
            if (thisReceiver
                    && (instanceCall || opcode == Opcodes.INVOKESPECIAL)
                    && ownerName.equals(targetOwner)
                    && !"<init>".equals(name)
                    && "()V".equals(descriptor)) {
                evidence.helpers.add(new MethodKey(name, descriptor));
            }
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && targetOwner.equals(registrationContext)
                    && "register".equals(name)
                    && ("(L" + targetOwner.replace("Context", "Resource") + ";)V").equals(descriptor)) {
                evidence.registrations.add(targetOwner);
            }
            clear();
            if (opcode == Opcodes.INVOKESTATIC && "getGlobalContext".equals(name) && targetOwner.endsWith("/Core")) {
                String context = targetOwner.substring(0, targetOwner.length() - 4) + "Context";
                if (CONTEXTS.contains(context) && ("()L" + context + ";").equals(descriptor)) globalContext = context;
            }
        }

        @Override
        public void visitInsn(int opcode) {
            instruction();
            clear();
            if (opcode == Opcodes.RETURN) evidence.returned = true;
            if (opcode == Opcodes.ATHROW) evidence.linear = false;
        }

        @Override
        public void visitLabel(Label label) {
            clear();
        }

        @Override
        public void visitJumpInsn(int opcode, Label target) {
            evidence.linear = false;
            clear();
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            evidence.linear = false;
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            instruction();
            clear();
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            instruction();
            clear();
        }

        @Override
        public void visitLdcInsn(Object value) {
            instruction();
            clear();
        }

        @Override
        public void visitIincInsn(int slot, int increment) {
            instruction();
            if (slot == 0) thisOverwritten = true;
            clear();
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... args) {
            instruction();
            clear();
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label fallback, Label... targets) {
            evidence.linear = false;
            clear();
        }

        @Override
        public void visitLookupSwitchInsn(Label fallback, int[] keys, Label[] targets) {
            evidence.linear = false;
            clear();
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            instruction();
            clear();
        }
    }
}
