package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.BudgetRequest;
import com.moniewise.moniewise_backend.dto.request.LockRequest;
import com.moniewise.moniewise_backend.dto.request.SpendEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.BudgetResponse;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.exception.TncAcceptanceRequiredException;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.service.BudgetService;
import com.moniewise.moniewise_backend.service.EnvelopeService;
import com.moniewise.moniewise_backend.service.UserService;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/budgets")
public class BudgetController {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private UserService userService;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private  EnvelopeService envelopeService;

    private static final Logger logger = LoggerFactory.getLogger(BudgetController.class);

//    @PostMapping
//    public ResponseEntity<?> createBudget(@RequestBody BudgetRequest request, Authentication authentication) {
//
//            String email = authentication.getName();
//            BudgetResponse response = budgetService.createBudget(request, email);
//            return new ResponseEntity<>(response, HttpStatus.CREATED);
//
//    }

//    @PostMapping
//    public ResponseEntity<?> createBudget(@RequestBody BudgetRequest request, Authentication authentication) {
//        String email = authentication.getName();
//        User user = userService.findByEmail(email); // Use this instead of login()
//
//        // ✅ Check if user accepted T&C
//        if (!Boolean.TRUE.equals(user.getTncAccepted())) {
//            return ResponseEntity.status(HttpStatus.FORBIDDEN)
//                    .body(Map.of("error", "Please accept the Terms and Conditions to continue"));
//        }
//
//        // ✅ Proceed to create the budget
//        BudgetResponse response = budgetService.createBudget(request, email);
//        return new ResponseEntity<>(response, HttpStatus.CREATED);
//    }

    @PostMapping
    public ResponseEntity<?> createBudget(@Valid @RequestBody BudgetRequest request, Authentication authentication) {
        String email = authentication.getName();
        logger.debug("Creating budget for user {} with request: {}", email, request);

        User user = userService.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not found"));
        }

        // Check if user accepted T&C
        if (!Boolean.TRUE.equals(user.getTncAccepted())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Please accept the Terms and Conditions to continue"));
        }

        // Proceed to create the budget
        try {
            BudgetResponse response = budgetService.createBudget(request, email);
            return new ResponseEntity<>(response, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error creating budget for user {}: {}", email, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create budget"));
        }
    }

    // New: List all Budgets for the user
    @GetMapping
    public ResponseEntity<List<BudgetResponse>> getBudgets(Authentication authentication) {
        String email = authentication.getName();
        List<BudgetResponse> budgets = budgetService.getBudgets(email);
        return ResponseEntity.ok(budgets);
    }

    // New: GET /budgets/{budgetId}
    @GetMapping("/{budgetId}")
    public ResponseEntity<?> getBudgetById(@PathVariable Long budgetId, Authentication authentication) {
        try {
            String email = authentication.getName();
            BudgetResponse budget = budgetService.getBudgetById(budgetId, email);
            return ResponseEntity.ok(budget);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error fetching budget: " + e.getMessage());
        }
    }

    @GetMapping("/{budgetId}/envelopes")
    public ResponseEntity<?> getEnvelopesByBudget(@PathVariable Long budgetId, Authentication authentication) {
        try {
            System.out.println("Received request for budgetId: " + budgetId + ", auth: " + authentication);
            String email = authentication.getName();
            System.out.println("User email from token: " + email);
            List<EnvelopeResponse> envelopes = budgetService.getEnvelopesByBudget(budgetId, email);
            return ResponseEntity.ok(envelopes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error fetching envelopes: " + e.getMessage());
        }
    }

    // New: PATCH /budgets/{budgetId}/activate
    @PatchMapping("/{budgetId}/activate")
    public ResponseEntity<?> activateBudget(@PathVariable Long budgetId, Authentication authentication) {
        try {
            String email = authentication.getName();
            BudgetResponse budget = budgetService.activateBudget(budgetId, email);
            return ResponseEntity.ok(budget);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error activating budget: " + e.getMessage());
        }
    }


    // New: DELETE /budgets/{budgetId}
    @DeleteMapping("/{budgetId}")
    public ResponseEntity<?> deleteBudget(@PathVariable Long budgetId, Authentication authentication) {
        try {
            String email = authentication.getName();
            budgetService.deleteBudget(budgetId, email);
            return ResponseEntity.ok("Budget deleted successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error deleting budget: " + e.getMessage());
        }
    }


    // 12/04/2025 --->// New: Move money between Envelopes

    @Data
    public static class TransferExternalRequest {
        private ExternalAccount externalAccount;
        private Double amount;
    }

    @Data
    public static class ExternalAccount {
        private String accountNumber;
        private String bankCode;
        private String recipientName;
        private String bankName; // Optional
    }

    // 12/04/2025 --->// New: Top-up Budget
    @PostMapping("/{id}/topup")
    public ResponseEntity<?> topUpBudget(@PathVariable Long id, @RequestBody Map<String, Object> requestBody, Authentication authentication) {
        System.out.println("POST /budgets/" + id + "/topup called");
        try {
            String email = authentication.getName();
            Double amount = Double.valueOf(requestBody.get("amount").toString());
            budgetService.topUpBudget(id, amount, email);
            return ResponseEntity.ok("Budget topped up successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error topping up budget: " + e.getMessage());
        }
    }

    // New: Extend Budget// 13/04/2025 --->
    @PostMapping("/{id}/extend")
    public ResponseEntity<?> extendBudget(@PathVariable Long id, @RequestBody Map<String, Object> requestBody, Authentication authentication) {
        System.out.println("POST /budgets/" + id + "/extend called");
        try {
            String email = authentication.getName();
            String newName = (String) requestBody.get("new_name");
            String newEndDateStr = (String) requestBody.get("new_end_date");
            LocalDate newEndDate = LocalDate.parse(newEndDateStr);
            budgetService.extendBudget(id, newName, newEndDate, email);
            return ResponseEntity.ok("Budget extended successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error extending budget: " + e.getMessage());
        }
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboard(Authentication authentication) {
        try {
            Map<String, Object> dashboard = budgetService.getDashboard(authentication.getName());
            return ResponseEntity.ok(dashboard);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 14/04/2025
    @PostMapping("/envelopes/spend")
    public ResponseEntity<?> spendEnvelope(@RequestBody SpendEnvelopeRequest request, Authentication authentication) {
        try {
            EnvelopeResponse response = budgetService.spendEnvelope(request, authentication.getName());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @ExceptionHandler(TncAcceptanceRequiredException.class)
    public ResponseEntity<?> handleTncAcceptanceRequired(TncAcceptanceRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", ex.getMessage(),          // Use commas (,) instead of colons (:)
                "tnc", ex.getTncContent(),
                "version", ex.getTncVersion()
        )); // Closing parenthesis and semicolon
    }

    @PostMapping("/envelopes/lock")
    public ResponseEntity<?> lockEnvelope(
            @RequestBody LockRequest request,
            Authentication authentication
    ) {
        try {
            String email = authentication.getName();
            EnvelopeResponse response = budgetService.lockEnvelope(
                    request.getEnvelopeId(),
                    request.getLockType(),
                    request.getDurationDays(),
                    request.getInterestRate(),
                    email
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}