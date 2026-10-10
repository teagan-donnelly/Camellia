package com.camellia.ai;

import java.util.List;
import java.util.Map;

/** JSON Schemas passed to Gemini as structured-output contracts (responseJsonSchema). */
public final class AiSchemas {

    private AiSchemas() {}

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> strArray(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
    }

    private static Map<String, Object> statements(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of(
                "type", "object",
                "properties", Map.of(
                        "statement", str("One concise statement."),
                        "sourceRecordIds", strArray("Record IDs (exactly as given, e.g. REC-NDL-ACC-55012-1) that support this statement.")),
                "required", List.of("statement", "sourceRecordIds")));
    }

    /** Answer contract shared by chat, flag explanations and search summaries. */
    public static final Map<String, Object> ASSISTANT_ANSWER = Map.of(
            "type", "object",
            "properties", Map.of(
                    "answer", str("Direct answer in 1-6 sentences. Cite record IDs in square brackets after each patient-specific fact."),
                    "documentedFacts", statements("Facts stated in the provided records, each with its supporting record IDs."),
                    "interpretation", statements("Cautious, review-oriented AI interpretation of the documented facts. No diagnoses, no treatment recommendations."),
                    "missingInformation", strArray("Relevant information NOT found in the provided records. Missing information is not evidence of absence."),
                    "suggestedFollowUpQuestions", strArray("Up to 3 short follow-up questions the clinician might ask next.")),
            "required", List.of("answer", "documentedFacts", "interpretation", "missingInformation", "suggestedFollowUpQuestions"));

    public static final List<String> OBSERVATION_CATEGORIES = List.of("CONDITION", "SYMPTOM", "LAB_FINDING", "MEDICATION",
            "PROCEDURE_HISTORY", "ANESTHESIA_HISTORY", "UPCOMING_CARE_NEED", "CLINICAL_CONCERN", "CARE_RELATIONSHIP");

    public static final Map<String, Object> EXTRACTION = Map.of(
            "type", "object",
            "properties", Map.of("observations", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "category", Map.of("type", "string", "enum", OBSERVATION_CATEGORIES),
                                    "statement", str("Normalized observation in plain clinical language, faithful to the source."),
                                    "sourceRecordId", str("The single record ID the observation came from."),
                                    "evidenceQuote", str("A short verbatim quote (5-25 words) copied exactly from that record.")),
                            "required", List.of("category", "statement", "sourceRecordId", "evidenceQuote")))),
            "required", List.of("observations"));

    public static Map<String, Object> searchCriteria(List<String> concepts, List<String> recordTypes, List<String> orgIds,
                                                     List<String> patientIds, List<String> flagCategories) {
        List<String> patientEnum = new java.util.ArrayList<>(patientIds);
        patientEnum.add("");
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("keywords", strArray("Free-text terms to match in record text, only when no concept fits. Keep short.")),
                        Map.entry("concepts", Map.of("type", "array", "items", Map.of("type", "string", "enum", concepts))),
                        Map.entry("recordTypes", Map.of("type", "array", "items", Map.of("type", "string", "enum", recordTypes))),
                        Map.entry("organizationIds", Map.of("type", "array", "items", Map.of("type", "string", "enum", orgIds))),
                        Map.entry("patientId", Map.of("type", "string", "enum", patientEnum, "description", "Empty string unless the query names one patient.")),
                        Map.entry("dateFrom", str("YYYY-MM-DD or empty string.")),
                        Map.entry("dateTo", str("YYYY-MM-DD or empty string.")),
                        Map.entry("obstetricOnly", Map.of("type", "boolean")),
                        Map.entry("upcomingDeliveryOnly", Map.of("type", "boolean")),
                        Map.entry("multipleOrganizationsOnly", Map.of("type", "boolean")),
                        Map.entry("openFlagsOnly", Map.of("type", "boolean")),
                        Map.entry("minMatchesPerPatient", Map.of("type", "integer", "description", "1 normally; 2 or 3 for 'repeated/recurring/multiple' requests.")),
                        Map.entry("matchMode", Map.of("type", "string", "enum", List.of("ANY", "ALL"))),
                        Map.entry("searchTarget", Map.of("type", "string", "enum", List.of("RECORDS", "FLAGS"),
                                "description", "FLAGS when the request is about clinical flags, alerts, flagged patients or review items; otherwise RECORDS.")),
                        Map.entry("flagCategories", Map.of("type", "array", "items", Map.of("type", "string", "enum", flagCategories))),
                        Map.entry("flagStatusGroup", Map.of("type", "string", "enum", List.of("OPEN", "CLOSED", "ANY"),
                                "description", "OPEN for open/unresolved/active/pending items, CLOSED for resolved/reviewed/dismissed, else ANY.")),
                        Map.entry("flagPriorities", Map.of("type", "array", "items", Map.of("type", "string", "enum", List.of("EXPEDITED", "STANDARD")))),
                        Map.entry("flagSpecialties", strArray("Specialties of the evidence (e.g. Cardiology, Hematology, Obstetrics), only if requested.")),
                        Map.entry("explanation", str("One sentence describing how the request was interpreted."))),
                "required", List.of("keywords", "concepts", "recordTypes", "organizationIds", "patientId", "dateFrom", "dateTo",
                        "obstetricOnly", "upcomingDeliveryOnly", "multipleOrganizationsOnly", "openFlagsOnly",
                        "minMatchesPerPatient", "matchMode", "searchTarget", "flagCategories", "flagStatusGroup",
                        "flagPriorities", "flagSpecialties", "explanation"));
    }

    public static final Map<String, Object> PING = Map.of(
            "type", "object",
            "properties", Map.of("status", str("The word ok"), "message", str("One short friendly sentence confirming you are reachable.")),
            "required", List.of("status", "message"));
}
