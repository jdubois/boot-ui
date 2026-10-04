package io.github.jdubois.bootui.engine.codepaths;

import java.util.ArrayList;
import java.util.List;

/** Builds code-paths blobs in the bridge's layout, as the agent flushes them. */
final class Blobs {

    private final long generation;
    private final long request;
    private final long execution;
    private final int kind;
    private long start;
    private long end;
    private long dropped;
    private int flags;
    private long sequence;
    private long submitter;
    private final List<long[]> nodes = new ArrayList<>();

    private Blobs(long generation, long request, long execution, int kind) {
        this.generation = generation;
        this.request = request;
        this.execution = execution;
        this.kind = kind;
    }

    static Blobs request(long generation, long request) {
        return new Blobs(generation, request, 0L, CodePathFragment.EXECUTION_NONE);
    }

    static Blobs handoff(long generation, long request, long execution) {
        return new Blobs(generation, request, execution, CodePathFragment.EXECUTION_ASYNC);
    }

    Blobs between(long startNanos, long endNanos) {
        start = startNanos;
        end = endNanos;
        return this;
    }

    Blobs dropped(long calls) {
        dropped = calls;
        return this;
    }

    Blobs flags(int value) {
        flags = value;
        return this;
    }

    /** The fragment's sequence, which its nodes' stamps carry. */
    Blobs sequence(long value) {
        sequence = value;
        return this;
    }

    /** The stamp of the node that submitted a handoff's work. */
    Blobs submitter(long stamp) {
        submitter = stamp;
        return this;
    }

    /** A node: parent index, method id, phase, calls, total, children's total. */
    Blobs node(int parent, int method, int phase, long calls, long total, long child) {
        nodes.add(new long[] {parent, method, phase, calls, total, child});
        return this;
    }

    long[] blob() {
        long[] blob = new long[CodePathFragment.HEADER + nodes.size() * CodePathFragment.NODE];
        blob[CodePathFragment.H_VERSION] = CodePathFragment.VERSION;
        blob[CodePathFragment.H_GENERATION] = generation;
        blob[CodePathFragment.H_REQUEST] = request;
        blob[CodePathFragment.H_EXECUTION] = execution;
        blob[CodePathFragment.H_FLAGS] = kind | flags;
        blob[CodePathFragment.H_START_NANOS] = start;
        blob[CodePathFragment.H_END_NANOS] = end;
        blob[CodePathFragment.H_NODES] = nodes.size();
        blob[CodePathFragment.H_DROPPED] = dropped;
        blob[CodePathFragment.H_START_MILLIS] = 1_000L + start / 1_000_000L;
        blob[CodePathFragment.H_SEQUENCE] = sequence;
        blob[CodePathFragment.H_SUBMITTER] = submitter;
        for (int i = 0; i < nodes.size(); i++) {
            System.arraycopy(nodes.get(i), 0, blob, CodePathFragment.HEADER + i * CodePathFragment.NODE, 6);
        }
        return blob;
    }

    /** The blob as an agent predating stamps flushed it: version 1, without a sequence or submitter. */
    long[] blobVersion1() {
        long[] blob = new long[CodePathFragment.HEADER_1 + nodes.size() * CodePathFragment.NODE];
        long[] current = blob();
        System.arraycopy(current, 0, blob, 0, CodePathFragment.HEADER_1);
        blob[CodePathFragment.H_VERSION] = CodePathFragment.VERSION_1;
        for (int i = 0; i < nodes.size(); i++) {
            System.arraycopy(nodes.get(i), 0, blob, CodePathFragment.HEADER_1 + i * CodePathFragment.NODE, 6);
        }
        return blob;
    }

    CodePathFragment fragment() {
        return CodePathFragment.decode(blob());
    }
}
