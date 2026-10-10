package com.camellia.controller;

import com.camellia.analysis.AnalysisService;
import com.camellia.analysis.ScreeningRule.RuleDescriptor;
import com.camellia.config.DemoUsers;
import com.camellia.coordination.CoordinationService;
import com.camellia.dto.Dtos.FlagDto;
import com.camellia.service.ClinicalViewService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class FlagController {

    private final ClinicalViewService views;
    private final CoordinationService coordination;
    private final AnalysisService analysis;

    public FlagController(ClinicalViewService views, CoordinationService coordination, AnalysisService analysis) {
        this.views = views;
        this.coordination = coordination;
        this.analysis = analysis;
    }

    @GetMapping("/flags")
    public List<FlagDto> flags(@RequestParam(required = false) String status, @RequestParam(required = false) String ruleId,
                               @RequestParam(required = false) String patientId) {
        return views.flagList(status, ruleId, patientId);
    }

    @GetMapping("/flags/{id}")
    public FlagDto flag(@PathVariable String id) {
        return views.flagDetail(id);
    }

    public record FlagStatusRequest(String status, String reason) {}

    @PatchMapping("/flags/{id}/status")
    public FlagDto updateStatus(@PathVariable String id, @RequestBody FlagStatusRequest req,
                                @RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        coordination.updateFlagStatus(id, req.status(), req.reason(), CurrentUser.of(user));
        return views.flagDetail(id);
    }

    @GetMapping("/analysis/rules")
    public List<RuleDescriptor> rules() {
        return analysis.describeRules();
    }

    @PostMapping("/analysis/run")
    public AnalysisService.RunResult run(@RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        return analysis.run(CurrentUser.of(user).name());
    }
}
