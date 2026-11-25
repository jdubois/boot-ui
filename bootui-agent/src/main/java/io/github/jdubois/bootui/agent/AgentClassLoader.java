package io.github.jdubois.bootui.agent;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * The agent's isolated class loader (PLAN-v2 D33): defines the implementation and the relocated Byte Buddy from
 * {@code inst/**.classdata} and bounded Byte Buddy shards, so class-path scanners and coverage tools never see them.
 * A lookup of {@code name.class}
 * resolves to {@code inst/name.classdata} (Byte Buddy reads advice bytes as resources), and any other resource to
 * {@code inst/name}. Its parent is the platform class loader; the bridge is reached through the bootstrap class loader.
 * Classes get the loader's default protection domain, whose code source has no location.
 */
public final class AgentClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final JarFile jar;
    private final String base;
    private static final String BYTE_BUDDY = "io/github/jdubois/bootui/agent/shaded/bytebuddy/";
    private static final String SHARDS = "inst/byte-buddy/";
    private final Object shardLock = new Object();
    private volatile ShardStore shards;

    public AgentClassLoader(JarFile jar, String jarPath, ClassLoader parent) throws MalformedURLException {
        super("bootui-agent", parent);
        this.jar = jar;
        this.base = "jar:" + new File(jarPath).toURI().toURL() + "!/";
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/');
        try {
            byte[] bytes;
            JarEntry entry = jar.getJarEntry("inst/" + path + ".classdata");
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
            } else if (path.startsWith(BYTE_BUDDY) && hasShards()) {
                bytes = shardStore().read(path.substring(BYTE_BUDDY.length()));
            } else {
                bytes = null;
            }
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String packageName = name.substring(0, dot);
                if (getDefinedPackage(packageName) == null) {
                    try {
                        definePackage(packageName, null, null, null, null, null, null, null);
                    } catch (IllegalArgumentException definedConcurrently) {
                        // another thread defined it first
                    }
                }
            }
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException ex) {
            throw new ClassNotFoundException(name, ex);
        }
    }

    /**
     * Defines, without initializing, every class of the agent jar whose name starts with {@code prefix}, and returns
     * how many. A class defined here runs no registered transformer inside another transformer's class loading, as one
     * first needed by a transformer does: a thread that holds this loader's lock for that class, and waits for a JDK class
     * another thread's transformer is loading, deadlocks with that thread when it waits for the same lock. A class that
     * does not load from the flat layout is left for its first use. Packed classes fail warm-up on invalid payloads or
     * missing agent types; only unavailable external optional APIs are skipped. Public because the isolated
     * implementation and this parent-defined loader belong to different runtime packages.
     */
    public int define(String prefix) {
        String entryPrefix = "inst/" + prefix.replace('.', '/');
        int defined = 0;
        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
            String entry = entries.nextElement().getName();
            if (entry.startsWith(entryPrefix) && entry.endsWith(".classdata")) {
                String name = entry.substring("inst/".length(), entry.length() - ".classdata".length())
                        .replace('/', '.');
                try {
                    loadClass(name);
                    defined++;
                } catch (Throwable ex) {
                    // Left for its first use.
                }
            }
        }
        String buddyPrefix = BYTE_BUDDY.replace('/', '.');
        if ((buddyPrefix.startsWith(prefix) || prefix.startsWith(buddyPrefix)) && hasShards()) {
            try {
                ShardStore store = shardStore();
                for (String suffix : store.names) {
                    String name = buddyPrefix + suffix.replace('/', '.');
                    if (name.startsWith(prefix)) {
                        try {
                            loadClass(name);
                            defined++;
                        } catch (NoClassDefFoundError unavailable) {
                            // Byte Buddy includes optional dispatchers for APIs such as JNA that the agent does not
                            // ship.
                            Throwable missing = unavailable.getCause();
                            if (!(missing instanceof ClassNotFoundException)
                                    || missing.getCause() != null
                                    || missing.getMessage() == null
                                    || missing.getMessage().startsWith("io.github.jdubois.bootui.agent.")) {
                                throw new IllegalStateException(
                                        "Cannot warm the packed Byte Buddy classes", unavailable);
                            }
                        }
                    }
                }
            } catch (IOException | ClassNotFoundException ex) {
                throw new IllegalStateException("Cannot warm the packed Byte Buddy classes", ex);
            }
        }
        return defined;
    }

    @Override
    protected URL findResource(String name) {
        String entry = name.endsWith(".class")
                ? "inst/" + name.substring(0, name.length() - ".class".length()) + ".classdata"
                : "inst/" + name;
        if (jar.getJarEntry(entry) == null) {
            if (name.endsWith(".class") && name.startsWith(BYTE_BUDDY) && hasShards()) {
                String suffix = name.substring(BYTE_BUDDY.length(), name.length() - ".class".length());
                try {
                    if (shardStore().find(suffix) >= 0) {
                        return new URL(null, "bootui-agent:" + name, new PackedResource(this, suffix));
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException("Cannot read the agent's Byte Buddy index", ex);
                }
            }
            return null;
        }
        try {
            return URI.create(base + entry).toURL();
        } catch (MalformedURLException | IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        URL url = findResource(name);
        return url == null
                ? Collections.<URL>emptyEnumeration()
                : Collections.enumeration(Collections.singletonList(url));
    }

    private boolean hasShards() {
        return jar.getJarEntry(SHARDS + "index") != null || jar.getJarEntry(SHARDS + "0.bin") != null;
    }

    private ShardStore shardStore() throws IOException {
        ShardStore store = shards;
        if (store == null) {
            synchronized (shardLock) {
                store = shards;
                if (store == null) {
                    store = new ShardStore(jar);
                    shards = store;
                }
            }
        }
        return store;
    }

    private static byte[] bounded(JarFile jar, String name, int maximum) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        if (entry == null) {
            throw new IOException("Missing agent payload: " + name);
        }
        try (InputStream in = jar.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new IOException("Oversized agent payload: " + name);
            }
            return bytes;
        }
    }

    private static final class PackedResource extends URLStreamHandler {
        private final AgentClassLoader loader;
        private final String suffix;

        private PackedResource(AgentClassLoader loader, String suffix) {
            this.loader = loader;
            this.suffix = suffix;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new URLConnection(url) {
                @Override
                public void connect() {}

                @Override
                public InputStream getInputStream() throws IOException {
                    byte[] bytes = loader.shardStore().read(suffix);
                    if (bytes == null) {
                        throw new IOException("Missing packed agent resource: " + suffix);
                    }
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }

    private static final class ShardStore {
        private static final int MAX_SHARD = 256 * 1024;
        private static final Pattern MEMBER_NAME = Pattern.compile("[A-Za-z0-9_$/-]+");
        private final JarFile jar;
        private final String[] names;
        private final int[][] members;
        private final int[] sizes;
        private final long[] checksums;
        private final Map<Integer, byte[]> cache = new LinkedHashMap<>(4, 0.75f, true);
        private long inflations;
        private long inflatedBytes;

        private ShardStore(JarFile jar) throws IOException {
            this.jar = jar;
            byte[] index = bounded(jar, SHARDS + "index", 384 * 1024);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(index), java.nio.charset.StandardCharsets.UTF_8))) {
                if (!"BOOTUI-SHARDS-1".equals(reader.readLine())) {
                    throw new IOException("Unknown agent shard format");
                }
                int count = Integer.parseInt(reader.readLine());
                if (count < 1 || count > 256) {
                    throw new IOException("Invalid agent shard count");
                }
                sizes = new int[count];
                checksums = new long[count];
                for (int id = 0; id < count; id++) {
                    String[] fields = reader.readLine().split("\t", -1);
                    if (fields.length != 2) {
                        throw new IOException("Invalid agent shard descriptor");
                    }
                    sizes[id] = Integer.parseInt(fields[0]);
                    checksums[id] = Long.parseLong(fields[1]);
                    if (sizes[id] < 1 || sizes[id] > MAX_SHARD || checksums[id] < 0 || checksums[id] > 0xffffffffL) {
                        throw new IOException("Invalid agent shard size or checksum");
                    }
                }
                List<String> paths = new ArrayList<>();
                List<int[]> locations = new ArrayList<>();
                int[] ends = new int[count];
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 4
                            || paths.size() >= 8192
                            || !MEMBER_NAME.matcher(fields[0]).matches()
                            || (!paths.isEmpty() && paths.get(paths.size() - 1).compareTo(fields[0]) >= 0)) {
                        throw new IOException("Invalid or unsorted agent shard member");
                    }
                    int id = Integer.parseInt(fields[1]);
                    int offset = Integer.parseInt(fields[2]);
                    int length = Integer.parseInt(fields[3]);
                    if (id < 0
                            || id >= count
                            || offset != ends[id]
                            || length < 1
                            || length > 192 * 1024
                            || offset > sizes[id] - length) {
                        throw new IOException("Invalid agent shard member bounds");
                    }
                    ends[id] += length;
                    paths.add(fields[0]);
                    locations.add(new int[] {id, offset, length});
                }
                if (!Arrays.equals(ends, sizes)) {
                    throw new IOException("Incomplete agent shard index");
                }
                names = paths.toArray(new String[0]);
                members = locations.toArray(new int[0][]);
            } catch (NumberFormatException | NullPointerException ex) {
                throw new IOException("Malformed agent shard index", ex);
            }
        }

        private int find(String suffix) {
            return Arrays.binarySearch(names, suffix);
        }

        // This lock guards only bounded JAR reads and byte arrays, never class definition or callbacks.
        private synchronized byte[] read(String suffix) throws IOException {
            int member = find(suffix);
            if (member < 0) {
                return null;
            }
            int[] location = members[member];
            int id = location[0];
            byte[] bytes = cache.get(id);
            if (bytes == null) {
                bytes = bounded(jar, SHARDS + id + ".bin", sizes[id]);
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if (bytes.length != sizes[id] || crc.getValue() != checksums[id]) {
                    throw new IOException("Corrupt agent shard: " + id);
                }
                cache.put(id, bytes);
                if (cache.size() > 4) {
                    cache.remove(cache.keySet().iterator().next());
                }
                inflations++;
                inflatedBytes += bytes.length;
            }
            return Arrays.copyOfRange(bytes, location[1], location[1] + location[2]);
        }
    }
}
