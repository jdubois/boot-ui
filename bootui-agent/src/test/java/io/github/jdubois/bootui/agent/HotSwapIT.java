package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * IntelliJ HotSwap with the agent claimed (PLAN-v2 M5-1): a bean class of the claimed packages, instrumented by the
 * inventory and code-paths sensors, is redefined with an edited method body, through {@code Instrumentation} and through
 * JDI as IntelliJ IDEA's debugger does. The JVM hands the new bytes to the agent's transformer, which applies both
 * advices again with the same method ids; the method keeps its executed flag for the run, nothing fails, a schema change
 * the JVM refuses leaves the class as it was, and a release restores the HotSwapped body (on JDK 17, the body the class
 * was loaded with: JDK-7124710, fixed in JDK 20). Code Inventory hashes class files at each run, so a HotSwapped method
 * is compared only after the next restart, which is documented.
 */
class HotSwapIT {

    private static final String DEBUGGEE = "bootuiagentit.HotSwapBehaviors";
    private static final String SHOP = "bootuihotswapapp/Shop";

    private static final List<String> REQUIRED = List.of(
            "before the HotSwap, the bean's methods are timed and executed",
            "the HotSwapped body runs",
            "the code-paths advice is applied again to the HotSwapped class",
            "the inventory advice is applied again to the HotSwapped class",
            "every method keeps its id",
            "a HotSwapped method keeps its executed flag and is neither late nor failed",
            "a HotSwap the JVM refuses leaves the class running and instrumented",
            "a method a refused HotSwap would have added is not tracked",
            "a new run counts the HotSwapped method afresh under the same id",
            "a release restores the class without the advice, the HotSwapped body from JDK 20 on");

    @Test
    void aRedefinitionThroughInstrumentationReappliesTheAdviceWithTheSameIds() throws Exception {
        Path versions = versions();
        ChildJvm.Output output = ChildJvm.runWithClassPaths(
                List.of(
                        ChildJvm.javaAgent(ChildJvm.AGENT),
                        "-javaagent:" + System.getProperty("bytebuddy.agent.jar"),
                        "-Dbootui.agent.it.hotswap=" + versions),
                appJar(),
                null,
                "hotswap-behaviors",
                "instrumentation");

        assertAllPass(output.exitCode(), output.text(), output.toString());
        assertThat(output.value("REDEFINE_v2")).as(output.toString()).isEqualTo("ok");
        assertThat(output.value("REDEFINE_v3")).as(output.toString()).contains("UnsupportedOperationException");
    }

    @Test
    void aRedefinitionThroughJdiAsIntelliJsHotSwapReappliesTheAdviceWithTheSameIds() throws Exception {
        Path versions = versions();
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("home").setValue(System.getProperty("java.home"));
        arguments
                .get("options")
                .setValue("\"" + ChildJvm.javaAgent(ChildJvm.AGENT) + "\" -cp \"" + appJar() + File.pathSeparator
                        + ChildJvm.TEST_CLASSES + "\"");
        arguments.get("main").setValue("bootuiagentit.ChildMain hotswap-behaviors jdi");
        VirtualMachine vm = connector.launch(arguments);
        Drain out = new Drain(vm.process().getInputStream());
        Drain err = new Drain(vm.process().getErrorStream());
        List<String> redefinitions = new ArrayList<>();
        try {
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(DEBUGGEE);
            prepare.enable();
            boolean done = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
            while (!done && System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(1_000);
                if (events == null) {
                    continue;
                }
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent prepared) {
                        Method redefine = prepared.referenceType()
                                .methodsByName("redefine")
                                .get(0);
                        BreakpointRequest breakpoint =
                                vm.eventRequestManager().createBreakpointRequest(redefine.location());
                        breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        breakpoint.enable();
                    } else if (event instanceof BreakpointEvent hit) {
                        redefinitions.add(hotSwap(vm, hit.thread(), versions));
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        done = true;
                    }
                }
                events.resume();
            }
            assertThat(vm.process().waitFor(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (vm.process().isAlive()) {
                vm.process().destroyForcibly();
            }
        }
        out.await();
        err.await();
        String text = out.text() + err.text();
        String described = "JDI redefinitions " + redefinitions + "\n" + text;

        assertAllPass(vm.process().exitValue(), text, described);
        assertThat(redefinitions).as(described).hasSize(2);
        assertThat(redefinitions.get(0)).as(described).isEqualTo("v2 ok");
        assertThat(redefinitions.get(1)).as(described).contains("v3").contains("UnsupportedOperationException");
    }

    /** At the breakpoint on {@code redefine(version)}: redefines the class, as IntelliJ IDEA does, and tells the debuggee. */
    private static String hotSwap(VirtualMachine vm, ThreadReference thread, Path versions) throws Exception {
        String version = ((StringReference) thread.frame(0).getArgumentValues().get(0)).value();
        ReferenceType shop = vm.classesByName(SHOP.replace('/', '.')).get(0);
        String refusal = null;
        try {
            vm.redefineClasses(Map.of(shop, Files.readAllBytes(versions.resolve("Shop-" + version + ".class"))));
        } catch (UnsupportedOperationException | LinkageError ex) {
            refusal = ex.toString();
        }
        ClassType debuggee = (ClassType) thread.frame(0).location().declaringType();
        debuggee.setValue(debuggee.fieldByName("jdiRefusal"), refusal == null ? null : vm.mirrorOf(refusal));
        return version + " " + (refusal == null ? "ok" : refusal);
    }

    private static void assertAllPass(int exitCode, String text, String described) {
        assertThat(exitCode).as(described).isZero();
        assertThat(text).as(described).contains("SELF_TEST_code-paths=true null");
        assertThat(text).as(described).contains("SELF_TEST_inventory=true null");
        assertThat(text.lines().filter(line -> line.startsWith("  FAIL")))
                .as(described)
                .isEmpty();
        List<String> passed =
                text.lines().filter(line -> line.startsWith("  PASS ")).toList();
        for (String behavior : REQUIRED) {
            assertThat(passed)
                    .as("%s in %s", behavior, described)
                    .anySatisfy(line -> assertThat(line).startsWith("  PASS " + behavior));
        }
        String status = text.lines()
                .filter(line -> line.startsWith("STATUS="))
                .findFirst()
                .orElse("");
        assertThat(status).as(described).contains("errors=0");
    }

    /** The application jar: {@code Shop} from a code source that is not a test root, as an application's classes are. */
    private static String appJar() throws IOException {
        return TestJars.jar("hotswap-app.jar", "bootuihotswapapp", List.of()).toString();
    }

    /**
     * {@code Shop-v2.class}, the edit HotSwap applies ({@code price()} returns {@code base() + 2}), and
     * {@code Shop-v3.class}, which also adds a method, a schema change.
     */
    static Path versions() throws IOException {
        byte[] original = Files.readAllBytes(ChildJvm.TEST_CLASSES.resolve(SHOP + ".class"));
        Path directory = ChildJvm.WORK.resolve("hotswap");
        Files.createDirectories(directory);
        byte[] v2 = edit(original, false);
        Files.write(directory.resolve("Shop-v2.class"), v2);
        Files.write(directory.resolve("Shop-v3.class"), edit(v2, true));
        return directory;
    }

    private static byte[] edit(byte[] bytes, boolean addMethod) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {

                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (addMethod || !"price".equals(name)) {
                            return visitor;
                        }
                        return new MethodVisitor(Opcodes.ASM9, visitor) {

                            @Override
                            public void visitInsn(int opcode) {
                                super.visitInsn(opcode == Opcodes.ICONST_1 ? Opcodes.ICONST_2 : opcode);
                            }
                        };
                    }

                    @Override
                    public void visitEnd() {
                        if (addMethod) {
                            MethodVisitor extra = super.visitMethod(Opcodes.ACC_PUBLIC, "extra", "()I", null, null);
                            extra.visitCode();
                            extra.visitIntInsn(Opcodes.BIPUSH, 7);
                            extra.visitInsn(Opcodes.IRETURN);
                            extra.visitMaxs(0, 0);
                            extra.visitEnd();
                        }
                        super.visitEnd();
                    }
                },
                0);
        return writer.toByteArray();
    }

    /** Reads a stream to its end on its own thread, so the debuggee never blocks on a full pipe. */
    private static final class Drain extends Thread {

        private final InputStream stream;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Drain(InputStream stream) {
            this.stream = stream;
            setDaemon(true);
            start();
        }

        @Override
        public void run() {
            try {
                stream.transferTo(bytes);
            } catch (IOException ex) {
                // The debuggee is gone.
            }
        }

        String text() {
            synchronized (bytes) {
                return bytes.toString();
            }
        }

        void await() throws InterruptedException {
            join(10_000);
        }
    }
}
