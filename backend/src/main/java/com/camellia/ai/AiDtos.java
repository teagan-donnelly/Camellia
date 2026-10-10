package com.camellia.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

/** Wire types for AI features. */
public final class AiDtos {

    private AiDtos() {}

    // ---------- raw model output (parsed from Gemini JSON) ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawStatement(String statement, List<String> sourceRecordIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawAnswer(String answer, List<RawStatement> documentedFacts, List<RawStatement> interpretation,
                            List<String> missingInformation, List<String> suggestedFollowUpQuestions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawObservation(String category, String statement, String sourceRecordId, String evidenceQuote) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawObservations(List<RawObservation> observations) {}

    // ---------- validated output returned to the UI ----------

    public record Statement(String statement, List<String> sourceRecordIds) {}

    public record Citation(String recordId, String title, String recordType, LocalDateTime recordDate, String orgId,
                           String orgName, String author, String sourceFormat) {}

    public record Validation(int citationsRemoved, int uncitedFactsRemoved, int recordsProvided) {}

    /**
     * mode = GEMINI (live model output, validated) or RETRIEVAL_ONLY (Gemini unavailable: no AI text, just the retrieved records).
     */
    public record AiAnswer(String mode, boolean aiGenerated, String model, LocalDateTime generatedAt, String answer,
                           List<Statement> documentedFacts, List<Statement> interpretation, List<String> missingInformation,
                           List<String> suggestedFollowUpQuestions, List<Citation> citations, List<Citation> retrievedRecords,
                           Validation validation, Long latencyMs, String conversationId, String notice) {}

    // ---------- requests ----------

    public record ChatRequest(String conversationId, String contextType, String contextId, List<String> recordIds, String message) {}

    public record ExplainFlagRequest(String flagId, Boolean refresh) {}

    public record SearchSummaryRequest(String query, List<String> recordIds) {}

    public record ExtractionResult(String patientId, String model, LocalDateTime generatedAt, int recordsAnalyzed,
                                   int observationsAccepted, int observationsRejected, List<String> rejectionReasons,
                                   List<com.camellia.model.AiObservation> observations) {}
}
