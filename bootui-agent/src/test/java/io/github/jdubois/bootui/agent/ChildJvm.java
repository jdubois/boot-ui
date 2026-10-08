package io.github.jdubois.bootui.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs {@code bootuiagentit.ChildMain} in a forked JVM of the current JDK and returns what it printed. */
final class ChildJvm {

    static final Path AGENT = Path.of(System.getProperty("bootui.agent.jar"));
    static final Path TEST_AGENT = Path.of(System.getProperty("bootui.agent.it.jar"));
    static final Path TEST_CLASSES = Path.of(System.getProperty("bootui.agent.test-classes"));
    static final Path WORK = Path.of(System.getProperty("bootui.agent.work"));

    private ChildJvm() {}

    static Output run(List<String> jvmOptions, String... programArguments) throws IOException, InterruptedException {
        return run(jvmOptions, null, programArguments);
    }

    static Output run(List<String> jvmOptions, Path extraClassPath, String... programArguments)
            throws IOException, InterruptedException {
        return runWithClassPath(
                jvmOptions, extraClassPath == null ? null : extraClassPath.toString(), programArguments);
    }

    static Output runWithClassPath(List<String> jvmOptions, String extraClassPath, String... programArguments)
            throws IOException, InterruptedException {
        return runWithClassPaths(jvmOptions, null, extraClassPath, programArguments);
    }

    /** With {@code firstClassPath} before the test classes, so its classes win, and {@code extraClassPath} after. */
    static Output runWithClassPaths(
            List<String> jvmOptions, String firstClassPath, String extraClassPath, String... programArguments)
            throws IOException, InterruptedException {
        Files.createDirectories(WORK);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.add("-cp");
        String classPath = TEST_CLASSES.toString();
        if (firstClassPath != null) {
            classPath = firstClassPath + java.io.File.pathSeparator + classPath;
        }
        if (extraClassPath != null) {
            classPath = classPath + java.io.File.pathSeparator + extraClassPath;
        }
        command.add(classPath);
        command.add("bootuiagentit.ChildMain");
        command.addAll(List.of(programArguments));
        ProcessBuilder builder =
                new ProcessBuilder(command).redirectErrorStream(true).directory(WORK.toFile());
        Map<String, String> environment = builder.environment();
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.remove("_JAVA_OPTIONS");
        Path log = Files.createTempFile(WORK, "child-", ".log");
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            String threads = threadDump(process);
            process.destroyForcibly();
            throw new IllegalStateException(
                    "child JVM timed out: " + Files.readString(log) + "\n---- its threads ----\n" + threads);
        }
        return new Output(process.exitValue(), Files.readString(log), command);
    }

    /**
     * The threads of a child JVM that hung, from {@code jcmd Thread.print}, so its timeout says where it waited; bounded,
     * since a JVM that cannot reach a safepoint never answers.
     */
    private static String threadDump(Process process) {
        try {
            Path dump = Files.createTempFile(WORK, "child-threads-", ".txt");
            Process jcmd = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "jcmd")
                                    .toString(),
                            Long.toString(process.pid()),
                            "Thread.print",
                            "-l")
                    .redirectErrorStream(true)
                    .redirectOutput(dump.toFile())
                    .start();
            if (!jcmd.waitFor(30, TimeUnit.SECONDS)) {
                jcmd.destroyForcibly();
                return "jcmd did not answer within 30 s: " + Files.readString(dump);
            }
            return Files.readString(dump);
        } catch (IOException | RuntimeException ex) {
            return "no thread dump: " + ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "no thread dump: interrupted";
        }
    }

    static String javaAgent(Path jar) {
        return "-javaagent:" + jar;
    }

    record Output(int exitCode, String text, List<String> command) {

        /** An integer entry of a printed {@code Map}, such as {@code retransformed=3} in the installer status. */
        int number(String key, String entry) {
            String map = value(key);
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("\\b" + entry + "=(\\d+)").matcher(map == null ? "" : map);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        }

        String value(String key) {
            for (String line : text.split("\\R")) {
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1);
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "exit " + exitCode + " for " + String.join(" ", command) + "\n" + text;
        }
    }
}
