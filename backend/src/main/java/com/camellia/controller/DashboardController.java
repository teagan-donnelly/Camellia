package com.camellia.controller;

import com.camellia.dto.Dtos.DashboardDto;
import com.camellia.integration.IntegrationService;
import com.camellia.integration.gemini.GoogleGenAiGateway;
import com.camellia.security.AuthorizationService;
import com.camellia.security.DemoUser;
import com.camellia.service.ClinicalViewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard metrics computed only from records the active user may access. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ClinicalViewService views;
    private final IntegrationService integrations;
    private final GoogleGenAiGateway gemini;
    private final AuthorizationService auth;

    public DashboardController(ClinicalViewService views, IntegrationService integrations, GoogleGenAiGateway gemini, AuthorizationService auth) {
        this.views = views;
        this.integrations = integrations;
        this.gemini = gemini;
        this.auth = auth;
    }

    @GetMapping
    public DashboardDto dashboard(DemoUser user) {
        return views.dashboard(auth.scope(user), integrations.mode().name(), gemini.isConfigured(), gemini.model());
    }
}
