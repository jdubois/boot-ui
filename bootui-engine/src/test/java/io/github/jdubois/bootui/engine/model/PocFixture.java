package io.github.jdubois.bootui.engine.model;

import java.util.List;
import java.util.Map;

/**
 * The PoC's change-impact evidence ({@code docs/PLAN-v2.md} §1.1), converted into a fixture: a repository that seven
 * beans reach, and the routes those beans handle, which served 571 requests. One unrelated controller and its route
 * must stay out of the impact.
 */
final class PocFixture {

    static final String REPOSITORY = "ownerRepository";

    /** The routes, the controller bean that handles each, and its requests in the run. */
    static final Map<String, Object[]> ROUTES = Map.of(
            "GET /owners/{id}", new Object[] {"OwnerController", 300},
            "POST /owners", new Object[] {"OwnerController", 41},
            "GET /pets/{id}", new Object[] {"PetController", 150},
            "POST /visits", new Object[] {"VisitController", 60},
            "GET /reports", new Object[] {"ReportController", 20},
            "GET /vets", new Object[] {"VetController", 90});

    private PocFixture() {}

    static StructureSnapshot structure() {
        return new StructureSnapshot(
                "poc-run",
                ROUTES.entrySet().stream()
                        .map(route -> new StructureSnapshot.RouteHandler(
                                route.getKey(), "org.petclinic." + route.getValue()[0]))
                        .toList(),
                List.of(
                        bean(REPOSITORY, "OwnerRepository", true),
                        bean("vetRepository", "VetRepository", true),
                        bean("ownerService", "OwnerService", false, REPOSITORY),
                        bean("clinicService", "ClinicService", false, REPOSITORY),
                        bean("reportService", "ReportService", false, "clinicService"),
                        bean("ownerController", "OwnerController", false, "ownerService"),
                        bean("petController", "PetController", false, "ownerService", "clinicService"),
                        bean("visitController", "VisitController", false, "clinicService"),
                        bean("reportController", "ReportController", false, "reportService"),
                        bean("vetController", "VetController", false, "vetRepository")));
    }

    static JournalFixture journal() {
        JournalFixture journal = new JournalFixture();
        ROUTES.forEach((route, handler) -> {
            String method = route.substring(0, route.indexOf(' '));
            String template = route.substring(route.indexOf(' ') + 1);
            for (int i = 0; i < (int) handler[1]; i++) {
                journal.request(method, template);
            }
        });
        return journal;
    }

    private static StructureSnapshot.Bean bean(String name, String type, boolean repository, String... dependencies) {
        return new StructureSnapshot.Bean(name, "org.petclinic." + type, repository, List.of(dependencies));
    }
}
