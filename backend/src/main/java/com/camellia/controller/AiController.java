package com.camellia.controller;

import com.camellia.ai.AiDtos.*;
import com.camellia.ai.ClinicalAiService;
import com.camellia.config.DemoUsers;
import com.camellia.web.ApiException;
import org.springframework.web.bind.annotation.*;

/** Gemini-backed endpoints. AI output is advisory and never changes clinical data or workflow state. */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final ClinicalAiService ai;

    public AiController(ClinicalAiService ai) {
        this.ai = ai;
    }

    @PostMapping("/chat")
    public AiAnswer chat(@RequestBody ChatRequest req, @RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        return ai.chat(req, CurrentUser.of(user).name());
    }

    @PostMapping("/explain-flag")
    public AiAnswer explainFlag(@RequestBody ExplainFlagRequest req, @RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        if (req.flagId() == null || req.flagId().isBlank()) throw ApiException.badRequest("flagId is required");
        return ai.explainFlag(req.flagId(), Boolean.TRUE.equals(req.refresh()), CurrentUser.of(user).name());
    }

    @PostMapping("/search-summary")
    public AiAnswer searchSummary(@RequestBody SearchSummaryRequest req, @RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        return ai.summarizeSearch(req.query() == null ? "" : req.query(), req.recordIds(), CurrentUser.of(user).name());
    }
}
