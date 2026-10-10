package com.camellia.ingestion;

import com.camellia.model.LabResult;

import java.util.Set;
import java.util.TreeSet;

/** Human-readable text and deterministic tags for a normalized lab result. */
public final class LabText {

    public static final String PLATELET_LOINC = "777-3";

    private LabText() {}

    public static String describe(LabResult l) {
        String value = l.valueNum() != null ? FhirLikeNormalizer.fmt(l.valueNum()) : l.valueText();
        String ref = l.refText() == null ? "not provided" : l.refText();
        String flag = l.abnormalFlag() == null ? "" : " — laboratory flag: " + l.abnormalFlag()
                + (l.isBelowReference() ? " (below reference range)" : l.isAboveReference() ? " (above reference range)" : "");
        return "%s (LOINC %s): %s %s, reference range %s%s. Collected %s.".formatted(
                l.testName(), l.testCode(), value, l.unit() == null ? "" : l.unit(), ref, flag, l.collectedAt());
    }

    public static Set<String> tags(LabResult l) {
        Set<String> tags = new TreeSet<>();
        tags.add("LAB");
        if (l.isOutOfReference()) tags.add("ABNORMAL_LAB");
        if (PLATELET_LOINC.equals(l.testCode())) {
            tags.add("PLATELET");
            if (l.isBelowReference()) tags.add("LOW_PLATELETS");
        }
        return tags;
    }
}
