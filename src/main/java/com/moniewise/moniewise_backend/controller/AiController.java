package com.moniewise.moniewise_backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.moniewise.moniewise_backend.dto.request.AiStarterEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.AiStarterEnvelopeResponse;
import com.moniewise.moniewise_backend.service.AiBudgetService;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiBudgetService aiBudgetService;

    public AiController(AiBudgetService aiBudgetService) {
        this.aiBudgetService = aiBudgetService;
    }

    @PostMapping("/starter-envelopes")
    public ResponseEntity<AiStarterEnvelopeResponse> generateStarterEnvelopes(
        @RequestBody AiStarterEnvelopeRequest request
    ) {
        AiStarterEnvelopeResponse response =
            aiBudgetService.generateStarterEnvelopes(request);

        return ResponseEntity.ok(response);
    }
}

