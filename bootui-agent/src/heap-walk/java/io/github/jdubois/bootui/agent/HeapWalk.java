package io.github.jdubois.bootui.agent;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads an HPROF heap dump and answers which application runs the agent itself strongly reaches (PLAN-v2 M5-0 second
 * pass, extended by the M5-1 reviews). The walk starts from the statics and every instance of each class whose name
 * starts with the agent prefix, and from every thread whose name starts with {@code bootui-agent} but the engine's
 * (a thread running a task of a BootUI module beside the agent, as the engine's drain thread); it follows instance
 * fields, array elements, instance-to-class and class-to-loader edges, but never a reference's referent, queue, or
 * pending links, nor the links of the cleaner's list of cleanables, nor a thread group's links to its threads, subgroups,
 * and parent, nor the statics of a class outside the prefix
 * (the application's and the JDK's own pins are not the agent's). Each reached copy of the run class reports its static
 * {@code SENTINEL.run}.
 *
 * <p>A dependency-free test source root of its own, which the Spring sample's DevTools restart test and the Quarkus
 * live reload test add with the build helper plugin, so every leak test walks the heap the same way and nothing
 * test-only is published.
 */
public final class HeapWalk {

    private static final long CHUNK = 1L << 30;

    /** BootUI's own modules beside the agent, whose threads (the engine's drain) are not the agent's. */
    private static final List<String> BOOTUI_MODULES = List.of(
            "io/github/jdubois/bootui/engine/",
            "io/github/jdubois/bootui/core/",
            "io/github/jdubois/bootui/spi/",
            "io/github/jdubois/bootui/autoconfigure/",
            "io/github/jdubois/bootui/quarkus/");

    private final List<MappedByteBuffer> maps = new ArrayList<>();
    private int idSize;
    private final Map<Long, String> strings = new HashMap<>();
    private final Map<Long, String> classNames = new HashMap<>();
    private final Map<Long, long[]> classInfo = new HashMap<>();
    private final Map<Long, long[]> fieldNames = new HashMap<>();
    private final Map<Long, byte[]> fieldTypes = new HashMap<>();
    private final Map<Long, List<long[]>> statics = new HashMap<>();
    private final Map<Long, Long> offsets = new HashMap<>();
    private long referenceClass;
    /** The thread objects the dump lists as roots: the threads alive when it was taken. */
    private final Set<Long> threadRoots = new HashSet<>();

    private HeapWalk() {}

    public static HeapWalk read(Path dump) throws IOException {
        HeapWalk walk = new HeapWalk();
        walk.parse(dump);
        return walk;
    }

    /**
     * Run number to the path by which the thread-locals of the threads named with one of {@code threadPrefixes} (JVM-wide
     * threads such as the common pool's workers) reach it: a propagated context left set after its task would.
     */
    public Map<Integer, String> runsReachedThroughThreadLocals(
            List<String> threadPrefixes, String agentPrefix, String runClass) {
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, String> edge = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (Long object : offsets.keySet()) {
            long type = classOf(object);
            if (!isThread(type)) {
                continue;
            }
            String threadName = threadName(object, type);
            if (threadName == null || threadPrefixes.stream().noneMatch(threadName::startsWith)) {
                continue;
            }
            for (String field : List.of("threadLocals", "inheritableThreadLocals")) {
                Object map = field(object, type, field);
                if (map instanceof Long id && id != 0) {
                    root(id, parent, edge, queue, field + " of " + threadName);
                }
            }
        }
        return walk(parent, edge, queue, agentPrefix, runClass);
    }

    /** How many threads named with {@code threadPrefix} were alive when the dump was taken. */
    public int liveThreads(String threadPrefix) {
        int count = 0;
        for (Long thread : threadRoots) {
            String name = threadName(thread, classOf(thread));
            if (name != null && name.startsWith(threadPrefix)) {
                count++;
            }
        }
        return count;
    }

    /** The run number of every copy of {@code runClass} the dump holds, reached by the agent or not. */
    public SortedSet<Integer> runsPresent(String runClass) {
        SortedSet<Integer> runs = new TreeSet<>();
        for (Map.Entry<Long, String> entry : classNames.entrySet()) {
            if (entry.getValue().equals(runClass) && classInfo.containsKey(entry.getKey())) {
                runs.add(sentinelRun(entry.getKey()));
            }
        }
        return runs;
    }

    /**
     * Run number to the path by which the threads named with {@code threadPrefix}, whoever runs them, reach it: a
     * control, so a leak test can show the walk does reach a run through the paths it follows.
     */
    public Map<Integer, String> runsReachedFromThreads(String threadPrefix, String agentPrefix, String runClass) {
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, String> edge = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (Long object : offsets.keySet()) {
            long type = classOf(object);
            if (isThread(type)) {
                String threadName = threadName(object, type);
                if (threadName != null && threadName.startsWith(threadPrefix)) {
                    root(object, parent, edge, queue, "thread " + threadName);
                }
            }
        }
        return walk(parent, edge, queue, agentPrefix, runClass);
    }

    /** Run number to the path the agent reaches it by. */
    public Map<Integer, String> runsReachedByAgent(String agentPrefix, String runClass) {
        return runsReachedByAgent(agentPrefix, runClass, Set.of());
    }

    /**
     * Run number to the path the agent reaches it by, never starting from a thread named in {@code excludedThreads}: a
     * leak test's own mutation thread, named as an agent thread, so its control cannot hide a real leak of the run it
     * keeps.
     */
    public Map<Integer, String> runsReachedByAgent(String agentPrefix, String runClass, Set<String> excludedThreads) {
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, String> edge = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (Map.Entry<Long, String> entry : classNames.entrySet()) {
            if (entry.getValue().startsWith(agentPrefix) && classInfo.containsKey(entry.getKey())) {
                root(entry.getKey(), parent, edge, queue, "agent class");
            }
        }
        for (Long object : offsets.keySet()) {
            long type = classOf(object);
            String name = classNames.getOrDefault(type, "");
            if (name.startsWith(agentPrefix)) {
                root(object, parent, edge, queue, "agent instance");
            } else if (isThread(type)) {
                String threadName = threadName(object, type);
                if (threadName != null
                        && threadName.startsWith("bootui-agent")
                        && !excludedThreads.contains(threadName)
                        && !engineTask(object, type, agentPrefix)) {
                    root(object, parent, edge, queue, "agent thread " + threadName);
                }
            }
        }
        return walk(parent, edge, queue, agentPrefix, runClass);
    }

    private Map<Integer, String> walk(
            Map<Long, Long> parent,
            Map<Long, String> edge,
            ArrayDeque<Long> queue,
            String agentPrefix,
            String runClass) {
        TreeMap<Integer, String> runs = new TreeMap<>();
        while (!queue.isEmpty()) {
            long object = queue.poll();
            List<Object[]> next;
            if (classInfo.containsKey(object)) {
                String name = classNames.getOrDefault(object, "");
                if (name.equals(runClass)) {
                    int run = sentinelRun(object);
                    runs.putIfAbsent(run, path(object, parent, edge));
                }
                if (name.startsWith(agentPrefix)) {
                    next = refs(object);
                } else {
                    next = new ArrayList<>();
                    next.add(new Object[] {classInfo.get(object)[1], "<classloader>"});
                }
            } else {
                next = refs(object);
            }
            for (Object[] ref : next) {
                long to = (Long) ref[0];
                if (to == 0 || parent.containsKey(to)) {
                    continue;
                }
                if (!offsets.containsKey(to) && !classInfo.containsKey(to)) {
                    continue;
                }
                parent.put(to, object);
                edge.put(to, (String) ref[1]);
                queue.add(to);
            }
        }
        return runs;
    }

    private static void root(
            long object, Map<Long, Long> parent, Map<Long, String> edge, ArrayDeque<Long> queue, String kind) {
        if (!parent.containsKey(object)) {
            parent.put(object, -1L);
            edge.put(object, "ROOT " + kind);
            queue.add(object);
        }
    }

    private String path(long object, Map<Long, Long> parent, Map<Long, String> edge) {
        StringBuilder path = new StringBuilder();
        long current = object;
        int depth = 0;
        while (current != -1L && depth++ < 40) {
            path.append("\n    ").append(describe(current)).append(" <- ").append(edge.get(current));
            current = parent.getOrDefault(current, -1L);
        }
        return path.toString();
    }

    private String describe(long object) {
        if (classInfo.containsKey(object)) {
            return "class " + classNames.get(object);
        }
        return classNames.getOrDefault(classOf(object), "?") + "@" + Long.toHexString(object);
    }

    private boolean isThread(long type) {
        long current = type;
        while (current != 0 && classInfo.containsKey(current)) {
            if ("java/lang/Thread".equals(classNames.get(current))) {
                return true;
            }
            current = classInfo.get(current)[0];
        }
        return false;
    }

    /**
     * Whether the thread runs a task of a BootUI module beside the agent, as the engine's {@code bootui-agent-drain}
     * thread does for its claim: the engine's, not the agent's, so not a root (a walk from it would follow the
     * application run that owns it into whatever that run, or its framework, retains). JDK 19 and later keep the task
     * in the thread's {@code holder}, earlier JDKs in {@code target}.
     */
    private boolean engineTask(long thread, long type, String agentPrefix) {
        Object task = null;
        Object holder = field(thread, type, "holder");
        if (holder instanceof Long id && id != 0) {
            task = field(id, classOf(id), "task");
        }
        if (!(task instanceof Long)) {
            task = field(thread, type, "target");
        }
        if (!(task instanceof Long id) || id == 0) {
            return false;
        }
        String name = classNames.getOrDefault(classOf(id), "");
        if (name.startsWith(agentPrefix)) {
            return false;
        }
        for (String module : BOOTUI_MODULES) {
            if (name.startsWith(module)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Root kind ({@code classes}, {@code instances}, {@code threads}) to how many roots of that kind the walk from the
     * agent starts from: a walk from no root reaches nothing, so a leak test asserts there are some.
     */
    public Map<String, Integer> agentRoots(String agentPrefix) {
        int classes = 0;
        int instances = 0;
        int threads = 0;
        for (Map.Entry<Long, String> entry : classNames.entrySet()) {
            if (entry.getValue().startsWith(agentPrefix) && classInfo.containsKey(entry.getKey())) {
                classes++;
            }
        }
        for (Long object : offsets.keySet()) {
            long type = classOf(object);
            if (classNames.getOrDefault(type, "").startsWith(agentPrefix)) {
                instances++;
            } else if (isThread(type)) {
                String threadName = threadName(object, type);
                if (threadName != null
                        && threadName.startsWith("bootui-agent")
                        && !engineTask(object, type, agentPrefix)) {
                    threads++;
                }
            }
        }
        Map<String, Integer> roots = new TreeMap<>();
        roots.put("classes", classes);
        roots.put("instances", instances);
        roots.put("threads", threads);
        return roots;
    }

    private String threadName(long thread, long type) {
        Object name = field(thread, type, "name");
        return name instanceof Long id ? string(id) : null;
    }

    private int sentinelRun(long type) {
        for (long[] entry : statics.getOrDefault(type, List.of())) {
            if ("SENTINEL".equals(strings.get(entry[0]))) {
                Object run = field(entry[1], classOf(entry[1]), "run");
                return run instanceof Integer value ? value : -1;
            }
        }
        return -1;
    }

    /** A reference field as its object id (Long), an int field as Integer, or null. */
    private Object field(long object, long type, String wanted) {
        Long offset = offsets.get(object);
        if (offset == null || u1(offset) != 0x21) {
            return null;
        }
        long position = offset + 1 + idSize + 4 + idSize + 4;
        long current = type;
        while (current != 0 && fieldTypes.containsKey(current)) {
            byte[] types = fieldTypes.get(current);
            long[] names = fieldNames.get(current);
            for (int i = 0; i < types.length; i++) {
                if (wanted.equals(strings.get(names[i]))) {
                    if (types[i] == 2) {
                        return id(position);
                    }
                    if (types[i] == 10) {
                        return (int) u4(position);
                    }
                }
                position += size(types[i]);
            }
            current = classInfo.get(current)[0];
        }
        return null;
    }

    private String string(long object) {
        Long offset = offsets.get(object);
        if (offset == null || u1(offset) != 0x21) {
            return null;
        }
        long type = classOf(object);
        Object value = field(object, type, "value");
        Long array = value instanceof Long id ? offsets.get(id) : null;
        if (array == null || u1(array) != 0x23) {
            return null;
        }
        int coder = 0;
        long position = offset + 1 + idSize + 4 + idSize + 4;
        byte[] types = fieldTypes.get(type);
        long[] names = fieldNames.get(type);
        for (int i = 0; i < types.length; i++) {
            if ("coder".equals(strings.get(names[i])) && types[i] == 8) {
                coder = u1(position);
            }
            position += size(types[i]);
        }
        int length = (int) u4(array + 1 + idSize + 4);
        long data = array + 1 + idSize + 9;
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) u1(data + i);
        }
        return new String(bytes, coder == 0 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16BE);
    }

    private long classOf(long object) {
        Long offset = offsets.get(object);
        if (offset == null) {
            return -1;
        }
        int tag = u1(offset);
        if (tag == 0x21) {
            return id(offset + 1 + idSize + 4);
        }
        if (tag == 0x22) {
            return id(offset + 1 + idSize + 8);
        }
        return -1;
    }

    private List<Object[]> refs(long object) {
        List<Object[]> out = new ArrayList<>();
        if (classInfo.containsKey(object)) {
            long[] info = classInfo.get(object);
            out.add(new Object[] {info[1], "<classloader>"});
            out.add(new Object[] {info[0], "<super>"});
            for (long[] entry : statics.getOrDefault(object, List.of())) {
                out.add(new Object[] {entry[1], "static " + strings.get(entry[0])});
            }
            return out;
        }
        Long offset = offsets.get(object);
        if (offset == null) {
            return out;
        }
        int tag = u1(offset);
        if (tag == 0x21) {
            long type = id(offset + 1 + idSize + 4);
            out.add(new Object[] {type, "<class>"});
            long position = offset + 1 + idSize + 4 + idSize + 4;
            long current = type;
            while (current != 0 && fieldTypes.containsKey(current)) {
                byte[] types = fieldTypes.get(current);
                long[] names = fieldNames.get(current);
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == 2) {
                        String name = strings.get(names[i]);
                        if (!infrastructure(current, name)) {
                            out.add(new Object[] {id(position), name});
                        }
                    }
                    position += size(types[i]);
                }
                current = classInfo.get(current)[0];
            }
        } else if (tag == 0x22) {
            long length = u4(offset + 1 + idSize + 4);
            long position = offset + 1 + idSize + 8 + idSize;
            for (int i = 0; i < length; i++) {
                out.add(new Object[] {id(position + (long) i * idSize), "[" + i + "]"});
            }
        }
        return out;
    }

    /**
     * Whether a reference field is the JDK's reference bookkeeping rather than a strong hold: a reference's referent
     * and its queue and pending links, and the links of the cleaner's list of registered cleanables (a cleanable
     * reached from a jar the agent opened links to every other cleanable in the JVM). A cleanable's own action is
     * still followed. Nor a thread group's links to its threads, subgroups, and parent: any long-lived agent thread's
     * group reaches every live thread in the JVM through them (until JDK 19), and a live thread is a GC root of its
     * own, so what it keeps is never the agent's.
     */
    private boolean infrastructure(long declaringClass, String field) {
        if (declaringClass == referenceClass) {
            return "referent".equals(field)
                    || "discovered".equals(field)
                    || "queue".equals(field)
                    || "next".equals(field);
        }
        String owner = classNames.getOrDefault(declaringClass, "");
        if (owner.equals("java/lang/ThreadGroup")) {
            return "threads".equals(field) || "groups".equals(field) || "parent".equals(field);
        }
        return owner.equals("jdk/internal/ref/PhantomCleanable")
                || owner.startsWith("jdk/internal/ref/CleanerImpl$Cleanable");
    }

    private void parse(Path dump) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(dump.toFile(), "r");
                FileChannel channel = file.getChannel()) {
            long size = channel.size();
            for (long position = 0; position < size; position += CHUNK) {
                maps.add(
                        channel.map(FileChannel.MapMode.READ_ONLY, position, Math.min(CHUNK + 65536, size - position)));
            }
            long position = 0;
            while (u1(position) != 0) {
                position++;
            }
            position++;
            idSize = (int) u4(position);
            position += 4 + 8;
            while (position < size) {
                int tag = u1(position);
                long length = u4(position + 5);
                long body = position + 9;
                if (tag == 0x01) {
                    long id = id(body);
                    byte[] bytes = new byte[(int) length - idSize];
                    for (int i = 0; i < bytes.length; i++) {
                        bytes[i] = (byte) u1(body + idSize + i);
                    }
                    strings.put(id, new String(bytes, StandardCharsets.UTF_8));
                } else if (tag == 0x02) {
                    classNames.put(id(body + 4), strings.get(id(body + 8 + idSize)));
                } else if (tag == 0x0C || tag == 0x1C) {
                    heap(body, body + length);
                }
                position = body + length;
            }
        }
        for (Map.Entry<Long, String> entry : classNames.entrySet()) {
            if ("java/lang/ref/Reference".equals(entry.getValue())) {
                referenceClass = entry.getKey();
            }
        }
    }

    private void heap(long position, long end) {
        long p = position;
        while (p < end) {
            int sub = u1(p);
            long start = p;
            p++;
            switch (sub) {
                case 0xFF, 0x05, 0x07 -> p += idSize;
                case 0x01 -> p += 2L * idSize;
                case 0x02, 0x03 -> p += idSize + 8;
                case 0x08 -> {
                    threadRoots.add(id(p));
                    p += idSize + 8;
                }
                case 0x04, 0x06 -> p += idSize + 4;
                case 0x20 -> p = classDump(p);
                case 0x21 -> {
                    offsets.put(id(p), start);
                    p += idSize + 4 + idSize + 4 + u4(p + idSize + 4 + idSize);
                }
                case 0x22 -> {
                    offsets.put(id(p), start);
                    p += idSize + 8 + idSize + u4(p + idSize + 4) * idSize;
                }
                case 0x23 -> {
                    offsets.put(id(p), start);
                    p += idSize + 9 + u4(p + idSize + 4) * size(u1(p + idSize + 8));
                }
                default -> throw new IllegalStateException("HPROF sub-record " + Integer.toHexString(sub));
            }
        }
    }

    private long classDump(long position) {
        long p = position;
        long type = id(p);
        classInfo.put(type, new long[] {id(p + idSize + 4), id(p + 2L * idSize + 4)});
        p += idSize + 4 + 6L * idSize + 4;
        int constants = u2(p);
        p += 2;
        for (int i = 0; i < constants; i++) {
            p += 3 + size(u1(p + 2));
        }
        int staticCount = u2(p);
        p += 2;
        List<long[]> references = new ArrayList<>();
        for (int i = 0; i < staticCount; i++) {
            long name = id(p);
            int kind = u1(p + idSize);
            if (kind == 2) {
                references.add(new long[] {name, id(p + idSize + 1)});
            }
            p += idSize + 1 + size(kind);
        }
        statics.put(type, references);
        int fields = u2(p);
        p += 2;
        long[] names = new long[fields];
        byte[] types = new byte[fields];
        for (int i = 0; i < fields; i++) {
            names[i] = id(p);
            types[i] = (byte) u1(p + idSize);
            p += idSize + 1;
        }
        fieldNames.put(type, names);
        fieldTypes.put(type, types);
        return p;
    }

    private int size(int type) {
        return switch (type) {
            case 2 -> idSize;
            case 4, 8 -> 1;
            case 5, 9 -> 2;
            case 6, 10 -> 4;
            case 7, 11 -> 8;
            default -> throw new IllegalStateException("HPROF basic type " + type);
        };
    }

    private int u1(long position) {
        return maps.get((int) (position / CHUNK)).get((int) (position % CHUNK)) & 0xFF;
    }

    private int u2(long position) {
        return (u1(position) << 8) | u1(position + 1);
    }

    private long u4(long position) {
        return ((long) u1(position) << 24) | (u1(position + 1) << 16) | (u1(position + 2) << 8) | u1(position + 3);
    }

    private long id(long position) {
        return idSize == 8 ? (u4(position) << 32) | u4(position + 4) : u4(position);
    }
}
