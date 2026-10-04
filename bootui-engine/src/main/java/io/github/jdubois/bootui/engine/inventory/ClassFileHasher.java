package io.github.jdubois.bootui.engine.inventory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hashes each method of a class file over what it does, not how the compiler laid it out ({@code docs/PLAN-v2.md}
 * §5.15, M5-3): a JDK-only class-file parser, no ASM. A method's hash covers its access flags, its runtime-visible
 * method and parameter annotations, and, for a method with code, its maximum stack and locals, its instructions with
 * every constant-pool operand resolved to its symbolic value (class and member references, string and number
 * constants, method types and handles, and {@code invokedynamic} and dynamic constants through their bootstrap method
 * handle and static arguments), branch and switch targets as instruction ordinals, and its exception table with the
 * resolved catch types. The class's runtime-visible annotations, such as a {@code @RequestMapping} prefix, are folded
 * into every method's hash, and its fields' runtime-visible annotations, such as {@code @Value}, into its constructors',
 * so a change to either changes the methods they configure. Abstract methods are hashed too, over their flags and
 * annotations, such as a repository's {@code @Query}. Debug attributes ({@code LineNumberTable},
 * {@code LocalVariableTable}, {@code StackMapTable}, {@code SourceFile}, and every other attribute) are ignored, and so
 * are constant-pool indices and {@code ldc} versus {@code ldc_w}, so the same source compiled with or without debug
 * information, or with a constant pool in another order, hashes the same. So are the ordinals the compiler numbers
 * lambdas ({@code lambda$name$N}) and anonymous and local classes ({@code Outer$N}) of the class's own nest with: a
 * reference to one hashes without its number, so adding a lambda to one method leaves the others' hashes alone.
 *
 * <p>The stored code hash is 32 bits; a method's key, {@code className#name+descriptor} as the agent keys its ids, is
 * hashed to 64 bits by {@link #keyHash}. Malformed bytes throw {@link MalformedClassException}: the caller skips the
 * class and counts it.
 */
public final class ClassFileHasher {

    static final int ACC_SYNTHETIC = 0x1000;
    static final int ACC_INTERFACE = 0x0200;
    static final int ACC_ABSTRACT = 0x0400;
    static final int ACC_NATIVE = 0x0100;

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_INTEGER = 3;
    private static final int CONSTANT_FLOAT = 4;
    private static final int CONSTANT_LONG = 5;
    private static final int CONSTANT_DOUBLE = 6;
    private static final int CONSTANT_CLASS = 7;
    private static final int CONSTANT_STRING = 8;
    private static final int CONSTANT_FIELDREF = 9;
    private static final int CONSTANT_METHODREF = 10;
    private static final int CONSTANT_INTERFACE_METHODREF = 11;
    private static final int CONSTANT_NAME_AND_TYPE = 12;
    private static final int CONSTANT_METHOD_HANDLE = 15;
    private static final int CONSTANT_METHOD_TYPE = 16;
    private static final int CONSTANT_DYNAMIC = 17;
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;
    private static final int CONSTANT_MODULE = 19;
    private static final int CONSTANT_PACKAGE = 20;

    /** The deepest nesting of annotation element values read; deeper is malformed. */
    static final int MAX_ELEMENT_DEPTH = 32;

    private ClassFileHasher() {}

    /** One class: its binary name ({@code com.example.Outer$Inner}), access flags, and every method. */
    public record ClassHashes(String className, int access, List<MethodHash> methods) {

        public ClassHashes {
            methods = List.copyOf(methods);
        }

        /** Whether the compiler marked the class synthetic, as for an enum switch map. */
        public boolean synthetic() {
            return (access & ACC_SYNTHETIC) != 0;
        }
    }

    /**
     * One method: its name ({@code <init>} for a constructor, {@code <clinit>} for a static initializer), descriptor,
     * access flags, whether it has code (abstract and native methods do not), and its 32-bit code hash.
     */
    public record MethodHash(String name, String descriptor, int access, boolean hasCode, int codeHash) {

        /** The method's key, as the agent keys its ids: {@code className#name+descriptor}. */
        public String key(String className) {
            return className + "#" + name + descriptor;
        }

        /** Whether it is abstract: no code to instrument, yet its signature and annotations are inventoried. */
        public boolean isAbstract() {
            return !hasCode && (access & ACC_ABSTRACT) != 0 && (access & ACC_NATIVE) == 0;
        }

        /** Whether Code Inventory lists it: a method with code, or an abstract one. Native methods are not listed. */
        public boolean inventoried() {
            return hasCode || isAbstract();
        }
    }

    /** A class file the parser could not read. */
    public static final class MalformedClassException extends Exception {

        private static final long serialVersionUID = 1L;

        MalformedClassException(String message) {
            super(message);
        }

        MalformedClassException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The 64-bit FNV-1a hash of a method key, which the run history keeps instead of the key itself. */
    public static long keyHash(String key) {
        long hash = FNV_OFFSET;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            hash = (hash ^ (c & 0xFF)) * FNV_PRIME;
            hash = (hash ^ (c >>> 8)) * FNV_PRIME;
        }
        return hash;
    }

    /**
     * {@code symbol}, a class name, member name, or descriptor of the class whose outermost class is {@code topLevel}
     * (an internal name), without the ordinals the compiler numbers its nest's synthetic members with: the {@code N} of
     * a lambda's {@code lambda$name$N}, and of an anonymous or local class {@code topLevel$N} or
     * {@code topLevel$Inner$NLocal}, become {@code #}.
     */
    static String normalize(String symbol, String topLevel) {
        if (symbol == null || (symbol.indexOf("lambda$") < 0 && (topLevel == null || symbol.indexOf(topLevel) < 0))) {
            return symbol;
        }
        StringBuilder out = new StringBuilder(symbol.length());
        int i = 0;
        int length = symbol.length();
        while (i < length) {
            if (symbol.startsWith("lambda$", i)) {
                int name = i + "lambda$".length();
                int dollar = symbol.indexOf('$', name);
                if (dollar > name) {
                    int digits = dollar + 1;
                    int end = digits;
                    while (end < length && Character.isDigit(symbol.charAt(end))) {
                        end++;
                    }
                    if (end > digits && (end == length || !Character.isJavaIdentifierPart(symbol.charAt(end)))) {
                        out.append(symbol, i, digits).append('#');
                        i = end;
                        continue;
                    }
                }
            } else if (topLevel != null
                    && symbol.startsWith(topLevel, i)
                    && nameStarts(symbol, i)
                    && i + topLevel.length() < length
                    && symbol.charAt(i + topLevel.length()) == '$') {
                // The nest's own names: each segment's leading ordinal, as in Outer$1 or Outer$Inner$2Local.
                int at = i + topLevel.length();
                out.append(symbol, i, at);
                while (at < length && symbol.charAt(at) == '$') {
                    out.append('$');
                    at++;
                    int end = at;
                    while (end < length && Character.isDigit(symbol.charAt(end))) {
                        end++;
                    }
                    if (end > at) {
                        out.append('#');
                        at = end;
                    }
                    while (at < length
                            && symbol.charAt(at) != '$'
                            && Character.isJavaIdentifierPart(symbol.charAt(at))) {
                        out.append(symbol.charAt(at));
                        at++;
                    }
                }
                i = at;
                continue;
            }
            out.append(symbol.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** Whether a class name may start at {@code i}: not inside another name, a descriptor's {@code L} aside. */
    private static boolean nameStarts(String symbol, int i) {
        if (i == 0) {
            return true;
        }
        char previous = symbol.charAt(i - 1);
        if (previous == '/') {
            return false;
        }
        if (!Character.isJavaIdentifierPart(previous)) {
            return true;
        }
        // A descriptor's object type, as in (IJLcom/x/Outer$1;)V; a package segment never ends with L right before it.
        return previous == 'L' && (i == 1 || symbol.charAt(i - 2) != '/');
    }

    /** Parses and hashes {@code bytes}. */
    public static ClassHashes hash(byte[] bytes) throws MalformedClassException {
        if (bytes == null) {
            throw new MalformedClassException("no bytes");
        }
        try {
            return new Parser(bytes).parse();
        } catch (MalformedClassException ex) {
            throw ex;
        } catch (RuntimeException | IOException ex) {
            throw new MalformedClassException("malformed class file: " + ex, ex);
        }
    }

    /** The binary name of a class file's {@code this_class}, without hashing anything, or {@code null}. */
    static String className(byte[] bytes) {
        try {
            return new Parser(bytes).thisClass();
        } catch (RuntimeException | IOException | MalformedClassException ex) {
            return null;
        }
    }

    /** A 64-bit FNV-1a accumulator over typed tokens, folded to 32 bits. */
    private static final class Fnv {

        private long hash = FNV_OFFSET;

        void add(int value) {
            for (int shift = 0; shift < 32; shift += 8) {
                hash = (hash ^ ((value >>> shift) & 0xFF)) * FNV_PRIME;
            }
        }

        void add(long value) {
            add((int) value);
            add((int) (value >>> 32));
        }

        void add(String value) {
            add(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                hash = (hash ^ (c & 0xFF)) * FNV_PRIME;
                hash = (hash ^ (c >>> 8)) * FNV_PRIME;
            }
        }

        int fold() {
            return (int) (hash ^ (hash >>> 32));
        }
    }

    private static final class Parser {

        private final byte[] b;
        private int pos;
        private int[] tags;
        private int[] offsets;
        private String[] utf8;
        private String[] resolved;
        private int bootstrapOffset = -1;
        private int[] bootstrapEntries;
        /** The internal name of the class's outermost class, whose nest's ordinals are not hashed. */
        private String topLevel;

        private int classHash;
        private int fieldHash;

        Parser(byte[] bytes) {
            this.b = bytes;
        }

        String thisClass() throws IOException, MalformedClassException {
            header();
            pos += 2;
            return binaryName(classNameAt(u2()));
        }

        ClassHashes parse() throws IOException, MalformedClassException {
            header();
            int access = u2();
            String className = binaryName(classNameAt(u2()));
            pos += 2; // super_class
            topLevel = topLevel(className.replace('.', '/'));
            int interfaces = u2();
            pos += 2 * interfaces;
            int fields = u2();
            List<int[]> annotatedFields = new ArrayList<>();
            for (int i = 0; i < fields; i++) {
                pos += 2; // access_flags
                int name = u2();
                int descriptor = u2();
                int attributes = u2();
                for (int a = 0; a < attributes; a++) {
                    String attribute = utf8At(u2());
                    int length = u4();
                    int start = pos;
                    check((long) start + length);
                    if ("RuntimeVisibleAnnotations".equals(attribute)) {
                        annotatedFields.add(new int[] {name, descriptor, start});
                    }
                    pos = start + length;
                }
            }
            int methodCount = u2();
            int[][] methods = new int[methodCount][];
            for (int i = 0; i < methodCount; i++) {
                int methodAccess = u2();
                int name = u2();
                int descriptor = u2();
                int code = -1;
                int annotations = -1;
                int parameterAnnotations = -1;
                int attributes = u2();
                for (int a = 0; a < attributes; a++) {
                    String attribute = utf8At(u2());
                    int length = u4();
                    int start = pos;
                    check((long) start + length);
                    switch (attribute) {
                        case "Code" -> code = start;
                        case "RuntimeVisibleAnnotations" -> annotations = start;
                        case "RuntimeVisibleParameterAnnotations" -> parameterAnnotations = start;
                        default -> {
                            // Debug and every other attribute: ignored.
                        }
                    }
                    pos = start + length;
                }
                methods[i] = new int[] {methodAccess, name, descriptor, code, annotations, parameterAnnotations};
            }
            int attributes = u2();
            int classAnnotations = -1;
            for (int a = 0; a < attributes; a++) {
                String attribute = utf8At(u2());
                int length = u4();
                int start = pos;
                check((long) start + length);
                if ("BootstrapMethods".equals(attribute)) {
                    bootstrapOffset = start;
                } else if ("RuntimeVisibleAnnotations".equals(attribute)) {
                    classAnnotations = start;
                }
                pos = start + length;
            }
            indexBootstrapMethods();
            // The class's own annotations configure every method, as a @RequestMapping prefix does.
            Fnv classFnv = new Fnv();
            if (classAnnotations >= 0) {
                pos = classAnnotations;
                classFnv.add("@c");
                annotations(classFnv);
            }
            classHash = classAnnotations >= 0 ? classFnv.fold() : 0;
            // Its fields' annotations configure the instance its constructors build, as @Value and @Autowired do.
            Fnv fieldFnv = new Fnv();
            for (int[] field : annotatedFields) {
                fieldFnv.add(utf8At(field[0]));
                fieldFnv.add(normalize(utf8At(field[1])));
                pos = field[2];
                annotations(fieldFnv);
            }
            fieldHash = annotatedFields.isEmpty() ? 0 : fieldFnv.fold();
            List<MethodHash> hashes = new ArrayList<>(methodCount);
            for (int[] method : methods) {
                hashes.add(method(method));
            }
            return new ClassHashes(className, access, hashes);
        }

        private void header() throws MalformedClassException {
            pos = 0;
            if (u4() != 0xCAFEBABE) {
                throw new MalformedClassException("not a class file");
            }
            pos += 4; // minor and major versions
            int count = u2();
            tags = new int[count];
            offsets = new int[count];
            utf8 = new String[count];
            resolved = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = u1();
                tags[i] = tag;
                offsets[i] = pos;
                switch (tag) {
                    case CONSTANT_UTF8 -> pos += 2 + u2At(pos);
                    case CONSTANT_INTEGER, CONSTANT_FLOAT, CONSTANT_FIELDREF, CONSTANT_METHODREF -> pos += 4;
                    case CONSTANT_INTERFACE_METHODREF, CONSTANT_NAME_AND_TYPE, CONSTANT_DYNAMIC -> pos += 4;
                    case CONSTANT_INVOKE_DYNAMIC -> pos += 4;
                    case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                        pos += 8;
                        i++;
                    }
                    case CONSTANT_CLASS, CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE ->
                        pos += 2;
                    case CONSTANT_METHOD_HANDLE -> pos += 3;
                    default -> throw new MalformedClassException("unknown constant pool tag " + tag + " at " + i);
                }
                check(pos);
            }
        }

        private MethodHash method(int[] method) throws IOException, MalformedClassException {
            int access = method[0];
            String name = utf8At(method[1]);
            String descriptor = utf8At(method[2]);
            Fnv fnv = new Fnv();
            fnv.add(access);
            if (classHash != 0) {
                fnv.add(classHash);
            }
            if (fieldHash != 0 && "<init>".equals(name)) {
                fnv.add(fieldHash);
            }
            if (method[4] >= 0) {
                pos = method[4];
                fnv.add("@");
                annotations(fnv);
            }
            if (method[5] >= 0) {
                pos = method[5];
                int parameters = u1();
                fnv.add("@p");
                fnv.add(parameters);
                for (int p = 0; p < parameters; p++) {
                    annotations(fnv);
                }
            }
            boolean hasCode = method[3] >= 0;
            if (hasCode) {
                pos = method[3];
                code(fnv);
            }
            return new MethodHash(name, descriptor, access, hasCode, fnv.fold());
        }

        private void annotations(Fnv fnv) throws IOException, MalformedClassException {
            int count = u2();
            fnv.add(count);
            for (int i = 0; i < count; i++) {
                annotation(fnv, 0);
            }
        }

        private void annotation(Fnv fnv, int depth) throws IOException, MalformedClassException {
            fnv.add(utf8At(u2()));
            int pairs = u2();
            fnv.add(pairs);
            for (int i = 0; i < pairs; i++) {
                fnv.add(utf8At(u2()));
                elementValue(fnv, depth + 1);
            }
        }

        private void elementValue(Fnv fnv, int depth) throws IOException, MalformedClassException {
            if (depth > MAX_ELEMENT_DEPTH) {
                throw new MalformedClassException("annotation values nest too deep");
            }
            int tag = u1();
            fnv.add(tag);
            switch (tag) {
                case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> fnv.add(resolve(u2()));
                case 's', 'c' -> fnv.add(utf8At(u2()));
                case 'e' -> {
                    fnv.add(utf8At(u2()));
                    fnv.add(utf8At(u2()));
                }
                case '@' -> annotation(fnv, depth);
                case '[' -> {
                    int values = u2();
                    fnv.add(values);
                    for (int i = 0; i < values; i++) {
                        elementValue(fnv, depth + 1);
                    }
                }
                default -> throw new MalformedClassException("unknown element value tag " + tag);
            }
        }

        private void code(Fnv fnv) throws IOException, MalformedClassException {
            fnv.add(u2()); // max_stack
            fnv.add(u2()); // max_locals
            int length = u4();
            int start = pos;
            check((long) start + length);
            int end = start + length;
            int[] ordinals = new int[length + 1];
            Arrays.fill(ordinals, -1);
            int count = 0;
            int at = start;
            while (at < end) {
                ordinals[at - start] = count++;
                at = next(at, start, end);
            }
            ordinals[length] = count;
            fnv.add(count);
            at = start;
            while (at < end) {
                instruction(fnv, at, start, ordinals);
                at = next(at, start, end);
            }
            pos = start + length;
            int handlers = u2();
            fnv.add(handlers);
            for (int i = 0; i < handlers; i++) {
                fnv.add(target(ordinals, u2()));
                fnv.add(target(ordinals, u2()));
                fnv.add(target(ordinals, u2()));
                int type = u2();
                fnv.add(type == 0 ? "*" : resolve(type));
            }
            // The Code attribute's own attributes (LineNumberTable, LocalVariable*Table, StackMapTable): ignored.
        }

        /**
         * The offset of the instruction after the one at {@code at}, which is always past {@code at} and never past
         * {@code end}, the end of the code: switch sizes are computed in {@code long}, so a malformed count can neither
         * overflow into an earlier offset, which would loop forever, nor run past the code.
         */
        private int next(int at, int start, int end) throws MalformedClassException {
            long next = nextUnchecked(at, start);
            if (next <= at || next > end) {
                throw new MalformedClassException("instructions overrun the code");
            }
            return (int) next;
        }

        private long nextUnchecked(int at, int start) throws MalformedClassException {
            int opcode = u1At(at);
            switch (opcode) {
                case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc -> {
                    return at + 2;
                }
                case 0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1 -> {
                    return at + 3;
                }
                case 0xc5 -> {
                    return at + 4;
                }
                case 0xb9, 0xba, 0xc8, 0xc9 -> {
                    return at + 5;
                }
                case 0xaa -> {
                    int base = padded(at, start);
                    int low = s4At(base + 4);
                    int high = s4At(base + 8);
                    if (high < low) {
                        throw new MalformedClassException("tableswitch high below low");
                    }
                    return base + 12L + 4L * ((long) high - (long) low + 1L);
                }
                case 0xab -> {
                    int base = padded(at, start);
                    int pairs = s4At(base + 4);
                    if (pairs < 0) {
                        throw new MalformedClassException("negative lookupswitch pairs");
                    }
                    return base + 8L + 8L * pairs;
                }
                case 0xc4 -> {
                    return u1At(at + 1) == 0x84 ? at + 6 : at + 4;
                }
                default -> {
                    if ((opcode >= 0x00 && opcode <= 0x0f)
                            || (opcode >= 0x1a && opcode <= 0x35)
                            || (opcode >= 0x3b && opcode <= 0x83)
                            || (opcode >= 0x85 && opcode <= 0x98)
                            || (opcode >= 0xac && opcode <= 0xb1)
                            || opcode == 0xbe
                            || opcode == 0xbf
                            || opcode == 0xc2
                            || opcode == 0xc3) {
                        return at + 1;
                    }
                    if ((opcode >= 0x99 && opcode <= 0xa8) || opcode == 0xc6 || opcode == 0xc7) {
                        return at + 3;
                    }
                    throw new MalformedClassException("unknown opcode " + opcode);
                }
            }
        }

        private void instruction(Fnv fnv, int at, int start, int[] ordinals)
                throws IOException, MalformedClassException {
            int opcode = u1At(at);
            // ldc and ldc_w differ only in their index's width, which the constant pool's order decides.
            fnv.add(opcode == 0x12 ? 0x13 : opcode);
            switch (opcode) {
                case 0x10, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc ->
                    fnv.add(u1At(at + 1));
                case 0x11 -> fnv.add((short) u2At(at + 1));
                case 0x12 -> fnv.add(resolve(u1At(at + 1)));
                case 0x13, 0x14, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb, 0xbd, 0xc0, 0xc1 ->
                    fnv.add(resolve(u2At(at + 1)));
                case 0xc5 -> {
                    fnv.add(resolve(u2At(at + 1)));
                    fnv.add(u1At(at + 3));
                }
                case 0x84 -> {
                    fnv.add(u1At(at + 1));
                    fnv.add((byte) u1At(at + 2));
                }
                case 0xc8, 0xc9 -> fnv.add(target(ordinals, at - start + s4At(at + 1)));
                case 0xaa -> {
                    int base = padded(at, start);
                    fnv.add(target(ordinals, at - start + s4At(base)));
                    int low = s4At(base + 4);
                    int high = s4At(base + 8);
                    fnv.add(low);
                    fnv.add(high);
                    // next() already bounded the table within the code, so the count fits.
                    int entries = (int) ((long) high - (long) low + 1L);
                    for (int i = 0; i < entries; i++) {
                        fnv.add(target(ordinals, at - start + s4At(base + 12 + 4 * i)));
                    }
                }
                case 0xab -> {
                    int base = padded(at, start);
                    fnv.add(target(ordinals, at - start + s4At(base)));
                    int pairs = s4At(base + 4);
                    fnv.add(pairs);
                    for (int i = 0; i < pairs; i++) {
                        fnv.add(s4At(base + 8 + 8 * i));
                        fnv.add(target(ordinals, at - start + s4At(base + 12 + 8 * i)));
                    }
                }
                case 0xc4 -> {
                    int widened = u1At(at + 1);
                    fnv.add(widened);
                    fnv.add(u2At(at + 2));
                    if (widened == 0x84) {
                        fnv.add((short) u2At(at + 4));
                    }
                }
                default -> {
                    if ((opcode >= 0x99 && opcode <= 0xa8) || opcode == 0xc6 || opcode == 0xc7) {
                        fnv.add(target(ordinals, at - start + (short) u2At(at + 1)));
                    }
                }
            }
        }

        /** The offset of a switch's default target: after the opcode, padded to four bytes from the code's start. */
        private static int padded(int at, int start) {
            int relative = at - start + 1;
            return start + ((relative + 3) & ~3);
        }

        private static int target(int[] ordinals, int offset) throws MalformedClassException {
            if (offset < 0 || offset >= ordinals.length || ordinals[offset] < 0) {
                throw new MalformedClassException("a branch targets offset " + offset + ", not an instruction");
            }
            return ordinals[offset];
        }

        // ---- the constant pool ---------------------------------------------------------------------------------

        private void indexBootstrapMethods() throws MalformedClassException {
            if (bootstrapOffset < 0) {
                bootstrapEntries = new int[0];
                return;
            }
            int at = bootstrapOffset;
            int count = u2At(at);
            at += 2;
            bootstrapEntries = new int[count];
            for (int i = 0; i < count; i++) {
                bootstrapEntries[i] = at;
                int arguments = u2At(at + 2);
                at += 4 + 2 * arguments;
                check(at);
            }
        }

        /** The symbolic value of constant {@code index}, with every reference it holds resolved. */
        private String resolve(int index) throws IOException, MalformedClassException {
            return resolve(index, 0);
        }

        private String resolve(int index, int depth) throws IOException, MalformedClassException {
            if (index <= 0 || index >= tags.length || tags[index] == 0) {
                throw new MalformedClassException("bad constant pool index " + index);
            }
            if (depth > 16) {
                throw new MalformedClassException("constant pool references nest too deep");
            }
            String known = resolved[index];
            if (known != null) {
                return known;
            }
            int at = offsets[index];
            String value =
                    switch (tags[index]) {
                        case CONSTANT_UTF8 -> utf8At(index);
                        case CONSTANT_INTEGER -> "I" + s4At(at);
                        case CONSTANT_FLOAT -> "F" + s4At(at);
                        case CONSTANT_LONG -> "J" + (((long) s4At(at) << 32) | (s4At(at + 4) & 0xFFFFFFFFL));
                        case CONSTANT_DOUBLE -> "D" + (((long) s4At(at) << 32) | (s4At(at + 4) & 0xFFFFFFFFL));
                        case CONSTANT_CLASS -> "C" + normalize(utf8At(u2At(at)));
                        case CONSTANT_STRING -> "S" + utf8At(u2At(at));
                        case CONSTANT_METHOD_TYPE -> "T" + normalize(utf8At(u2At(at)));
                        case CONSTANT_MODULE -> "M" + utf8At(u2At(at));
                        case CONSTANT_PACKAGE -> "P" + utf8At(u2At(at));
                        case CONSTANT_FIELDREF, CONSTANT_METHODREF, CONSTANT_INTERFACE_METHODREF ->
                            "R" + tags[index] + resolve(u2At(at), depth + 1) + "." + resolve(u2At(at + 2), depth + 1);
                        case CONSTANT_NAME_AND_TYPE ->
                            normalize(utf8At(u2At(at))) + ":" + normalize(utf8At(u2At(at + 2)));
                        case CONSTANT_METHOD_HANDLE -> "H" + u1At(at) + resolve(u2At(at + 1), depth + 1);
                        case CONSTANT_DYNAMIC, CONSTANT_INVOKE_DYNAMIC ->
                            "Y" + tags[index] + bootstrap(u2At(at), depth + 1) + "|" + resolve(u2At(at + 2), depth + 1);
                        default -> throw new MalformedClassException("unknown constant tag " + tags[index]);
                    };
            resolved[index] = value;
            return value;
        }

        /** A bootstrap method: its handle and its static arguments, resolved. */
        private String bootstrap(int index, int depth) throws IOException, MalformedClassException {
            if (index < 0 || index >= bootstrapEntries.length) {
                throw new MalformedClassException("bad bootstrap method index " + index);
            }
            int at = bootstrapEntries[index];
            StringBuilder text = new StringBuilder("B").append(resolve(u2At(at), depth));
            int arguments = u2At(at + 2);
            for (int i = 0; i < arguments; i++) {
                text.append(',').append(resolve(u2At(at + 4 + 2 * i), depth));
            }
            return text.toString();
        }

        private String classNameAt(int index) throws IOException, MalformedClassException {
            if (index <= 0 || index >= tags.length || tags[index] != CONSTANT_CLASS) {
                throw new MalformedClassException("bad class index " + index);
            }
            return utf8At(u2At(offsets[index]));
        }

        private String utf8At(int index) throws IOException, MalformedClassException {
            if (index <= 0 || index >= tags.length || tags[index] != CONSTANT_UTF8) {
                throw new MalformedClassException("bad UTF-8 constant index " + index);
            }
            String value = utf8[index];
            if (value == null) {
                int at = offsets[index];
                int length = u2At(at);
                check(at + 2 + length);
                value = new DataInputStream(new ByteArrayInputStream(b, at, length + 2)).readUTF();
                utf8[index] = value;
            }
            return value;
        }

        private static String binaryName(String internalName) {
            return internalName.replace('/', '.');
        }

        /** The outermost class of an internal class name: {@code com/x/Outer} for {@code com/x/Outer$Inner$1}. */
        private static String topLevel(String internalName) {
            int slash = internalName.lastIndexOf('/');
            int dollar = internalName.indexOf('$', slash + 1);
            return dollar < 0 ? internalName : internalName.substring(0, dollar);
        }

        /** {@link ClassFileHasher#normalize} for this class's nest. */
        private String normalize(String symbol) {
            return ClassFileHasher.normalize(symbol, topLevel);
        }

        // ---- bytes ---------------------------------------------------------------------------------------------

        private void skipAttributes() throws MalformedClassException {
            int attributes = u2();
            for (int a = 0; a < attributes; a++) {
                pos += 2;
                int length = u4();
                check((long) pos + length);
                pos += length;
            }
        }

        private int u1() throws MalformedClassException {
            int value = u1At(pos);
            pos += 1;
            return value;
        }

        private int u2() throws MalformedClassException {
            int value = u2At(pos);
            pos += 2;
            return value;
        }

        private int u4() throws MalformedClassException {
            int value = s4At(pos);
            pos += 4;
            if (value < 0 && value != 0xCAFEBABE) {
                throw new MalformedClassException("negative length at " + (pos - 4));
            }
            return value;
        }

        private int u1At(int at) throws MalformedClassException {
            check(at + 1);
            return b[at] & 0xFF;
        }

        private int u2At(int at) throws MalformedClassException {
            check(at + 2);
            return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
        }

        private int s4At(int at) throws MalformedClassException {
            check(at + 4);
            return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
        }

        private void check(long end) throws MalformedClassException {
            if (end < 0 || end > b.length) {
                throw new MalformedClassException("truncated class file");
            }
        }
    }
}
