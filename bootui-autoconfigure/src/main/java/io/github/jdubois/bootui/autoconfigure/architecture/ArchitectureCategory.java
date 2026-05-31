package io.github.jdubois.bootui.autoconfigure.architecture;

/**
 * Logical grouping for the curated architecture rules so the panel can describe what each
 * rule inspects without depending on project-specific layering knowledge.
 */
enum ArchitectureCategory {
    PACKAGE_STRUCTURE("Package structure"),
    CODING_PRACTICES("Coding practices"),
    SPRING_STEREOTYPES("Spring stereotypes");

    private final String label;

    ArchitectureCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
