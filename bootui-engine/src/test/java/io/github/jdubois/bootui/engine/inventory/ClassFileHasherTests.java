package io.github.jdubois.bootui.engine.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.ClassHashes;
import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.MalformedClassException;
import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.MethodHash;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassFileHasherTests {

    private static final String ANNOTATION = """
            package demo;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface GetMapping { String value(); }
            """;

    /** The fixture: {@code %s} slots for an extra first method, the greeting, the reference, and the route. */
    private static final String GREETER = """
            package demo;
            import java.util.List;
            import java.util.function.Supplier;
            public class Greeter {
                %s
                @GetMapping("%s")
                public String hello(String name) {
                    if (name == null) {
                        return "Hello";
                    }
                    return "%s" + name + "!";
                }
                public int sum(List<Integer> values) {
                    int total = 0;
                    for (int value : values) {
                        total += value;
                    }
                    return total;
                }
                public Supplier<String> later() {
                    return this::%s;
                }
                String first() { return "a"; }
                String second() { return "b"; }
                public String classify(int code) {
                    switch (code) {
                        case 1: return "one";
                        case 2: return "two";
                        case 100: return "hundred";
                        default: return "many";
                    }
                }
                public String tabled(int code) {
                    switch (code) {
                        case 1: return "one";
                        case 2: return "two";
                        case 3: return "three";
                        case 4: return "four";
                        default: return "many";
                    }
                }
                public Runnable task() {
                    return () -> System.out.println(sum(List.of(1, 2, 3)));
                }
                public long divide(long value) {
                    try {
                        return Long.MAX_VALUE / value;
                    } catch (ArithmeticException ex) {
                        return -1L;
                    }
                }
                public double ratio(double value) {
                    return value * 3.14159 + 1_000_000_000_000L;
                }
                static class Inner {
                    Inner() {}
                }
            }
            """;

    private static Map<String, Integer> hashes(Map<String, byte[]> classes, String className) throws Exception {
        ClassHashes hashes = ClassFileHasher.hash(classes.get(className));
        Map<String, Integer> byKey = new LinkedHashMap<>();
        for (MethodHash method : hashes.methods()) {
            byKey.put(method.key(hashes.className()), method.codeHash());
        }
        return byKey;
    }

    private static Map<String, byte[]> greeter(
            String extra, String route, String greeting, String reference, String... options) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("demo.GetMapping", ANNOTATION);
        sources.put("demo.Greeter", GREETER.formatted(extra, route, greeting, reference));
        return Compiler.compile(sources, options);
    }

    private static Map<String, byte[]> plainGreeter(String... options) {
        return greeter("", "/hello", "Hello, ", "first", options);
    }

    @Test
    void debugInformationDoesNotChangeAnyHash() throws Exception {
        Map<String, Integer> withDebug = hashes(plainGreeter("-g"), "demo.Greeter");
        Map<String, Integer> withoutDebug = hashes(plainGreeter("-g:none"), "demo.Greeter");

        assertThat(withDebug).containsKey("demo.Greeter#hello(Ljava/lang/String;)Ljava/lang/String;");
        assertThat(withDebug).containsKey("demo.Greeter#<init>()V");
        assertThat(withDebug).isEqualTo(withoutDebug);
        assertThat(hashes(plainGreeter("-g"), "demo.Greeter$Inner"))
                .isEqualTo(hashes(plainGreeter("-g:none"), "demo.Greeter$Inner"))
                .containsKey("demo.Greeter$Inner#<init>()V");
    }

    @Test
    void aReorderedConstantPoolDoesNotChangeTheOtherMethodsHashes() throws Exception {
        // An extra method placed first puts its constants, and those the others share, at the pool's start.
        String extra = """
                public String extra() {
                    String text = "Hello" + "many" + Long.MAX_VALUE + 3.14159;
                    System.out.println(text);
                    return java.util.List.of("one", "two", "three", "hundred").toString();
                }
                """;
        Map<String, byte[]> plain = plainGreeter("-g");
        Map<String, byte[]> reordered = greeter(extra, "/hello", "Hello, ", "first", "-g:none");
        assertThat(Arrays.equals(plain.get("demo.Greeter"), reordered.get("demo.Greeter")))
                .isFalse();

        Map<String, Integer> before = hashes(plain, "demo.Greeter");
        Map<String, Integer> after = hashes(reordered, "demo.Greeter");
        after.remove("demo.Greeter#extra()Ljava/lang/String;");

        assertThat(after).isEqualTo(before);
    }

    @Test
    void aChangedLiteralChangesOnlyItsMethod() throws Exception {
        Map<String, Integer> before = hashes(plainGreeter("-g"), "demo.Greeter");
        Map<String, Integer> after = hashes(greeter("", "/hello", "Hi, ", "first", "-g"), "demo.Greeter");

        assertThat(changed(before, after)).containsExactly("demo.Greeter#hello(Ljava/lang/String;)Ljava/lang/String;");
    }

    @Test
    void aChangedMethodReferenceInALambdaChangesItsMethod() throws Exception {
        Map<String, Integer> before = hashes(plainGreeter("-g"), "demo.Greeter");
        Map<String, Integer> after = hashes(greeter("", "/hello", "Hello, ", "second", "-g"), "demo.Greeter");

        assertThat(changed(before, after)).containsExactly("demo.Greeter#later()Ljava/util/function/Supplier;");
    }

    @Test
    void aChangedAnnotationValueChangesItsMethod() throws Exception {
        Map<String, Integer> before = hashes(plainGreeter("-g"), "demo.Greeter");
        Map<String, Integer> after = hashes(greeter("", "/greet", "Hello, ", "first", "-g"), "demo.Greeter");

        assertThat(changed(before, after)).containsExactly("demo.Greeter#hello(Ljava/lang/String;)Ljava/lang/String;");
    }

    @Test
    void abstractAndInterfaceMethodsHaveNoCode() throws Exception {
        Map<String, String> sources = Map.of("demo.Shape", """
                package demo;
                public interface Shape {
                    double area();
                    default String describe() { return "area " + area(); }
                    static Shape unit() { return () -> 1.0; }
                }
                """);
        ClassHashes shape = ClassFileHasher.hash(Compiler.compile(sources).get("demo.Shape"));

        assertThat(shape.className()).isEqualTo("demo.Shape");
        assertThat(shape.methods())
                .filteredOn(method -> method.name().equals("area"))
                .singleElement()
                .satisfies(method -> assertThat(method.hasCode()).isFalse());
        assertThat(shape.methods())
                .filteredOn(method -> method.name().equals("describe"))
                .singleElement()
                .satisfies(method -> assertThat(method.hasCode()).isTrue());
    }

    @Test
    void aSyntheticClassIsReported() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("demo.Color", "package demo; public enum Color { RED, GREEN }");
        sources.put("demo.Painter", """
                package demo;
                public class Painter {
                    public int code(Color color) {
                        switch (color) {
                            case RED: return 1;
                            default: return 2;
                        }
                    }
                }
                """);
        Map<String, byte[]> classes = Compiler.compile(sources);
        assertThat(classes).containsKey("demo.Painter$1");

        assertThat(ClassFileHasher.hash(classes.get("demo.Painter$1")).synthetic())
                .isTrue();
        assertThat(ClassFileHasher.hash(classes.get("demo.Painter")).synthetic())
                .isFalse();
    }

    @Test
    void malformedBytesFailSoftWithACheckedException() throws Exception {
        byte[] valid = plainGreeter("-g").get("demo.Greeter");

        assertThatThrownBy(() -> ClassFileHasher.hash(Arrays.copyOf(valid, valid.length / 2)))
                .isInstanceOf(MalformedClassException.class);
        assertThatThrownBy(() -> ClassFileHasher.hash(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}))
                .isInstanceOf(MalformedClassException.class);
        assertThatThrownBy(() -> ClassFileHasher.hash(null)).isInstanceOf(MalformedClassException.class);
        byte[] corrupt = valid.clone();
        corrupt[10] = (byte) 0x7F; // the first constant's tag
        assertThatThrownBy(() -> ClassFileHasher.hash(corrupt)).isInstanceOf(MalformedClassException.class);
    }

    // ---- malformed code: switches and annotations ------------------------------------------------------------

    /**
     * A minimal class file {@code T} with one static method {@code m()V} whose code is {@code code}, and, when
     * {@code annotation} is given, its RuntimeVisibleAnnotations attribute body; constant 8 is the UTF-8 {@code LA;},
     * constant 9 {@code v}.
     */
    private static byte[] classWithCode(byte[] code, byte[] annotation) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        try {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(61);
            String[] utf8 = {
                null, "T", null, "java/lang/Object", null, "m", "()V", "Code", "LA;", "v", "RuntimeVisibleAnnotations"
            };
            out.writeShort(utf8.length);
            for (int i = 1; i < utf8.length; i++) {
                if (i == 2 || i == 4) {
                    out.writeByte(7); // CONSTANT_Class
                    out.writeShort(i - 1);
                } else {
                    out.writeByte(1);
                    out.writeUTF(utf8[i]);
                }
            }
            out.writeShort(0x0021);
            out.writeShort(2);
            out.writeShort(4);
            out.writeShort(0); // interfaces
            out.writeShort(0); // fields
            out.writeShort(1); // methods
            out.writeShort(0x0009);
            out.writeShort(5);
            out.writeShort(6);
            out.writeShort(annotation == null ? 1 : 2);
            out.writeShort(7);
            out.writeInt(2 + 2 + 4 + code.length + 2 + 2);
            out.writeShort(1);
            out.writeShort(1);
            out.writeInt(code.length);
            out.write(code);
            out.writeShort(0); // exception table
            out.writeShort(0); // attributes
            if (annotation != null) {
                out.writeShort(10);
                out.writeInt(annotation.length);
                out.write(annotation);
            }
            out.writeShort(0); // class attributes
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        return bytes.toByteArray();
    }

    private static byte[] ints(int... values) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4 * values.length);
        for (int value : values) {
            buffer.putInt(value);
        }
        return buffer.array();
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @Test
    void aWellFormedHandBuiltClassHashes() throws Exception {
        ClassHashes hashes = ClassFileHasher.hash(classWithCode(new byte[] {(byte) 0xb1}, null));

        assertThat(hashes.className()).isEqualTo("T");
        assertThat(hashes.methods())
                .singleElement()
                .satisfies(method -> assertThat(method.hasCode()).isTrue());
    }

    @Test
    void aTableswitchWhoseSizeOverflowsFailsInsteadOfLoopingForever() {
        // tableswitch at 0, padded to 4: default, low 0, high 0x3FFFFFFB. In int arithmetic, 4 * (high - low + 1)
        // overflows to -16, and the next instruction would be at 0 again: the parser would loop forever.
        byte[] code = concat(new byte[] {(byte) 0xaa, 0, 0, 0}, ints(0, 0, 0x3FFFFFFB));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            assertThatThrownBy(() -> ClassFileHasher.hash(classWithCode(code, null)))
                    .isInstanceOf(MalformedClassException.class)
                    .hasMessageContaining("overrun");
        });
    }

    @Test
    void aLookupswitchWhosePairsOverflowFailsInsteadOfLoopingForever() {
        // Four nops, then lookupswitch at 4, padded to 8: default, then 0x1FFFFFFE pairs. In int arithmetic,
        // 8 * pairs overflows to -16, and the next instruction would be at 0, the first nop: a loop.
        byte[] code = concat(new byte[] {0, 0, 0, 0, (byte) 0xab, 0, 0, 0}, ints(0, 0x1FFFFFFE));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            assertThatThrownBy(() -> ClassFileHasher.hash(classWithCode(code, null)))
                    .isInstanceOf(MalformedClassException.class);
        });
    }

    @Test
    void aSwitchRunningPastTheCodeFails() {
        byte[] code = concat(new byte[] {(byte) 0xaa, 0, 0, 0}, ints(0, 0, 3));

        assertThatThrownBy(() -> ClassFileHasher.hash(classWithCode(code, null)))
                .isInstanceOf(MalformedClassException.class);
    }

    @Test
    void annotationValuesNestedTooDeepFailInsteadOfOverflowingTheStack() {
        int depth = 10_000;
        java.io.ByteArrayOutputStream annotation = new java.io.ByteArrayOutputStream();
        annotation.writeBytes(new byte[] {0, 1, 0, 8, 0, 1, 0, 9}); // one annotation LA;, one pair v =
        for (int i = 0; i < depth; i++) {
            annotation.writeBytes(new byte[] {'[', 0, 1});
        }
        annotation.writeBytes(new byte[] {'s', 0, 9});
        byte[] bytes = classWithCode(new byte[] {(byte) 0xb1}, annotation.toByteArray());

        assertThatThrownBy(() -> ClassFileHasher.hash(bytes))
                .isInstanceOf(MalformedClassException.class)
                .hasMessageContaining("nest too deep");
    }

    // ---- compiler noise --------------------------------------------------------------------------------------

    @Test
    void ordinalsOfTheNestsLambdasAndAnonymousClassesAreNotHashed() {
        assertThat(ClassFileHasher.normalize("demo/Greeter.lambda$task$3:()V", "demo/Greeter"))
                .isEqualTo("demo/Greeter.lambda$task$#:()V");
        assertThat(ClassFileHasher.normalize("demo/Greeter$2", "demo/Greeter")).isEqualTo("demo/Greeter$#");
        assertThat(ClassFileHasher.normalize("(ILdemo/Greeter$Inner$12Local;)V", "demo/Greeter"))
                .isEqualTo("(ILdemo/Greeter$Inner$#Local;)V");
        assertThat(ClassFileHasher.normalize("demo/GreeterTwo$1", "demo/Greeter"))
                .as("another class")
                .isEqualTo("demo/GreeterTwo$1");
        assertThat(ClassFileHasher.normalize("other/demo/Greeter$1", "demo/Greeter"))
                .as("a class of another package")
                .isEqualTo("other/demo/Greeter$1");
        assertThat(ClassFileHasher.normalize("demo/Greeter$Inner", "demo/Greeter"))
                .isEqualTo("demo/Greeter$Inner");
    }

    /** {@code %1$s}: an extra method placed first; {@code %2$s}: an extra statement placed first in {@code first()}. */
    private static final String NOISY = """
            package demo;
            public class Noisy {
                %1$s
                public Runnable first() {
                    %2$s
                    return () -> System.out.println("first");
                }
                public Runnable second() {
                    return () -> System.out.println("second");
                }
                public Object anonymous() {
                    return new Object() {
                        @Override
                        public String toString() { return "anonymous"; }
                    };
                }
            }
            """;

    @Test
    void addingALambdaOrAnAnonymousClassFirstLeavesTheOthersMethodsUnchanged() throws Exception {
        String zero = """
                public Object zero() {
                    return new Object() {
                        @Override
                        public String toString() { return "zero"; }
                    };
                }
                """;
        String early = "Runnable early = () -> System.out.println(\"early\"); early.run();";
        Map<String, byte[]> before = Compiler.compile(Map.of("demo.Noisy", NOISY.formatted("", "")), "-g");
        Map<String, byte[]> after = Compiler.compile(Map.of("demo.Noisy", NOISY.formatted(zero, early)), "-g");
        // The compiler renumbered first()'s lambda and the anonymous class.
        assertThat(hashes(before, "demo.Noisy")).containsKey("demo.Noisy#lambda$first$0()V");
        assertThat(hashes(after, "demo.Noisy")).containsKey("demo.Noisy#lambda$first$1()V");
        assertThat(before).containsKey("demo.Noisy$1").doesNotContainKey("demo.Noisy$2");
        assertThat(after).containsKey("demo.Noisy$2");

        Map<String, Integer> was = hashes(before, "demo.Noisy");
        Map<String, Integer> now = hashes(after, "demo.Noisy");
        for (String method : java.util.List.of(
                "demo.Noisy#second()Ljava/lang/Runnable;",
                "demo.Noisy#anonymous()Ljava/lang/Object;",
                "demo.Noisy#lambda$second$0()V",
                "demo.Noisy#<init>()V")) {
            assertThat(now.get(method)).as(method).isEqualTo(was.get(method));
        }
        assertThat(now.get("demo.Noisy#lambda$first$1()V")).isEqualTo(was.get("demo.Noisy#lambda$first$0()V"));
        assertThat(now.get("demo.Noisy#first()Ljava/lang/Runnable;"))
                .as("the method the developer edited")
                .isNotEqualTo(was.get("demo.Noisy#first()Ljava/lang/Runnable;"));
        assertThat(hashes(after, "demo.Noisy$2").values())
                .as("the renumbered anonymous class hashes as it did")
                .containsExactlyElementsOf(hashes(before, "demo.Noisy$1").values());
    }

    // ---- what configures a method ------------------------------------------------------------------------------

    private static Map<String, byte[]> configured(String prefix, String field, String query) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("demo.Mapping", """
                package demo;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Mapping { String value(); }
                """);
        sources.put("demo.Controller", """
                package demo;
                @Mapping("%s")
                public class Controller {
                    @Mapping("%s")
                    String greeting;
                    public Controller() {}
                    public String hello() { return "hello"; }
                }
                """.formatted(prefix, field));
        sources.put("demo.Repository", """
                package demo;
                public interface Repository {
                    @Mapping("%s")
                    java.util.List<String> find(String name);
                }
                """.formatted(query));
        return Compiler.compile(sources, "-g");
    }

    @Test
    void aClassAnnotationChangesEveryMethodItConfigures() throws Exception {
        Map<String, Integer> before = hashes(configured("/api", "${greeting}", "select a"), "demo.Controller");
        Map<String, Integer> after = hashes(configured("/v2/api", "${greeting}", "select a"), "demo.Controller");

        assertThat(changed(before, after))
                .containsExactlyInAnyOrder("demo.Controller#<init>()V", "demo.Controller#hello()Ljava/lang/String;");
    }

    @Test
    void aFieldAnnotationChangesTheConstructorsOnly() throws Exception {
        Map<String, Integer> before = hashes(configured("/api", "${greeting}", "select a"), "demo.Controller");
        Map<String, Integer> after = hashes(configured("/api", "${welcome}", "select a"), "demo.Controller");

        assertThat(changed(before, after)).containsExactly("demo.Controller#<init>()V");
    }

    @Test
    void anAbstractMethodsAnnotationIsHashedAndItIsInventoried() throws Exception {
        ClassHashes before = ClassFileHasher.hash(
                configured("/api", "${greeting}", "select a").get("demo.Repository"));
        ClassHashes after = ClassFileHasher.hash(
                configured("/api", "${greeting}", "select b").get("demo.Repository"));

        MethodHash find = before.methods().get(0);
        assertThat(find.isAbstract()).isTrue();
        assertThat(find.inventoried()).isTrue();
        assertThat(ClassScanner.inventoried(before)).containsExactly(find);
        assertThat(after.methods().get(0).codeHash()).isNotEqualTo(find.codeHash());
    }

    @Test
    void keyHashesAreStableAndDistinct() {
        long first = ClassFileHasher.keyHash("demo.Greeter#hello(Ljava/lang/String;)Ljava/lang/String;");
        assertThat(ClassFileHasher.keyHash("demo.Greeter#hello(Ljava/lang/String;)Ljava/lang/String;"))
                .isEqualTo(first);
        assertThat(ClassFileHasher.keyHash("demo.Greeter#hello(Ljava/lang/Object;)Ljava/lang/String;"))
                .isNotEqualTo(first);
        assertThat(ClassFileHasher.className(plainGreeter("-g").get("demo.Greeter")))
                .isEqualTo("demo.Greeter");
    }

    private static java.util.List<String> changed(Map<String, Integer> before, Map<String, Integer> after) {
        assertThat(after.keySet()).isEqualTo(before.keySet());
        return before.keySet().stream()
                .filter(key -> !before.get(key).equals(after.get(key)))
                .toList();
    }
}
