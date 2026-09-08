package io.github.jdubois.bootui.engine.advisor;

/** Internal coverage accompanying a rule result without changing its public outcome. */
public record AdvisorRuleAssessment<T>(T result, boolean incomplete) {}
