package com.camellia.controller;

import com.camellia.ai.AiDtos.*;
import com.camellia.ai.ClinicalAiService;
import com.camellia.security.DemoUser;
import com.camellia.web.ApiException;
import org.springframework.web.bind.annotation.*;

/** Gemini-backed endpoints. AI output is advisory, scoped to the user's access, and never changes data or workflow state. */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final ClinicalAiService ai;

    public AiController(ClinicalAiService ai) {
        this.ai = ai;
    }

    @PostMapping("/chat")
    public AiAnswer chat(DemoUser user, @RequestBody ChatRequest req) {
        return ai.chat(req, user);
    }

    @PostMapping("/explain-flag")
    public AiAnswer explainFlag(DemoUser user, @RequestBody ExplainFlagRequest req) {
        if (req.flagId() == null || req.flagId().isBlank()) throw ApiException.badRequest("flagId is required");
        return ai.explainFlag(req.flagId(), Boolean.TRUE.equals(req.refresh()), user);
    }

    @PostMapping("/search-summary")
    public AiAnswer searchSummary(DemoUser user, @RequestBody SearchSummaryRequest req) {
        return ai.summarizeSearch(req.query() == null ? "" : req.query(), req.recordIds(), user);
    }
}
