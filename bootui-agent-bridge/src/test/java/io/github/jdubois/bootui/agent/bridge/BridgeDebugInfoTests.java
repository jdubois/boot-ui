package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * The bridge carries no line numbers and no local variable tables, only its source file names, so a debugger stepping
 * into an instrumented method steps over the inlined advice's calls into the bridge, rather than stopping inside
 * BootUI (PLAN-v2 M5-4a): on the compiled classes, and through JDI in a forked JVM whose stepping makes, line by line,
 * the calls an instrumented method and the adapters make into the bridge inside a request's scope.
 */
class BridgeDebugInfoTests {

    private static final String DEBUGGEE = "bridgedebuggee.StepDebuggee";

    /** The step filters debuggers apply by default. */
    private static final List<String> STEP_FILTERS = List.of("java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*");

    @Test
    void bridgeClassesHaveNoLineNumbersAndNoLocalVariables() throws IOException {
        Path classes = Path.of(System.getProperty("bridge.classes", "target/classes"));
        List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(path -> path.toString().endsWith(".class")).toList();
        }
        assertThat(files).isNotEmpty();
        List<String> debugInfo = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (Path file : files) {
            new ClassReader(Files.readAllBytes(file)).accept(new DebugInfo(debugInfo, sources), 0);
        }
        assertThat(debugInfo).isEmpty();
        assertThat(sources).as("source file names are kept for stack traces").hasSameSizeAs(files);
    }

    @Test
    void steppingIntoTheBridgesCallsStepsOverThem() throws Exception {
        String classPath = System.getProperty("bridge.classes", "target/classes")
                + File.pathSeparator
                + Path.of(StepDebugeeLocation.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("home").setValue(System.getProperty("java.home"));
        arguments.get("options").setValue("-cp \"" + classPath + "\"");
        arguments.get("main").setValue(DEBUGGEE);
        VirtualMachine vm = connector.launch(arguments);
        List<String> stops = new ArrayList<>();
        try {
            EventRequestManager requests = vm.eventRequestManager();
            ClassPrepareRequest prepare = requests.createClassPrepareRequest();
            prepare.addClassFilter(DEBUGGEE);
            prepare.enable();
            vm.resume();
            boolean done = false;
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (!done && System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(1_000);
                if (events == null) {
                    continue;
                }
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent prepared) {
                        ReferenceType type = prepared.referenceType();
                        Location first = type.methodsByName("steps")
                                .get(0)
                                .allLineLocations()
                                .get(0);
                        BreakpointRequest breakpoint = requests.createBreakpointRequest(first);
                        // The warm-up call passes first: the second call is the one stepped.
                        breakpoint.addCountFilter(2);
                        breakpoint.enable();
                    } else if (event instanceof BreakpointEvent hit) {
                        stops.add(describe(hit.location()));
                        step(requests, hit.thread());
                    } else if (event instanceof StepEvent stepped) {
                        requests.deleteEventRequest(stepped.request());
                        Location location = stepped.location();
                        stops.add(describe(location));
                        if ("main".equals(location.method().name())) {
                            done = true;
                        } else {
                            step(requests, stepped.thread());
                        }
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        done = true;
                    }
                }
                events.resume();
            }
        } finally {
            String output =
                    read(vm.process().getInputStream()) + read(vm.process().getErrorStream());
            try {
                vm.exit(0);
            } catch (RuntimeException ex) {
                // Already gone.
            }
            assertThat(output).doesNotContain("Exception");
        }

        // Every line of steps(), the line-numbered helper it steps into, then back in main: never in the bridge.
        assertThat(stops).as(stops.toString()).noneMatch(stop -> stop.startsWith("io.github.jdubois.bootui"));
        assertThat(stops.get(stops.size() - 1)).startsWith(DEBUGGEE + ".main:");
        assertThat(stops).as(stops.toString()).anyMatch(stop -> stop.startsWith(DEBUGGEE + ".local:"));
        assertThat(stops.stream()
                        .filter(stop -> stop.startsWith(DEBUGGEE + ".steps:"))
                        .distinct())
                .as(stops.toString())
                .hasSize(8);
    }

    private static void step(EventRequestManager requests, com.sun.jdi.ThreadReference thread) {
        StepRequest step = requests.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
        for (String filter : STEP_FILTERS) {
            step.addClassExclusionFilter(filter);
        }
        step.addCountFilter(1);
        step.enable();
    }

    private static String describe(Location location) {
        return location.declaringType().name() + "." + location.method().name() + ":" + location.lineNumber();
    }

    private static String read(InputStream stream) {
        try {
            return stream.available() > 0 ? new String(stream.readNBytes(stream.available())) : "";
        } catch (IOException ex) {
            return "";
        }
    }

    /** Locates the test classes' root, where the debuggee is compiled. */
    static final class StepDebugeeLocation {}

    /** Collects every line number and local variable entry, and every source file name. */
    static final class DebugInfo extends ClassVisitor {

        private final List<String> debugInfo;
        private final List<String> sources;
        private String owner;

        DebugInfo(List<String> debugInfo, List<String> sources) {
            super(Opcodes.ASM9);
            this.debugInfo = debugInfo;
            this.sources = sources;
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            owner = name;
        }

        @Override
        public void visitSource(String source, String debug) {
            if (source != null) {
                sources.add(owner + ": " + source);
            }
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            String where = owner + "." + name + descriptor;
            return new MethodVisitor(Opcodes.ASM9) {

                @Override
                public void visitLineNumber(int line, Label start) {
                    debugInfo.add(where + " has line " + line);
                }

                @Override
                public void visitLocalVariable(
                        String variable,
                        String variableDescriptor,
                        String variableSignature,
                        Label start,
                        Label end,
                        int index) {
                    debugInfo.add(where + " has local variable " + variable);
                }
            };
        }
    }
}
