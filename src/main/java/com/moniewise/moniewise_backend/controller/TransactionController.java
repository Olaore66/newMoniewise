// src/main/java/com/moniewise/moniewise_backend/controller/TransactionController.java
package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.TransactionDetailResponse;
import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
import com.moniewise.moniewise_backend.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transactions")  // or "/api/transactions" – your choice
public class TransactionController {

    @Autowired private TransactionService transactionService;

    // GET /transactions
    // → Returns all transactions for the logged-in user (paginated)
    @GetMapping
    public ResponseEntity<Page<TransactionListResponse>> getUserTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @AuthenticationPrincipal Long userId) {

        Page<TransactionListResponse> result =
                transactionService.getTransactionsForUser(userId, page, size);

        return ResponseEntity.ok(result);
    }

    // GET /transactions/{id}
    // → Returns single transaction detail
    @GetMapping("/{id}")
    public TransactionDetailResponse getTransactionDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId) {

        return transactionService.getTransactionDetail(id, userId);
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