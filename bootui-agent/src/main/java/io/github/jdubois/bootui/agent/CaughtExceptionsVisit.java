package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.CaughtExceptions;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * The caught-exceptions sensor's visit (PLAN-v2 M5-6a), a raw ASM visitor the shared application-methods transformer
 * applies last, so it is the outermost and reads the class's own exception tables, before any advice adds handlers.
 *
 * <p><b>Handler entry.</b> At each exception handler whose table entries all name a type, before its first instruction
 * (after its label, line number, and frame), it inserts {@code DUP; SIPUSH site; INVOKESTATIC CaughtExceptions.caught}:
 * straight-line code adding no branch target, so the handler's frame stays valid and no frame is added. Catch-any
 * handlers ({@code finally}, {@code synchronized}) are left alone.
 *
 * <p><b>Exceptional exit.</b> To each method with such a handler but a constructor, it appends one catch-any entry
 * covering the whole original code, emitted after the method's own entries so theirs keep precedence, and before
 * anything an advice visitor further in emits, since advice adds its own entry on the first event after the table. Its
 * handler, placed after the last instruction, calls {@code CaughtExceptions.leaving} and rethrows. Its frame lists
 * {@code this} and the declared parameters, the leading locals Byte Buddy's advice requires; a slot the method stores a
 * value of another verification kind into is {@code TOP}, which the JVM accepts anywhere. A slot stored with a value of
 * the same kind keeps its declared type, which the languages compiling to it guarantee assignable. A {@code TOP}
 * parameter makes a Byte Buddy advice visitor further in reject the class, which the sensor then retransforms without
 * this visit.
 *
 * <p>Nothing here computes frames or maximums through ASM, which would load classes inside the transformer: the
 * maximum stack is raised to at least 3 by hand, the depth at a handler being exactly 1. Nothing resolves a type: the
 * visit compares instruction operands only. Classes older than Java 7 (version 51), whose methods may lack stack map
 * frames, are left alone. Each handler's site is registered in the bridge as the method is read, and its flags once the
 * whole method was read: a handler another agent's inlined advice added ({@link MethodVisit#foreign}) is skipped at
 * run time, as is one that is also a jump target.
 */
final class CaughtExceptionsVisit implements AsmVisitorWrapper {

    /** The bridge class the inserted code calls. */
    static final String BRIDGE = "io/github/jdubois/bootui/agent/bridge/CaughtExceptions";

    static final String CAUGHT_DESCRIPTOR = "(Ljava/lang/Object;I)V";
    static final String LEAVING_DESCRIPTOR = "(Ljava/lang/Throwable;I)V";

    /** Instructions after a handler's label within which its line number must appear. */
    static final int LINE_WINDOW = 4;

    /** The oldest class-file major version visited: Java 7, whose methods with branches all carry frames. */
    static final int MIN_MAJOR = 51;

    /** The stack depth the inserted code needs: the exception, its copy, and the site. */
    static final int STACK = 3;

    /** Loads what the visit uses, before the transformer can call it inside class loading. */
    static void warm() {
        MethodVisit visit = new MethodVisit(null, "warm/Up", Opcodes.ACC_STATIC, "up", "(JLjava/lang/String;D)V");
        visit.frameLocals();
        new ClassVisit(null);
        visit.foreign(new Site(new Label()));
        MethodVisit.kind(Type.getType("J"));
        MethodVisit.frameType(Type.getType("Ljava/lang/String;"));
    }

    @Override
    public int mergeWriter(int flags) {
        return flags;
    }

    @Override
    public int mergeReader(int flags) {
        return flags | ClassReader.EXPAND_FRAMES;
    }

    @Override
    public ClassVisitor wrap(
            TypeDescription instrumentedType,
            ClassVisitor classVisitor,
            Implementation.Context implementationContext,
            TypePool typePool,
            FieldList<FieldDescription.InDefinedShape> fields,
            MethodList<?> methods,
            int writerFlags,
            int readerFlags) {
        return new ClassVisit(classVisitor);
    }

    /** Reads the class's version and name, and visits each method with code. */
    static final class ClassVisit extends ClassVisitor {

        private String owner;
        private boolean enabled;

        ClassVisit(ClassVisitor delegate) {
            super(OpenedClassReader.ASM_API, delegate);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            owner = name;
            enabled = (version & 0xFFFF) >= MIN_MAJOR;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!enabled
                    || delegate == null
                    || "<clinit>".equals(name)
                    || name.startsWith("$")
                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0) {
                return delegate;
            }
            return new MethodVisit(delegate, owner, access, name, descriptor);
        }
    }

    /** One handler: its label, declared types, and what reading the method found. */
    static final class Site {
        final Label label;
        final StringBuilder types = new StringBuilder();
        final List<String> declared = new ArrayList<String>(2);
        boolean catchAny;
        int id = -1;
        int line;
        boolean lineSeen;
        /** Instructions read since its label, or -1 before it or once past the line window. */
        int counted = -1;
        /** Whether its label was read and its first instruction not yet. */
        boolean awaitingFirst;
        /** Its first instruction's opcode, or -1. */
        int firstOpcode = -1;
        /** The local its first instruction stores to or loads, or -1. */
        int firstVar = -1;

        Site(Label label) {
            this.label = label;
        }
    }

    /** Store kinds of a local slot. */
    static final int CLEAN = 0;

    static final int SAME_KIND = 1;
    static final int OTHER_KIND = 2;

    /** Verification kinds. */
    static final int KIND_INT = 1;

    static final int KIND_LONG = 2;
    static final int KIND_FLOAT = 3;
    static final int KIND_DOUBLE = 4;
    static final int KIND_REFERENCE = 5;

    /** Reads one method, inserting the handler-entry calls and the exit handler. */
    static final class MethodVisit extends MethodVisitor {

        private final String owner;
        private final String name;
        private final String descriptor;
        private final boolean isStatic;
        private final boolean constructor;
        /** Per local slot of {@code this} and the parameters: its verification kind, 0 for a wide value's second. */
        private final int[] slotKinds;
        /** Per parameter slot: {@link #CLEAN}, {@link #SAME_KIND}, or {@link #OTHER_KIND}. */
        private final int[] stores;

        private final List<Site> sites = new ArrayList<Site>();
        private final List<Label> jumpTargets = new ArrayList<Label>();
        /** The local variable slots the method's LocalVariableTable names, and whether it has one. */
        private final List<Integer> namedSlots = new ArrayList<Integer>();

        private boolean localVariableTable;
        private boolean code;
        private boolean anyLine;
        private Site hook;
        private boolean inserted;
        private Label exitStart;
        private Label exitEnd;
        private Label exitHandler;
        private int exitSite = -1;

        MethodVisit(MethodVisitor delegate, String owner, int access, String name, String descriptor) {
            super(OpenedClassReader.ASM_API, delegate);
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.isStatic = (access & Opcodes.ACC_STATIC) != 0;
            this.constructor = "<init>".equals(name);
            Type[] arguments = Type.getArgumentTypes(descriptor);
            int size = isStatic ? 0 : 1;
            for (Type argument : arguments) {
                size += argument.getSize();
            }
            slotKinds = new int[size];
            stores = new int[size];
            int slot = 0;
            if (!isStatic) {
                slotKinds[slot++] = KIND_REFERENCE;
            }
            for (Type argument : arguments) {
                slotKinds[slot] = kind(argument);
                slot += argument.getSize();
            }
        }

        static int kind(Type type) {
            switch (type.getSort()) {
                case Type.LONG:
                    return KIND_LONG;
                case Type.FLOAT:
                    return KIND_FLOAT;
                case Type.DOUBLE:
                    return KIND_DOUBLE;
                case Type.OBJECT:
                case Type.ARRAY:
                    return KIND_REFERENCE;
                default:
                    return KIND_INT;
            }
        }

        // ---- the exception table -----------------------------------------------------------------------------------

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (code && exitStart != null) {
                // An entry after the code started would follow the exit handler's, which would then catch first:
                // the transformation fails, and the class is retransformed without this visit.
                throw new IllegalStateException("an exception table entry after the code of " + owner + "#" + name);
            }
            if (!code) {
                Site site = site(handler);
                if (site == null) {
                    site = new Site(handler);
                    sites.add(site);
                }
                if (type == null) {
                    site.catchAny = true;
                } else if (!site.declared.contains(type)) {
                    // A handler javac split into several ranges names its type once per range.
                    site.declared.add(type);
                    if (site.types.length() > 0) {
                        site.types.append('|');
                    }
                    site.types.append(type);
                }
            }
            super.visitTryCatchBlock(start, end, handler, type);
        }

        private Site site(Label label) {
            for (Site site : sites) {
                if (site.label == label) {
                    return site;
                }
            }
            return null;
        }

        /**
         * The first event after the exception table: registers the sites, and appends the exit handler's entry, then
         * its start label, before the event, so any advice visitor further in adds its own entry after it.
         */
        private void beforeCode() {
            if (code) {
                return;
            }
            code = true;
            int ordinal = 0;
            for (Site site : sites) {
                if (site.catchAny) {
                    continue;
                }
                String types = site.types.toString();
                site.id = CaughtExceptions.site(owner + "#" + name + descriptor + "#" + ordinal + "#" + types, types);
                ordinal++;
                if (site.id >= 0 && exitSite < 0) {
                    exitSite = site.id;
                }
            }
            if (exitSite >= 0 && !constructor) {
                exitStart = new Label();
                Label end = new Label();
                Label handler = new Label();
                exitEnd = end;
                exitHandler = handler;
                super.visitTryCatchBlock(exitStart, end, handler, null);
                super.visitLabel(exitStart);
            }
        }

        // ---- labels, lines, and frames -----------------------------------------------------------------------------

        @Override
        public void visitLabel(Label label) {
            beforeCode();
            Site site = site(label);
            if (site != null && site.id >= 0) {
                hook = site;
                site.counted = 0;
                site.awaitingFirst = true;
            }
            super.visitLabel(label);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            beforeCode();
            anyLine = true;
            for (Site site : sites) {
                if (site.counted >= 0 && !site.lineSeen) {
                    site.lineSeen = true;
                    site.line = line;
                    site.counted = -1;
                }
            }
            super.visitLineNumber(line, start);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            beforeCode();
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        // ---- instructions ------------------------------------------------------------------------------------------

        /**
         * Before an instruction of {@code opcode}, on local {@code var} or -1: the pending handler's entry call, the
         * handler's first instruction, and the line window's count.
         */
        private void instruction(int opcode, int var) {
            beforeCode();
            for (Site site : sites) {
                if (site.awaitingFirst) {
                    site.awaitingFirst = false;
                    site.firstOpcode = opcode;
                    site.firstVar = var;
                }
            }
            if (hook != null) {
                Site site = hook;
                hook = null;
                super.visitInsn(Opcodes.DUP);
                super.visitIntInsn(Opcodes.SIPUSH, site.id);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "caught", CAUGHT_DESCRIPTOR, false);
                inserted = true;
            }
            for (Site site : sites) {
                if (site.counted >= 0 && ++site.counted > LINE_WINDOW) {
                    site.counted = -1;
                }
            }
        }

        @Override
        public void visitInsn(int opcode) {
            instruction(opcode, -1);
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            instruction(opcode, -1);
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            instruction(opcode, varIndex);
            stored(opcode, varIndex);
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            instruction(opcode, -1);
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String fieldOwner, String fieldName, String fieldDescriptor) {
            instruction(opcode, -1);
            super.visitFieldInsn(opcode, fieldOwner, fieldName, fieldDescriptor);
        }

        @Override
        public void visitMethodInsn(
                int opcode, String methodOwner, String methodName, String methodDescriptor, boolean isInterface) {
            instruction(opcode, -1);
            super.visitMethodInsn(opcode, methodOwner, methodName, methodDescriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(
                String indyName, String indyDescriptor, Handle bootstrap, Object... bootstrapArguments) {
            instruction(Opcodes.INVOKEDYNAMIC, -1);
            super.visitInvokeDynamicInsn(indyName, indyDescriptor, bootstrap, bootstrapArguments);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            instruction(opcode, -1);
            jumpTargets.add(label);
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            instruction(Opcodes.LDC, -1);
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            instruction(Opcodes.IINC, varIndex);
            if (varIndex < stores.length) {
                mark(varIndex, slotKinds[varIndex] == KIND_INT ? SAME_KIND : OTHER_KIND);
            }
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            instruction(Opcodes.TABLESWITCH, -1);
            jumpTargets.add(dflt);
            for (Label label : labels) {
                jumpTargets.add(label);
            }
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            instruction(Opcodes.LOOKUPSWITCH, -1);
            jumpTargets.add(dflt);
            for (Label label : labels) {
                jumpTargets.add(label);
            }
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String arrayDescriptor, int numDimensions) {
            instruction(Opcodes.MULTIANEWARRAY, -1);
            super.visitMultiANewArrayInsn(arrayDescriptor, numDimensions);
        }

        /** A store into a slot of {@code this} or a parameter: of its declared verification kind, or another. */
        private void stored(int opcode, int slot) {
            int kind;
            switch (opcode) {
                case Opcodes.ISTORE:
                    kind = KIND_INT;
                    break;
                case Opcodes.LSTORE:
                    kind = KIND_LONG;
                    break;
                case Opcodes.FSTORE:
                    kind = KIND_FLOAT;
                    break;
                case Opcodes.DSTORE:
                    kind = KIND_DOUBLE;
                    break;
                case Opcodes.ASTORE:
                    kind = KIND_REFERENCE;
                    break;
                default:
                    return;
            }
            boolean wide = kind == KIND_LONG || kind == KIND_DOUBLE;
            if (slot < stores.length) {
                // The receiver's slot keeps its type only while never stored.
                boolean receiver = !isStatic && slot == 0;
                mark(slot, !receiver && slotKinds[slot] == kind ? SAME_KIND : OTHER_KIND);
            }
            if (wide
                    && slot + 1 < stores.length
                    && !(slot < stores.length && slotKinds[slot] == kind && slotKinds[slot + 1] == 0)) {
                // The second half of a wide value lands on another parameter's slot.
                mark(slot + 1, OTHER_KIND);
            }
        }

        private void mark(int slot, int store) {
            if (stores[slot] < store) {
                stores[slot] = store;
            }
            if (store == OTHER_KIND && slotKinds[slot] == 0 && slot > 0) {
                // The second slot of a wide parameter: the whole parameter changes kind.
                stores[slot - 1] = OTHER_KIND;
            }
        }

        // ---- the end of the code -----------------------------------------------------------------------------------

        @Override
        public void visitLocalVariable(
                String variableName, String variableDescriptor, String signature, Label start, Label end, int index) {
            beforeCode();
            localVariableTable = true;
            namedSlots.add(Integer.valueOf(index));
            super.visitLocalVariable(variableName, variableDescriptor, signature, start, end, index);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            beforeCode();
            boolean exit = exitStart != null;
            if (exit) {
                super.visitLabel(exitEnd);
                super.visitLabel(exitHandler);
                Object[] locals = frameLocals();
                super.visitFrame(Opcodes.F_NEW, locals.length, locals, 1, new Object[] {"java/lang/Throwable"});
                super.visitInsn(Opcodes.DUP);
                super.visitIntInsn(Opcodes.SIPUSH, exitSite);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "leaving", LEAVING_DESCRIPTOR, false);
                super.visitInsn(Opcodes.ATHROW);
            }
            for (Site site : sites) {
                if (site.id < 0) {
                    continue;
                }
                int flags = 0;
                if (foreign(site)) {
                    flags |= CaughtExceptions.FLAG_FOREIGN;
                }
                if (jumpTargets.contains(site.label)) {
                    flags |= CaughtExceptions.FLAG_SHARED;
                }
                if (exit) {
                    flags |= CaughtExceptions.FLAG_EXIT_HANDLER;
                }
                if (constructor) {
                    flags |= CaughtExceptions.FLAG_CONSTRUCTOR;
                }
                CaughtExceptions.siteRead(site.id, flags, site.line);
            }
            super.visitMaxs(inserted || exit ? Math.max(maxStack, STACK) : maxStack, maxLocals);
        }

        /**
         * Whether the handler is one another agent's inlined advice added, by signals only such handlers show
         * together: in a method with line numbers, none within its first {@value #LINE_WINDOW} instructions (advice is
         * inlined without debug information), it catches exactly {@code java/lang/Throwable} (as advice's suppression
         * and exit-on-throwable handlers do), and it does not start by storing the exception into a local the method's
         * LocalVariableTable names (advice's locals are never named). javac and kotlinc give a handler on its try's
         * line no line number of its own, so the line alone would take {@code try { ... } catch (E e) {}} written on
         * one line for advice.
         */
        boolean foreign(Site site) {
            if (!anyLine || site.lineSeen || !"java/lang/Throwable".equals(site.types.toString())) {
                return false;
            }
            boolean namedStore = site.firstOpcode == Opcodes.ASTORE
                    && localVariableTable
                    && namedSlots.contains(Integer.valueOf(site.firstVar));
            return !namedStore;
        }

        /**
         * The exit handler's locals: {@code this} and each parameter at its declared type, or {@code TOP} where the
         * method stores a value of another verification kind; every other local is implicitly {@code TOP}.
         */
        Object[] frameLocals() {
            List<Object> locals = new ArrayList<Object>();
            int slot = 0;
            if (!isStatic) {
                locals.add(stores[0] == OTHER_KIND ? Opcodes.TOP : owner);
                slot = 1;
            }
            for (Type argument : Type.getArgumentTypes(descriptor)) {
                boolean other =
                        stores[slot] == OTHER_KIND || (argument.getSize() == 2 && stores[slot + 1] == OTHER_KIND);
                if (other) {
                    locals.add(Opcodes.TOP);
                    if (argument.getSize() == 2) {
                        locals.add(Opcodes.TOP);
                    }
                } else {
                    locals.add(frameType(argument));
                }
                slot += argument.getSize();
            }
            return locals.toArray();
        }

        static Object frameType(Type type) {
            switch (type.getSort()) {
                case Type.LONG:
                    return Opcodes.LONG;
                case Type.FLOAT:
                    return Opcodes.FLOAT;
                case Type.DOUBLE:
                    return Opcodes.DOUBLE;
                case Type.OBJECT:
                case Type.ARRAY:
                    return type.getInternalName();
                default:
                    return Opcodes.INTEGER;
            }
        }
    }
}
