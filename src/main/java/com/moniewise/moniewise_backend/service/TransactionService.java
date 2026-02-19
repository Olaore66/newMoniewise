//package com.moniewise.moniewise_backend.service;
//
//import com.moniewise.moniewise_backend.dto.response.TransactionDetailResponse;
//import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
//import com.moniewise.moniewise_backend.dto.response.TransactionMeta;
//import com.moniewise.moniewise_backend.entity.*;
//import com.moniewise.moniewise_backend.enums.TransactionCategory;
//import com.moniewise.moniewise_backend.enums.TransactionType;
//import com.moniewise.moniewise_backend.repository.*;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.data.domain.Pageable;
//import org.springframework.security.core.annotation.AuthenticationPrincipal;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.math.BigDecimal;
//import java.util.Comparator;
//import java.util.List;
//import java.util.Set;
//import java.util.stream.Collectors;
//
//import static com.moniewise.moniewise_backend.enums.TransactionType.*;
////import static com.moniewise.moniewise_backend.security.JwtAuthenticationFilter.log;
//
//
//@Service
//@Transactional(readOnly = true)
//public class TransactionService {
//
//    @Autowired
//    private TransactionLogRepository transactionLogRepo;
//
//    @Autowired
//    private EnvelopeRepository envelopeRepo;
//
//    @Autowired
//    private BudgetRepository budgetRepo;
//
//
//    // CHANGE THIS METHOD:
//    public Page<TransactionListResponse> getTransactionsForUser(Long userId, int page, int size) {
//
//        size = Math.min(size, 100);
//        Pageable pageable = PageRequest.of(page, size);
//
//        return transactionLogRepo.findUserVisibleTransactions(userId, USER_VISIBLE_TYPES, pageable)
//                .map(this::mapToListResponse);
//    }
//
//    // GET: Single transaction detail
//    public TransactionDetailResponse getTransactionDetail(Long transactionId, Long userId) {
//        TransactionLog log = transactionLogRepo.findById(transactionId)
//                .orElseThrow(() -> new RuntimeException("Transaction not found"));
//
//        if (!log.getUserId().equals(userId)) {
//            throw new SecurityException("Access denied");
//        }
//
//        return mapToDetailResponse(log);
//    }
//
//    private TransactionListResponse mapToListResponse(TransactionLog t) {
//        String sourceName = getEnvelopeName(t.getSourceEnvelopeId());
//        String targetName = getEnvelopeName(t.getTargetEnvelopeId());
//        String budgetName = getBudgetName(t.getBudgetId());
//
//        // 1. Clean, user-friendly title
//        String title = switch (t.getTransactionType()) {
//            case WALLET_DEPOSIT -> "Wallet funded";
//            case ENVELOPE_TO_ENVELOPE -> "From %s to %s".formatted(sourceName, targetName);
//            case ENVELOPE_TO_EXTERNAL -> "Sent to bank";
//            case ENVELOPE_TO_USER -> "Sent to user";
//            case USER_TO_ENVELOPE -> "Received from user";
//            case BUDGET_CREATION_FEE -> "Budget creation fee";
//            case BUDGET_ALLOCATION -> "Allocated to budget";
//            case BUDGET_UNALLOCATED_REFUNDED -> "Refunded to wallet";
//            default -> t.getTransactionType().name().replace("_", " ");
//        };
//
//
//        // 2. Correct amount sign
//       boolean isOutgoing = switch (t.getTransactionType()) {
//            case WALLET_DEPOSIT,
//                    USER_TO_ENVELOPE,
//                    BUDGET_UNALLOCATED_REFUNDED -> false;
//
//            case ENVELOPE_TO_ENVELOPE,
//                    ENVELOPE_TO_EXTERNAL,
//                    ENVELOPE_TO_USER,
//                    BUDGET_CREATION_FEE,
//                    BUDGET_ALLOCATION,
//                    WALLET_TO_BUDGET -> true;
//
//            default -> throw new IllegalStateException("Unhandled transaction type: " + t.getTransactionType());
//        };
//
//
//
//
//        BigDecimal displayAmount = isOutgoing
//                ? t.getAmount().negate()
//                : t.getAmount();
//
//        // 3. Description
//        String description = t.getDescription() != null
//                ? t.getDescription()
//                : switch (t.getTransactionType()) {
//            case ENVELOPE_TO_ENVELOPE -> "%s • ₦%,.2f moved".formatted(budgetName, t.getAmount());
//            case WALLET_DEPOSIT -> "Added to wallet";
//            default -> budgetName != null ? budgetName : "Transaction";
//        };
//
//        TransactionCategory category = TransactionClassifier.categoryOf(t.getTransactionType());
//
//        TransactionType transactionType = transactionLog.getTransactionType();
//        BigDecimal amount = transactionLog.getAmount();
//
////        TransactionMeta meta = new TransactionMeta(
////                sourceName,
////                targetName,
////                budgetName,
////                isOutgoing(transactionType, amount),
////                determineCategory(transactionType)
////        );
//
//        TransactionMeta meta = new TransactionMeta(
//                sourceName,
//                targetName,
//                budgetName,
//                isOutgoing(transactionType, amount),
//                determineCategory(transactionType)
//        );
//
//        return new TransactionListResponse(
//                t.getId(),
//                title,
//                description,
//                displayAmount,
//                t.getFee() != null ? t.getFee() : BigDecimal.ZERO,
//                t.getTransactionType(), // now enum, not String!
//                t.getCreatedAt(),
//                meta
////                new TransactionMeta(sourceName, targetName, budgetName, isOutgoing,  category)
//        );
//    }
//    private TransactionDetailResponse mapToDetailResponse(TransactionLog t) {
//        String sourceName = getEnvelopeName(t.getSourceEnvelopeId());
//        String targetName = getEnvelopeName(t.getTargetEnvelopeId());
//        String budgetName = getBudgetName(t.getBudgetId());
//
//        String title = switch (t.getTransactionType()) {
//            case WALLET_DEPOSIT -> "Wallet funded";
//            case ENVELOPE_TO_ENVELOPE -> "From %s to %s".formatted(sourceName, targetName);
//            case ENVELOPE_TO_EXTERNAL -> "Sent to bank";
//            case ENVELOPE_TO_USER -> "Sent to user";
//            case USER_TO_ENVELOPE -> "Received from user";
//            case BUDGET_CREATION_FEE -> "Budget creation fee";
//            case BUDGET_ALLOCATION -> "Allocated to budget";
//            case BUDGET_UNALLOCATED_REFUNDED -> "Refunded to wallet";
//            default -> t.getTransactionType().name().replace("_", " ");
//        };
//
//
//        return new TransactionDetailResponse(
//                t.getId(),
//                title,
//                t.getDescription(),
//                t.getAmount(),
//                t.getFee() != null ? t.getFee() : BigDecimal.ZERO,
//                t.getAmount().subtract(t.getFee() != null ? t.getFee() : BigDecimal.ZERO),
//                t.getTransactionType(),
//                t.getCreatedAt(),
//                t.getBudgetId(),
//                budgetName,
//                sourceName,
//                t.getSourceEnvelopeId(),
//                targetName,
//                t.getTargetEnvelopeId(),
//                t.getExternalAccountId()
//        );
//    }
//
//    private String getEnvelopeName(Long envelopeId) {
//        if (envelopeId == null) return "System";  //"External"
//
//        return envelopeRepo.findById(envelopeId)
//                .map(Envelope::getName)
//                .orElse("Unknown Envelope");
//    }
//
//    private String getBudgetName(Long budgetId) {
//        if (budgetId == null) return "No Budget";
//        return budgetRepo.findById(budgetId)
//                .map(Budget::getName)
//                .orElse("Unknown Budget");
//    }
//
//    private static final Set<TransactionType> USER_VISIBLE_TYPES = Set.of(
//            WALLET_DEPOSIT,
//            WALLET_TO_BUDGET,
//            BUDGET_ALLOCATION,
//            ENVELOPE_TO_ENVELOPE,
//            ENVELOPE_TO_EXTERNAL,
//            ENVELOPE_TO_USER,
//            USER_TO_ENVELOPE,
//            BUDGET_CREATION_FEE,
//            BUDGET_UNALLOCATED_REFUNDED
//    );
//
//    private TransactionCategory determineCategory(TransactionType type) {
//        return switch (type) {
//            case WALLET_DEPOSIT,
//                    WALLET_DEDUCTION,
//                    BUDGET_ALLOCATION,
//                    BUDGET_UNALLOCATED_REFUNDED,
//                    BUDGET_COMPLETION_REFUND,
//                    ENVELOPE_TO_ENVELOPE,
//                    STRICT_LOCK_ROLLBACK,
//                    DISBURSEMENT_REFUNDED,
//                    BUDGET_CREATION_FEE -> TransactionCategory.INTERNAL;
//
//            case ENVELOPE_TO_EXTERNAL,
//                    WALLET_TO_EXTERNAL -> TransactionCategory.TO_EXTERNAL_BANK;
//
//            case USER_TO_USER,
//                    ENVELOPE_TO_USER,
//                    WALLET_TO_USER,
//                    USER_TO_ENVELOPE -> TransactionCategory.TO_MONIEWISE_USER;
//
//            default -> TransactionCategory.INTERNAL;
//        };
//    }
//
//    private boolean isOutgoing(TransactionType type, BigDecimal amount) {
//        // Most reliable: use amount sign, fallback to type
//        if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) return true;
//        return switch (type) {
//            case ENVELOPE_DISBURSEMENT,
//                    ENVELOPE_TO_EXTERNAL,
//                    ENVELOPE_TO_ENVELOPE,  // outgoing from source
//                    WALLET_DEDUCTION,
//                    BUDGET_CREATION_FEE -> true;
//            default -> false;
//        };
//    }
//
//}
package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.TransactionDetailResponse;
import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.moniewise.moniewise_backend.enums.TransactionType.*;

@Service
@Transactional(readOnly = true)
public class TransactionService {

    @Autowired
    private TransactionLogRepository transactionLogRepo;

    @Autowired
    private EnvelopeRepository envelopeRepo;

    @Autowired
    private BudgetRepository budgetRepo;

    private static final Set<TransactionType> USER_VISIBLE_TYPES = Set.of(
            WALLET_DEPOSIT, WALLET_TO_BUDGET, BUDGET_ALLOCATION, ENVELOPE_TO_ENVELOPE,
            ENVELOPE_TO_EXTERNAL, ENVELOPE_TO_USER, USER_TO_ENVELOPE, BUDGET_CREATION_FEE,
            BUDGET_UNALLOCATED_REFUNDED, STRICT_LOCK_ROLLBACK, DISBURSEMENT_REFUNDED
    );

    // =========================================================================
    // ✅ OPTIMIZED: Batch Fetching (Reduces DB calls from ~41 to 3)
    // =========================================================================
    public Page<TransactionListResponse> getTransactionsForUser(Long userId, int page, int size) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size);

        // 1. Fetch the Page
        Page<TransactionLog> transactionPage = transactionLogRepo.findUserVisibleTransactions(userId, USER_VISIBLE_TYPES, pageable);

        // 2. Collect IDs to batch fetch (Prevent N+1 Problem)
        Set<Long> envelopeIds = new HashSet<>();
        transactionPage.getContent().forEach(t -> {
            if (t.getSourceEnvelopeId() != null) envelopeIds.add(t.getSourceEnvelopeId());
            if (t.getTargetEnvelopeId() != null) envelopeIds.add(t.getTargetEnvelopeId());
        });

        // 3. Batch Fetch Names into a Map (ID -> Name)
        // Note: You need a repository method findByIdIn(Set<Long> ids) or similar,
        // or just use findAllById and map it manually.
        Map<Long, String> envelopeNames = new HashMap<>();
        if (!envelopeIds.isEmpty()) {
            envelopeRepo.findAllById(envelopeIds).forEach(e ->
                    envelopeNames.put(e.getId(), e.getName())
            );
        }

        // 4. Map to Response using the Memory Cache
        return transactionPage.map(t -> mapToListResponse(t, envelopeNames));
    }

    public TransactionDetailResponse getTransactionDetail(Long transactionId, Long userId) {
        TransactionLog log = transactionLogRepo.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        if (!log.getUserId().equals(userId)) {
            throw new SecurityException("Access denied");
        }

        return mapToDetailResponse(log);
    }

    // Updated Signature to accept the Cache Map
    private TransactionListResponse mapToListResponse(TransactionLog t, Map<Long, String> envelopeNameCache) {
        // Use Cache or Default
        String sourceName = t.getSourceEnvelopeId() != null
                ? envelopeNameCache.getOrDefault(t.getSourceEnvelopeId(), "Unknown Envelope")
                : "System";

        String targetName = t.getTargetEnvelopeId() != null
                ? envelopeNameCache.getOrDefault(t.getTargetEnvelopeId(), "Unknown Envelope")
                : "System";

        // 1. CALCULATE DIRECTION & AMOUNT
        boolean isOutgoing = isOutgoing(t.getTransactionType(), t.getAmount());
        BigDecimal displayAmount = isOutgoing ? t.getAmount().negate() : t.getAmount();
        String direction = isOutgoing ? "OUT" : "IN";

        // 2. DETERMINE TITLE, SUBTITLE & ICON
        String title;
        String subtitle = t.getDescription(); // Default
        String iconType;

        switch (t.getTransactionType()) {
            case ENVELOPE_TO_USER: // P2P Sent
                title = "Transfer to " + getCounterpartyName(t);
                subtitle = "P2P Transfer";
                iconType = "USER";
                break;
            case USER_TO_ENVELOPE: // P2P Received
            case USER_TO_USER:
                title = "Received from " + getCounterpartyName(t);
                subtitle = "P2P Received";
                iconType = "USER";
                break;
            case ENVELOPE_TO_EXTERNAL: // Bank Transfer
                title = (t.getDescription() != null) ? t.getDescription().replace("Transfer to ", "") : "Bank Transfer";
                subtitle = "Bank Transfer";
                iconType = "BANK";
                break;
            case ENVELOPE_TO_ENVELOPE: // Internal
                title = "Moved to " + targetName;
                subtitle = "From " + sourceName;
                iconType = "SWAP";
                break;
            case WALLET_DEPOSIT:
                title = "Wallet Funded";
                subtitle = "Deposit";
                iconType = "WALLET";
                break;
            case ENVELOPE_DISBURSEMENT:
                title = "Daily Disbursement";
                subtitle = sourceName;
                iconType = "CASH";
                break;
            default:
                title = formatEnumName(t.getTransactionType());
                subtitle = "Transaction";
                iconType = "DEFAULT";
        }

        // 3. GENERATE PATH (Deep Link)
        String path = "/transactions/" + t.getId();

        return new TransactionListResponse(
                t.getId(),
                title,
                subtitle,
                displayAmount,
                null,
                iconType,
                direction,
                path,
                t.getCreatedAt(),
                t.getTransactionType()
        );
    }

    // Helper to format "ENVELOPE_TO_ENVELOPE" -> "Envelope To Envelope"
    private String formatEnumName(TransactionType type) {
        if (type == null) return "Transaction";
        return type.name().charAt(0) + type.name().substring(1).toLowerCase().replace('_', ' ');
    }

    private String getCounterpartyName(TransactionLog t) {
        if(t.getDescription() != null && t.getDescription().contains("Transfer to ")) {
            return t.getDescription().replace("Transfer to ", "");
        }
        return "Wisemonie User";
    }

//    private TransactionDetailResponse mapToDetailResponse(TransactionLog t) {
//        // For detail view, single queries are fine (no N+1 issue here)
//        String sourceName = getEnvelopeName(t.getSourceEnvelopeId());
//        String targetName = getEnvelopeName(t.getTargetEnvelopeId());
//        String budgetName = getBudgetName(t.getBudgetId());
//
//        String title = switch (t.getTransactionType()) {
//            case WALLET_DEPOSIT -> "Wallet funded";
//            case ENVELOPE_TO_ENVELOPE -> "From %s to %s".formatted(sourceName, targetName);
//            case ENVELOPE_TO_EXTERNAL -> "Sent to bank";
//            case ENVELOPE_TO_USER -> "Sent to user";
//            case USER_TO_ENVELOPE -> "Received from user";
//            case BUDGET_CREATION_FEE -> "Budget creation fee";
//            case BUDGET_ALLOCATION -> "Allocated to budget";
//            case BUDGET_UNALLOCATED_REFUNDED -> "Refunded to wallet";
//            default -> t.getTransactionType().name().replace("_", " ");
//        };
//
//        BigDecimal fee = t.getFee() != null ? t.getFee() : BigDecimal.ZERO;
//
//        return new TransactionDetailResponse(
//                t.getId(),
//                title,
//                t.getDescription(),
//                t.getAmount(),
//                fee,
//                t.getAmount().subtract(fee),
//                t.getTransactionType(),
//                t.getCreatedAt(),
//                t.getBudgetId(),
//                budgetName,
//                sourceName,
//                t.getSourceEnvelopeId(),
//                targetName,
//                t.getTargetEnvelopeId(),
//                t.getExternalAccountId()
//        );
//    }
// Replace this method in your TransactionService.java

    private TransactionDetailResponse mapToDetailResponse(TransactionLog t) {
        String sourceName = getEnvelopeName(t.getSourceEnvelopeId());
        String targetName = getEnvelopeName(t.getTargetEnvelopeId());
        String budgetName = getBudgetName(t.getBudgetId());

        // 1. Amount & Direction Math (Fixes the Double Negative issue)
        boolean isDebit = isOutgoing(t.getTransactionType(), t.getAmount());
        String direction = isDebit ? "DEBIT" : "CREDIT";

        // Force amount to be positive for display
        BigDecimal absoluteAmount = t.getAmount().abs();
        BigDecimal fee = t.getFee() != null ? t.getFee() : BigDecimal.ZERO;

        // Net Amount: If Debit, they paid Amount + Fee. If Credit, they got Amount - Fee.
        BigDecimal netAmount = isDebit ? absoluteAmount.add(fee) : absoluteAmount.subtract(fee);

        // 2. Resolve Sender and Recipient (Fixes the "System to System" issue)
        String sender = "System";
        String recipient = "System";

        switch (t.getTransactionType()) {
            case BUDGET_ALLOCATION:
                sender = "Main Wallet";
                recipient = budgetName != null ? "Budget: " + budgetName : "Budget Envelopes";
                break;
            case BUDGET_CREATION_FEE:
                sender = "Main Wallet";
                recipient = "MonieWise Fee";
                break;
            case BUDGET_UNALLOCATED_REFUNDED:
                sender = "Unallocated Funds";
                recipient = "Main Wallet";
                break;
            case WALLET_DEPOSIT:
                sender = "External Transfer / Card";
                recipient = "Main Wallet";
                break;
            case WALLET_WITHDRAWAL:
            case ENVELOPE_TO_EXTERNAL:
                sender = t.getTransactionType() == WALLET_WITHDRAWAL ? "Main Wallet" : sourceName;
                recipient = t.getExternalAccountId() != null ? "Bank: " + t.getExternalAccountId() : "External Bank";
                break;
            case ENVELOPE_TO_ENVELOPE:
                sender = sourceName;
                recipient = targetName;
                break;
            case ENVELOPE_TO_USER:
                sender = sourceName;
                recipient = getCounterpartyName(t);
                break;
            case USER_TO_ENVELOPE:
                sender = getCounterpartyName(t);
                recipient = targetName;
                break;
            default:
                sender = "MonieWise Account";
                recipient = "MonieWise Account";
                break;
        }

        // 3. User-Friendly Title
        String title = switch (t.getTransactionType()) {
            case WALLET_DEPOSIT -> "Wallet funded";
            case ENVELOPE_TO_ENVELOPE -> "From %s to %s".formatted(sourceName, targetName);
            case ENVELOPE_TO_EXTERNAL -> "Sent to bank";
            case ENVELOPE_TO_USER -> "Sent to " + getCounterpartyName(t);
            case USER_TO_ENVELOPE -> "Received from " + getCounterpartyName(t);
            case BUDGET_CREATION_FEE -> "Budget creation fee";
            case BUDGET_ALLOCATION -> "Allocated to budget";
            case BUDGET_UNALLOCATED_REFUNDED -> "Refunded to wallet";
            default -> formatEnumName(t.getTransactionType());
        };

        // Fallbacks for missing data
        String reference = t.getReference() != null ? t.getReference() : "N/A";
        String status = t.getStatus() != null ? t.getStatus().name() : "COMPLETED";

        return new TransactionDetailResponse(
                t.getId(),
                reference,
                status,
                direction,
                title,
                t.getDescription(),
                absoluteAmount,
                fee,
                netAmount,
                sender,
                recipient,
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
        if (envelopeId == null) return "System";
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

    private boolean isOutgoing(TransactionType type, BigDecimal amount) {
        if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
            return true;
        }
        return switch (type) {
            case ENVELOPE_DISBURSEMENT,
                    ENVELOPE_TO_EXTERNAL,
                    ENVELOPE_TO_ENVELOPE,
                    ENVELOPE_TO_USER,
                    WALLET_DEDUCTION,
                    BUDGET_CREATION_FEE,
                    BUDGET_ALLOCATION -> true;
            default -> false;
        };
    }
}