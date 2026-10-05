package io.github.jdubois.bootui.agent;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bootuicaughtapp.ExitAdvice;
import bootuicaughtapp.Handlers;
import io.github.jdubois.bootui.agent.bridge.CaughtExceptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;

/**
 * The caught-exceptions visit (PLAN-v2 M5-6a) on javac's own output and on hand-made bytecode: the JVM defines and
 * verifies every transformed class, which then behaves exactly as before; the exit handler's entry follows the
 * method's own and precedes an advice's; and each site is registered with the flags its handler earns.
 */
class CaughtExceptionsVisitTests {

    private static final List<String> FIXTURES = List.of(
            "bootuicaughtapp.Handlers",
            "bootuicaughtapp.Handlers$1",
            "bootuicaughtapp.Handlers$Defaults",
            "bootuicaughtapp.Rethrow");

    @Test
    void javacFixturesVerifyAndBehaveTheSameWithTheVisitAlone() throws Exception {
        assertBehavesLikeTheOriginal(load(CaughtExceptionsVisitTests::visitAlone));
    }

    @Test
    void javacFixturesVerifyAndBehaveTheSameBeneathAnAdviceThatChecksFrames() throws Exception {
        int before = ExitAdvice.EXITS.get();
        assertBehavesLikeTheOriginal(load(CaughtExceptionsVisitTests::withAdvice));
        assertThat(ExitAdvice.EXITS.get()).as("the advice still ran").isGreaterThan(before);
    }

    @Test
    void theExitHandlersEntryFollowsTheMethodsOwnAndPrecedesTheAdvices() throws Exception {
        Map<String, List<String>> tables =
                tables(withAdvice("bootuicaughtapp.Handlers", original("bootuicaughtapp.Handlers")));

        // The method's own entry, then the visit's catch-any, then the advice's: each keeps its precedence.
        assertThat(tables.get("rethrows()I")).containsExactly("java/lang/IllegalStateException", "leaving", "advice");
        // javac splits the outer handler's range around the inner one: three entries of the method's own.
        assertThat(tables.get("nested()I"))
                .containsExactly(
                        "java/lang/IllegalStateException",
                        "java/lang/IllegalStateException",
                        "java/lang/IllegalStateException",
                        "leaving",
                        "advice");
        // A finally alone has no typed handler: no exit handler is added.
        assertThat(tables.get("finallyOnly()I")).doesNotContain("leaving");
        // A constructor's throws are not observed: its handler is reported, no exit handler is added.
        assertThat(tables.get("<init>()V")).doesNotContain("leaving");
    }

    @Test
    void handlerEntriesCallTheBridgeAndCatchAnyHandlersAreLeftAlone() throws Exception {
        Map<String, List<String>> calls = handlerCalls(visitAlone(original("bootuicaughtapp.Handlers")));

        assertThat(calls.get("swallowed()I")).containsExactly("caught");
        assertThat(calls.get("multi(I)I")).containsExactly("caught");
        // The monitor exits' catch-any handlers are not reported; the typed one is.
        assertThat(calls.get("locked()I")).containsExactly("caught");
        assertThat(calls.get("finallyOnly()I")).isEmpty();
    }

    @Test
    void sitesAreRegisteredWithTheirTypesLinesAndFlags() throws Exception {
        visitAlone(original("bootuicaughtapp.Handlers"));
        Map<String, String[]> sites = sites();

        String[] swallowed = sites.get("bootuicaughtapp/Handlers#swallowed()I#0#java/io/IOException");
        assertThat(swallowed).isNotNull();
        assertThat(Integer.parseInt(swallowed[2])).as("line").isPositive();
        assertThat(flags(swallowed))
                .isEqualTo(CaughtExceptions.FLAG_EXIT_HANDLER
                        | CaughtExceptions.FLAG_COMPLETE
                        | CaughtExceptions.SHAPE_DISCARDS);

        String[] multi = sites.get("bootuicaughtapp/Handlers#multi(I)I#0#java/lang/IllegalStateException|"
                + "java/lang/UnsupportedOperationException");
        assertThat(multi).as("one site for a multi-catch: %s", sites.keySet()).isNotNull();

        String[] constructor = sites.get("bootuicaughtapp/Handlers#<init>()V#0#java/lang/NumberFormatException");
        assertThat(constructor).isNotNull();
        assertThat(flags(constructor) & CaughtExceptions.FLAG_CONSTRUCTOR).isNotZero();
        assertThat(flags(constructor) & CaughtExceptions.FLAG_EXIT_HANDLER).isZero();

        assertThat(sites.keySet()).noneMatch(key -> key.startsWith("bootuicaughtapp/Handlers#finallyOnly"));
        assertThat(sites.keySet()).anyMatch(key -> key.startsWith("bootuicaughtapp/Handlers#lambda$lambda$"));

        // A try-with-resources: the user's own handler, and javac's handlers closing the resource, which rethrow, so
        // their method's exit handler sees the throw; javac gives some of them no line number.
        List<String[]> resources = sites.entrySet().stream()
                .filter(site -> site.getKey().startsWith("bootuicaughtapp/Handlers#resources()I#"))
                .map(Map.Entry::getValue)
                .toList();
        assertThat(resources)
                .as("%s", sites.keySet())
                .filteredOn(site -> site[1].equals("java/io/IOException"))
                .singleElement()
                .satisfies(site ->
                        assertThat(flags(site) & CaughtExceptions.FLAG_FOREIGN).isZero());
        assertThat(resources)
                .filteredOn(site -> site[1].equals("java/lang/Throwable"))
                .isNotEmpty()
                .allSatisfy(site -> assertThat(flags(site) & CaughtExceptions.FLAG_EXIT_HANDLER)
                        .isNotZero());
    }

    @Test
    void handlerShapesAreReadFromTheHandlersOwnCode() throws Exception {
        visitAlone(original("bootuicaughtapp.Handlers"));
        Map<String, String[]> sites = sites();

        assertThat(shapes(sites, "swallowed()I#0#java/io/IOException")).isEqualTo(CaughtExceptions.SHAPE_DISCARDS);
        // Straight-line code ending by a throw: a rethrow or a wrap shows it too, and is recorded as rethrown first.
        assertThat(shapes(sites, "rethrows()I#0#java/lang/IllegalStateException"))
                .isEqualTo(CaughtExceptions.SHAPE_THROWS_NEW);
        assertThat(shapes(sites, "wraps()I#0#java/io/IOException")).isEqualTo(CaughtExceptions.SHAPE_THROWS_NEW);
        assertThat(shapes(sites, "replaced()I#0#java/io/IOException"))
                .isEqualTo(CaughtExceptions.SHAPE_DISCARDS | CaughtExceptions.SHAPE_THROWS_NEW);
        assertThat(shapes(sites, "replacedSometimes(Z)I#0#java/io/IOException"))
                .isEqualTo(CaughtExceptions.SHAPE_DISCARDS);
        assertThat(shapes(sites, "nestedThenHandsOn()Ljava/util/concurrent/CompletableFuture;#0#java/io/IOException"))
                .isEqualTo(CaughtExceptions.SHAPE_PASSES_AS_VALUE);
        assertThat(shapes(sites, "emitted(Lbootuicaughtapp/Handlers$Emitter;)I#0#java/io/IOException"))
                .isEqualTo(CaughtExceptions.SHAPE_PASSES_AS_VALUE);
        assertThat(shapes(sites, "logged(Lbootuicaughtapp/Handlers$AuditLogger;)I#0#java/io/IOException"))
                .isZero();
        assertThat(shapes(sites, "printed()I#0#java/lang/IllegalStateException"))
                .isEqualTo(CaughtExceptions.SHAPE_PRINTS_STACK_TRACE);
        assertThat(shapes(sites, "interrupted()I#0#java/lang/InterruptedException"))
                .isEqualTo(CaughtExceptions.SHAPE_DISCARDS | CaughtExceptions.SHAPE_REINTERRUPTS);
        assertThat(shapes(sites, "handedOn()Ljava/util/concurrent/CompletableFuture;#0#java/io/IOException"))
                .isEqualTo(CaughtExceptions.SHAPE_PASSES_AS_VALUE);
        assertThat(shapes(sites, "readsLater()I#0#java/io/IOException")).isZero();
    }

    private static int shapes(Map<String, String[]> sites, String key) {
        String[] site = sites.get("bootuicaughtapp/Handlers#" + key);
        assertThat(site).as("%s in %s", key, sites.keySet()).isNotNull();
        return flags(site)
                & (CaughtExceptions.SHAPE_DISCARDS
                        | CaughtExceptions.SHAPE_PRINTS_STACK_TRACE
                        | CaughtExceptions.SHAPE_REINTERRUPTS
                        | CaughtExceptions.SHAPE_PASSES_AS_VALUE
                        | CaughtExceptions.SHAPE_THROWS_NEW);
    }

    @Test
    void theSameCodeGetsTheSameSiteIds() throws Exception {
        visitAlone(original("bootuicaughtapp.Handlers"));
        Map<String, String[]> first = sites();
        int count = CaughtExceptions.siteCount();
        visitAlone(original("bootuicaughtapp.Handlers"));

        assertThat(CaughtExceptions.siteCount()).isEqualTo(count);
        assertThat(sites().keySet()).isEqualTo(first.keySet());
    }

    @Test
    void aParameterSlotStoredWithAnotherKindIsTopInTheExitFrameAndStillVerifies() throws Exception {
        byte[] transformed = visitAlone(Hand.storesAReferenceIntoAnIntParameter());

        assertThat(exitFrames(transformed).get("m(I)I")).containsExactly("TOP");
        assertThat(run(Hand.STORES, transformed, "m", 5)).isEqualTo(1);
    }

    @Test
    void aParameterReassignedWithItsOwnKindKeepsItsDeclaredTypeInTheExitFrame() throws Exception {
        byte[] transformed = visitAlone(original("bootuicaughtapp.Handlers"));

        assertThat(exitFrames(transformed).get("wide(JDLjava/lang/String;I)J"))
                .containsExactly("LONG", "DOUBLE", "java/lang/String", "INTEGER");
        assertThat(exitFrames(transformed).get("multi(I)I")).containsExactly("bootuicaughtapp/Handlers", "INTEGER");
    }

    @Test
    void aHandlerThatIsAlsoAJumpTargetIsShared() throws Exception {
        byte[] transformed = visitAlone(Hand.jumpIntoTheHandler());

        String[] site = sites().get(Hand.JUMP.replace('.', '/') + "#j(I)I#0#java/lang/IllegalStateException");
        assertThat(site).isNotNull();
        assertThat(flags(site) & CaughtExceptions.FLAG_SHARED).isNotZero();
        assertThat(run(Hand.JUMP, transformed, "j", 0)).isEqualTo(2);
        assertThat(run(Hand.JUMP, transformed, "j", 1)).isEqualTo(2);
    }

    @Test
    void aThrowableHandlerWithoutALineNumberOrANamedLocalInAMethodWithLinesIsForeign() throws Exception {
        byte[] transformed = visitAlone(Hand.handlerWithoutLine("java/lang/Throwable"));

        String[] site = sites().get(Hand.FOREIGN.replace('.', '/') + "#f()I#0#java/lang/Throwable");
        assertThat(site).isNotNull();
        assertThat(flags(site) & CaughtExceptions.FLAG_FOREIGN).isNotZero();
        assertThat(run(Hand.FOREIGN, transformed, "f", null)).isEqualTo(3);
    }

    /** Inlined advice catches {@code Throwable}: a handler of another type without a line number is the application's. */
    @Test
    void aTypedHandlerWithoutALineNumberIsTheApplications() throws Exception {
        visitAlone(Hand.handlerWithoutLine("java/lang/IllegalStateException"));

        String[] site = sites().get(Hand.FOREIGN.replace('.', '/') + "#f()I#0#java/lang/IllegalStateException");
        assertThat(site).isNotNull();
        assertThat(flags(site) & CaughtExceptions.FLAG_FOREIGN).isZero();
    }

    /**
     * javac gives no handler a line number of its own when it is on its try's line ({@code Code.addLineNumber} skips a
     * repeated line), so a whole try/catch written on one line is the application's, whatever it catches. Compiled
     * here, from source, so no formatter can split the line.
     */
    @Test
    void aTryCatchWrittenOnOneLineIsTheApplications() throws Exception {
        String source = "package bootuicaughtoneline;\n"
                + "public class OneLine {\n"
                + "    public static int sleep(long millis) {\n"
                + "        try { Thread.sleep(millis); } catch (InterruptedException e) { return 1; }\n"
                + "        return 0;\n"
                + "    }\n"
                + "    public static int parse(String text) {\n"
                + "        int value; try { value = Integer.parseInt(text); } catch (NumberFormatException e) { value = -1; } return value;\n"
                + "    }\n"
                + "    public static int any(Object value) {\n"
                + "        try { return value.hashCode(); } catch (Throwable t) { return -2; }\n"
                + "    }\n"
                + "}\n";
        byte[] original = OneLineSource.compile("bootuicaughtoneline.OneLine", source);
        assertThat(lines(original).get("parse(Ljava/lang/String;)I"))
                .as("javac gave the one-line method's handler no line of its own")
                .hasSize(1);
        byte[] transformed = visitAlone(original);

        Map<String, String[]> sites = sites();
        for (String key : List.of(
                "bootuicaughtoneline/OneLine#sleep(J)I#0#java/lang/InterruptedException",
                "bootuicaughtoneline/OneLine#parse(Ljava/lang/String;)I#0#java/lang/NumberFormatException",
                "bootuicaughtoneline/OneLine#any(Ljava/lang/Object;)I#0#java/lang/Throwable")) {
            assertThat(sites.get(key)).as(key).isNotNull();
            assertThat(flags(sites.get(key)) & CaughtExceptions.FLAG_FOREIGN)
                    .as(key)
                    .isZero();
        }
        Class<?> type = Class.forName(
                "bootuicaughtoneline.OneLine", true, new Loader(Map.of("bootuicaughtoneline.OneLine", transformed)));
        assertThat(type.getMethod("parse", String.class).invoke(null, "x")).isEqualTo(-1);
        assertThat(type.getMethod("any", Object.class).invoke(null, (Object) null))
                .isEqualTo(-2);
    }

    /** kotlinc's one-line {@code try}/{@code catch} expression, compiled by the build, is the application's too. */
    @Test
    void kotlinsOneLineTryCatchIsTheApplications() throws Exception {
        visitAlone(original("bootuicaughtkt.OneLineKt"));

        Map<String, String[]> sites = sites();
        assertThat(sites.keySet())
                .as("%s", sites.keySet())
                .anyMatch(key -> key.startsWith("bootuicaughtkt/OneLineKt#parse(Ljava/lang/String;)I#0#"));
        sites.forEach((key, site) -> {
            if (key.startsWith("bootuicaughtkt/OneLineKt#")) {
                assertThat(flags(site) & CaughtExceptions.FLAG_FOREIGN).as(key).isZero();
            }
        });
    }

    @Test
    void classesOlderThanJava7AreLeftAlone() throws Exception {
        byte[] original = Hand.java6();
        byte[] transformed = visitAlone(original);

        assertThat(handlerCalls(transformed).get("m()I")).isEmpty();
        assertThat(sites().keySet()).noneMatch(key -> key.startsWith(Hand.JAVA6.replace('.', '/')));
        assertThat(run(Hand.JAVA6, transformed, "m", null)).isEqualTo(4);
    }

    @Test
    void anExceptionTableEntryAfterTheCodeFailsTheTransformation() {
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor visit = new CaughtExceptionsVisit().wrap(null, writer, null, null, null, null, 0, 0);
        visit.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "late/Table", null, "java/lang/Object", null);
        MethodVisitor method = visit.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V", null, null);
        method.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        method.visitLabel(start);

        assertThatThrownBy(() -> method.visitTryCatchBlock(start, end, handler, "java/lang/Error"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after the code");
    }

    // ---- behavior -------------------------------------------------------------------------------------------------

    private static void assertBehavesLikeTheOriginal(ClassLoader loader) throws Exception {
        Class<?> type = Class.forName("bootuicaughtapp.Handlers", true, loader);
        assertThat(type.getClassLoader()).isSameAs(loader);
        Object handlers = type.getConstructor().newInstance();
        assertThat(call(handlers, "built")).isEqualTo(3);
        assertThat(call(handlers, "swallowed")).isEqualTo(1);
        assertThat(thrown(handlers, "rethrows")).isInstanceOf(IllegalStateException.class);
        assertThat(thrown(handlers, "wraps"))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(thrown(handlers, "helper")).isInstanceOf(IllegalArgumentException.class);
        assertThat(call(handlers, "nested")).isEqualTo(2);
        assertThat(call(handlers, "finallyOnly")).isEqualTo(4);
        assertThat(call(handlers, "finallyOnly")).isEqualTo(5);
        assertThat(call(handlers, "multi", 0)).isEqualTo(5);
        assertThat(call(handlers, "multi", 1)).isEqualTo(6);
        assertThat(call(handlers, "loop", 20)).isEqualTo(20);
        Method wide = type.getMethod("wide", long.class, double.class, String.class, int.class);
        assertThat(wide.invoke(null, 1L, 2.0d, " a ", 3)).isEqualTo(10L);
        assertThat(wide.invoke(null, 1L, 2.0d, " ", 3)).isEqualTo(-1L);
        assertThat(call(handlers, "lambda")).isEqualTo(7);
        assertThat(call(handlers, "resources")).isEqualTo(8);
        assertThat(call(handlers, "locked")).isEqualTo(9);
        assertThat(call(handlers, "defaults")).isEqualTo(10);
        // The original code, through the test's own class loader, for reference.
        Handlers reference = new Handlers();
        assertThat(reference.swallowed()).isEqualTo(1);
        assertThat(Handlers.wide(1L, 2.0d, " a ", 3)).isEqualTo(10L);
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        Class<?>[] types = new Class<?>[arguments.length];
        Arrays.fill(types, int.class);
        return target.getClass().getMethod(name, types).invoke(target, arguments);
    }

    private static Throwable thrown(Object target, String name) throws Exception {
        try {
            target.getClass().getMethod(name).invoke(target);
            return null;
        } catch (InvocationTargetException ex) {
            return ex.getCause();
        }
    }

    private static Object run(String className, byte[] bytes, String method, Integer argument) throws Exception {
        Map<String, byte[]> classes = Map.of(className, bytes);
        ClassLoader loader = new Loader(classes);
        Class<?> type = Class.forName(className, true, loader);
        if (argument == null) {
            return type.getMethod(method).invoke(null);
        }
        return type.getMethod(method, int.class).invoke(null, argument);
    }

    // ---- transformations ------------------------------------------------------------------------------------------

    /** The visit alone, through ASM, as the agent's transformer applies it when no advice applies. */
    static byte[] visitAlone(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        CaughtExceptionsVisit visit = new CaughtExceptionsVisit();
        reader.accept(visit.wrap(null, writer, null, null, null, null, 0, 0), visit.mergeReader(0));
        return writer.toByteArray();
    }

    /** Beneath an advice that checks frames, applied first, so the visit is the outermost, as in the agent. */
    static byte[] withAdvice(String name, byte[] original) {
        ClassFileLocator locator = new ClassFileLocator.Compound(
                ClassFileLocator.Simple.of(name, original),
                ClassFileLocator.ForClassLoader.of(CaughtExceptionsVisitTests.class.getClassLoader()));
        TypeDescription type = TypePool.Default.of(locator).describe(name).resolve();
        return new ByteBuddy()
                .decorate(type, locator)
                .visit(Advice.to(ExitAdvice.class)
                        .on(isMethod().and(not(isAbstract())).and(not(isNative()))))
                .visit(new CaughtExceptionsVisit())
                .make()
                .getBytes();
    }

    private static byte[] withAdvice(byte[] original) {
        return withAdvice(new ClassReader(original).getClassName().replace('/', '.'), original);
    }

    private static ClassLoader load(Function<byte[], byte[]> transformation) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        for (String name : FIXTURES) {
            classes.put(name, transformation.apply(original(name)));
        }
        return new Loader(classes);
    }

    static byte[] original(String name) throws IOException {
        try (InputStream in = CaughtExceptionsVisitTests.class
                .getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Defines the given classes itself, child first, and delegates every other class to the test's class loader. */
    static final class Loader extends ClassLoader {

        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes) {
            super(CaughtExceptionsVisitTests.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                byte[] bytes = classes.get(name);
                if (type == null && bytes != null) {
                    type = defineClass(name, bytes, 0, bytes.length);
                }
                if (type == null) {
                    return super.loadClass(name, resolve);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }
    }

    // ---- reading the output ---------------------------------------------------------------------------------------

    /** Per method, its exception table in order: each entry's type, or {@code leaving} or {@code advice} for added ones. */
    static Map<String, List<String>> tables(byte[] bytes) {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                List<String> table = new ArrayList<>();
                                tables.put(name + descriptor, table);
                                return new HandlerReader(table);
                            }
                        },
                        0);
        return tables;
    }

    /** Reads each entry's handler code to tell the visit's exit handler, which calls {@code leaving}, from others. */
    static final class HandlerReader extends MethodVisitor {

        private final List<String> table;
        private final List<Label> handlers = new ArrayList<>();
        private final List<String> types = new ArrayList<>();
        private final Map<Label, String> kinds = new HashMap<>();
        private Label current;
        private int seen;

        HandlerReader(List<String> table) {
            super(Opcodes.ASM9);
            this.table = table;
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            handlers.add(handler);
            types.add(type);
        }

        @Override
        public void visitLabel(Label label) {
            if (handlers.contains(label)) {
                current = label;
                seen = 0;
            }
        }

        @Override
        public void visitInsn(int opcode) {
            seen++;
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            seen++;
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            seen++;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            seen++;
            if (current != null && seen <= 3 && owner.equals(CaughtExceptionsVisit.BRIDGE) && name.equals("leaving")) {
                kinds.put(current, "leaving");
            }
        }

        @Override
        public void visitEnd() {
            int leaving = -1;
            for (int i = 0; i < handlers.size() && leaving < 0; i++) {
                if (kinds.containsKey(handlers.get(i))) {
                    leaving = i;
                }
            }
            for (int i = 0; i < handlers.size(); i++) {
                if (i == leaving) {
                    table.add("leaving");
                } else if (leaving >= 0 && i > leaving) {
                    table.add("advice");
                } else {
                    table.add(types.get(i) == null ? "any" : types.get(i));
                }
            }
        }
    }

    /** Per method, the bridge calls its handlers make within their first three instructions. */
    static Map<String, List<String>> handlerCalls(byte[] bytes) {
        Map<String, List<String>> calls = new LinkedHashMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                List<String> found = new ArrayList<>();
                                calls.put(name + descriptor, found);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String method,
                                            String methodDescriptor,
                                            boolean isInterface) {
                                        if (owner.equals(CaughtExceptionsVisit.BRIDGE) && method.equals("caught")) {
                                            found.add(method);
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return calls;
    }

    /** Per method, the locals of the exit handler's frame: the frame whose stack is one {@code java/lang/Throwable} last. */
    static Map<String, List<String>> exitFrames(byte[] bytes) {
        Map<String, List<String>> frames = new LinkedHashMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private List<String> last;
                                    private boolean leaving;

                                    @Override
                                    public void visitFrame(
                                            int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                                        last = new ArrayList<>();
                                        for (int i = 0; i < numLocal; i++) {
                                            last.add(describe(local[i]));
                                        }
                                        leaving = false;
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String method,
                                            String methodDescriptor,
                                            boolean isInterface) {
                                        if (owner.equals(CaughtExceptionsVisit.BRIDGE)
                                                && method.equals("leaving")
                                                && last != null) {
                                            frames.put(name + descriptor, last);
                                            leaving = true;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.EXPAND_FRAMES);
        return frames;
    }

    /** Per method, the lines its LineNumberTable lists. */
    static Map<String, List<Integer>> lines(byte[] bytes) {
        Map<String, List<Integer>> lines = new LinkedHashMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                List<Integer> found = new ArrayList<>();
                                lines.put(name + descriptor, found);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitLineNumber(int line, Label start) {
                                        found.add(line);
                                    }
                                };
                            }
                        },
                        0);
        return lines;
    }

    /** Compiles one class from source with javac's default debug information, in memory. */
    static final class OneLineSource {

        static byte[] compile(String name, String source) throws IOException {
            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            assertThat(compiler).as("javac").isNotNull();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            javax.tools.JavaFileObject input =
                    new javax.tools.SimpleJavaFileObject(
                            java.net.URI.create("string:///" + name.replace('.', '/') + ".java"),
                            javax.tools.JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                            return source;
                        }
                    };
            javax.tools.StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, null);
            javax.tools.JavaFileManager files = new javax.tools.ForwardingJavaFileManager<>(standard) {
                @Override
                public javax.tools.JavaFileObject getJavaFileForOutput(
                        Location location,
                        String className,
                        javax.tools.JavaFileObject.Kind kind,
                        javax.tools.FileObject sibling) {
                    return new javax.tools.SimpleJavaFileObject(
                            java.net.URI.create("bytes:///" + className.replace('.', '/') + ".class"), kind) {
                        @Override
                        public java.io.OutputStream openOutputStream() {
                            return bytes;
                        }
                    };
                }
            };
            Boolean compiled = compiler.getTask(
                            null, files, null, List.of("-g", "--release", "17"), null, List.of(input))
                    .call();
            assertThat(compiled).as("compiled %s", name).isTrue();
            return bytes.toByteArray();
        }
    }

    static String describe(Object local) {
        if (local == Opcodes.TOP) {
            return "TOP";
        }
        if (local == Opcodes.INTEGER) {
            return "INTEGER";
        }
        if (local == Opcodes.LONG) {
            return "LONG";
        }
        if (local == Opcodes.DOUBLE) {
            return "DOUBLE";
        }
        if (local == Opcodes.FLOAT) {
            return "FLOAT";
        }
        return String.valueOf(local);
    }

    /** The registered sites by key: {@code key, types, line, flags}. */
    static Map<String, String[]> sites() {
        Map<String, String[]> sites = new HashMap<>();
        for (String site : CaughtExceptions.sites(0)) {
            if (site != null) {
                String[] parts = site.split("\t");
                sites.put(parts[0], parts);
            }
        }
        return sites;
    }

    static int flags(String[] site) {
        return Integer.parseInt(site[3]);
    }

    // ---- hand-made bytecode ---------------------------------------------------------------------------------------

    /** Classes javac would not write, built with ASM and explicit frames. */
    static final class Hand {

        static final String STORES = "bootuicaughthand.Stores";
        static final String JUMP = "bootuicaughthand.Jump";
        static final String FOREIGN = "bootuicaughthand.Foreign";
        static final String JAVA6 = "bootuicaughthand.Java6";

        /** {@code static int m(int x)}: stores a reference into {@code x}'s slot inside its try, then throws. */
        static byte[] storesAReferenceIntoAnIntParameter() {
            ClassWriter writer = start(STORES, Opcodes.V17);
            MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "(I)I", null, null);
            m.visitCode();
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            m.visitTryCatchBlock(start, end, handler, "java/lang/IllegalStateException");
            m.visitLabel(start);
            m.visitLineNumber(10, start);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitVarInsn(Opcodes.ASTORE, 0);
            throwNew(m);
            m.visitLabel(end);
            m.visitLabel(handler);
            m.visitLineNumber(11, handler);
            m.visitFrame(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {"java/lang/IllegalStateException"});
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.ICONST_1);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(2, 1);
            m.visitEnd();
            return end(writer);
        }

        /** {@code static int j(int x)}: jumps into its own handler with the exception on the stack when {@code x} is 0. */
        static byte[] jumpIntoTheHandler() {
            ClassWriter writer = start(JUMP, Opcodes.V17);
            MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "j", "(I)I", null, null);
            m.visitCode();
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            m.visitTryCatchBlock(start, end, handler, "java/lang/IllegalStateException");
            m.visitLabel(start);
            m.visitLineNumber(20, start);
            m.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
            m.visitVarInsn(Opcodes.ILOAD, 0);
            m.visitJumpInsn(Opcodes.IFEQ, handler);
            m.visitInsn(Opcodes.ATHROW);
            m.visitLabel(end);
            m.visitLabel(handler);
            m.visitLineNumber(21, handler);
            m.visitFrame(Opcodes.F_FULL, 1, new Object[] {Opcodes.INTEGER}, 1, new Object[] {
                "java/lang/IllegalStateException"
            });
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.ICONST_2);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(3, 1);
            m.visitEnd();
            return end(writer);
        }

        /** {@code static int f()}: its handler's first line number comes six instructions in. */
        static byte[] handlerWithoutLine(String caught) {
            ClassWriter writer = start(FOREIGN, Opcodes.V17);
            MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "f", "()I", null, null);
            m.visitCode();
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            Label later = new Label();
            m.visitTryCatchBlock(start, end, handler, caught);
            m.visitLabel(start);
            m.visitLineNumber(30, start);
            throwNew(m);
            m.visitLabel(end);
            m.visitLabel(handler);
            m.visitFrame(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {caught});
            m.visitInsn(Opcodes.POP);
            for (int i = 0; i < 5; i++) {
                m.visitInsn(Opcodes.NOP);
            }
            m.visitLabel(later);
            m.visitLineNumber(31, later);
            m.visitInsn(Opcodes.ICONST_3);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(2, 0);
            m.visitEnd();
            return end(writer);
        }

        /** A Java 6 class with a handler: no stack map frames required, so the visit leaves it alone. */
        static byte[] java6() {
            ClassWriter writer = start(JAVA6, Opcodes.V1_6);
            MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()I", null, null);
            m.visitCode();
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            m.visitTryCatchBlock(start, end, handler, "java/lang/IllegalStateException");
            m.visitLabel(start);
            throwNew(m);
            m.visitLabel(end);
            m.visitLabel(handler);
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.ICONST_4);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(2, 0);
            m.visitEnd();
            return end(writer);
        }

        private static ClassWriter start(String name, int version) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(
                    version,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                    name.replace('.', '/'),
                    null,
                    "java/lang/Object",
                    null);
            return writer;
        }

        private static byte[] end(ClassWriter writer) {
            writer.visitEnd();
            return writer.toByteArray();
        }

        private static void throwNew(MethodVisitor m) {
            m.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
            m.visitInsn(Opcodes.ATHROW);
        }
    }
}
