package bootuicodepathsapp;

import io.github.jdubois.bootui.agent.bridge.CodePaths;

/** Loaded and run before a refine names it as a bean class, which then retransforms it. */
public class LateBean {

    public int depthInside() {
        return CodePaths.depth();
    }
}
