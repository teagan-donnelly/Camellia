package com.camellia.controller;

import com.camellia.analysis.AnalysisService;
import com.camellia.analysis.ScreeningRule.RuleDescriptor;
import com.camellia.coordination.CoordinationService;
import com.camellia.dto.Dtos.FlagDto;
import com.camellia.repository.FlagRepository;
import com.camellia.search.FlagSearchCriteria;
import com.camellia.search.FlagSearchService;
import com.camellia.security.AuthorizationService;
import com.camellia.security.AuthorizationService.AccessScope;
import com.camellia.security.DemoUser;
import com.camellia.security.Permission;
import com.camellia.service.ClinicalViewService;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/** Clinical alerts (flags): list, structured flag search, detail, review status, screening rules and runs. */
@RestController
@RequestMapping("/api")
public class FlagController {

    private final ClinicalViewService views;
    private final CoordinationService coordination;
    private final AnalysisService analysis;
    private final AuthorizationService auth;
    private final FlagRepository flags;
    private final FlagSearchService flagSearch;

    public FlagController(ClinicalViewService views, CoordinationService coordination, AnalysisService analysis,
                          AuthorizationService auth, FlagRepository flags, FlagSearchService flagSearch) {
        this.views = views;
        this.coordination = coordination;
        this.analysis = analysis;
        this.auth = auth;
        this.flags = flags;
        this.flagSearch = flagSearch;
    }

    @GetMapping("/flags")
    public List<FlagDto> flags(DemoUser user, @RequestParam(required = false) String status, @RequestParam(required = false) String ruleId,
                               @RequestParam(required = false) String patientId) {
        auth.requirePermission(user, Permission.CLINICAL_ALERTS);
        return views.flagList(status, ruleId, patientId, auth.scope(user));
    }

    /** Structured flag filtering (Clinical Alerts filter bar). Combine any parameters; values are allow-listed and bound. */
    @GetMapping("/flags/search")
    public FlagSearchService.FlagSearchResult searchFlags(DemoUser user,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(required = false) String category,
                                                          @RequestParam(required = false) String statusGroup,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String priority,
                                                          @RequestParam(required = false) String specialty,
                                                          @RequestParam(required = false) String concept,
                                                          @RequestParam(required = false) String patientId) {
        auth.requirePermission(user, Permission.CLINICAL_ALERTS);
        return flagSearch.search(new FlagSearchCriteria(q, csv(category), statusGroup, csv(status), csv(priority), csv(specialty),
                csv(concept), patientId), auth.scope(user));
    }

    /** Executes flag criteria produced by natural-language interpretation. */
    @PostMapping("/flags/search")
    public FlagSearchService.FlagSearchResult executeFlagSearch(DemoUser user, @RequestBody FlagSearchCriteria criteria) {
        auth.requirePermission(user, Permission.CLINICAL_ALERTS);
        return flagSearch.search(criteria, auth.scope(user));
    }

    @GetMapping("/flags/facets")
    public FlagSearchService.Facets facets(DemoUser user) {
        auth.requirePermission(user, Permission.CLINICAL_ALERTS);
        return flagSearch.facets(auth.scope(user));
    }

    @GetMapping("/flags/{id}")
    public FlagDto flag(DemoUser user, @PathVariable String id) {
        auth.requireFlag(user, flags.findById(id).orElse(null));
        return views.flagDetail(id, auth.scope(user));
    }

    public record FlagStatusRequest(String status, String reason) {}

    @PatchMapping("/flags/{id}/status")
    public FlagDto updateStatus(DemoUser user, @PathVariable String id, @RequestBody FlagStatusRequest req) {
        coordination.updateFlagStatus(id, req.status(), req.reason(), user);
        return views.flagDetail(id, auth.scope(user));
    }

    @GetMapping("/analysis/rules")
    public List<RuleDescriptor> rules(DemoUser user) {
        auth.requirePermission(user, Permission.CLINICAL_ALERTS);
        return analysis.describeRules().stream()
                .filter(r -> user.has(Permission.ANESTHESIA_OB_ALERTS) || !AuthorizationService.ANESTHESIA_RULE.equals(r.id()))
                .toList();
    }

    /** Runs screening for the whole network, but only reports outcomes the user is allowed to see. */
    @PostMapping("/analysis/run")
    public AnalysisService.RunResult run(DemoUser user) {
        auth.requirePermission(user, Permission.CLINICAL_ALERTS);
        return scoped(analysis.run(user.name()), auth.scope(user));
    }

    static AnalysisService.RunResult scoped(AnalysisService.RunResult r, AccessScope scope) {
        List<AnalysisService.Outcome> visible = r.outcomes().stream()
                .filter(o -> scope.allowsPatient(o.patientId()) && scope.allowsRule(o.ruleId()))
                .map(o -> scope.anesthesia() ? o : new AnalysisService.Outcome(o.patientId(), o.patientName(), o.ruleId(), o.flagId(), null, o.result()))
                .toList();
        int created = (int) visible.stream().filter(o -> o.result().equals("CREATED")).count();
        int updated = (int) visible.stream().filter(o -> o.result().equals("EVIDENCE_UPDATED")).count();
        int unchanged = (int) visible.stream().filter(o -> o.result().startsWith("UNCHANGED")).count();
        int alerts = scope.anesthesia() ? (int) visible.stream().filter(o -> o.alertId() != null && o.result().equals("CREATED")).count() : 0;
        int patients = scope.networkWide() ? r.patientsEvaluated() : scope.patientIds().size();
        return new AnalysisService.RunResult(r.runId(), r.startedAt(), r.finishedAt(), patients, created, updated, unchanged, alerts, visible);
    }

    private static List<String> csv(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
