package io.github.jdubois.bootui.engine.inventory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles Java sources at test time, in memory, so class-file fixtures differ only in what a test says. */
final class Compiler {

    private Compiler() {}

    /** Compiles {@code sources} (binary class name to source) with {@code options}: binary name to class file. */
    static Map<String, byte[]> compile(Map<String, String> sources, String... options) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler");
        }
        Map<String, ByteArrayOutputStream> outputs = new LinkedHashMap<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, null);
        JavaFileManager manager = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(
                    Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(
                        URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        outputs.put(className, out);
                        return out;
                    }
                };
            }
        };
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, source) -> units.add(
                new SimpleJavaFileObject(
                        URI.create("string:///" + name.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return source;
                    }
                }));
        List<String> args = new ArrayList<>(List.of("--release", "17"));
        args.addAll(List.of(options));
        StringWriter errors = new StringWriter();
        Boolean ok = compiler.getTask(errors, manager, null, args, null, units).call();
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("compilation failed: " + errors);
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        outputs.forEach((name, out) -> classes.put(name, out.toByteArray()));
        return classes;
    }

    /** Writes {@code classes} under {@code root} as a class directory. */
    static void write(Path root, Map<String, byte[]> classes) throws IOException {
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Path file = root.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
    }
}
