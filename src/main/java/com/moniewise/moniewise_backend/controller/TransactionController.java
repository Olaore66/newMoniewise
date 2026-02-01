// src/main/java/com/moniewise/moniewise_backend/controller/TransactionController.java
package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.TransactionDetailResponse;
import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@RestController

@RequestMapping("/transactions")  // or "/api/transactions" – your choice
public class TransactionController {

    @Autowired private TransactionService transactionService;
    @Autowired private UserRepository userRepository;

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
        String username = userDetails.getUsername();
        return userRepository.findByEmail(username)
                .map(user -> user.getId())
                .orElseThrow(() -> new IllegalArgumentException("User not found for email: " + username));
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
}