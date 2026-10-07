package io.github.jdubois.bootui.agent.resolverfixture;

/** A thread local's holder the resolver's throttle test reports as loaded once the test says so. */
public final class SecondHolder {

    public static final ThreadLocal<String> LOCAL = new ThreadLocal<>();

    private SecondHolder() {}
}
