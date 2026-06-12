package io.github.jdubois.bootui.autoconfigure.crac.fixtures;

/** Captures environment and system configuration in a static initializer (CRAC-CONFIG-001). */
public class ConfigCapturer {

    static final String REGION = System.getProperty("app.region");
    static final String HOME = System.getenv("HOME");

    public String region() {
        return REGION;
    }

    public String home() {
        return HOME;
    }
}
