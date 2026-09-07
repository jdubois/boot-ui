package io.github.jdubois.bootui.spi;

/** A sanitized explanation of evidence that could not be obtained for an application-advisor rule. */
public record QuarkusAppEvidenceProblem(String ruleId, String message) {}
