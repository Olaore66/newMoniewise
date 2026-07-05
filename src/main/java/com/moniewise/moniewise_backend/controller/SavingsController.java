package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.CreateSavingsGoalRequest;
import com.moniewise.moniewise_backend.dto.request.FundSavingsRequest;
import com.moniewise.moniewise_backend.dto.request.SavingsP2PTransferRequest;
import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.service.SavingsService;
import com.moniewise.moniewise_backend.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/savings") // Adjust your API version path if needed
public class SavingsController {

    private static final Logger logger = LoggerFactory.getLogger(SavingsController.class);

    private final SavingsService savingsService;
    private final UserService userService;

    public SavingsController(SavingsService savingsService, UserService userService) {
        this.savingsService = savingsService;
        this.userService = userService;
    }

    /**
     * POST: Create a new Savings Goal (Phase 1)
     */
    @PostMapping
    public ResponseEntity<?> createSavingsGoal(
            @Valid @RequestBody CreateSavingsGoalRequest request,
            Principal principal) {
        try {
            // 1. Get the securely authenticated user
            User user = userService.findByEmail(principal.getName());

            // 2. Call the Wealth Engine
            SavingsGoal createdGoal = savingsService.createSavingsGoal(
                    user.getId(),
                    request.getName(),
                    request.getTargetAmount(),
                    request.getInitialDeposit(),
                    request.getMaturityDate(),
                    request.getInterestRate()
            );

            return new ResponseEntity<>(createdGoal, HttpStatus.CREATED);

        } catch (IllegalArgumentException | IllegalStateException e) {
            // Catches validation errors like "Duplicate Name" or "Insufficient Funds"
            logger.warn("Savings creation failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error creating savings goal", e);
            return ResponseEntity.internalServerError().body("An error occurred while creating your savings goal.");
        }
    }

    /**
     * GET: Fetch all savings goals (all statuses) for the savings screen
     */
    @GetMapping
    public ResponseEntity<?> getAllSavings(Principal principal) {
        try {
            User user = userService.findByEmail(principal.getName());
            return ResponseEntity.ok(savingsService.getAllSavingsForUser(user.getId()));
        } catch (Exception e) {
            logger.error("Failed to fetch savings for {}", principal.getName(), e);
            return ResponseEntity.internalServerError().body("Failed to load savings goals.");
        }
    }

    /**
     * GET: Fetch only active savings pots
     */
    @GetMapping("/active")
    public ResponseEntity<?> getActiveSavings(Principal principal) {
        try {
            User user = userService.findByEmail(principal.getName());
            return ResponseEntity.ok(savingsService.getActiveSavingsForUser(user.getId()));
        } catch (Exception e) {
            logger.error("Failed to fetch active savings for {}", principal.getName(), e);
            return ResponseEntity.internalServerError().body("Failed to load savings goals.");
        }
    }

    /**
     * POST: Withdraw a matured savings goal — pays out principal + interest to wallet
     */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<?> withdrawSavings(
            @PathVariable("id") Long savingsGoalId,
            Principal principal) {
        try {
            User user = userService.findByEmail(principal.getName());
            SavingsGoal result = savingsService.withdrawSavings(user.getId(), savingsGoalId);
            return ResponseEntity.ok(result);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            logger.warn("Withdrawal failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error during savings withdrawal", e);
            return ResponseEntity.internalServerError().body("An error occurred while processing your withdrawal.");
        }
    }

    /**
     * POST: Send money from a MATURED savings pot to another Wisemonie user (P2P).
     */
    @PostMapping("/{id}/transfer/p2p")
    public ResponseEntity<?> transferSavingsToUser(
            @PathVariable("id") Long savingsGoalId,
            @RequestBody SavingsP2PTransferRequest request,
            Principal principal) {
        try {
            User user = userService.findByEmail(principal.getName());
            SavingsGoal result = savingsService.transferSavingsToUser(user.getId(), savingsGoalId, request);
            return ResponseEntity.ok(result);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            logger.warn("Savings P2P failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error during savings P2P transfer", e);
            return ResponseEntity.internalServerError().body("An error occurred while sending your savings.");
        }
    }

    /**
     * POST: Sweep a savings-linked envelope's balance into its savings goal
     */
    @PostMapping("/sweep-envelope/{envelopeId}")
    public ResponseEntity<?> sweepEnvelopeToSavings(
            @PathVariable("envelopeId") Long envelopeId,
            Principal principal) {
        try {
            SavingsGoal result = savingsService.triggerEnvelopeSweep(principal.getName(), envelopeId);
            return ResponseEntity.ok(result);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            logger.warn("Envelope sweep failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error during envelope sweep", e);
            return ResponseEntity.internalServerError().body("An error occurred while sweeping funds.");
        }
    }

    /**
     * POST: Manually Top-Up an existing Savings Goal from Wallet
     */
    @PostMapping("/{id}/fund")
    public ResponseEntity<?> fundSavingsGoal(
            @PathVariable("id") Long savingsGoalId,
            @Valid @RequestBody FundSavingsRequest request,
            Principal principal) {
        try {
            // 1. Authenticate user
            User user = userService.findByEmail(principal.getName());

            // 2. Execute manual top-up
            SavingsGoal updatedGoal = savingsService.manualTopUp(
                    user.getId(),
                    savingsGoalId,
                    request.getAmount()
            );

            return ResponseEntity.ok(updatedGoal);

        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            logger.warn("Manual top-up failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error during manual top-up", e);
            return ResponseEntity.internalServerError().body("An error occurred while topping up your savings.");
        }
    }
}