package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ConstantDynamic;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Handle;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Label;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Type;
import io.github.jdubois.bootui.engine.archunit.ArchUnitBytecodeImports;
import io.github.jdubois.bootui.engine.archunit.KotlinBytecode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional precision for ARCH-SPRING-004, not a general receiver or alias analysis. Only recognized
 * straight-line expressions can discharge the existing owner-based suspicion. Anything unsupported
 * keeps that heuristic, including ambiguous ArchUnit sites containing a mixture of receivers.
 */
final class ProxyReceiverEvidence {
    static final ProxyReceiverEvidence NONE = new ProxyReceiverEvidence(Map.of());

    private static final System.Logger LOGGER = System.getLogger(ProxyReceiverEvidence.class.getName());
    private static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LAMBDA_ORIGINS = 64;
    private static final int MAX_EXPANDED_SITES = 16_384;
    private static final String PROVIDER = "org/springframework/beans/factory/ObjectProvider";
    private static final String LAZY = "org.springframework.context.annotation.Lazy";
    private final Map<Site, Boolean> proxyReceivers;

    private ProxyReceiverEvidence(Map<Site, Boolean> proxyReceivers) {
        this.proxyReceivers = proxyReceivers;
    }

    static ProxyReceiverEvidence read(JavaClass type, ArchUnitBytecodeImports.Bytecode captured) {
        if (KotlinBytecode.isKotlinClass(type)
                || type.getMethodCallsFromSelf().stream()
                                .noneMatch(access -> access.getTargetOwner().equals(type))
                        && type.getMethodReferencesFromSelf().stream()
                                .noneMatch(access -> access.getTargetOwner().equals(type))) {
            return NONE;
        }
        return captured.bytesOf(type).map(bytes -> parse(type, bytes)).orElse(NONE);
    }

    static ProxyReceiverEvidence parse(JavaClass type, byte[] bytes) {
        try {
            if (bytes.length > MAX_CLASS_BYTES) {
                LOGGER.log(System.Logger.Level.DEBUG, "Receiver precision bytecode limit: {0}", type.getName());
                return NONE;
            }
            Bytecode bytecode = new Bytecode(type);
            new ClassReader(bytes).accept(bytecode, ClassReader.SKIP_FRAMES);
            return bytecode.evidence();
        } catch (IllegalArgumentException
                | IndexOutOfBoundsException
                | NegativeArraySizeException
                | ClassCastException ex) {
            LOGGER.log(System.Logger.Level.DEBUG, "Receiver precision unavailable for " + type.getName(), ex);
            return NONE;
        }
    }

    boolean isProxyReceiver(JavaCodeUnitAccess<?> access) {
        return proxyReceivers.getOrDefault(
                new Site(
                        MethodKey.of(access.getOrigin()),
                        new TargetKey(
                                access.getTarget().getName(),
                                access.getTarget().getRawParameterTypes().stream()
                                        .map(JavaClass::getName)
                                        .toList()),
                        access.getLineNumber(),
                        access instanceof JavaMethodReference),
                false);
    }

    private enum Value {
        UNKNOWN,
        THIS,
        PROXY,
        PROVIDER
    }

    private record MethodKey(String name, String descriptor) {
        static MethodKey of(JavaCodeUnit method) {
            return new MethodKey(method.getName(), method.getDescriptor());
        }
    }

    private record TargetKey(String name, List<String> parameters) {
        static TargetKey of(String name, String descriptor) {
            return new TargetKey(
                    name,
                    Arrays.stream(Type.getArgumentTypes(descriptor))
                            .map(type -> type.getSort() == Type.ARRAY
                                    ? type.getDescriptor().replace('/', '.')
                                    : type.getClassName())
                            .toList());
        }
    }

    private record Site(MethodKey origin, TargetKey target, int line, boolean reference) {}

    private record Call(Site site, boolean proxy) {}

    private static final class Bytecode extends ClassVisitor {
        private final String owner;
        private final Map<String, Value> fields = new HashMap<>();
        private final Map<MethodKey, ExpressionVisitor> methods = new HashMap<>();
        private final Map<MethodKey, Set<MethodKey>> enclosing = new HashMap<>();

        Bytecode(JavaClass type) {
            super(Opcodes.ASM9);
            owner = type.getName().replace('.', '/');
            for (JavaField field : type.getFields()) {
                if (field.getModifiers().contains(JavaModifier.STATIC)
                        || !isInjected(field)
                        || field.getAccessesToSelf().stream()
                                .anyMatch(access -> access.getAccessType() == JavaFieldAccess.AccessType.SET)) {
                    continue;
                }
                if (field.getRawType().getName().equals(PROVIDER.replace('/', '.'))
                        && field.getType() instanceof JavaParameterizedType parameterized
                        && parameterized.getActualTypeArguments().size() == 1
                        && parameterized.getActualTypeArguments().get(0) instanceof JavaClass argument
                        && argument.equals(type)) {
                    fields.put(field.getName(), Value.PROVIDER);
                } else if (field.getRawType().equals(type)
                        && field.tryGetAnnotationOfType(LAZY)
                                .map(annotation -> Boolean.TRUE.equals(
                                        annotation.get("value").orElse(true)))
                                .orElse(false)) {
                    fields.put(field.getName(), Value.PROXY);
                }
            }
        }

        private static boolean isInjected(JavaField field) {
            return field.isAnnotatedWith(SpringStereotypes.AUTOWIRED)
                    || field.isAnnotatedWith(SpringStereotypes.JAKARTA_INJECT)
                    || field.isAnnotatedWith(SpringStereotypes.JAVAX_INJECT)
                    || field.isAnnotatedWith(SpringStereotypes.JAVAX_RESOURCE)
                    || field.isAnnotatedWith("jakarta.annotation.Resource");
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (!owner.equals(name)) {
                throw new IllegalArgumentException("Receiver bytecode does not match the imported class");
            }
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodKey key = new MethodKey(name, descriptor);
            ExpressionVisitor visitor = new ExpressionVisitor(this, key, access);
            methods.put(key, visitor);
            return visitor;
        }

        ProxyReceiverEvidence evidence() {
            enclosing.keySet().removeIf(target -> {
                ExpressionVisitor implementation = methods.get(target);
                return implementation == null || !implementation.isLambdaImplementation();
            });
            Map<Site, Boolean> result = new HashMap<>();
            int expandedSites = 0;
            for (var entry : methods.entrySet()) {
                MethodKey method = entry.getKey();
                ExpressionVisitor visitor = entry.getValue();
                Set<MethodKey> origins = new HashSet<>();
                ArrayDeque<MethodKey> pending = new ArrayDeque<>();
                pending.add(method);
                while (!pending.isEmpty()) {
                    MethodKey current = pending.removeFirst();
                    if (origins.add(current)) {
                        if (origins.size() > MAX_LAMBDA_ORIGINS) {
                            LOGGER.log(System.Logger.Level.DEBUG, "Receiver precision lambda-origin limit: {0}", owner);
                            return NONE;
                        }
                        pending.addAll(enclosing.getOrDefault(current, Set.of()));
                    }
                }
                for (Call call : visitor.calls) {
                    for (MethodKey origin : origins) {
                        if (++expandedSites > MAX_EXPANDED_SITES) {
                            LOGGER.log(System.Logger.Level.DEBUG, "Receiver precision site limit: {0}", owner);
                            return NONE;
                        }
                        Site site = call.site();
                        // ArchUnit can fold lambda origins and coalesce identical calls on one line.
                        // Exempt only if EVERY bytecode instruction matching that site uses a proxy.
                        result.merge(
                                new Site(origin, site.target(), site.line(), site.reference()),
                                visitor.supported && call.proxy(),
                                (left, right) -> left && right);
                    }
                }
            }
            return new ProxyReceiverEvidence(Map.copyOf(result));
        }
    }

    /**
     * Recognizes reference-producing expressions and local aliases with a small instruction whitelist.
     * Branches, exception handlers and unrecognized instructions invalidate the entire method's precision;
     * there is deliberately no control-flow merge, frame inference or interprocedural value propagation.
     */
    private static final class ExpressionVisitor extends MethodVisitor {
        private final Bytecode bytecode;
        private final MethodKey method;
        private final int access;
        private final List<Value> stack = new ArrayList<>();
        private final Map<Integer, Value> locals = new HashMap<>();
        private final List<Call> calls = new ArrayList<>();
        private boolean supported = true;
        private int line;

        ExpressionVisitor(Bytecode bytecode, MethodKey method, int access) {
            super(Opcodes.ASM9);
            this.bytecode = bytecode;
            this.method = method;
            this.access = access;
            if ((access & Opcodes.ACC_STATIC) == 0) locals.put(0, Value.THIS);
        }

        private boolean isLambdaImplementation() {
            return (access & Opcodes.ACC_SYNTHETIC) != 0
                    && (access & Opcodes.ACC_BRIDGE) == 0
                    && method.name().startsWith("lambda$");
        }

        @Override
        public void visitLineNumber(int number, Label start) {
            line = number;
        }

        private Value pop() {
            return stack.isEmpty() ? Value.UNKNOWN : stack.remove(stack.size() - 1);
        }

        private Value pop(int slots) {
            Value value = Value.UNKNOWN;
            for (int i = 0; i < slots; i++) value = pop();
            return value;
        }

        private void push(Value value, int slots) {
            for (int i = 0; i < slots; i++) stack.add(value);
        }

        private Value arguments(String descriptor) {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            Value first = Value.UNKNOWN;
            for (int i = arguments.length - 1; i >= 0; i--) first = pop(arguments[i].getSize());
            return first;
        }

        @Override
        public void visitVarInsn(int opcode, int slot) {
            switch (opcode) {
                case Opcodes.ALOAD -> stack.add(locals.getOrDefault(slot, Value.UNKNOWN));
                case Opcodes.ASTORE -> locals.put(slot, pop());
                case Opcodes.ILOAD, Opcodes.FLOAD -> push(Value.UNKNOWN, 1);
                case Opcodes.LLOAD, Opcodes.DLOAD -> push(Value.UNKNOWN, 2);
                case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.LSTORE, Opcodes.DSTORE -> {
                    pop(opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1);
                    locals.remove(slot);
                    if (opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE) locals.remove(slot + 1);
                }
                default -> supported = false;
            }
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
                case Opcodes.NOP -> {}
                case Opcodes.ACONST_NULL,
                        Opcodes.ICONST_M1,
                        Opcodes.ICONST_0,
                        Opcodes.ICONST_1,
                        Opcodes.ICONST_2,
                        Opcodes.ICONST_3,
                        Opcodes.ICONST_4,
                        Opcodes.ICONST_5,
                        Opcodes.FCONST_0,
                        Opcodes.FCONST_1,
                        Opcodes.FCONST_2 -> push(Value.UNKNOWN, 1);
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> push(Value.UNKNOWN, 2);
                case Opcodes.DUP -> {
                    Value value = pop();
                    push(value, 2);
                }
                case Opcodes.POP -> pop();
                case Opcodes.POP2 -> pop(2);
                case Opcodes.RETURN,
                        Opcodes.ARETURN,
                        Opcodes.IRETURN,
                        Opcodes.FRETURN,
                        Opcodes.LRETURN,
                        Opcodes.DRETURN -> stack.clear();
                default -> supported = false;
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            int slots = Type.getType(descriptor).getSize();
            switch (opcode) {
                case Opcodes.GETFIELD -> {
                    Value receiver = pop();
                    push(
                            receiver == Value.THIS && owner.equals(bytecode.owner)
                                    ? bytecode.fields.getOrDefault(name, Value.UNKNOWN)
                                    : Value.UNKNOWN,
                            slots);
                }
                case Opcodes.GETSTATIC -> push(Value.UNKNOWN, slots);
                case Opcodes.PUTFIELD -> {
                    pop(slots);
                    pop();
                }
                case Opcodes.PUTSTATIC -> pop(slots);
                default -> supported = false;
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            arguments(descriptor);
            Value receiver = opcode == Opcodes.INVOKESTATIC ? Value.UNKNOWN : pop();
            if (owner.equals(bytecode.owner)) {
                calls.add(new Call(
                        new Site(method, TargetKey.of(name, descriptor), line, false),
                        receiver == Value.PROXY
                                && (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE)));
            }
            boolean proxy = opcode == Opcodes.INVOKESTATIC
                            && owner.equals("org/springframework/aop/framework/AopContext")
                            && name.equals("currentProxy")
                            && descriptor.equals("()Ljava/lang/Object;")
                    || receiver == Value.PROVIDER
                            && owner.equals(PROVIDER)
                            && Set.of("getObject", "getIfAvailable", "getIfUnique")
                                    .contains(name)
                            && descriptor.equals("()Ljava/lang/Object;");
            push(
                    proxy ? Value.PROXY : Value.UNKNOWN,
                    Type.getReturnType(descriptor).getSize());
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... args) {
            Value receiver = arguments(descriptor);
            if (bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                    && (bootstrap.getName().equals("metafactory")
                            || bootstrap.getName().equals("altMetafactory"))
                    && args.length >= 3
                    && args[1] instanceof Handle implementation
                    && implementation.getOwner().equals(bytecode.owner)) {
                MethodKey target = new MethodKey(implementation.getName(), implementation.getDesc());
                bytecode.enclosing
                        .computeIfAbsent(target, ignored -> new HashSet<>())
                        .add(method);
                boolean bound = Type.getArgumentTypes(descriptor).length > 0
                        && (implementation.getTag() == Opcodes.H_INVOKEVIRTUAL
                                || implementation.getTag() == Opcodes.H_INVOKEINTERFACE);
                calls.add(new Call(
                        new Site(method, TargetKey.of(implementation.getName(), implementation.getDesc()), line, true),
                        bound && receiver == Value.PROXY));
            }
            push(Value.UNKNOWN, Type.getReturnType(descriptor).getSize());
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            switch (opcode) {
                case Opcodes.CHECKCAST -> {}
                case Opcodes.NEW -> push(Value.UNKNOWN, 1);
                default -> supported = false;
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof ConstantDynamic) supported = false;
            push(Value.UNKNOWN, value instanceof Long || value instanceof Double ? 2 : 1);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) push(Value.UNKNOWN, 1);
            else supported = false;
        }

        @Override
        public void visitJumpInsn(int opcode, Label target) {
            supported = false;
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            supported = false;
        }

        @Override
        public void visitIincInsn(int slot, int increment) {
            supported = false;
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label fallback, Label... targets) {
            supported = false;
        }

        @Override
        public void visitLookupSwitchInsn(Label fallback, int[] keys, Label[] targets) {
            supported = false;
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            supported = false;
        }
    }
}
