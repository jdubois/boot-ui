package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.CodePaths;

/**
 * The code-paths sensor's self-test probe: the one class outside the bean classes its visit instruments. Its methods
 * report the depth their entries pushed, so the self-test sees the entry advice ran, and the depth after them, so it
 * sees both exit paths popped.
 */
final class CodePathsProbe {

    /** The depth inside this call, then inside a nested call. */
    public int[] ping() {
        return new int[] {CodePaths.depth(), nested()};
    }

    /** The depth inside this call. */
    public int nested() {
        return CodePaths.depth();
    }

    /** Throws, its message the depth inside this call. */
    public int fail() {
        throw new IllegalStateException(String.valueOf(CodePaths.depth()));
    }
}
