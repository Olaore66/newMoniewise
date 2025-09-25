package com.moniewise.moniewise_backend.controller;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;

import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.service.EnvelopeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;


import javax.validation.Valid;
import java.util.*;

@RestController
@RequestMapping("/envelopes")
public class EnvelopeController {

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private EnvelopeService envelopeService;

    private static final Logger logger = LoggerFactory.getLogger(BudgetController.class);

    @PostMapping("/{id}/move")
    public ResponseEntity<?> moveMoney(@PathVariable Long id, @RequestBody Map<String, Object> requestBody, Authentication authentication) {
        System.out.println("POST /envelopes/" + id + "/move called");
        try {
            String email = authentication.getName();
            Long targetId = Long.valueOf(requestBody.get("target_envelope_id").toString());
            Double amount = Double.valueOf(requestBody.get("amount").toString());
            envelopeService.moveMoney(id, targetId, amount, email);
            return ResponseEntity.ok("Money moved successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error moving money: " + e.getMessage());
        }
    }

    // 08/06/20225 -new fix for transfer to external
    @PostMapping("/{id}/transfer-external")
    public ResponseEntity<?> transferToExternal(
            @PathVariable Long id,
            @RequestBody BudgetController.TransferExternalRequest request,
            Authentication authentication) {
        Logger logger = LoggerFactory.getLogger(BudgetController.class);
        logger.info("POST /envelopes/{}/transfer-external called with payload: {}", id, request);
        try {
            String email = authentication.getName();
            validateTransferRequest(request);
            envelopeService.transferToExternal(id, request.getExternalAccount(), request.getAmount(), email);
            return ResponseEntity.ok("Transfer to external account initiated successfully");
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid transfer request for envelope {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SecurityException e) {
            logger.warn("Unauthorized transfer attempt for envelope {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            logger.error("Error initiating transfer for envelope {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error initiating transfer: " + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    private void validateTransferRequest(BudgetController.TransferExternalRequest request) {
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (request.getExternalAccount() == null) {
            throw new IllegalArgumentException("External account details are required");
        }
        BudgetController.ExternalAccount account = request.getExternalAccount();
        if (account.getAccountNumber() == null || account.getAccountNumber().isBlank()) {
            throw new IllegalArgumentException("Account number is required");
        }
        if (account.getBankCode() == null || account.getBankCode().isBlank()) {
            throw new IllegalArgumentException("Bank code is required");
        }
        if (account.getRecipientName() == null || account.getRecipientName().isBlank()) {
            throw new IllegalArgumentException("Recipient name is required");
        }
        if (!account.getAccountNumber().matches("\\d{10}")) {
            throw new IllegalArgumentException("Account number must be a 10-digit NUBAN");
        }
    }

           // Create an envelope
           // Create Envelope
           @PostMapping
           public ResponseEntity<EnvelopeResponse> createEnvelope(
                   @RequestBody EnvelopeRequest request,
                   Authentication authentication
           ) {
               String email = authentication.getName();
               EnvelopeResponse response = envelopeService.createEnvelope(request, email);
               return new ResponseEntity<>(response, HttpStatus.CREATED);
           }

    // Get Envelope
    @GetMapping("/{envelopeId}")
    public ResponseEntity<EnvelopeResponse> getEnvelope(
            @PathVariable Long envelopeId,
            Authentication authentication
    ) {
        String email = authentication.getName();
        EnvelopeResponse response = envelopeService.getEnvelopeById(envelopeId, email);
        return ResponseEntity.ok(response);
    }

    // Update Envelope (only conditions allowed)
    @PutMapping("/{envelopeId}")
    public ResponseEntity<EnvelopeResponse> updateEnvelope(
            @PathVariable Long envelopeId,
            @RequestBody EnvelopeRequest request,
            Authentication authentication
    ) {
        String email = authentication.getName();
        EnvelopeResponse response = envelopeService.updateEnvelopeConditions(envelopeId, request, email);
        return ResponseEntity.ok(response);
    }

    // Delete Envelope (only if parent budget is not ACTIVE)
    @DeleteMapping("/{envelopeId}")
    public ResponseEntity<?> deleteEnvelope(
            @PathVariable Long envelopeId,
            Authentication authentication
    ) {
        String email = authentication.getName();
        envelopeService.deleteEnvelope(envelopeId, email);
        return ResponseEntity.noContent().build();
    }
    }

