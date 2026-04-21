// src/main/java/com/moniewise/moniewise_backend/controller/TransactionController.java
package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.TransactionDecisionResponseDto;
import com.moniewise.moniewise_backend.dto.WithdrawalInitiationResponseDto;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.TransactionDetailResponse;
import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
import com.moniewise.moniewise_backend.service.TransactionService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;


import java.math.BigDecimal;

@RestController

@RequestMapping("/transactions")  // or "/api/transactions" – your choice
public class TransactionController {

    @Autowired private TransactionService transactionService;
    @Autowired private UserService userService;

    @GetMapping
    public ResponseEntity<Page<TransactionListResponse>> getUserTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @AuthenticationPrincipal UserDetails userDetails
            ) {

        Long userId = getUserIdFromUserDetails(userDetails);

//        System.out.println("Authenticated userId: " + userId);

        Page<TransactionListResponse> result =
                transactionService.getTransactionsForUser(userId, page, size);

        return ResponseEntity.ok(result);
    }

    // GET /transactions/{id}
    // → Returns single transaction detail
    @GetMapping("/{trans_id}")
    public TransactionDetailResponse getTransactionDetail(
            @PathVariable Long trans_id,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = getUserIdFromUserDetails(userDetails);
        return transactionService.getTransactionDetail(trans_id, userId);
    }

    private Long getUserIdFromUserDetails(UserDetails userDetails) {
        return userService.getRequiredUserIdByEmail(userDetails.getUsername());
    }

    // Optional: by budget (uncomment when ready)
    // @GetMapping("/budget/{budgetId}")
    // public ResponseEntity<Page<TransactionListResponse>> getBudgetTransactions(
    //         @PathVariable Long budgetId,
    //         @RequestParam(defaultValue = "0") int page,
    //         @RequestParam(defaultValue = "30") int size,
    //         @AuthenticationPrincipal Long userId) {
    //     return ResponseEntity.ok(transactionService.getTransactionsForBudget(budgetId, page, size));
    // }

    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawalInitiationResponseDto> initiateWithdrawal(@AuthenticationPrincipal UserDetails userDetails,
                                                                               @RequestBody WithdrawalRequest request) {
        Long userId = getUserIdFromUserDetails(userDetails);
        WithdrawalInitiationResponseDto response = transactionService.initiateWithdrawal(userId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/transfer")
    public ResponseEntity<Void> initiateTransfer(@AuthenticationPrincipal UserDetails userDetails,
                                                 @RequestParam BigDecimal amount,
                                                 @RequestParam Long sourceEnvelopeId,
                                                 @RequestParam String destinationReference) {
        Long userId = getUserIdFromUserDetails(userDetails);
        transactionService.initiateTransfer(userId, amount, sourceEnvelopeId, destinationReference);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/decision/{transactionRequestId}")
    public ResponseEntity<TransactionDecisionResponseDto> getDecision(@PathVariable Long transactionRequestId) {
        return ResponseEntity.ok(transactionService.getDecision(transactionRequestId));
    }
}
