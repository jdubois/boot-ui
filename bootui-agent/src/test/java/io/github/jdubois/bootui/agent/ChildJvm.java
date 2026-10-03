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
        Files.createDirectories(WORK);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(
                extraClassPath == null
                        ? TEST_CLASSES.toString()
                        : TEST_CLASSES + java.io.File.pathSeparator + extraClassPath);
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
            process.destroyForcibly();
            throw new IllegalStateException("child JVM timed out: " + Files.readString(log));
        }
        return new Output(process.exitValue(), Files.readString(log), command);
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
