package io.github.jdubois.bootui.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The agent's isolated class loader (PLAN-v2 D33): defines the implementation and the relocated Byte Buddy from
 * {@code inst/**.classdata}, so class-path scanners and coverage tools never see them. A lookup of {@code name.class}
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

    public AgentClassLoader(JarFile jar, String jarPath, ClassLoader parent) throws MalformedURLException {
        super("bootui-agent", parent);
        this.jar = jar;
        this.base = "jar:" + new File(jarPath).toURI().toURL() + "!/";
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        JarEntry entry = jar.getJarEntry("inst/" + name.replace('.', '/') + ".classdata");
        if (entry == null) {
            throw new ClassNotFoundException(name);
        }
        try (InputStream in = jar.getInputStream(entry)) {
            byte[] bytes = in.readAllBytes();
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

    @Override
    protected URL findResource(String name) {
        String entry = name.endsWith(".class")
                ? "inst/" + name.substring(0, name.length() - ".class".length()) + ".classdata"
                : "inst/" + name;
        if (jar.getJarEntry(entry) == null) {
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
}
