package com.camellia.controller;

import com.camellia.dto.Dtos.DashboardDto;
import com.camellia.integration.IntegrationService;
import com.camellia.integration.gemini.GoogleGenAiGateway;
import com.camellia.service.ClinicalViewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ClinicalViewService views;
    private final IntegrationService integrations;
    private final GoogleGenAiGateway gemini;

    public DashboardController(ClinicalViewService views, IntegrationService integrations, GoogleGenAiGateway gemini) {
        this.views = views;
        this.integrations = integrations;
        this.gemini = gemini;
    }

    @GetMapping
    public DashboardDto dashboard() {
        return views.dashboard(integrations.mode().name(), gemini.isConfigured(), gemini.model());
    }
}
