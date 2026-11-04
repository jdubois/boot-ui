package io.github.jdubois.bootui.cli.ci;

enum CiFramework {
    SPRING_BOOT("Spring Boot"),
    QUARKUS("Quarkus");

    private final String displayName;

    CiFramework(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }

    boolean supports(CiPolicy.Advisor advisor) {
        return this != QUARKUS || advisor != CiPolicy.Advisor.SPRING;
    }

    boolean supportsPolicy(CiPolicy policy) {
        return policy.orderedAdvisors().stream().allMatch(this::supports);
    }

    static CiFramework fromName(String name) {
        for (CiFramework framework : values()) {
            if (framework.displayName.equals(name)) {
                return framework;
            }
        }
        return null;
    }
}
