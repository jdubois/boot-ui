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
        return policy.advisors().entrySet().stream()
                .allMatch(entry -> !entry.getValue().required() || supports(entry.getKey()));
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
