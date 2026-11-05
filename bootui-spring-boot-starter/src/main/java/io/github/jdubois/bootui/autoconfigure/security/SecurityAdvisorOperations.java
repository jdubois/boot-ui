package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.web.AdvisorViolationsEndpoint;
import io.github.jdubois.bootui.core.dto.SecurityReport;

/** Classloading-safe operations shared by the Spring Security and static-only advisor controllers. */
public interface SecurityAdvisorOperations extends AdvisorViolationsEndpoint {

    SecurityReport security();

    SecurityReport scan();
}
