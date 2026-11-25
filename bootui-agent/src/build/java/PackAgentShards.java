import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** Build-only packing: the outer agent JAR compresses bounded groups of unchanged Byte Buddy class bytes together. */
public final class PackAgentShards {

    private static final String PREFIX = "io/github/jdubois/bootui/agent/shaded/bytebuddy/";
    // Keep the accepted names and hard bounds aligned with AgentClassLoader.ShardStore.
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z0-9_$/-]+\\.class");
    private static final int TARGET_BYTES = 128 * 1024;
    private static final int MAX_CLASS_BYTES = 192 * 1024;

    public static void main(String[] args) throws Exception {
        Path layout = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path root = layout.resolve(PREFIX);
        List<Path> classes;
        try (var files = Files.walk(root)) {
            classes = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> {
                        String name = root.relativize(path).toString().replace('\\', '/');
                        return name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
                    }))
                    .toList();
        }
        if (classes.isEmpty()) {
            throw new IllegalStateException("No shaded Byte Buddy classes to pack");
        }
        Files.createDirectories(output);
        List<byte[]> shards = new ArrayList<>();
        StringBuilder members = new StringBuilder();
        ByteArrayOutputStream shard = new ByteArrayOutputStream(TARGET_BYTES);
        for (Path file : classes) {
            String name = root.relativize(file).toString().replace('\\', '/');
            if (!name.endsWith(".class")) {
                throw new IllegalStateException("Unexpected Byte Buddy resource: " + name);
            }
            if (!CLASS_NAME.matcher(name).matches()) {
                throw new IllegalStateException("Unsupported Byte Buddy class name: " + name);
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0 || bytes.length > MAX_CLASS_BYTES) {
                throw new IllegalStateException("Oversized or empty Byte Buddy class: " + name);
            }
            if (shard.size() > 0 && shard.size() + bytes.length > TARGET_BYTES) {
                shards.add(shard.toByteArray());
                shard.reset();
            }
            members.append(name, 0, name.length() - ".class".length())
                    .append('\t')
                    .append(shards.size())
                    .append('\t')
                    .append(shard.size())
                    .append('\t')
                    .append(bytes.length)
                    .append('\n');
            shard.write(bytes);
        }
        shards.add(shard.toByteArray());
        if (shards.size() > 256 || classes.size() > 8192) {
            throw new IllegalStateException("Byte Buddy exceeds the agent shard bounds");
        }
        StringBuilder index =
                new StringBuilder("BOOTUI-SHARDS-1\n").append(shards.size()).append('\n');
        for (int id = 0; id < shards.size(); id++) {
            byte[] bytes = shards.get(id);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            index.append(bytes.length).append('\t').append(crc.getValue()).append('\n');
            Files.write(output.resolve(id + ".bin"), bytes);
        }
        index.append(members);
        byte[] encoded = index.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 384 * 1024) {
            throw new IllegalStateException("Byte Buddy shard index exceeds its runtime bound");
        }
        Files.write(output.resolve("index"), encoded);
    }
}
