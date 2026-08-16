package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.AutoTransferSetupRequest;
import com.moniewise.moniewise_backend.dto.response.AutoTransferResponse;
import com.moniewise.moniewise_backend.service.EnvelopeAutoTransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/envelopes")
public class EnvelopeAutoTransferController {

    private final EnvelopeAutoTransferService autoTransferService;

    public EnvelopeAutoTransferController(EnvelopeAutoTransferService autoTransferService) {
        this.autoTransferService = autoTransferService;
    }

    @PostMapping("/{envelopeId}/auto-transfer")
    public ResponseEntity<?> setupAutoTransfer(
            @PathVariable Long envelopeId,
            @RequestBody AutoTransferSetupRequest request,
            Authentication authentication) {
        try {
            AutoTransferResponse response = autoTransferService.setupAutoTransfer(
                    envelopeId, request, authentication.getName());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{envelopeId}/auto-transfer")
    public ResponseEntity<?> disableAutoTransfer(
            @PathVariable Long envelopeId,
            Authentication authentication) {
        autoTransferService.disableAutoTransfer(envelopeId, authentication.getName());
        return ResponseEntity.ok(Map.of("message", "Auto-transfer disabled"));
    }

    @GetMapping("/{envelopeId}/auto-transfer")
    public ResponseEntity<?> getAutoTransferStatus(
            @PathVariable Long envelopeId,
            Authentication authentication) {
        AutoTransferResponse response = autoTransferService.getAutoTransferStatus(
                envelopeId, authentication.getName());
        if (response == null) {
            return ResponseEntity.ok(Map.of("isAutomated", false));
        }
        return ResponseEntity.ok(response);
    }
}
