package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Handle;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Label;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Type;
import io.github.jdubois.bootui.engine.archunit.KotlinBytecode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.LambdaMetafactory;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

/**
 * Retains the actual lambda body for Thread constructor sites: ArchUnit folds Java lambda accesses
 * into their enclosing method and does not expose the SAM type.
 *
 * <p>Uses ArchUnit's internal, relocated ASM to avoid a duplicate reader. ArchUnit upgrades must pass
 * the reader and packaged-runtime regressions described in CONTRIBUTING.md.
 */
final class ThreadFactoryLambdaAnalysis {

    private final ThreadFactoryTypeHierarchy hierarchy;

    ThreadFactoryLambdaAnalysis(JavaClasses classes) {
        hierarchy = new ThreadFactoryTypeHierarchy(classes);
    }

    List<JavaConstructorCall> violations(JavaClass type) {
        List<JavaConstructorCall> calls = type.getConstructorCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().isAssignableTo(Thread.class))
                .toList();
        if (calls.isEmpty()) {
            return List.of();
        }
        Map<ConstructorKey, List<JavaConstructorCall>> callsByTarget =
                calls.stream().collect(Collectors.groupingBy(ConstructorKey::of));

        hierarchy.observe(type);
        Bytecode bytecode = read(type);
        Set<MethodKey> factories = new HashSet<>();
        type.getMethods().stream()
                .filter(ThreadFactoryLambdaAnalysis::isFactoryMethod)
                .map(MethodKey::of)
                .forEach(factories::add);
        bytecode.bindings.forEach((implementation, bindings) -> {
            Integer access = bytecode.methods.get(implementation);
            if (access != null
                    && isGeneratedBody(type, implementation, access)
                    && !bytecode.directCalls.contains(implementation)
                    && bindings.stream().allMatch(Binding::threadFactory)) {
                factories.add(implementation);
            }
        });

        List<JavaConstructorCall> violations = new ArrayList<>();
        Set<JavaConstructorCall> unmatched = new HashSet<>(calls);
        for (Construction construction : bytecode.constructions) {
            List<JavaConstructorCall> targets = callsByTarget.getOrDefault(construction.target(), List.of());
            if (targets.isEmpty()) {
                continue;
            }
            Set<MethodKey> origins = new HashSet<>();
            bytecode.origins(construction.origin(), origins);
            List<JavaConstructorCall> candidates = targets.stream()
                    .filter(candidate -> origins.contains(MethodKey.of(candidate.getOrigin()))
                            && candidate.getLineNumber() == construction.line())
                    .sorted(Comparator.comparing((JavaConstructorCall call) ->
                                    call.getOrigin().getModifiers().contains(JavaModifier.SYNTHETIC))
                            .thenComparing(JavaConstructorCall::getDescription))
                    .toList();
            if (candidates.isEmpty()) {
                throw new IllegalStateException("Thread construction origin could not be resolved");
            }
            // A lambda may have several imported origins (notably $deserializeLambda$), but one
            // instruction cannot account for two calls in the same origin, even on the same line.
            Set<MethodKey> consumedOrigins = new HashSet<>();
            List<JavaConstructorCall> consumed = new ArrayList<>();
            for (JavaConstructorCall candidate : candidates) {
                if (unmatched.contains(candidate) && consumedOrigins.add(MethodKey.of(candidate.getOrigin()))) {
                    unmatched.remove(candidate);
                    consumed.add(candidate);
                }
            }
            if (consumed.isEmpty()) {
                throw new IllegalStateException("Thread construction bytecode changed during analysis");
            }
            if (!factories.contains(construction.origin())) {
                violations.add(consumed.get(0));
            }
        }
        if (!unmatched.isEmpty()) {
            throw new IllegalStateException("Thread construction bytecode changed during analysis");
        }
        return List.copyOf(violations);
    }

    private static boolean isFactoryMethod(JavaMethod method) {
        return method.getOwner().isAssignableTo(ThreadFactory.class)
                && method.getName().equals("newThread")
                && method.getModifiers().contains(JavaModifier.PUBLIC)
                && !method.getModifiers().contains(JavaModifier.STATIC)
                && method.getRawParameterTypes().size() == 1
                && method.getRawParameterTypes().get(0).isEquivalentTo(Runnable.class)
                && method.getRawReturnType().isAssignableTo(Thread.class);
    }

    private static boolean isGeneratedBody(JavaClass type, MethodKey method, int access) {
        // Kotlin's SAM bodies are private/static but not ACC_SYNTHETIC. A name alone never grants
        // an exemption: the caller also requires a verified LambdaMetafactory ThreadFactory binding.
        return (access & Opcodes.ACC_SYNTHETIC) != 0
                || KotlinBytecode.isKotlinClass(type)
                        && (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC))
                                == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                        && method.name().matches(".*\\$lambda[$-]\\d+");
    }

    private Bytecode read(JavaClass type) {
        try {
            URL resource = type.getSource()
                    .orElseThrow(() -> new IllegalStateException("Thread construction bytecode is unavailable"))
                    .getUri()
                    .toURL();
            Bytecode bytecode = new Bytecode(type.getName(), hierarchy);
            classReader(resource).accept(bytecode, ClassReader.SKIP_FRAMES);
            return bytecode;
        } catch (IOException ex) {
            throw new UncheckedIOException("Thread construction bytecode could not be read", ex);
        }
    }

    static ClassReader classReader(URL resource) {
        try {
            var connection = resource.openConnection();
            String protocol = connection instanceof JarURLConnection jar
                    ? jar.getJarFileURL().getProtocol()
                    : resource.getProtocol();
            if (!Set.of("file", "jrt", "nested").contains(protocol)) {
                throw new IllegalStateException("Thread construction bytecode is not a local resource");
            }
            connection.setUseCaches(false);
            try (InputStream input = connection.getInputStream()) {
                return new ClassReader(input);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Thread construction bytecode could not be read", ex);
        }
    }

    private record MethodKey(String name, String descriptor) {
        static MethodKey of(JavaCodeUnit method) {
            return new MethodKey(method.getName(), method.getDescriptor());
        }
    }

    private record Binding(MethodKey enclosing, boolean threadFactory) {}

    private record ConstructorKey(String owner, List<String> parameters) {
        static ConstructorKey of(JavaConstructorCall call) {
            return new ConstructorKey(
                    call.getTargetOwner().getName(),
                    call.getTarget().getRawParameterTypes().stream()
                            .map(JavaClass::getName)
                            .toList());
        }

        static ConstructorKey of(String owner, String descriptor) {
            return new ConstructorKey(
                    owner,
                    Arrays.stream(Type.getArgumentTypes(descriptor))
                            .map(type -> type.getSort() == Type.ARRAY
                                    ? type.getDescriptor().replace('/', '.')
                                    : type.getClassName())
                            .toList());
        }
    }

    private record Construction(MethodKey origin, ConstructorKey target, int line) {}

    private static final class Bytecode extends ClassVisitor {
        private final String owner;
        private final ThreadFactoryTypeHierarchy hierarchy;
        private final Map<MethodKey, Integer> methods = new HashMap<>();
        private final Map<MethodKey, List<Binding>> bindings = new HashMap<>();
        private final Set<MethodKey> directCalls = new HashSet<>();
        private final List<Construction> constructions = new ArrayList<>();

        Bytecode(String owner, ThreadFactoryTypeHierarchy hierarchy) {
            super(Opcodes.ASM9);
            this.owner = owner;
            this.hierarchy = hierarchy;
        }

        void origins(MethodKey method, Set<MethodKey> result) {
            ArrayDeque<MethodKey> pending = new ArrayDeque<>();
            pending.add(method);
            while (!pending.isEmpty()) {
                MethodKey current = pending.removeFirst();
                if (result.add(current)) {
                    for (Binding binding : bindings.getOrDefault(current, List.of())) {
                        pending.add(binding.enclosing());
                    }
                }
            }
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (!name.replace('/', '.').equals(owner)) {
                throw new IllegalStateException("Thread construction bytecode does not match the imported class");
            }
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodKey method = new MethodKey(name, descriptor);
            methods.put(method, access);
            return new MethodVisitor(Opcodes.ASM9) {
                private int line;

                @Override
                public void visitLineNumber(int number, Label start) {
                    line = number;
                }

                @Override
                public void visitMethodInsn(int opcode, String target, String name, String descriptor, boolean itf) {
                    String targetName = target.replace('/', '.');
                    if (name.equals("<init>")) {
                        constructions.add(new Construction(method, ConstructorKey.of(targetName, descriptor), line));
                    } else if (targetName.equals(owner)) {
                        directCalls.add(new MethodKey(name, descriptor));
                    }
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... args) {
                    if (!bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                            || !(bootstrap.getName().equals("metafactory")
                                    || bootstrap.getName().equals("altMetafactory"))
                            || args.length < 3
                            || !(args[0] instanceof Type sam)
                            || !(args[1] instanceof Handle implementation)
                            || !implementation.getOwner().replace('/', '.').equals(owner)) {
                        return;
                    }
                    List<String> interfaces = interfaces(descriptor, bootstrap, args);
                    boolean factory = name.equals("newThread")
                            && Arrays.equals(sam.getArgumentTypes(), new Type[] {Type.getType(Runnable.class)})
                            && sam.getReturnType().getSort() == Type.OBJECT
                            && hierarchy.isAssignableTo(sam.getReturnType().getClassName(), Thread.class)
                            && (interfaces.contains(ThreadFactory.class.getName())
                                    || interfaces.stream()
                                            .anyMatch(type -> hierarchy.isAssignableTo(type, ThreadFactory.class)));
                    bindings.computeIfAbsent(
                                    new MethodKey(implementation.getName(), implementation.getDesc()),
                                    ignored -> new ArrayList<>())
                            .add(new Binding(method, factory));
                }
            };
        }

        private static List<String> interfaces(String descriptor, Handle bootstrap, Object[] args) {
            List<String> interfaces = new ArrayList<>();
            interfaces.add(Type.getReturnType(descriptor).getClassName());
            if (bootstrap.getName().equals("altMetafactory")) {
                if (args.length < 4 || !(args[3] instanceof Integer flags)) {
                    throw new IllegalStateException("Invalid lambda bootstrap flags");
                }
                if ((flags & LambdaMetafactory.FLAG_MARKERS) != 0) {
                    if (args.length < 5
                            || !(args[4] instanceof Integer count)
                            || count < 0
                            || count > args.length - 5) {
                        throw new IllegalStateException("Invalid lambda bootstrap interfaces");
                    }
                    for (int i = 0; i < count; i++) {
                        if (!(args[5 + i] instanceof Type type) || type.getSort() != Type.OBJECT) {
                            throw new IllegalStateException("Invalid lambda bootstrap interface");
                        }
                        interfaces.add(type.getClassName());
                    }
                }
            }
            return interfaces;
        }
    }
}
