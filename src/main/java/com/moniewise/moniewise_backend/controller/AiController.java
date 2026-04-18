package com.moniewise.moniewise_backend.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import com.moniewise.moniewise_backend.dto.request.AiBudgetAssistantTurnRequest;
import com.moniewise.moniewise_backend.dto.request.AiStarterEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAssistantTurnResponse;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAllocationResponse;
import com.moniewise.moniewise_backend.dto.response.AiDashboardNextActionResponse;
import com.moniewise.moniewise_backend.dto.response.AiStarterEnvelopeResponse;
import com.moniewise.moniewise_backend.service.AiBudgetService;
import com.moniewise.moniewise_backend.service.GeminiService;
import com.moniewise.moniewise_backend.service.AiInsightService;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiBudgetService aiBudgetService;
    private final AiInsightService aiInsightService;
    private final GeminiService geminiService;

    public AiController(
        AiBudgetService aiBudgetService,
        AiInsightService aiInsightService,
        GeminiService geminiService
    ) {
        this.aiBudgetService = aiBudgetService;
        this.aiInsightService = aiInsightService;
        this.geminiService = geminiService;
    }

    @PostMapping("/starter-envelopes")
    public ResponseEntity<AiStarterEnvelopeResponse> generateStarterEnvelopes(
        @RequestBody AiStarterEnvelopeRequest request
    ) {
        AiStarterEnvelopeResponse response =
            aiBudgetService.generateStarterEnvelopes(request);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/budget-allocation")
    public ResponseEntity<AiBudgetAllocationResponse> generateBudgetAllocation(
        @RequestBody AiStarterEnvelopeRequest request
    ) {
        AiBudgetAllocationResponse response =
            aiBudgetService.generateBudgetAllocation(request);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/budget-assistant/turn")
    public ResponseEntity<AiBudgetAssistantTurnResponse> processBudgetAssistantTurn(
        @RequestBody AiBudgetAssistantTurnRequest request
    ) {
        AiBudgetAssistantTurnResponse response =
            aiBudgetService.processBudgetAssistantTurn(request);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/dashboard-next-action")
    public ResponseEntity<AiDashboardNextActionResponse> getDashboardNextAction(
        Authentication authentication
    ) {
        AiDashboardNextActionResponse response =
            aiInsightService.getDashboardNextAction(authentication.getName());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/gemini-status")
    public ResponseEntity<Map<String, Object>> getGeminiStatus() {
        return ResponseEntity.ok(geminiService.getConfigurationStatus());
    }
}
