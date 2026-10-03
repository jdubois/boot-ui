package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One BootUI application's claim on the agent. Immutable: every transition replaces the whole record. The engine holds
 * the only strong references to {@code capture} and {@code reopen}, fresh objects for each claim, so a claim whose engine
 * is gone without disarming is detected as abandoned once they are collected.
 */
final class Claim {

    final long generation;
    final long token;
    final String slot;
    final String owner;
    final String application;
    final String mode;
    final List<String> packages;
    final long armedAt;
    final boolean armed;
    final WeakReference<Supplier<Object>> capture;
    final WeakReference<Function<Object, AutoCloseable>> reopen;

    Claim(
            long generation,
            long token,
            String owner,
            String application,
            String mode,
            List<String> packages,
            long armedAt,
            boolean armed,
            WeakReference<Supplier<Object>> capture,
            WeakReference<Function<Object, AutoCloseable>> reopen) {
        this.generation = generation;
        this.token = token;
        this.slot = slot(mode, application);
        this.owner = owner;
        this.application = application;
        this.mode = mode;
        this.packages = packages;
        this.armedAt = armedAt;
        this.armed = armed;
        this.capture = capture;
        this.reopen = reopen;
    }

    static String slot(String mode, String application) {
        return mode + ":" + application;
    }

    /** The engine that claimed is gone without disarming: its capture was collected. */
    boolean abandoned() {
        return armed && capture.get() == null;
    }

    Claim withPackages(List<String> more) {
        List<String> merged = new ArrayList<String>(packages);
        for (int i = 0; i < more.size(); i++) {
            String name = more.get(i);
            if (!merged.contains(name)) {
                merged.add(name);
            }
        }
        return new Claim(
                generation,
                token,
                owner,
                application,
                mode,
                Collections.unmodifiableList(merged),
                armedAt,
                armed,
                capture,
                reopen);
    }

    Claim disarmed() {
        return new Claim(generation, token, owner, application, mode, packages, armedAt, false, capture, reopen);
    }

    /** JDK types only, for the agent and for status. */
    Map<String, Object> describe() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("generation", Long.valueOf(generation));
        map.put("owner", owner);
        map.put("application", application);
        map.put("mode", mode);
        map.put("packages", new ArrayList<String>(packages));
        map.put("armedAt", Long.valueOf(armedAt));
        map.put("armed", Boolean.valueOf(armed));
        map.put("abandoned", Boolean.valueOf(abandoned()));
        return map;
    }
}
