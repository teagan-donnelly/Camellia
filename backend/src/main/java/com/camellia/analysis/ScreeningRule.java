package com.camellia.analysis;

import java.util.List;

/** A deterministic, explainable screening rule. Rules never diagnose; they suggest human review. */
public interface ScreeningRule {

    String id();

    String version();

    /** Plain-language description including the configured prototype parameters. */
    RuleDescriptor describe();

    List<CandidateFlag> evaluate(PatientSnapshot snapshot);

    record RuleDescriptor(String id, String version, String name, String description, List<String> parameters,
                          String routesTo) {}
}
