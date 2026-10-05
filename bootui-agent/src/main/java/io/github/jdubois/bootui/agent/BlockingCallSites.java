package io.github.jdubois.bootui.agent;

import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * The blocking sensor's call-site visit (PLAN-v2 §5.16, M5-5c), applied by the application-methods transformer to the
 * claimed classes: rewrites, in every method with code, each call to {@code Thread.sleep(long)},
 * {@code Thread.sleep(long, int)}, {@code Thread.sleep(Duration)}, {@code TimeUnit.sleep(long)}, and
 * {@code Object.wait()}, {@code wait(long)}, and {@code wait(long, int)} into a static call to the bridge's substitute
 * of the same stack shape ({@code Blocking.sleep}, {@code Blocking.waitOn}, the receiver first), which calls the
 * original. {@code Thread.sleep} and {@code Object.wait} are {@code native} on JDK 17, and retransformation cannot add
 * the wrappers a native-method prefix needs, so call sites are rewritten on every JDK. Matching is by owner, name, and
 * descriptor only, resolving no type: {@code Object.wait} is {@code final}, so a {@code wait} of these descriptors on any
 * owner is it. A {@code sleep} qualified by a {@code Thread} subclass, a method reference through
 * {@code invokedynamic}, and a dynamic language's call are left as they are. The method's shape and frames never change.
 */
final class BlockingCallSites implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {

    /** The bridge class holding the substitutes, by internal name. */
    static final String BRIDGE = "io/github/jdubois/bootui/agent/bridge/Blocking";

    /** Call sites rewritten since the JVM started, for status. */
    static final AtomicLong REWRITTEN = new AtomicLong();

    private static final AsmVisitorWrapper VISITOR =
            new AsmVisitorWrapper.ForDeclaredMethods().invokable(ElementMatchers.any(), new BlockingCallSites());

    private BlockingCallSites() {}

    /** The visit, for a type's builder. */
    static AsmVisitorWrapper visitor() {
        return VISITOR;
    }

    @Override
    public MethodVisitor wrap(
            TypeDescription instrumentedType,
            MethodDescription instrumentedMethod,
            MethodVisitor methodVisitor,
            Implementation.Context implementationContext,
            TypePool typePool,
            int writerFlags,
            int readerFlags) {
        return new Rewriter(methodVisitor);
    }

    /** The substitute's descriptor for a call, or {@code null} when the call is not rewritten. */
    static String substitute(int opcode, String owner, String name, String descriptor) {
        if ("sleep".equals(name)) {
            if (opcode == Opcodes.INVOKESTATIC
                    && "java/lang/Thread".equals(owner)
                    && ("(J)V".equals(descriptor)
                            || "(JI)V".equals(descriptor)
                            || "(Ljava/time/Duration;)V".equals(descriptor))) {
                return descriptor;
            }
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && "java/util/concurrent/TimeUnit".equals(owner)
                    && "(J)V".equals(descriptor)) {
                return "(Ljava/util/concurrent/TimeUnit;J)V";
            }
            return null;
        }
        if ("wait".equals(name)
                && (opcode == Opcodes.INVOKEVIRTUAL
                        || opcode == Opcodes.INVOKEINTERFACE
                        || opcode == Opcodes.INVOKESPECIAL)
                && ("()V".equals(descriptor) || "(J)V".equals(descriptor) || "(JI)V".equals(descriptor))) {
            return "(Ljava/lang/Object;" + descriptor.substring(1);
        }
        return null;
    }

    /** The bridge method a rewritten call goes to. */
    static String substituteName(String name) {
        return "wait".equals(name) ? "waitOn" : "sleep";
    }

    static final class Rewriter extends MethodVisitor {

        Rewriter(MethodVisitor delegate) {
            super(OpenedClassReader.ASM_API, delegate);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            String replaced = substitute(opcode, owner, name, descriptor);
            if (replaced == null) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            REWRITTEN.incrementAndGet();
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, substituteName(name), replaced, false);
        }
    }
}
