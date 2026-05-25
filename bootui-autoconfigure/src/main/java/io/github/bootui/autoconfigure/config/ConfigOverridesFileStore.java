package io.github.bootui.autoconfigure.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Reads and writes BootUI runtime configuration overrides to a local file.
 *
 * <p>The file is treated as developer-local: BootUI never commits it, never sends
 * it anywhere, and recommends that the path is git-ignored. By default it is
 * {@code .bootui/application-bootui.properties} in the working directory.</p>
 */
public class ConfigOverridesFileStore {

    private final Path file;

    public ConfigOverridesFileStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public Map<String, Object> load() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read BootUI overrides file: " + file, e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            result.put(name, props.getProperty(name));
        }
        return result;
    }

    public synchronized void save(Map<String, Object> overrides) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Properties props = new Properties();
            overrides.forEach((k, v) -> props.setProperty(k, v == null ? "" : String.valueOf(v)));
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "BootUI local runtime overrides — do not commit.");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write BootUI overrides file: " + file, e);
        }
    }

    public synchronized void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to delete BootUI overrides file: " + file, e);
        }
    }
}
