package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.TransactionDetailResponse;
import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
import com.moniewise.moniewise_backend.dto.response.TransactionMeta;
import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class TransactionService {

    @Autowired
    private TransactionLogRepository transactionLogRepo;

    @Autowired
    private EnvelopeRepository envelopeRepo;

    @Autowired
    private BudgetRepository budgetRepo;

    // CHANGE THIS METHOD:
    public Page<TransactionListResponse> getTransactionsForUser(Long userId, int page, int size) {

        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size);

        return transactionLogRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::mapToListResponse);
    }

//    // Optional: by budget
//    public List<TransactionListResponse> getTransactionsForBudget(Long budgetId) {
//        List<TransactionLog> logs = transactionLogRepo.findByBudgetId(budgetId);
//        return logs.stream()
//                .sorted(Comparator.comparing(TransactionLog::getCreatedAt).reversed())
//                .map(this::mapToListResponse)
//                .collect(Collectors.toList());
//    }

    // GET: Single transaction detail
    public TransactionDetailResponse getTransactionDetail(Long transactionId, Long userId) {
        TransactionLog log = transactionLogRepo.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        if (!log.getUserId().equals(userId)) {
            throw new SecurityException("Access denied");
        }

        return mapToDetailResponse(log);
    }

    private TransactionListResponse mapToListResponse(TransactionLog t) {
        String sourceName = getEnvelopeName(t.getSourceEnvelopeId());
        String targetName = getEnvelopeName(t.getTargetEnvelopeId());
        String budgetName = getBudgetName(t.getBudgetId());

        String title = switch (t.getTransactionType()) {
            case "envelope_to_envelope" -> "From %s to %s".formatted(sourceName, targetName);
            case "deposit"              -> "Deposit to %s".formatted(targetName);
            case "withdrawal"           -> "Withdrawn from %s".formatted(sourceName);
            default                     -> t.getTransactionType().replace("_", " ");
        };

        String description = t.getDescription() != null ? t.getDescription() :
                "%s • ₦%,.2f".formatted(budgetName, t.getAmount());

        boolean isOutgoing = t.getSourceEnvelopeId() != null;

        return new TransactionListResponse(
                t.getId(),
                title,
                description,
                isOutgoing ? t.getAmount().negate() : t.getAmount(),
                t.getFee() != null ? t.getFee() : BigDecimal.ZERO,
                t.getTransactionType(),
                t.getCreatedAt(),
                new TransactionMeta(sourceName, targetName, budgetName, isOutgoing)
        );
    }

    private TransactionDetailResponse mapToDetailResponse(TransactionLog t) {
        String sourceName = getEnvelopeName(t.getSourceEnvelopeId());
        String targetName = getEnvelopeName(t.getTargetEnvelopeId());
        String budgetName = getBudgetName(t.getBudgetId());

        String title = switch (t.getTransactionType()) {
            case "envelope_to_envelope" -> "From %s to %s".formatted(sourceName, targetName);
            case "deposit"              -> "Added to %s".formatted(targetName);
            case "withdrawal"           -> "Withdrawn from %s".formatted(sourceName);
            default                     -> t.getTransactionType();
        };

        return new TransactionDetailResponse(
                t.getId(),
                title,
                t.getDescription(),
                t.getAmount(),
                t.getFee() != null ? t.getFee() : BigDecimal.ZERO,
                t.getAmount().subtract(t.getFee() != null ? t.getFee() : BigDecimal.ZERO),
                t.getTransactionType(),
                t.getCreatedAt(),
                t.getBudgetId(),
                budgetName,
                sourceName,
                t.getSourceEnvelopeId(),
                targetName,
                t.getTargetEnvelopeId(),
                t.getExternalAccountId()
        );
    }

    private String getEnvelopeName(Long envelopeId) {
        if (envelopeId == null) return "External";
        return envelopeRepo.findById(envelopeId)
                .map(Envelope::getName)
                .orElse("Unknown Envelope");
    }

    private String getBudgetName(Long budgetId) {
        if (budgetId == null) return "No Budget";
        return budgetRepo.findById(budgetId)
                .map(Budget::getName)
                .orElse("Unknown Budget");
    }
}